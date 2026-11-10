package io.edifice.nats.runtime;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns the connection to NATS.
 * <p>
 * The connection is opened asynchronously, on first use, and retried in the background: the application starts and
 * stays up while NATS is unreachable. The application only sees a proxy of the connection, see
 * {@link LazyConnectionHandler}.
 */
@Singleton
public class NatsConnectionManager {

  private static final Logger LOG = Logger.getLogger(NatsConnectionManager.class);
  private static final String PROPERTY_PREFIX = "io.nats.client.";

  private final NatsConfig config;
  private final Instance<TlsConfigurationRegistry> tlsRegistry;
  private final String applicationName;

  private final Object lock = new Object();
  /** Any connection object jnats reported, even one that never connected, so that it can be closed. */
  private final AtomicReference<Connection> seen = new AtomicReference<>();
  /** The connection once it has been established at least once. */
  private final AtomicReference<Connection> established = new AtomicReference<>();
  private final List<Consumer<Connection>> onConnected = new ArrayList<>();
  private final Connection proxy;
  private boolean started;
  private boolean closed;

  @Inject
  public NatsConnectionManager(NatsConfig config, Instance<TlsConfigurationRegistry> tlsRegistry,
                               @ConfigProperty(name = "quarkus.application.name", defaultValue = "quarkus")
                               String applicationName) {
    this.config = config;
    this.tlsRegistry = tlsRegistry;
    this.applicationName = applicationName;
    this.proxy = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
      new Class<?>[]{Connection.class}, new LazyConnectionHandler(established::get));
  }

  /**
   * @return the connection given to the application, which starts connecting if not done yet
   */
  public Connection connection() {
    start();
    return proxy;
  }

  /**
   * @return whether the connection is currently established
   */
  public boolean isConnected() {
    final Connection connection = established.get();
    return connection != null && connection.getStatus() == Connection.Status.CONNECTED;
  }

  /**
   * Runs an action once the connection is established for the first time, or immediately if it already is. The
   * action runs on the thread that reports the connection.
   *
   * @param action what to do with the real connection
   */
  public void whenConnected(Consumer<Connection> action) {
    final Connection connection;
    synchronized (lock) {
      connection = established.get();
      if (connection == null) {
        onConnected.add(action);
      }
    }
    if (connection != null) {
      action.accept(connection);
    }
    start();
  }

  /**
   * Starts connecting in the background, once.
   */
  public void start() {
    synchronized (lock) {
      if (started || closed) {
        return;
      }
      started = true;
    }
    final Options options = buildOptions();
    LOG.infof("Connecting to NATS %s (retried in the background while unavailable)", String.join(",",
      options.getServers().stream().map(Object::toString).toList()));
    try {
      Nats.connectAsynchronously(options, true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while starting the NATS connection", e);
    }
  }

  Options buildOptions() {
    final Properties properties = new Properties();
    config.options().forEach((key, value) ->
      properties.setProperty(key.startsWith(PROPERTY_PREFIX) ? key : PROPERTY_PREFIX + key, value));
    final Options.Builder builder = new Options.Builder(properties);
    config.url().ifPresent(url -> builder.servers(url.split("\\s*,\\s*")));
    if (config.username().isPresent()) {
      builder.userInfo(config.username().get(), config.password().orElse(""));
    }
    config.token().ifPresent(token -> builder.token(token.toCharArray()));
    config.credsFile().ifPresent(file -> builder.authHandler(Nats.credentials(file)));
    config.tlsConfigurationName().ifPresent(name -> builder.sslContext(sslContext(name)));
    builder.maxReconnects(config.reconnect().max());
    builder.reconnectWait(config.reconnect().waitTime());
    builder.connectionTimeout(config.connectionTimeout());
    if (!properties.containsKey(PROPERTY_PREFIX + "name")) {
      builder.connectionName(applicationName);
    }
    builder.connectionListener(this::onEvent);
    builder.errorListener(new LoggingErrorListener());
    return builder.build();
  }

  private javax.net.ssl.SSLContext sslContext(String name) {
    if (!tlsRegistry.isResolvable()) {
      throw new IllegalStateException("edifice.nats.tls-configuration-name is set but the TLS registry is not available");
    }
    final TlsConfiguration tls = tlsRegistry.get().get(name).orElseThrow(() -> new IllegalStateException(
      "edifice.nats.tls-configuration-name refers to the unknown TLS configuration '" + name + "'"));
    try {
      return tls.createSSLContext();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot create the SSL context of the TLS configuration '" + name + "'", e);
    }
  }

  private void onEvent(Connection connection, ConnectionListener.Events event) {
    remember(connection);
    LOG.debugf("NATS connection event: %s", event);
    if (event != ConnectionListener.Events.CONNECTED && event != ConnectionListener.Events.RECONNECTED) {
      if (event == ConnectionListener.Events.DISCONNECTED && established.get() != null) {
        LOG.warn("NATS connection lost, reconnecting");
      }
      return;
    }
    if (event == ConnectionListener.Events.RECONNECTED) {
      LOG.info("NATS connection re-established");
    }
    final List<Consumer<Connection>> actions;
    synchronized (lock) {
      if (closed || !established.compareAndSet(null, connection)) {
        return;
      }
      actions = new ArrayList<>(onConnected);
      onConnected.clear();
    }
    LOG.infof("Connected to NATS %s", connection.getConnectedUrl());
    for (Consumer<Connection> action : actions) {
      try {
        action.accept(connection);
      } catch (RuntimeException e) {
        LOG.error("Failed to set up a NATS subscription", e);
      }
    }
  }

  private void remember(Connection connection) {
    if (connection == null) {
      return;
    }
    final boolean alreadyClosed;
    synchronized (lock) {
      seen.compareAndSet(null, connection);
      alreadyClosed = closed;
    }
    if (alreadyClosed) {
      // Not on the jnats callback thread, which close() waits for.
      Thread.ofVirtual().name("nats-close").start(() -> closeQuietly(connection));
    }
  }

  void onShutdown(@Observes ShutdownEvent event) {
    close();
  }

  @PreDestroy
  void close() {
    final Connection connection;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      onConnected.clear();
      connection = seen.get();
    }
    if (connection != null) {
      closeQuietly(connection);
      LOG.info("NATS connection closed");
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection.getStatus() == Connection.Status.CLOSED) {
      return;
    }
    try {
      connection.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOG.debug("Failed to close the NATS connection", e);
    }
  }

  private final class LoggingErrorListener implements ErrorListener {
    @Override
    public void errorOccurred(Connection conn, String error) {
      remember(conn);
      LOG.warnf("NATS error: %s", error);
    }

    @Override
    public void exceptionOccurred(Connection conn, Exception exp) {
      remember(conn);
      if (established.get() == null) {
        LOG.debugf("NATS is not reachable yet: %s", exp.getMessage());
      } else {
        LOG.warnf("NATS connection error: %s", exp.getMessage());
      }
    }
  }
}
