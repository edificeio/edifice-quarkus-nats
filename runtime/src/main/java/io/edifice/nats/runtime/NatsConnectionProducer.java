package io.edifice.nats.runtime;

import io.nats.client.Connection;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the NATS {@link Connection} injected by the generated clients and by the application.
 * <p>
 * The connection is a proxy, returned at once whether NATS is reachable or not. An application can replace it by
 * producing its own {@link Connection}.
 */
@Singleton
public class NatsConnectionProducer {

  @Produces
  @Singleton
  @DefaultBean
  Connection connection(NatsConnectionManager manager) {
    return manager.connection();
  }
}
