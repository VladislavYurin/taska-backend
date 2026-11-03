package ru.taska.security.tenant;

import com.google.protobuf.Message;
import io.grpc.*;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLSession;
import reactor.core.Disposable;

/** Opt-in adapter; only registered unary-request methods are accepted. */
public final class TenantServerInterceptor implements ServerInterceptor {
  static final Metadata.Key<String> ASSERTION =
      Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER);
  private final RpcContextSecurity security;
  private final Map<String, RpcContextSecurity.Policy> policies;
  private final String environment;

  public TenantServerInterceptor(
      RpcContextSecurity security,
      Map<String, RpcContextSecurity.Policy> policies,
      String environment) {
    this.security = Objects.requireNonNull(security);
    this.policies = Map.copyOf(policies);
    this.environment = Objects.requireNonNull(environment);
  }

  private String peer(Attributes attributes) {
    try {
      SSLSession session = attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      if (session == null) throw RpcContextSecurity.unauthenticated();
      var cert = (X509Certificate) session.getPeerCertificates()[0];
      String service = null;
      for (var san : cert.getSubjectAlternativeNames())
        if (Integer.valueOf(6).equals(san.get(0))) {
          URI uri = URI.create((String) san.get(1));
          String prefix = "/" + environment + "/";
          if ("spiffe".equals(uri.getScheme())
              && "taska".equals(uri.getHost())
              && uri.getQuery() == null
              && uri.getFragment() == null
              && uri.getPath().startsWith(prefix)
              && !uri.getPath().substring(prefix.length()).contains("/")) {
            if (service != null) throw RpcContextSecurity.unauthenticated();
            service = uri.getPath().substring(prefix.length());
            if (!uri.toString().equals("spiffe://taska/" + environment + "/" + service))
              throw RpcContextSecurity.unauthenticated();
          }
        }
      if (service == null || service.isBlank()) throw RpcContextSecurity.unauthenticated();
      return service;
    } catch (Exception e) {
      throw RpcContextSecurity.unauthenticated();
    }
  }

  @Override
  public <Req, Resp> ServerCall.Listener<Req> interceptCall(
      ServerCall<Req, Resp> call, Metadata headers, ServerCallHandler<Req, Resp> next) {
    var method = call.getMethodDescriptor();
    var policy = policies.get(method.getFullMethodName());
    if (policy == null || !method.getType().clientSendsOneMessage()) {
      call.close(Status.PERMISSION_DENIED, new Metadata());
      return new ServerCall.Listener<>() {};
    }
    final String peer;
    try {
      peer = peer(call.getAttributes());
      var assertions = headers.getAll(ASSERTION);
      int count = 0;
      if (assertions != null) for (String ignored : assertions) count++;
      if (count != 1) throw RpcContextSecurity.unauthenticated();
    } catch (Exception e) {
      call.close(Status.UNAUTHENTICATED, new Metadata());
      return new ServerCall.Listener<>() {};
    }
    var inherited = io.grpc.Context.current();
    call.request(1);
    return new ServerCall.Listener<>() {
      ServerCall.Listener<Req> delegate;
      Disposable verification;
      boolean received, halfClosed, cancelled, completed;

      @Override
      public synchronized void onMessage(Req message) {
        if (cancelled) return;
        if (received || !(message instanceof Message proto)) {
          cancelled = true;
          if (verification != null) verification.dispose();
          if (delegate != null) delegate.onCancel();
          call.close(Status.UNAUTHENTICATED, new Metadata());
          return;
        }
        received = true;
        verification =
            security
                .verify(proto, headers.get(ASSERTION), method.getFullMethodName(), peer, policy)
                .subscribe(
                    v -> {
                      synchronized (this) {
                        if (cancelled || inherited.isCancelled()) return;
                        var context = inherited.withValue(RpcContextBridge.INBOUND, v);
                        context.run(
                            () -> {
                              delegate = Contexts.interceptCall(context, call, headers, next);
                              delegate.onMessage(message);
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
                          call.close(Status.fromThrowable(error), new Metadata());
                        }
                      }
                    });
      }

      @Override
      public synchronized void onHalfClose() {
        if (cancelled) return;
        halfClosed = true;
        if (delegate != null) delegate.onHalfClose();
        else if (!received) {
          cancelled = true;
          call.close(Status.UNAUTHENTICATED, new Metadata());
        }
      }

      @Override
      public synchronized void onCancel() {
        cancelled = true;
        if (verification != null) verification.dispose();
        if (delegate != null) delegate.onCancel();
      }

      @Override
      public synchronized void onComplete() {
        completed = true;
        if (delegate != null) delegate.onComplete();
      }

      @Override
      public synchronized void onReady() {
        if (delegate != null) delegate.onReady();
      }
    };
  }
}
