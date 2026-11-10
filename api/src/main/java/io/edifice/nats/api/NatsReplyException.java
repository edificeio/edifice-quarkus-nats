package io.edifice.nats.api;

/**
 * An error that a {@link BrokerListener} method returns to its caller.
 * <p>
 * Its message becomes the {@code err} of the reply, {@code {"err": "message"}}, and the reply carries the header
 * {@code failed: true}. It is logged at debug level only, since it is an expected answer rather than a failure of the
 * service. Any other exception is answered the same way but logged as an error.
 */
public class NatsReplyException extends RuntimeException {

  /**
   * @param message the error sent to the caller
   */
  public NatsReplyException(String message) {
    super(message);
  }

  /**
   * @param message the error sent to the caller
   * @param cause   the underlying failure, logged but not sent
   */
  public NatsReplyException(String message, Throwable cause) {
    super(message, cause);
  }
}
