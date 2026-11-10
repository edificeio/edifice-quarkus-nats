package io.edifice.nats.deployment;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A listener ready to be registered: its bean method and the invoker generated for it.
 */
public final class NatsListenerBuildItem extends MultiBuildItem {

  private final String subject;
  private final String description;
  private final String target;
  private final String requestType;
  private final boolean replies;
  private final String invokerClass;

  NatsListenerBuildItem(String subject, String description, String target, String requestType, boolean replies,
                        String invokerClass) {
    this.subject = subject;
    this.description = description;
    this.target = target;
    this.requestType = requestType;
    this.replies = replies;
    this.invokerClass = invokerClass;
  }

  public String subject() {
    return subject;
  }

  public String description() {
    return description;
  }

  public String target() {
    return target;
  }

  public String requestType() {
    return requestType;
  }

  public boolean replies() {
    return replies;
  }

  public String invokerClass() {
    return invokerClass;
  }
}
