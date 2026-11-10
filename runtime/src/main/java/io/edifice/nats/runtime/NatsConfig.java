package io.edifice.nats.runtime;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Connection to NATS and handling of the {@code @BrokerListener} methods.
 */
@ConfigMapping(prefix = "edifice.nats")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface NatsConfig {

  /**
   * The NATS servers, comma separated, for example {@code nats://nats:4222}. Set by the dev service in dev and test
   * modes. Defaults to {@code nats://localhost:4222}.
   */
  Optional<String> url();

  /**
   * The user name, sent with {@code password}.
   */
  Optional<String> username();

  /**
   * The password of {@code username}.
   */
  Optional<String> password();

  /**
   * The authentication token.
   */
  Optional<String> token();

  /**
   * A NATS credentials file (user JWT and NKey seed).
   */
  Optional<String> credsFile();

  /**
   * The name of the TLS configuration of the Quarkus TLS registry ({@code quarkus.tls.<name>.*}) used to connect.
   */
  Optional<String> tlsConfigurationName();

  /**
   * Reconnection to NATS.
   */
  Reconnect reconnect();

  /**
   * How long to wait for the connection to a server to be established.
   */
  @WithDefault("2s")
  Duration connectionTimeout();

  /**
   * The queue group of the listeners: the instances of a service share the messages of a subject instead of each
   * receiving all of them. An empty value subscribes without a queue group.
   */
  @WithDefault("${quarkus.application.name}")
  Optional<String> queueGroup();

  /**
   * The timeout of a request sent by a generated client, in milliseconds.
   */
  @WithDefault("${broker.request.timeout:10000}")
  long requestTimeout();

  /**
   * Execution of the listeners.
   */
  Listener listener();

  /**
   * Other jnats options, passed to {@code Options.Builder(Properties)}. The keys are the jnats property names, with
   * or without the {@code io.nats.client.} prefix, for example {@code edifice.nats.options.maxpings=3}. The options
   * above take precedence.
   */
  @ConfigDocMapKey("jnats-property")
  Map<String, String> options();

  /**
   * Reconnection to NATS.
   */
  interface Reconnect {

    /**
     * How many times to try to reconnect after a disconnection. {@code -1} retries forever.
     */
    @WithDefault("-1")
    int max();

    /**
     * How long to wait between two attempts to reconnect to the same server.
     */
    @WithDefault("2s")
    Duration waitTime();
  }

  /**
   * Execution of the listeners.
   */
  interface Listener {

    /**
     * Where the listener methods run. They never run on the NATS dispatcher thread, so a slow listener never delays
     * the others.
     */
    @WithDefault("virtual-threads")
    Execution execution();
  }

  /**
   * Where the listener methods run.
   */
  enum Execution {
    /**
     * One new virtual thread per message.
     */
    VIRTUAL_THREADS,
    /**
     * The Quarkus worker thread pool.
     */
    WORKER
  }
}
