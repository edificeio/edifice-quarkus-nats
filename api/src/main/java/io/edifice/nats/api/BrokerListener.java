package io.edifice.nats.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that answers the NATS messages received on a subject.
 * <p>
 * The method takes exactly one parameter, the request, read from the JSON payload of the message. Its return value is
 * sent back as {@code {"response": ...}} when the message has a reply subject. A method returning {@code void} never
 * replies.
 * <p>
 * The annotation is usually put on the methods of a generated listener interface. The application implements the
 * interface in a single class, which the extension makes an {@code @ApplicationScoped} bean.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BrokerListener {

  /**
   * The subject listened to.
   * <p>
   * A token written {@code {name}} is a subject parameter: the extension listens on {@code *} in its place and gives its
   * value through {@link NatsMessageContext#subjectParam(String)}. The NATS wildcards {@code *} and {@code >} are also
   * accepted.
   *
   * @return the subject, for example {@code directory.users.{userId}.get}
   */
  String subject();

  /**
   * What the endpoint does, for logs and documentation.
   *
   * @return a one-line description
   */
  String description() default "";
}
