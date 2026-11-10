package io.edifice.nats.runtime;

import io.nats.client.Connection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Backs the {@link Connection} handed out to the application, before and after the real connection exists.
 * <p>
 * Until NATS has been reached once, every call fails fast with an {@link IllegalStateException}, except
 * {@code getStatus}, {@code toString}, {@code equals} and {@code hashCode}. Afterwards every call goes to the real
 * connection, which buffers or fails by itself while it reconnects.
 */
final class LazyConnectionHandler implements InvocationHandler {

  private final Supplier<Connection> delegate;

  LazyConnectionHandler(Supplier<Connection> delegate) {
    this.delegate = delegate;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    final Connection connection = delegate.get();
    if (connection == null) {
      return switch (method.getName()) {
        case "toString" -> "NatsConnection[not connected yet]";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        case "getStatus" -> Connection.Status.DISCONNECTED;
        default -> throw new IllegalStateException("The NATS connection is not available yet");
      };
    }
    if (method.getName().equals("equals") && args != null && args.length == 1) {
      return proxy == args[0];
    }
    if (method.getName().equals("hashCode") && (args == null || args.length == 0)) {
      return System.identityHashCode(proxy);
    }
    try {
      return method.invoke(connection, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
