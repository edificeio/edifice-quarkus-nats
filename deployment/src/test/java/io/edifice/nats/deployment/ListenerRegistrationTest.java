package io.edifice.nats.deployment;

import io.edifice.nats.api.BrokerListener;
import io.edifice.nats.runtime.NatsListenerDefinition;
import io.edifice.nats.runtime.NatsListenerDispatcher;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener implementation with no annotation becomes an application scoped bean, kept although nothing injects it.
 */
class ListenerRegistrationTest {

  @RegisterExtension
  static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
    .withApplicationRoot(jar -> jar.addClasses(Listener.class, Impl.class, Request.class, Response.class))
    .overrideConfigKey("edifice.nats.devservices.enabled", "false")
    .overrideConfigKey("edifice.nats.url", "nats://127.0.0.1:1");

  @Inject
  NatsListenerDispatcher dispatcher;

  @Test
  void implementorIsAnApplicationScopedBean() {
    final InjectableBean<Impl> bean = Arc.container().instance(Impl.class).getBean();
    assertEquals(ApplicationScoped.class, bean.getScope());
  }

  @Test
  void listenersAreRegistered() {
    final List<NatsListenerDefinition> listeners = dispatcher.listeners();
    assertEquals(2, listeners.size());
    final NatsListenerDefinition get = listeners.stream()
      .filter(l -> l.subject().template().equals("test.users.{userId}.get")).findFirst().orElseThrow();
    assertEquals("test.users.*.get", get.subject().wildcard());
    assertEquals(Request.class, get.requestType());
    assertTrue(get.replies());
    assertEquals("Gets a user", get.description());
    final NatsListenerDefinition notify = listeners.stream()
      .filter(l -> l.subject().template().equals("test.notify.>")).findFirst().orElseThrow();
    assertFalse(notify.replies());
  }

  public interface Listener {
    @BrokerListener(subject = "test.users.{userId}.get", description = "Gets a user")
    Response get(Request request);

    @BrokerListener(subject = "test.notify.>")
    void notifyAll(Request request);
  }

  public static class Impl implements Listener {
    @Override
    public Response get(Request request) {
      return new Response(request.value());
    }

    @Override
    public void notifyAll(Request request) {
    }
  }

  public record Request(String value) {
  }

  public record Response(String value) {
  }
}
