package io.edifice.nats.deployment;

import io.edifice.nats.api.BrokerListener;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.fail;

class DuplicateSubjectTest {

  @RegisterExtension
  static final QuarkusExtensionTest TEST = BuildFailures.application(Users.class, Groups.class, Request.class,
      UsersImpl.class, GroupsImpl.class)
    .assertException(BuildFailures.failsWith("is already listened to by"));

  @Test
  void buildFails() {
    fail("the build should have failed");
  }

  public interface Users {
    @BrokerListener(subject = "test.{userId}.get")
    void get(Request request);
  }

  public interface Groups {
    // Another parameter name, but the same wildcard subject.
    @BrokerListener(subject = "test.{groupId}.get")
    void get(Request request);
  }

  public static class UsersImpl implements Users {
    @Override
    public void get(Request request) {
    }
  }

  public static class GroupsImpl implements Groups {
    @Override
    public void get(Request request) {
    }
  }

  public record Request(String value) {
  }
}
