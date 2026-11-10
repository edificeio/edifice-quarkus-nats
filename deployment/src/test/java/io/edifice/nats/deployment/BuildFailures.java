package io.edifice.nats.deployment;

import io.quarkus.test.QuarkusExtensionTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Helpers for the tests of the build failures.
 */
final class BuildFailures {

  private BuildFailures() {
  }

  /**
   * An application made of the given classes, without NATS.
   */
  static QuarkusExtensionTest application(Class<?>... classes) {
    return new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar.addClasses(classes))
      .overrideConfigKey("edifice.nats.devservices.enabled", "false")
      .overrideConfigKey("edifice.nats.url", "nats://127.0.0.1:1");
  }

  /**
   * Checks that the build failed with a message containing the given text.
   */
  static Consumer<Throwable> failsWith(String expected) {
    return thrown -> {
      final List<String> messages = messages(thrown);
      assertTrue(messages.stream().anyMatch(m -> m.contains(expected)),
        "expected a build error containing '" + expected + "' but got " + messages);
    };
  }

  private static List<String> messages(Throwable thrown) {
    final List<String> messages = new ArrayList<>();
    final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    final List<Throwable> todo = new ArrayList<>(List.of(thrown));
    while (!todo.isEmpty()) {
      final Throwable t = todo.removeLast();
      if (t == null || !seen.add(t)) {
        continue;
      }
      messages.add(String.valueOf(t.getMessage()));
      todo.add(t.getCause());
      todo.addAll(List.of(t.getSuppressed()));
    }
    return messages;
  }
}
