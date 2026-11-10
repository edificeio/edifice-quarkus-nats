package io.edifice.nats.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Reports the NATS connection as a readiness check, named {@code nats-connection}.
 * <p>
 * A lost connection takes the service out of the load balancing without restarting it: the connection is retried in
 * the background. Registered only when quarkus-smallrye-health is present.
 */
@Readiness
@ApplicationScoped
public class NatsConnectionHealthCheck implements HealthCheck {

  @Inject
  NatsConnectionManager connectionManager;

  @Override
  public HealthCheckResponse call() {
    connectionManager.start();
    return HealthCheckResponse.named("nats-connection")
      .status(connectionManager.isConnected())
      .build();
  }
}
