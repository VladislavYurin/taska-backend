package ru.taska.security.tenant;

import io.grpc.*;
import java.util.concurrent.TimeUnit;

/** Preserve Spring channel lifecycle while installing the common client boundary. */
public final class InterceptedManagedChannel extends ManagedChannel {
  private final ManagedChannel delegate;
  private final Channel intercepted;

  public InterceptedManagedChannel(ManagedChannel delegate, ClientInterceptor interceptor) {
    this.delegate = delegate;
    intercepted = ClientInterceptors.intercept(delegate, interceptor);
  }

  public <Q, S> ClientCall<Q, S> newCall(MethodDescriptor<Q, S> method, CallOptions options) {
    return intercepted.newCall(method, options);
  }

  public String authority() {
    return delegate.authority();
  }

  public ManagedChannel shutdown() {
    delegate.shutdown();
    return this;
  }

  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  public ManagedChannel shutdownNow() {
    delegate.shutdownNow();
    return this;
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  public void enterIdle() {
    delegate.enterIdle();
  }

  public void resetConnectBackoff() {
    delegate.resetConnectBackoff();
  }
}
