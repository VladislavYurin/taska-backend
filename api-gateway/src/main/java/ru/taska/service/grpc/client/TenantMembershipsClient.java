package ru.taska.service.grpc.client;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.domain.GatewayContext;
import ru.taska.security.tenant.FileMtlsRpcChannels;
import ru.taska.security.tenant.RpcContextSecurity;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class TenantMembershipsClient implements AutoCloseable {

    private final ObjectProvider<RpcContextSecurity> security;
    private final String ca, cert, key, environment, host;
    private final int port;
    private ManagedChannel channel;

    public TenantMembershipsClient(
            ObjectProvider<RpcContextSecurity> security,
            @Value("${taska.rpc.tls.ca-certificate:}") String ca,
            @Value("${taska.rpc.tls.certificate:}") String cert,
            @Value("${taska.rpc.tls.private-key:}") String key,
            @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
            @Value("${taska.capabilities.tenant-host:tenant-service}") String host,
            @Value("${taska.capabilities.tenant-port:9099}") int port) {
        this.security = security;
        this.ca = ca;
        this.cert = cert;
        this.key = key;
        this.environment = environment;
        this.host = host;
        this.port = port;
    }

    private synchronized ManagedChannel channel() {
        if (channel == null)
            channel =
                    new FileMtlsRpcChannels(Path.of(ca), Path.of(cert), Path.of(key), environment)
                            .create(host, port, "tenant-service");
        return channel;
    }

    public Mono<MembershipPage> list(
            UUID tenantId,
            String cursor,
            int limit,
            GatewayContext context
    ) {
        return Mono.defer(
                        () -> {
                            var guard = security.getIfAvailable();
                            var parent = context.verified();
                            var policy = new RpcContextSecurity.Policy(true, false);
                            if (parent == null || guard == null)
                                return Mono.error(Status.UNAVAILABLE.asRuntimeException());
                            if (!tenantId.toString().equals(parent.tenantId()))
                                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
                            if (limit < 1 || limit > 100 ||
                                (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
                            ) {
                                return Mono.error(Status.INVALID_ARGUMENT.asRuntimeException());
                            }
                            var page = PageRequest.newBuilder().setLimit(limit);
                            if (cursor != null) {
                                page.setCursor(cursor);
                            }
                            var rpc = TenantServiceGrpc.getListTenantMembersMethod().getFullMethodName();
                            var request = ListTenantMembersRpcRequest.newBuilder()
                                    .setContext(
                                            guard.delegate(
                                                    parent,
                                                    "tenant-service",
                                                    "listTenantMembers",
                                                    parent.scopes()
                                            )
                                    )
                                    .setPage(page)
                                    .setTenantId(String.valueOf(tenantId))
                                    .build();
                            return guard
                                    .sign(parent, request, rpc, policy)
                                    .flatMap(assertion -> {
                                        var headers = new Metadata();
                                        headers.put(
                                                Metadata.Key.of(
                                                        "x-taska-context",
                                                        Metadata.ASCII_STRING_MARSHALLER
                                                ),
                                                assertion
                                        );
                                        return ReactorTenantServiceGrpc.newReactorStub(channel())
                                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                                                .listTenantMembers(request);
                                    });

                        })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PreDestroy
    public synchronized void close() {
        if (channel != null) channel.shutdownNow();
    }
}