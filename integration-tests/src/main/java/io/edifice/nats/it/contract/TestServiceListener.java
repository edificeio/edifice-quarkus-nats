package io.edifice.nats.it.contract;

import io.edifice.nats.api.BrokerListener;

/**
 * The listener interface of the test contract, written the way the generator writes the server artifacts.
 */
public interface TestServiceListener {

  @BrokerListener(subject = "it.echo", description = "Answers the message in upper case.")
  EchoResponse echo(EchoRequest request);

  @BrokerListener(subject = "it.users.{userId}.get", description = "Answers the user id and a header.")
  EchoResponse getUser(EchoRequest request);

  @BrokerListener(subject = "it.events.{kind}", description = "Records an event, without answering.")
  void notifyEvent(EchoRequest request);

  @BrokerListener(subject = "it.fail", description = "Always fails.")
  EchoResponse fail(EchoRequest request);

  @BrokerListener(subject = "it.scoped", description = "Answers the ids of its request scoped bean.")
  EchoResponse scoped(EchoRequest request);

  @BrokerListener(subject = "it.block", description = "Blocks until the test releases it.")
  void block(EchoRequest request);

  @BrokerListener(subject = "it.count", description = "Counts the messages received.")
  void count(EchoRequest request);
}
