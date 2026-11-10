package io.edifice.nats.deployment;

import io.edifice.nats.runtime.SubjectTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubjectTemplateTest {

  @Test
  void parametersBecomeWildcards() {
    final SubjectTemplate subject = SubjectTemplate.parse("directory.users.{userId}.groups.{groupId}");
    assertEquals("directory.users.*.groups.*", subject.wildcard());
    assertEquals(Map.of("userId", "u1", "groupId", "g1"), subject.extract("directory.users.u1.groups.g1"));
  }

  @Test
  void natsWildcardsAreKept() {
    final SubjectTemplate subject = SubjectTemplate.parse("audit.*.{action}.>");
    assertEquals("audit.*.*.>", subject.wildcard());
    assertEquals(Map.of("action", "create"), subject.extract("audit.x.create.a.b"));
  }

  @Test
  void literalSubjectHasNoParameter() {
    assertEquals(Map.of(), SubjectTemplate.parse("a.b").extract("a.b"));
  }

  @Test
  void invalidSubjectsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> SubjectTemplate.parse(""));
    assertThrows(IllegalArgumentException.class, () -> SubjectTemplate.parse("a.b{x}"));
    assertThrows(IllegalArgumentException.class, () -> SubjectTemplate.parse("a.>.b"));
    assertThrows(IllegalArgumentException.class, () -> SubjectTemplate.parse("a..b"));
    assertThrows(IllegalArgumentException.class, () -> SubjectTemplate.parse("a.{x}.{x}"));
  }
}
