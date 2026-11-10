package io.edifice.nats.it;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the listeners received, for the tests to check.
 */
@ApplicationScoped
public class Recorded {

  public final List<String> events = new CopyOnWriteArrayList<>();
  public final AtomicInteger counted = new AtomicInteger();
  public final AtomicInteger blocked = new AtomicInteger();
  public final AtomicInteger unblocked = new AtomicInteger();
  public volatile CountDownLatch release = new CountDownLatch(1);
}
