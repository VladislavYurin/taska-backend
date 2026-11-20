package ru.taska.security.trust;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.ManagedRpcTrust;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantClientInterceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Клинт для вызова TenantMembershipServiceGrpc и получения пачки membership пользователей
 */
public class GrpcTenantServiceClient implements AutoCloseable{

    private final ManagedChannel channel;
    private final Supplier<RpcContextSecurity> security;
    private final ManagedRpcTrust trust;

    public GrpcTenantServiceClient(
            ManagedChannel channel, Supplier<RpcContextSecurity> security,
            ManagedRpcTrust trust
    ) {
        this.channel = channel;
        this.security = security;
        this.trust = trust;
    }

    /**
     * Запрос по gRPC в TenantMembershipServiceGrpc для получения пачки пользователей с проверенным членством в переданном tenantId
     * @param tenantId id компании
     * @param userIds список id пользователей
     * @return пачка membership с проверенный членством в tenant
     */
    public Mono<MembershipValidationBatch> batchValidateMemberships(
            String  tenantId,
            List<UUID> userIds
    ) {
        return Mono.defer(
                        () -> {
                            var guard = security.get();
                            if (guard == null) return Mono.error(ManagedRpcTrust.unavailable());
                            var now = Instant.now();
                            var context =
                                    RequestContext.newBuilder()
                                            .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                                            .setContext(
                                                    TrustedTenantContext.newBuilder()
                                                            .setContextVersion(1)
                                                            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                                                            .setServicePurpose("membership")
                                                            .setServiceIdentity("auth-service")
                                                            .setActorGeneration(
                                                                    trust.snapshot().services().get("auth-service").generation())
                                                            .setTenantId(tenantId)
                                                            .addScopes("memberships:read")
                                                            .setIssuerService("auth-service")
                                                            .setAudienceService("tenant-service")
                                                            .setAction("batchValidateMemberships")
                                                            .setIssuedAt(now.toString())
                                                            .setExpiresAt(now.plusSeconds(30).toString())
                                                            .setAssertionId(UUID.randomUUID().toString()))
                                            .build();
                            var method = TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod().getFullMethodName();
                            var policy = new RpcContextSecurity.Policy(true, false);
                            return guard
                                    .authorize(context, method, policy)
                                    .flatMap(
                                            parent -> {
                                                var stub =
                                                        ReactorTenantMembershipServiceGrpc.newReactorStub(channel)
                                                                .withInterceptors(
                                                                        new TenantClientInterceptor(guard, Map.of(method, policy)))
                                                                .withOption(RpcContextBridge.OUTBOUND, parent)
                                                                .withDeadlineAfter(3, TimeUnit.SECONDS);
                                                return stub.batchValidateMemberships(
                                                        BatchValidateMembershipsRpcRequest.newBuilder()
                                                                .setContext(context)
                                                                .setTenantId(tenantId)
                                                                .addAllUserIds(userIds.stream().map(UUID::toString).toList())
                                                                .build()
                                                );
                                            })
                                    .onErrorResume(
                                            StatusRuntimeException.class,
                                            e ->
                                                    e.getStatus().getCode() == Status.Code.NOT_FOUND
                                                            ? Mono.empty()
                                                            : Mono.error(e)
                                    );
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}