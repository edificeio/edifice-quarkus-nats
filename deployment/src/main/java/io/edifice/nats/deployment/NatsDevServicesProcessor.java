package io.edifice.nats.deployment;

import io.quarkus.deployment.IsDevServicesSupportedByLaunchMode;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.DevServicesComposeProjectBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.Startable;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Starts a NATS container in dev and test modes when {@code edifice.nats.url} is not set, and sets it.
 */
@BuildSteps(onlyIf = {IsDevServicesSupportedByLaunchMode.class, DevServicesConfig.Enabled.class})
public class NatsDevServicesProcessor {

  static final String URL_PROPERTY = "edifice.nats.url";
  static final String DEV_SERVICE_LABEL = "edifice-dev-service-nats";
  static final int NATS_PORT = 4222;
  private static final Logger LOG = Logger.getLogger(NatsDevServicesProcessor.class);
  private static final ContainerLocator LOCATOR = ContainerLocator.locateContainerWithLabels(NATS_PORT,
    DEV_SERVICE_LABEL);

  @BuildStep
  void startNats(LaunchModeBuildItem launchMode, DockerStatusBuildItem dockerStatus,
                 DevServicesComposeProjectBuildItem composeProject,
                 List<DevServicesSharedNetworkBuildItem> sharedNetwork, DevServicesConfig devServicesConfig,
                 NatsBuildTimeConfig config, BuildProducer<DevServicesResultBuildItem> results) {
    final NatsBuildTimeConfig.DevServices devServices = config.devservices();
    if (!devServices.enabled()) {
      LOG.debug("Not starting the NATS dev service: disabled");
      return;
    }
    if (ConfigUtils.isPropertyNonEmpty(URL_PROPERTY)) {
      LOG.debugf("Not starting the NATS dev service: %s is set", URL_PROPERTY);
      return;
    }
    if (!dockerStatus.isContainerRuntimeAvailable()) {
      LOG.warnf("Not starting the NATS dev service: no working container runtime. Set %s or start Docker",
        URL_PROPERTY);
      return;
    }
    final boolean useSharedNetwork = DevServicesSharedNetworkBuildItem.isSharedNetworkRequired(devServicesConfig,
      sharedNetwork);
    final LaunchMode mode = launchMode.getLaunchMode();
    final DevServicesResultBuildItem running = LOCATOR
      .locateContainer(devServices.serviceName(), devServices.shared(), mode)
      .map(address -> DevServicesResultBuildItem.discovered()
        .feature(NatsProcessor.FEATURE)
        .containerId(address.getId())
        .config(Map.of(URL_PROPERTY, "nats://" + address.getUrl()))
        .build())
      .orElse(null);
    if (running != null) {
      LOG.infof("Using the shared NATS dev service %s", running.getConfig().get(URL_PROPERTY));
      results.produce(running);
      return;
    }
    results.produce(DevServicesResultBuildItem.owned()
      .feature(NatsProcessor.FEATURE)
      .serviceName(devServices.serviceName())
      .serviceConfig(devServices)
      .startable(() -> new NatsContainer(DockerImageName.parse(devServices.imageName()), devServices.port(),
        composeProject.getDefaultNetworkId(), useSharedNetwork, devServices.args().orElse(null))
        .withEnv(devServices.containerEnv())
        .withSharedServiceLabel(mode, devServices.serviceName()))
      .configProvider(Map.of(URL_PROPERTY, container -> "nats://" + container.getConnectionInfo()))
      .build());
  }

  /**
   * The NATS server container.
   */
  static final class NatsContainer extends GenericContainer<NatsContainer> implements Startable {

    private final OptionalInt fixedPort;
    private final boolean useSharedNetwork;
    private final String hostName;

    NatsContainer(DockerImageName image, OptionalInt fixedPort, String defaultNetworkId, boolean useSharedNetwork,
                  String args) {
      super(image);
      this.fixedPort = fixedPort;
      this.useSharedNetwork = useSharedNetwork;
      this.hostName = ConfigureUtil.configureNetwork(this, defaultNetworkId, useSharedNetwork, "nats");
      if (args != null && !args.isBlank()) {
        withCommand(args.trim().split("\\s+"));
      }
      waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    }

    NatsContainer withSharedServiceLabel(LaunchMode mode, String serviceName) {
      return ConfigureUtil.configureSharedServiceLabel(this, mode, DEV_SERVICE_LABEL, serviceName);
    }

    @Override
    protected void configure() {
      super.configure();
      if (useSharedNetwork) {
        return;
      }
      if (fixedPort.isPresent()) {
        addFixedExposedPort(fixedPort.getAsInt(), NATS_PORT);
      } else {
        addExposedPort(NATS_PORT);
      }
    }

    @Override
    public String getHost() {
      return useSharedNetwork ? hostName : super.getHost();
    }

    int port() {
      if (useSharedNetwork) {
        return NATS_PORT;
      }
      return fixedPort.isPresent() ? fixedPort.getAsInt() : getMappedPort(NATS_PORT);
    }

    @Override
    public String getConnectionInfo() {
      return getHost() + ":" + port();
    }

    @Override
    public void close() {
      super.close();
    }
  }
}
