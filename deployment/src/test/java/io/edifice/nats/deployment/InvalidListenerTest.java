package io.edifice.nats.deployment;

import io.edifice.nats.api.BrokerListener;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every problem is reported at once.
 */
class InvalidListenerTest {

  @RegisterExtension
  static final QuarkusExtensionTest TEST = BuildFailures.application(Listener.class, Impl.class, Request.class)
    .assertException(BuildFailures.failsWith("Invalid subject 'test.user{id}'")
      .andThen(BuildFailures.failsWith("must take exactly one parameter"))
      .andThen(BuildFailures.failsWith("must be a class")));

  @Test
  void buildFails() {
    fail("the build should have failed");
  }

  public interface Listener {
    @BrokerListener(subject = "test.user{id}")
    void partialParameter(Request request);

    @BrokerListener(subject = "test.two")
    void twoParameters(Request request, String other);

    @BrokerListener(subject = "test.list")
    void list(List<Request> requests);
  }

  public static class Impl implements Listener {
    @Override
    public void partialParameter(Request request) {
    }

    @Override
    public void twoParameters(Request request, String other) {
    }

    @Override
    public void list(List<Request> requests) {
    }
  }

  public record Request(String value) {
  }
}
