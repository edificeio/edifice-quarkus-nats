package io.edifice.nats.deployment;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Build time configuration of the NATS extension.
 */
@ConfigMapping(prefix = "edifice.nats")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface NatsBuildTimeConfig {

  /**
   * Whether the {@code nats-connection} readiness check is registered, when quarkus-smallrye-health is present.
   */
  @WithDefault("true")
  @WithName("health.enabled")
  boolean healthEnabled();

  /**
   * The NATS dev service.
   */
  DevServices devservices();

  /**
   * The NATS dev service, started in dev and test modes when {@code edifice.nats.url} is not set.
   */
  interface DevServices {

    /**
     * Whether to start a NATS container in dev and test modes.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The image of the container.
     */
    @WithDefault("nats:2.11-alpine")
    String imageName();

    /**
     * A fixed host port for the container. A random port is used when unset.
     */
    OptionalInt port();

    /**
     * Whether the container is shared between the applications running in dev mode: an application looks for a
     * running container labelled with {@code service-name} before starting its own.
     */
    @WithDefault("true")
    boolean shared();

    /**
     * The value of the {@code edifice-dev-service-nats} label used to share the container.
     */
    @WithDefault("nats")
    String serviceName();

    /**
     * Extra arguments of the {@code nats-server} command, for example {@code --js}.
     */
    Optional<String> args();

    /**
     * Environment variables of the container.
     */
    @ConfigDocMapKey("environment-variable-name")
    Map<String, String> containerEnv();
  }
}
