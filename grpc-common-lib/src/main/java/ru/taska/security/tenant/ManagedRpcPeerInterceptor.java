package ru.taska.security.tenant;

import io.grpc.*;
import java.security.cert.X509Certificate;

/** Checks every call, including calls reusing a TLS connection established before revocation. */
public final class ManagedRpcPeerInterceptor implements ServerInterceptor {
  public static final Context.Key<String> PEER = Context.key("taska-managed-rpc-peer");
  private final ManagedRpcTrust trust;
  private final String environment;

  public ManagedRpcPeerInterceptor(ManagedRpcTrust trust, String environment) {
    this.trust = trust;
    this.environment = environment;
  }

  @Override
  public <Q, S> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
    if (!call.getMethodDescriptor().getType().clientSendsOneMessage()) {
      call.close(Status.PERMISSION_DENIED, new Metadata());
      return new ServerCall.Listener<>() {};
    }
    var inherited = Context.current();
    // File/KMS-backed trust and certificate parsing never block a Netty event loop.
    class Pending extends ServerCall.Listener<Q> {
      ServerCall.Listener<Q> delegate;
      reactor.core.Disposable verification;
      Q message;
      boolean received, halfClosed, cancelled, completed;

      void verify() {
        verification =
            reactor.core.publisher.Mono.fromCallable(
                    () -> {
                      String peer =
                          TenantServerInterceptor.peerIdentity(call.getAttributes(), environment);
                      var session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
                      if (!trust.peerAllowed(
                          peer, (X509Certificate) session.getPeerCertificates()[0]))
                        throw Status.UNAUTHENTICATED.asRuntimeException();
                      return peer;
                    })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(
                    peer -> {
                      synchronized (this) {
                        if (cancelled || inherited.isCancelled()) return;
                        var context = inherited.withValue(PEER, peer);
                        context.run(
                            () -> {
                              delegate = Contexts.interceptCall(context, call, headers, next);
                              if (received) {
                                delegate.onMessage(message);
                                message = null;
                              }
                              if (call.isReady()) delegate.onReady();
                              if (halfClosed) delegate.onHalfClose();
                              if (completed) delegate.onComplete();
                            });
                      }
                    },
                    error -> {
                      synchronized (this) {
                        if (!cancelled) {
                          cancelled = true;
                          message = null;
                          if (delegate != null) delegate.onCancel();
                          call.close(
                              error instanceof StatusRuntimeException
                                  ? Status.fromThrowable(error)
                                  : Status.UNAUTHENTICATED,
                              new Metadata());
                        }
                      }
                    });
      }

      @Override
      public synchronized void onMessage(Q value) {
        if (cancelled) return;
        if (received) {
          cancelled = true;
          if (verification != null) verification.dispose();
          if (delegate != null) delegate.onCancel();
          call.close(Status.UNAUTHENTICATED, new Metadata());
          return;
        }
        received = true;
        if (delegate != null) delegate.onMessage(value);
        else message = value;
      }

      @Override
      public synchronized void onHalfClose() {
        halfClosed = true;
        if (!cancelled && delegate != null) delegate.onHalfClose();
      }

      @Override
      public synchronized void onCancel() {
        cancelled = true;
        message = null;
        if (verification != null) verification.dispose();
        if (delegate != null) delegate.onCancel();
      }

      @Override
      public synchronized void onComplete() {
        completed = true;
        if (!cancelled && delegate != null) delegate.onComplete();
      }

      @Override
      public synchronized void onReady() {
        if (!cancelled && delegate != null) delegate.onReady();
      }
    }
    call.request(1);
    var pending = new Pending();
    pending.verify();
    return pending;
  }
}
