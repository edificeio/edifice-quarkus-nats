package io.edifice.nats.deployment;

import io.quarkus.builder.item.MultiBuildItem;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;

import java.util.List;

/**
 * A method annotated with {@code @BrokerListener}, and the concrete classes that implement it.
 */
public final class ListenerMethodBuildItem extends MultiBuildItem {

  private final MethodInfo method;
  private final String subject;
  private final String description;
  private final List<DotName> implementors;
  private final boolean broadcast;

  ListenerMethodBuildItem(MethodInfo method, String subject, String description, List<DotName> implementors,
                          boolean broadcast) {
    this.method = method;
    this.subject = subject;
    this.description = description;
    this.implementors = List.copyOf(implementors);
    this.broadcast = broadcast;
  }

  public MethodInfo method() {
    return method;
  }

  public String subject() {
    return subject;
  }

  public String description() {
    return description;
  }

  public List<DotName> implementors() {
    return implementors;
  }

  public boolean broadcast() {
    return broadcast;
  }

  /**
   * @return the method as {@code Interface#method}, for messages
   */
  public String name() {
    return method.declaringClass().name() + "#" + method.name();
  }
}
