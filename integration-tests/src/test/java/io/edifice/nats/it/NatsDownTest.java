package io.edifice.nats.it;

import io.edifice.nats.it.contract.EchoRequest;
import io.edifice.nats.it.contract.TestServiceNatsServicePublisher;
import io.nats.client.Connection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application starts and answers while NATS is unreachable.
 */
@QuarkusTest
@TestProfile(NatsDownTest.NatsDown.class)
class NatsDownTest {

  @Inject
  Connection connection;
  @Inject
  TestServiceNatsServicePublisher client;

  @Test
  void applicationStartsWithoutNats() {
    assertEquals(Connection.Status.DISCONNECTED, connection.getStatus());
  }

  @Test
  void callsFailFast() {
    final long start = System.nanoTime();
    final RuntimeException error = assertThrows(RuntimeException.class, () -> client.echo(new EchoRequest("x")));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
    assertTrue(error.getMessage().contains("not available"), error.getMessage());
  }

  @Test
  void readinessIsDown() {
    given().when().get("/q/health/ready").then()
      .statusCode(503)
      .body("checks.find { it.name == 'nats-connection' }.status", equalTo("DOWN"));
  }

  public static class NatsDown implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
        "edifice.nats.url", "nats://127.0.0.1:1",
        "edifice.nats.devservices.enabled", "false");
    }
  }
}
