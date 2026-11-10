package io.edifice.nats.deployment;

import io.edifice.nats.api.BrokerListener;
import io.edifice.nats.runtime.NatsConnectionHealthCheck;
import io.edifice.nats.runtime.NatsConnectionManager;
import io.edifice.nats.runtime.NatsConnectionProducer;
import io.edifice.nats.runtime.NatsListenerDispatcher;
import io.edifice.nats.runtime.NatsMessageContextImpl;
import io.edifice.nats.runtime.NatsRecorder;
import io.edifice.nats.runtime.SubjectTemplate;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AutoAddScopeBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.InvokerFactoryBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.InvokerInfo;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.invoke.Invoker;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import java.lang.constant.ClassDesc;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the {@code @BrokerListener} methods, makes their implementors beans, generates their invokers and registers
 * them at runtime init.
 */
public class NatsProcessor {

  static final String FEATURE = "edifice-nats";
  static final DotName BROKER_LISTENER = DotName.createSimple(BrokerListener.class.getName());
  private static final Logger LOG = Logger.getLogger(NatsProcessor.class);

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE);
  }

  @BuildStep
  AdditionalBeanBuildItem beans() {
    return AdditionalBeanBuildItem.builder()
      .addBeanClasses(NatsConnectionManager.class, NatsListenerDispatcher.class, NatsMessageContextImpl.class)
      .setUnremovable()
      .addBeanClass(NatsConnectionProducer.class)
      .build();
  }

  @BuildStep
  void health(Capabilities capabilities, NatsBuildTimeConfig config, BuildProducer<HealthBuildItem> health) {
    if (capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
      health.produce(new HealthBuildItem(NatsConnectionHealthCheck.class.getName(), config.healthEnabled()));
    }
  }

  @BuildStep
  void nativeImage(BuildProducer<NativeImageProxyDefinitionBuildItem> proxies,
                   BuildProducer<ReflectiveClassBuildItem> reflective) {
    proxies.produce(new NativeImageProxyDefinitionBuildItem("io.nats.client.Connection"));
    // The connection proxy calls the methods of the real connection reflectively.
    reflective.produce(ReflectiveClassBuildItem.builder("io.nats.client.Connection", "io.nats.client.impl.NatsConnection")
      .methods().reason(getClass().getName()).build());
  }

  /**
   * Finds the {@code @BrokerListener} methods and their implementors, which become {@code @ApplicationScoped} beans.
   */
  @BuildStep
  void discover(CombinedIndexBuildItem combinedIndex, BuildProducer<ListenerMethodBuildItem> methods,
                BuildProducer<AutoAddScopeBuildItem> autoScopes, BuildProducer<UnremovableBeanBuildItem> unremovable) {
    final IndexView index = combinedIndex.getIndex();
    final Set<DotName> beanClasses = new LinkedHashSet<>();
    for (AnnotationInstance annotation : index.getAnnotations(BROKER_LISTENER)) {
      if (annotation.target().kind() != AnnotationTarget.Kind.METHOD) {
        continue;
      }
      final MethodInfo method = annotation.target().asMethod();
      final List<DotName> implementors = concreteImplementors(index, method.declaringClass());
      beanClasses.addAll(implementors);
      final var description = annotation.value("description");
      methods.produce(new ListenerMethodBuildItem(method, annotation.value("subject").asString(),
        description == null ? "" : description.asString(), implementors));
    }
    if (beanClasses.isEmpty()) {
      return;
    }
    autoScopes.produce(AutoAddScopeBuildItem.builder()
      .match((clazz, annotations, idx) -> beanClasses.contains(clazz.name()))
      .defaultScope(BuiltinScope.APPLICATION)
      .unremovable()
      .reason("Implements a @BrokerListener method")
      .build());
    // Also when the class already declares its own scope.
    unremovable.produce(UnremovableBeanBuildItem.beanTypes(beanClasses));
  }

  /**
   * The concrete classes that implement the method: the implementors of an interface, the subclasses of an abstract
   * class, or the class itself.
   */
  private static List<DotName> concreteImplementors(IndexView index, ClassInfo declaring) {
    final List<DotName> result = new ArrayList<>();
    if (isConcrete(declaring)) {
      result.add(declaring.name());
      return result;
    }
    final var candidates = declaring.isInterface()
      ? index.getAllKnownImplementations(declaring.name())
      : index.getAllKnownSubclasses(declaring.name());
    for (ClassInfo candidate : candidates) {
      if (isConcrete(candidate)) {
        result.add(candidate.name());
      }
    }
    result.sort(null);
    return result;
  }

  private static boolean isConcrete(ClassInfo clazz) {
    return !clazz.isInterface() && !Modifier.isAbstract(clazz.flags()) && !clazz.isAnnotation();
  }

  /**
   * Checks the listeners and generates their invokers. Produces the validation errors, which also makes it run before
   * ArC generates its classes, as the invoker factory requires.
   */
  @BuildStep
  void invokers(List<ListenerMethodBuildItem> methods, ValidationPhaseBuildItem validationPhase,
                InvokerFactoryBuildItem invokerFactory, BeanArchiveIndexBuildItem beanArchiveIndex,
                BuildProducer<ValidationErrorBuildItem> errors, BuildProducer<NatsListenerBuildItem> listeners,
                BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy) {
    final List<Throwable> problems = new ArrayList<>();
    final Map<String, String> bySubject = new HashMap<>();
    for (ListenerMethodBuildItem item : methods) {
      final MethodInfo method = item.method();
      final String name = item.name();
      final SubjectTemplate subject;
      try {
        subject = SubjectTemplate.parse(item.subject());
      } catch (IllegalArgumentException e) {
        problems.add(new DeploymentException("Invalid subject '" + item.subject() + "' on " + name + ": "
          + e.getMessage()));
        continue;
      }
      final String previous = bySubject.putIfAbsent(subject.wildcard(), name);
      if (previous != null) {
        problems.add(new DeploymentException("The subject '" + item.subject() + "' of " + name
          + " is already listened to by " + previous + " (both listen on " + subject.wildcard() + ")"));
        continue;
      }
      if (item.implementors().isEmpty()) {
        problems.add(new DeploymentException("No class implements " + name + " (subject '" + item.subject()
          + "'): implement " + method.declaringClass().name() + " in exactly one class, or remove the dependency"
          + " that brings it"));
        continue;
      }
      if (item.implementors().size() > 1) {
        problems.add(new DeploymentException("Several classes implement " + name + " (subject '" + item.subject()
          + "'): " + item.implementors() + ". Each message must be handled by exactly one class"));
        continue;
      }
      if (method.parametersCount() != 1) {
        problems.add(new DeploymentException(name + " must take exactly one parameter, the request, but takes "
          + method.parametersCount()));
        continue;
      }
      final Type requestType = method.parameterType(0);
      if (requestType.kind() != Type.Kind.CLASS) {
        problems.add(new DeploymentException("The request of " + name + " must be a class, not " + requestType));
        continue;
      }
      final DotName implementor = item.implementors().getFirst();
      final List<BeanInfo> beans = validationPhase.getContext().beans().classBeans().withBeanClass(implementor)
        .collect();
      if (beans.size() != 1) {
        problems.add(new DeploymentException(implementor + " implements " + name + " but is not a bean: "
          + "it must not be vetoed nor have a constructor with parameters without @Inject"));
        continue;
      }
      final BeanInfo bean = beans.getFirst();
      final MethodInfo target = targetMethod(beanArchiveIndex.getIndex(), bean.getImplClazz(), method);
      final InvokerInfo invoker = invokerFactory.createInvoker(bean, target).withInstanceLookup().build();
      final boolean replies = method.returnType().kind() != Type.Kind.VOID;
      final String targetName = implementor + "#" + method.name();
      listeners.produce(new NatsListenerBuildItem(item.subject(), item.description(), targetName,
        requestType.name().toString(), replies, className(invoker.getClassDesc())));
      reflectiveHierarchy.produce(ReflectiveHierarchyBuildItem.builder(requestType)
        .source(getClass().getSimpleName() + " > " + targetName).build());
      if (replies) {
        reflectiveHierarchy.produce(ReflectiveHierarchyBuildItem.builder(method.returnType())
          .source(getClass().getSimpleName() + " > " + targetName).build());
      }
      LOG.debugf("NATS listener %s -> %s", item.subject(), targetName);
    }
    if (!problems.isEmpty()) {
      errors.produce(new ValidationErrorBuildItem(problems));
    }
  }

  /**
   * The method of the bean class that implements the annotated method, or the annotated method itself when it is
   * inherited, for example a default method.
   */
  private static MethodInfo targetMethod(IndexView index, ClassInfo beanClass, MethodInfo declared) {
    ClassInfo clazz = beanClass;
    while (clazz != null) {
      final MethodInfo candidate = clazz.method(declared.name(), declared.parameterTypes());
      if (candidate != null && !Modifier.isAbstract(candidate.flags())) {
        return candidate;
      }
      final DotName superName = clazz.superName();
      clazz = superName == null ? null : index.getClassByName(superName);
    }
    return declared;
  }

  /**
   * The binary name of a class, for {@link RecorderContext#newInstance(String)}.
   */
  private static String className(ClassDesc desc) {
    final String descriptor = desc.descriptorString();
    return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  void register(NatsRecorder recorder, RecorderContext recorderContext, List<NatsListenerBuildItem> listeners,
                BeanContainerBuildItem beanContainer) throws ClassNotFoundException {
    final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    for (NatsListenerBuildItem listener : listeners) {
      final RuntimeValue<Invoker<Object, Object>> invoker = recorderContext.newInstance(listener.invokerClass());
      recorder.addListener(listener.subject(), listener.description(), listener.target(),
        Class.forName(listener.requestType(), false, classLoader), listener.replies(), invoker);
    }
    recorder.startListeners();
  }
}
