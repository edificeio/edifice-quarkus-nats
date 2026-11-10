package io.edifice.nats.api;

import io.nats.client.Message;
import io.nats.client.impl.Headers;

import java.util.Map;

/**
 * The NATS message being handled by a {@link BrokerListener} method.
 * <p>
 * Inject it in the listener, or in any bean it calls: it is request scoped, and a request context is active for the
 * whole handling of each message. Using it outside of a listener call throws a {@code ContextNotActiveException}.
 */
public interface NatsMessageContext {

  /**
   * @return the raw message received
   */
  Message message();

  /**
   * @return the subject the message was published on, with the subject parameters filled in
   */
  String subject();

  /**
   * @return the subject to reply to, or {@code null} for a message that expects no answer
   */
  String replyTo();

  /**
   * @return the headers of the message, empty when it has none
   */
  Headers headers();

  /**
   * The value of a subject parameter, the token written {@code {name}} in the subject of the listener.
   *
   * @param name the name of the parameter, without braces
   * @return its value in the subject of the message, or {@code null} if the subject has no such parameter
   */
  String subjectParam(String name);

  /**
   * @return every subject parameter by name, in the order of the subject
   */
  Map<String, String> subjectParams();
}
