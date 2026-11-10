package io.edifice.nats.deployment;

import io.edifice.nats.api.BrokerListener;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.fail;

class SeveralImplementorsTest {

  @RegisterExtension
  static final QuarkusExtensionTest TEST = BuildFailures.application(Listener.class, Request.class, First.class,
      Second.class)
    .assertException(BuildFailures.failsWith("Several classes implement"));

  @Test
  void buildFails() {
    fail("the build should have failed");
  }

  public interface Listener {
    @BrokerListener(subject = "test.twice")
    void twice(Request request);
  }

  public static class First implements Listener {
    @Override
    public void twice(Request request) {
    }
  }

  public static class Second implements Listener {
    @Override
    public void twice(Request request) {
    }
  }

  public record Request(String value) {
  }
}
