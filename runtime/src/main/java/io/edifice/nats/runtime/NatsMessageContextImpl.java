package io.edifice.nats.runtime;

import io.edifice.nats.api.NatsMessageContext;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import jakarta.enterprise.context.RequestScoped;

import java.util.Collections;
import java.util.Map;

/**
 * The {@link NatsMessageContext} of the message being handled, filled by the dispatcher once the request context of
 * the message is active.
 */
@RequestScoped
public class NatsMessageContextImpl implements NatsMessageContext {

  private static final Headers NO_HEADERS = new Headers(null, true);

  private Message message;
  private Map<String, String> subjectParams = Map.of();

  /**
   * Sets the message being handled, called once per request context.
   *
   * @param message       the message received
   * @param subjectParams its subject parameters
   */
  public void init(Message message, Map<String, String> subjectParams) {
    this.message = message;
    this.subjectParams = Collections.unmodifiableMap(subjectParams);
  }

  @Override
  public Message message() {
    return message;
  }

  @Override
  public String subject() {
    return message == null ? null : message.getSubject();
  }

  @Override
  public String replyTo() {
    return message == null ? null : message.getReplyTo();
  }

  @Override
  public Headers headers() {
    final Headers headers = message == null ? null : message.getHeaders();
    return headers == null ? NO_HEADERS : headers;
  }

  @Override
  public String subjectParam(String name) {
    return subjectParams.get(name);
  }

  @Override
  public Map<String, String> subjectParams() {
    return subjectParams;
  }
}
