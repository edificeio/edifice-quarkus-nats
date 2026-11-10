package io.edifice.nats.runtime;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.edifice.nats.api.NatsReplyException;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.ExecutorRecorder;
import io.quarkus.virtual.threads.VirtualThreads;
import io.quarkus.virtual.threads.VirtualThreadsRecorder;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;

/**
 * Subscribes the {@code @BrokerListener} methods once NATS is connected, and handles their messages.
 * <p>
 * Each message is handed off to a virtual thread or a worker thread, so that the NATS dispatcher thread is never
 * blocked. A request context is active for the whole handling of the message.
 */
@Singleton
public class NatsListenerDispatcher {

  static final String FAILED_HEADER = "failed";
  private static final Logger LOG = Logger.getLogger(NatsListenerDispatcher.class);

  private final List<NatsListenerDefinition> listeners = new ArrayList<>();
  private final NatsConnectionManager connectionManager;
  private final NatsConfig config;
  private final ObjectMapper objectMapper;
  private final NatsMessageContextImpl messageContext;
  private final Instance<ExecutorService> virtualThreads;
  private final List<Dispatcher> dispatchers = new ArrayList<>();

  @Inject
  public NatsListenerDispatcher(NatsConnectionManager connectionManager, NatsConfig config, ObjectMapper objectMapper,
                                NatsMessageContextImpl messageContext,
                                @VirtualThreads Instance<ExecutorService> virtualThreads) {
    this.connectionManager = connectionManager;
    this.config = config;
    this.objectMapper = objectMapper;
    this.messageContext = messageContext;
    this.virtualThreads = virtualThreads;
  }

  void add(NatsListenerDefinition listener) {
    listeners.add(listener);
  }

  /**
   * @return the listeners registered at startup
   */
  public List<NatsListenerDefinition> listeners() {
    return List.copyOf(listeners);
  }

  void start() {
    if (listeners.isEmpty()) {
      return;
    }
    final Executor executor = executor();
    final String queueGroup = config.queueGroup().filter(q -> !q.isBlank()).orElse(null);
    connectionManager.whenConnected(connection -> subscribe(connection, executor, queueGroup));
  }

  private Executor executor() {
    return switch (config.listener().execution()) {
      case WORKER -> ExecutorRecorder.getCurrent();
      case VIRTUAL_THREADS -> virtualThreads.isResolvable() ? virtualThreads.get() : VirtualThreadsRecorder.getCurrent();
    };
  }

  private synchronized void subscribe(Connection connection, Executor executor, String queueGroup) {
    for (NatsListenerDefinition listener : listeners) {
      final Dispatcher dispatcher = connection.createDispatcher();
      final String wildcard = listener.subject().wildcard();
      final String queue = listener.broadcast() ? null : queueGroup;
      if (queue == null) {
        dispatcher.subscribe(wildcard, message -> handOff(executor, listener, message));
      } else {
        dispatcher.subscribe(wildcard, queue, message -> handOff(executor, listener, message));
      }
      dispatchers.add(dispatcher);
      LOG.infof("Listening to NATS subject %s%s with %s", wildcard,
        queue == null ? (listener.broadcast() ? " (broadcast)" : "") : " (queue group " + queue + ")",
        listener.target());
    }
  }

  private void handOff(Executor executor, NatsListenerDefinition listener, Message message) {
    try {
      executor.execute(() -> handle(listener, message));
    } catch (RejectedExecutionException e) {
      LOG.warnf("Message on %s rejected, the application is stopping", message.getSubject());
    }
  }

  void handle(NatsListenerDefinition listener, Message message) {
    final ManagedContext requestContext = Arc.container().requestContext();
    requestContext.activate();
    try {
      messageContext.init(message, listener.subject().extract(message.getSubject()));
      final Object request;
      try {
        request = readRequest(message.getData(), listener.requestType());
      } catch (Exception e) {
        LOG.warnf("Invalid request received on %s: %s", message.getSubject(), e.getMessage());
        replyError(listener, message, "Invalid request: " + e.getMessage());
        return;
      }
      final Object response;
      try {
        response = listener.invoker().invoke(null, new Object[]{request});
      } catch (NatsReplyException e) {
        LOG.debugf(e, "%s answered an error on %s", listener.target(), message.getSubject());
        replyError(listener, message, e.getMessage());
        return;
      } catch (Exception e) {
        LOG.errorf(e, "%s failed on %s", listener.target(), message.getSubject());
        replyError(listener, message, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        return;
      }
      reply(listener, message, response);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Failed to handle the NATS message on %s", message.getSubject());
    } finally {
      requestContext.terminate();
    }
  }

  /**
   * Reads the payload as the request type. Callers going through broker-nats wrap the request in {@code {"data": ...}},
   * which is unwrapped unless the request type has a {@code data} property itself.
   */
  Object readRequest(byte[] data, Class<?> requestType) throws Exception {
    final JsonNode root = data == null || data.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(data);
    JsonNode node = root;
    if (root.isObject() && root.has("data") && !hasDataProperty(requestType)) {
      node = root.get("data");
    }
    return objectMapper.treeToValue(node, requestType);
  }

  private boolean hasDataProperty(Class<?> type) {
    final JavaType javaType = objectMapper.constructType(type);
    if (javaType.isContainerType() || javaType.isPrimitive() || type.getName().startsWith("java.")) {
      return false;
    }
    return objectMapper.getDeserializationConfig().introspect(javaType).findProperties().stream()
      .anyMatch(property -> property.getName().equals("data"));
  }

  private void reply(NatsListenerDefinition listener, Message message, Object response) {
    if (!listener.replies() || message.getReplyTo() == null) {
      return;
    }
    final byte[] payload;
    try {
      final ObjectNode envelope = objectMapper.createObjectNode();
      envelope.set("response", objectMapper.valueToTree(response));
      payload = objectMapper.writeValueAsBytes(envelope);
    } catch (Exception e) {
      LOG.errorf(e, "Failed to serialize the response of %s", listener.target());
      replyError(listener, message, "Failed to serialize the response: " + e.getMessage());
      return;
    }
    publish(message.getReplyTo(), false, payload);
  }

  private void replyError(NatsListenerDefinition listener, Message message, String error) {
    if (!listener.replies() || message.getReplyTo() == null) {
      return;
    }
    try {
      final ObjectNode envelope = objectMapper.createObjectNode();
      envelope.put("err", error);
      publish(message.getReplyTo(), true, objectMapper.writeValueAsBytes(envelope));
    } catch (Exception e) {
      LOG.errorf(e, "Failed to send the error reply of %s", listener.target());
    }
  }

  private void publish(String replyTo, boolean failed, byte[] payload) {
    final Headers headers = new Headers().put(FAILED_HEADER, String.valueOf(failed));
    connectionManager.connection().publish(replyTo, headers, payload);
  }
}
