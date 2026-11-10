package io.edifice.nats.it;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request scoped bean used by a listener: one instance per message, destroyed when the message is handled.
 */
@RequestScoped
public class RequestTracker {

  public static final AtomicInteger DESTROYED = new AtomicInteger();

  private String id;

  @PostConstruct
  void init() {
    id = UUID.randomUUID().toString();
  }

  public String id() {
    return id;
  }

  @PreDestroy
  void destroy() {
    DESTROYED.incrementAndGet();
  }
}
