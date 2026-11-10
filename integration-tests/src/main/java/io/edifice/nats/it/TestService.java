package io.edifice.nats.it;

import io.edifice.nats.api.NatsMessageContext;
import io.edifice.nats.api.NatsReplyException;
import io.edifice.nats.it.contract.EchoRequest;
import io.edifice.nats.it.contract.EchoResponse;
import io.edifice.nats.it.contract.TestServiceListener;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implements the test contract. Like an application would, it has no scope annotation: the extension adds one.
 */
public class TestService implements TestServiceListener {

  @Inject
  NatsMessageContext context;
  @Inject
  RequestTracker tracker;
  @Inject
  Recorded recorded;

  @Override
  public EchoResponse echo(EchoRequest request) {
    return new EchoResponse(request.message().toUpperCase(), context.subject(),
      Map.of("thread", Thread.currentThread().isVirtual() ? "virtual" : "platform"));
  }

  @Override
  public EchoResponse getUser(EchoRequest request) {
    final Map<String, String> values = new HashMap<>(context.subjectParams());
    final String tenant = context.headers().getFirst("x-tenant");
    if (tenant != null) {
      values.put("tenant", tenant);
    }
    values.put("userIdParam", context.subjectParam("userId"));
    return new EchoResponse(request.message(), context.subject(), values);
  }

  @Override
  public void notifyEvent(EchoRequest request) {
    recorded.events.add(context.subjectParam("kind") + ":" + request.message()
      + ":" + (context.replyTo() == null ? "no-reply" : "reply"));
  }

  @Override
  public EchoResponse fail(EchoRequest request) {
    if ("crash".equals(request.message())) {
      throw new IllegalStateException("unexpected crash");
    }
    throw new NatsReplyException("refused: " + request.message());
  }

  @Override
  public EchoResponse scoped(EchoRequest request) {
    final String first = tracker.id();
    final String second = tracker.id();
    return new EchoResponse(request.message(), context.subject(), Map.of("first", first, "second", second));
  }

  @Override
  public void block(EchoRequest request) {
    recorded.blocked.incrementAndGet();
    try {
      recorded.release.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    recorded.unblocked.incrementAndGet();
  }

  @Override
  public void count(EchoRequest request) {
    recorded.counted.incrementAndGet();
  }
}
