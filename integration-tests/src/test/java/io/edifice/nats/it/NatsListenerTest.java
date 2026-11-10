package io.edifice.nats.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.edifice.nats.it.contract.EchoRequest;
import io.edifice.nats.it.contract.EchoResponse;
import io.edifice.nats.it.contract.TestServiceNatsServicePublisher;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class NatsListenerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Inject
  TestServiceNatsServicePublisher client;
  @Inject
  Connection connection;
  @Inject
  ObjectMapper objectMapper;
  @Inject
  Recorded recorded;
  @ConfigProperty(name = "edifice.nats.queue-group")
  String queueGroup;

  @BeforeEach
  void connected() {
    await().atMost(Duration.ofSeconds(20)).until(() -> connection.getStatus() == Connection.Status.CONNECTED);
  }

  @Test
  void requestReplyThroughTheGeneratedClient() {
    final EchoResponse response = client.echo(new EchoRequest("hello"));
    assertEquals("HELLO", response.message());
    assertEquals("it.echo", response.subject());
    assertEquals("virtual", response.values().get("thread"));
  }

  @Test
  void replyIsTheResponseEnvelopeWithTheFailedHeader() throws Exception {
    final Message reply = connection.request("it.echo", json("{\"message\":\"raw\"}"), TIMEOUT);
    assertEquals("false", reply.getHeaders().getFirst("failed"));
    final JsonNode body = objectMapper.readTree(reply.getData());
    assertEquals("RAW", body.path("response").path("message").asText());
    assertTrue(body.path("err").isMissingNode());
  }

  @Test
  void brokerNatsDataWrapperIsUnwrapped() throws Exception {
    final Message reply = connection.request("it.echo", json("{\"data\":{\"message\":\"wrapped\"}}"), TIMEOUT);
    assertEquals("WRAPPED", objectMapper.readTree(reply.getData()).path("response").path("message").asText());
  }

  @Test
  void fireAndForgetIsHandledWithoutReply() {
    client.notifyEvent(new EchoRequest("payload"), "created");
    await().atMost(TIMEOUT).until(() -> recorded.events.contains("created:payload:no-reply"));
  }

  @Test
  void voidListenerNeverReplies() throws Exception {
    final var reply = connection.request("it.events.requested", json("{\"message\":\"x\"}"));
    assertThrows(Exception.class, () -> reply.get(1, java.util.concurrent.TimeUnit.SECONDS));
    await().atMost(TIMEOUT).until(() -> recorded.events.contains("requested:x:reply"));
  }

  @Test
  void subjectParametersAndHeadersAreInTheContext() throws Exception {
    final Headers headers = new Headers().put("x-tenant", "tenant-1");
    final EchoResponse response = client.getUser(new EchoRequest("who"), "42");
    assertEquals("42", response.values().get("userId"));
    assertEquals("42", response.values().get("userIdParam"));
    assertEquals("it.users.42.get", response.subject());

    final Message withHeaders = connection.request(io.nats.client.impl.NatsMessage.builder()
      .subject("it.users.7.get").headers(headers).data(json("{\"message\":\"h\"}")).build(), TIMEOUT);
    final JsonNode values = objectMapper.readTree(withHeaders.getData()).path("response").path("values");
    assertEquals("7", values.path("userId").asText());
    assertEquals("tenant-1", values.path("tenant").asText());
  }

  @Test
  void replyExceptionBecomesErr() throws Exception {
    final RuntimeException error = assertThrows(RuntimeException.class, () -> client.fail(new EchoRequest("x")));
    assertEquals("refused: x", error.getMessage());

    final Message reply = connection.request("it.fail", json("{\"message\":\"y\"}"), TIMEOUT);
    assertEquals("true", reply.getHeaders().getFirst("failed"));
    final JsonNode body = objectMapper.readTree(reply.getData());
    assertEquals("refused: y", body.path("err").asText());
    assertTrue(body.path("response").isMissingNode());
  }

  @Test
  void unexpectedExceptionBecomesErr() {
    final RuntimeException error = assertThrows(RuntimeException.class,
      () -> client.fail(new EchoRequest("crash")));
    assertEquals("unexpected crash", error.getMessage());
  }

  @Test
  void invalidRequestBecomesErr() throws Exception {
    final Message reply = connection.request("it.echo", json("{not json"), TIMEOUT);
    assertEquals("true", reply.getHeaders().getFirst("failed"));
    assertTrue(objectMapper.readTree(reply.getData()).path("err").asText().startsWith("Invalid request"));
  }

  @Test
  void requestScopedBeanLivesForOneMessage() {
    final int destroyedBefore = RequestTracker.DESTROYED.get();
    final EchoResponse first = client.scoped(new EchoRequest("a"));
    final EchoResponse second = client.scoped(new EchoRequest("b"));
    assertEquals(first.values().get("first"), first.values().get("second"));
    assertEquals(second.values().get("first"), second.values().get("second"));
    assertNotEquals(first.values().get("first"), second.values().get("first"));
    await().atMost(TIMEOUT).until(() -> RequestTracker.DESTROYED.get() >= destroyedBefore + 2);
  }

  @Test
  void blockingListenerDoesNotStallTheOthers() {
    recorded.release = new CountDownLatch(1);
    final int unblockedBefore = recorded.unblocked.get();
    final int blockedBefore = recorded.blocked.get();
    client.block(new EchoRequest("1"));
    client.block(new EchoRequest("2"));
    // Both run at the same time: the second message is not queued behind the first.
    await().atMost(TIMEOUT).until(() -> recorded.blocked.get() == blockedBefore + 2);
    final long start = System.nanoTime();
    assertEquals("STILL", client.echo(new EchoRequest("still")).message());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
    assertEquals(unblockedBefore, recorded.unblocked.get());
    recorded.release.countDown();
    await().atMost(TIMEOUT).until(() -> recorded.unblocked.get() == unblockedBefore + 2);
  }

  @Test
  void listenersShareTheirMessagesInTheQueueGroup() throws Exception {
    assertEquals("nats-it", queueGroup);
    final AtomicInteger stolen = new AtomicInteger();
    final var dispatcher = connection.createDispatcher();
    final Subscription other = dispatcher.subscribe("it.count", queueGroup, message -> stolen.incrementAndGet());
    try {
      connection.flush(TIMEOUT);
      final int countedBefore = recorded.counted.get();
      final int messages = 60;
      for (int i = 0; i < messages; i++) {
        client.count(new EchoRequest(String.valueOf(i)));
      }
      await().atMost(TIMEOUT).until(() -> recorded.counted.get() - countedBefore + stolen.get() == messages);
      final int counted = recorded.counted.get() - countedBefore;
      assertTrue(counted > 0 && counted < messages, "both members of the group received messages: " + counted);
    } finally {
      dispatcher.unsubscribe(other);
      connection.closeDispatcher(dispatcher);
    }
  }

  @Test
  void readinessReportsTheConnection() {
    given().when().get("/q/health/ready").then()
      .statusCode(200)
      .body("checks.name", hasItem("nats-connection"))
      .body("checks.find { it.name == 'nats-connection' }.status", equalTo("UP"));
  }

  @Test
  void listenerBeanIsApplicationScoped() {
    final var bean = io.quarkus.arc.Arc.container().instance(TestService.class);
    assertTrue(bean.isAvailable());
    assertNotNull(bean.getBean());
    assertEquals(jakarta.enterprise.context.ApplicationScoped.class, bean.getBean().getScope());
  }

  private static byte[] json(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
