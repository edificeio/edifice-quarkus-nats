package io.edifice.nats.deployment;

import io.edifice.nats.api.BrokerListener;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.fail;

class NoImplementorTest {

  @RegisterExtension
  static final QuarkusExtensionTest TEST = BuildFailures.application(Listener.class, Request.class)
    .assertException(BuildFailures.failsWith("No class implements"));

  @Test
  void buildFails() {
    fail("the build should have failed");
  }

  public interface Listener {
    @BrokerListener(subject = "test.orphan")
    void orphan(Request request);
  }

  public record Request(String value) {
  }
}
