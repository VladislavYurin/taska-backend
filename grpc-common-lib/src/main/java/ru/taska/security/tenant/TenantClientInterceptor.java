package ru.taska.security.tenant;

import com.google.protobuf.Message;
import io.grpc.*;
import java.util.Map;
import reactor.core.Disposable;

/** Delays transport start until the final protobuf message has been signed. */
public final class TenantClientInterceptor implements ClientInterceptor {
  private final RpcContextSecurity security;
  private final Map<String, RpcContextSecurity.Policy> policies;

  public TenantClientInterceptor(
      RpcContextSecurity security, Map<String, RpcContextSecurity.Policy> policies) {
    this.security = security;
    this.policies = Map.copyOf(policies);
  }

  @Override
  public <Req, Resp> ClientCall<Req, Resp> interceptCall(
      MethodDescriptor<Req, Resp> method, CallOptions options, Channel channel) {
    return new ClientCall<>() {
      final io.grpc.Context inherited = io.grpc.Context.current();
      ClientCall<Req, Resp> delegate;
      Listener<Resp> listener;
      Metadata headers;
      Disposable signing;
      int requested;
      boolean sent, halfClosed, closed;
      final io.grpc.Context.CancellationListener cancellation =
          c -> cancel("Cancelled", c.cancellationCause());

      @Override
      public synchronized void start(Listener<Resp> listener, Metadata headers) {
        this.listener = listener;
        this.headers = headers;
        inherited.addListener(cancellation, Runnable::run);
      }

      @Override
      public synchronized void request(int n) {
        if (delegate != null) delegate.request(n);
        else requested = Math.addExact(requested, n);
      }

      private synchronized void fail(Status status) {
        if (!closed) {
          closed = true;
          inherited.removeListener(cancellation);
          listener.onClose(status, new Metadata());
        }
      }

      @Override
      public synchronized void sendMessage(Req message) {
        var policy = policies.get(method.getFullMethodName());
        var supplied = options.getOption(RpcContextBridge.OUTBOUND);
        var parent =
            supplied != null ? supplied : RpcContextBridge.OUTBOUND_INHERITED.get(inherited);
        if (sent
            || policy == null
            || parent == null
            || !(message instanceof Message proto)
            || !method.getType().clientSendsOneMessage()) {
          fail(Status.UNAUTHENTICATED);
          return;
        }
        sent = true;
        final Message outbound;
        try {
          var route = RpcRoutePolicies.all().get(method.getFullMethodName());
          var field = proto.getDescriptorForType().findFieldByName("context");
          Message prepared = proto;
          if (route != null && field != null && !proto.hasField(field)) {
            var delegated =
                security.delegate(parent, route.audience(), route.action(), parent.scopes());
            prepared = proto.toBuilder().setField(field, delegated).build();
          }
          var header = prepared.getDescriptorForType().findFieldByName("header");
          if (route != null
              && header != null
              && header.getMessageType().getFullName().equals("ru.taska.common.v1.Header")) {
            var original = RpcContextBridge.details(parent).getHeader();
            var current =
                ru.taska.api.common.v1.Header.parseFrom(
                    ((Message) prepared.getField(header)).toByteArray());
            if (!current.getRequestId().equals(original.getRequestId()))
              throw Status.PERMISSION_DENIED.asRuntimeException();
            var stamped = current.toBuilder().clearActionChannel().clearTokenId();
            if (original.hasActionChannel()) stamped.setActionChannel(original.getActionChannel());
            if (original.hasTokenId()) stamped.setTokenId(original.getTokenId());
            prepared = prepared.toBuilder().setField(header, stamped.build()).build();
          }
          if (route != null)
            prepared =
                RpcMessageClaims.stampRoles(
                    prepared, RpcContextBridge.details(parent).getContext());
          outbound = security.prepare(parent, prepared);
        } catch (Exception e) {
          fail(
              e instanceof io.grpc.StatusRuntimeException
                  ? Status.fromThrowable(e)
                  : Status.UNAUTHENTICATED);
          return;
        }
        var pending = security.sign(parent, outbound, method.getFullMethodName(), policy);
        Deadline deadline = options.getDeadline();
        if (inherited.getDeadline() != null)
          deadline =
              deadline == null
                  ? inherited.getDeadline()
                  : deadline.minimum(inherited.getDeadline());
        if (deadline != null) {
          if (deadline.isExpired()) {
            fail(Status.DEADLINE_EXCEEDED);
            return;
          }
          pending =
              pending
                  .timeout(
                      java.time.Duration.ofNanos(
                          Math.max(
                              1,
                              deadline.timeRemaining(java.util.concurrent.TimeUnit.NANOSECONDS))))
                  .onErrorMap(
                      java.util.concurrent.TimeoutException.class,
                      e -> Status.DEADLINE_EXCEEDED.asRuntimeException());
        }
        signing =
            pending.subscribe(
                assertion -> {
                  synchronized (this) {
                    if (closed || inherited.isCancelled()) {
                      fail(Status.CANCELLED);
                      return;
                    }
                    inherited.run(
                        () -> {
                          headers.discardAll(TenantServerInterceptor.ASSERTION);
                          headers.put(TenantServerInterceptor.ASSERTION, assertion);
                          delegate = channel.newCall(method, options);
                          delegate.start(
                              new ForwardingClientCallListener.SimpleForwardingClientCallListener<
                                  Resp>(listener) {
                                @Override
                                public void onClose(Status status, Metadata trailers) {
                                  synchronized (TenantClientInterceptor.this) {
                                    inherited.removeListener(cancellation);
                                  }
                                  super.onClose(status, trailers);
                                }
                              },
                              headers);
                          if (requested > 0) delegate.request(requested);
                          delegate.sendMessage((Req) outbound);
                          if (halfClosed) delegate.halfClose();
                        });
                  }
                },
                e -> fail(Status.fromThrowable(e)));
      }

      @Override
      public synchronized void halfClose() {
        halfClosed = true;
        if (delegate != null) delegate.halfClose();
      }

      @Override
      public synchronized void cancel(String message, Throwable cause) {
        if (signing != null) signing.dispose();
        if (delegate != null) delegate.cancel(message, cause);
        else fail(Status.CANCELLED);
        closed = true;
        inherited.removeListener(cancellation);
      }

      @Override
      public synchronized boolean isReady() {
        return delegate != null && delegate.isReady();
      }

      @Override
      public synchronized void setMessageCompression(boolean enabled) {
        if (delegate != null) delegate.setMessageCompression(enabled);
      }
    };
  }
}
