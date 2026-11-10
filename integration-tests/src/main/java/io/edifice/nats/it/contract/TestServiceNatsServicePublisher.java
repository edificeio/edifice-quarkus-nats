package io.edifice.nats.it.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * The client of the test contract, written the way the generator writes the client artifacts.
 */
@ApplicationScoped
public class TestServiceNatsServicePublisher {

  @Inject
  Connection connection;
  @Inject
  ObjectMapper objectMapper;

  @ConfigProperty(name = "edifice.nats.request-timeout", defaultValue = "${broker.request.timeout:10000}")
  long requestTimeout;

  public EchoResponse echo(EchoRequest request) {
    return doRequest("it.echo", request, EchoResponse.class);
  }

  public EchoResponse getUser(EchoRequest request, String userId) {
    return doRequest("it.users." + userId + ".get", request, EchoResponse.class);
  }

  public void notifyEvent(EchoRequest request, String kind) {
    doPublish("it.events." + kind, request);
  }

  public EchoResponse fail(EchoRequest request) {
    return doRequest("it.fail", request, EchoResponse.class);
  }

  public EchoResponse scoped(EchoRequest request) {
    return doRequest("it.scoped", request, EchoResponse.class);
  }

  public void block(EchoRequest request) {
    doPublish("it.block", request);
  }

  public void count(EchoRequest request) {
    doPublish("it.count", request);
  }

  public void broadcastEvent(EchoRequest request) {
    doPublish("it.broadcast", request);
  }

  private void doPublish(final String subject, final Object request) {
    try {
      this.connection.publish(subject, this.objectMapper.writeValueAsBytes(request));
    } catch (Exception e) {
      throw new RuntimeException("Failed to publish to NATS subject " + subject, e);
    }
  }

  private <T> T doRequest(final String subject, final Object request, final Class<T> responseType) {
    final JsonNode responseTree;
    try {
      final byte[] payload = this.objectMapper.writeValueAsBytes(request);
      final Message reply = this.connection.request(subject, payload, Duration.ofMillis(requestTimeout));
      if (reply == null) {
        throw new RuntimeException("No reply received from NATS subject " + subject);
      }
      responseTree = this.objectMapper.readTree(reply.getData());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to send request to NATS subject " + subject, e);
    }
    final JsonNode err = responseTree.get("err");
    if (err != null && !err.isNull()) {
      Log.debug("An error occurred while requesting subject " + subject + " : " + err.asText());
      throw new RuntimeException(err.asText());
    }
    final JsonNode responseNode = responseTree.has("response") ? responseTree.get("response") : responseTree;
    try {
      return this.objectMapper.treeToValue(responseNode, responseType);
    } catch (Exception e) {
      throw new RuntimeException("Malformed reply received from NATS subject " + subject, e);
    }
  }
}
