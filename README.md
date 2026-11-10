# edifice-quarkus-nats

Quarkus extension that serves and calls the NATS contracts of the Edifice services. The contracts are written in
protobuf in [edifice-events-definition](https://github.com/edificeio/edifice-events-definition), whose generator
produces, for each domain:

- `<domain>-quarkus-api`: the DTO records;
- `<domain>-quarkus-client`: an `@ApplicationScoped XxxNatsServicePublisher` that injects the NATS `Connection` and
  the `ObjectMapper` provided here;
- `<domain>-quarkus-server`: an interface `XxxListener` whose methods carry `@BrokerListener`.

This repository replaces `edifice-quarkus-contracts-parent` 1.x, which stays frozen.

| Artifact | Content |
|---|---|
| `io.edifice:edifice-quarkus-nats-api` | `@BrokerListener`, `NatsMessageContext`, `NatsReplyException`. A plain jar, no Quarkus dependency. The generated server artifacts depend on it with the `provided` scope. |
| `io.edifice:edifice-quarkus-nats` | The extension: the connection, the listener dispatch, the health check. Add it to the service. |
| `io.edifice:edifice-quarkus-nats-deployment` | Its build steps and dev service. Resolved by Quarkus, never declared. |

Versions: Quarkus 3.36, Java 25, jnats 2.26. Jackson is the one of the Quarkus BOM.

## Usage

```xml
<dependency>
  <groupId>io.edifice</groupId>
  <artifactId>edifice-quarkus-nats</artifactId>
  <version>2.0.0</version>
</dependency>
<!-- The contracts the service serves or calls -->
<dependency>
  <groupId>io.edifice</groupId>
  <artifactId>directory-quarkus-server</artifactId>
  <version>...</version>
</dependency>
```

Serving a contract means implementing its listener interface in one class:

```java
public class DirectoryService implements DirectoryListener {

  @Inject
  NatsMessageContext context;

  @Override
  public UserResponse getUser(GetUserRequest request) {   // @BrokerListener(subject = "directory.users.{userId}.get")
    final String userId = context.subjectParam("userId");
    final String tenant = context.headers().getFirst("x-tenant");
    if (userId.isBlank()) {
      throw new NatsReplyException("userId is required"); // the caller receives {"err": "userId is required"}
    }
    return ...;
  }
}
```

No annotation is needed on the class: the extension makes it an `@ApplicationScoped` bean and keeps it although nothing
injects it. A class may declare another scope, and inject anything.

Calling a contract means injecting its generated client, `XxxNatsServicePublisher`.

## Features

- **Lazy, asynchronous connection.** The connection is opened in the background with `Nats.connectAsynchronously` and
  retried while NATS is unreachable: the application starts without NATS. The injected `Connection` is a proxy that
  fails fast with an `IllegalStateException` until NATS has been reached once, then delegates to the real connection.
  It is closed on shutdown, even if it never connected. An application can replace it by producing its own
  `Connection`.
- **Listener discovery at build time.** The `@BrokerListener` methods are found in the Jandex index, usually on the
  interfaces of the generated server jars. The build fails, with every problem listed, when an interface has no
  implementation or several, when two methods listen on the same subject, when a subject is invalid, or when a method
  does not take exactly one request whose type is a class.
- **No reflection to call listeners.** Each method is called through an ArC `Invoker` generated at build time. The
  request and response types are registered for reflection in native mode, for Jackson.
- **Subject parameters.** A token `{name}` of the subject is listened to as `*` and its value is given by
  `NatsMessageContext.subjectParam("name")`. The NATS wildcards `*` and `>` are accepted as is.
- **One thread per message.** The NATS dispatcher thread only hands each message off to a virtual thread (default) or to
  the Quarkus worker pool: a slow or blocking listener never delays another one, nor another message of its own subject.
- **Request context.** A request context is active for the whole handling of a message, so `@RequestScoped` beans work in
  listeners and in what they call. `NatsMessageContext`, request scoped, gives the raw message, its subject, reply
  subject, headers and subject parameters. The listener signatures stay those of the generated interfaces.
- **Queue group, read at runtime.** Every instance of a service subscribes in the queue group
  `edifice.nats.queue-group`, the application name by default, so that each message is handled once.
- **Readiness check** `nats-connection`, registered when the application has `quarkus-smallrye-health`.
- **Dev service.** In dev and test modes, without `edifice.nats.url`, a NATS container is started and
  `edifice.nats.url` points to it. In dev mode it is shared between the applications through the label
  `edifice-dev-service-nats`.

## Configuration

Runtime:

| Property | Default | Description |
|---|---|---|
| `edifice.nats.url` | `nats://localhost:4222` | The servers, comma separated. Set by the dev service. |
| `edifice.nats.username` / `password` | | User and password. |
| `edifice.nats.token` | | Authentication token. |
| `edifice.nats.creds-file` | | NATS credentials file (JWT and NKey). |
| `edifice.nats.tls-configuration-name` | | Name of a TLS configuration of the Quarkus TLS registry, `quarkus.tls.<name>.*`. |
| `edifice.nats.reconnect.max` | `-1` | Reconnection attempts after a disconnection, `-1` for ever. |
| `edifice.nats.reconnect.wait-time` | `2s` | Wait between two attempts on the same server. |
| `edifice.nats.connection-timeout` | `2s` | Timeout of the connection to a server. |
| `edifice.nats.queue-group` | `${quarkus.application.name}` | Queue group of the listeners. Empty: no queue group. |
| `edifice.nats.request-timeout` | `${broker.request.timeout:10000}` | Timeout of the requests of the generated clients, in milliseconds. |
| `edifice.nats.listener.execution` | `virtual-threads` | Where listeners run: `virtual-threads` or `worker`. |
| `edifice.nats.options."<property>"` | | Any other jnats option, passed to `Options.Builder(Properties)`, with or without the `io.nats.client.` prefix, for example `edifice.nats.options.maxpings=3`. The properties above take precedence. |

Build time:

| Property | Default | Description |
|---|---|---|
| `edifice.nats.health.enabled` | `true` | Registers the readiness check. |
| `edifice.nats.devservices.enabled` | `true` | Starts the NATS dev service. |
| `edifice.nats.devservices.image-name` | `nats:2.11-alpine` | Image of the container. |
| `edifice.nats.devservices.port` | random | Fixed host port. |
| `edifice.nats.devservices.shared` | `true` | Shares the container between applications in dev mode. |
| `edifice.nats.devservices.service-name` | `nats` | Value of the sharing label. |
| `edifice.nats.devservices.args` | | Arguments of `nats-server`, for example `--js`. |
| `edifice.nats.devservices.container-env."<name>"` | | Environment variables of the container. |

## Wire format

A listener reads the JSON payload of the message as its request. A top-level `{"data": ...}`, sent by the callers
going through broker-nats, is unwrapped, unless the request type has a `data` property itself. An empty payload is
read as `{}`.

When the message has a reply subject and the method does not return `void`, the reply is:

| Outcome | Payload | Header `failed` |
|---|---|---|
| Success | `{"response": <returned value>}` | `false` |
| `NatsReplyException` | `{"err": "<its message>"}` | `true` |
| Any other exception | `{"err": "<its message, or its class name>"}`, also logged as an error | `true` |
| Unreadable request | `{"err": "Invalid request: <reason>"}` | `true` |

A `void` method never replies, even to a request: its endpoint is fire-and-forget.

The generated clients read the reply the same way: `err` becomes a `RuntimeException` with its message, `response`
is read as the response type, and a reply without either envelope is read as the response itself.

## Migration from 1.x

| 1.x (`edifice-quarkus-contracts-parent`) | 2.0 |
|---|---|
| `io.edifice:edifice-nats-contract-runtime` | `io.edifice:edifice-quarkus-nats` |
| `io.edifice.natscontract.api.BrokerListener` | `io.edifice.nats.api.BrokerListener`, from the regenerated server artifacts |
| `io.nats.client.*` properties | `edifice.nats.*`, see the table above. Options without an equivalent go in `edifice.nats.options.*`. |
| `nats.connection.queue`, read at build time | `edifice.nats.queue-group`, read at runtime, the application name by default |
| `@Unremovable` / `@ApplicationScoped` on the listener class | Not needed |
| One dispatcher thread for every listener | One virtual thread per message |
| Reply: the bare response, or `{"errorMsg", "detailedMsg"}` | `{"response": ...}` or `{"err": "..."}`, with the header `failed` |
| `nats.json`, the annotation processor, the schema utils, the Temporal modules | Removed |

Steps:

1. Replace the dependency on `edifice-nats-contract-runtime` by `edifice-quarkus-nats`, and the contract artifacts by
   the ones generated from edifice-events-definition.
2. Rename the configuration: `io.nats.client.url` becomes `edifice.nats.url`, `io.nats.client.username` becomes
   `edifice.nats.username`, and so on.
3. Remove `@Unremovable` from the listener implementations.
4. If a listener needs the subject, the headers or the subject parameters, inject `NatsMessageContext`.
5. Callers that read the bare reply must read the envelope: the generated clients already do.

## Build

Everything runs in Docker, `maven:3.9-eclipse-temurin-25`. The integration tests start NATS through the Docker
socket of the host, so `compose.yaml` mounts it and runs on the host network.

```shell
./build.sh init      # writes .env: the user and the group of the Docker socket
./build.sh test      # mvn verify: unit tests, deployment tests, integration tests
./build.sh install   # installs the artifacts in ~/.m2, without the tests
./build.sh publish   # deploys to Nexus
```

Modules:

- `api`: the annotation and types used by the generated code.
- `runtime`: `NatsConnectionManager` (connection), `NatsConnectionProducer` (the `Connection` bean),
  `NatsListenerDispatcher` (subscriptions and message handling), `NatsRecorder`, `NatsConnectionHealthCheck`.
- `deployment`: `NatsProcessor` (discovery, validation, invokers, recording), `NatsDevServicesProcessor`. Its tests
  cover the build failures with `QuarkusExtensionTest`.
- `integration-tests`: an application with a hand-written listener interface and client shaped like the generated
  ones, tested with `@QuarkusTest` on the dev service: request/reply, fire-and-forget, subject parameters and headers,
  errors, queue group, request scope, concurrency, health, and startup without NATS.
