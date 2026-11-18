package ru.taska.service;

import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.GetTenantRpcRequest;
import ru.taska.api.b2b.v1.ListTenantsRpcRequest;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.PageRequest;
import ru.taska.api.b2b.v1.ReactorTenantServiceGrpc;
import ru.taska.api.b2b.v1.RenameTenantRequest;
import ru.taska.api.b2b.v1.RenameTenantRpcRequest;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantPage;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.TenantCreationClosureReason;
import ru.taska.domain.dto.TenantDto;
import ru.taska.domain.dto.TenantLifecycle;
import ru.taska.domain.dto.TenantPageDto;
import ru.taska.domain.dto.TenantRestrictionCode;
import ru.taska.security.tenant.FileMtlsRpcChannels;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Trusted gRPC adapter for tenant list, detail, and rename requests. */
@Service
public class TenantRegistryClient implements AutoCloseable {
  private static final Set<String> LIST_SCOPES = Set.of("tenants:select");
  private static final Set<String> READ_SCOPES = Set.of("read");
  private static final Set<String> RENAME_SCOPES = Set.of("read", "write");
  private static final Set<String> WRITE_SCOPES = Set.of("write");

  private final ObjectProvider<RpcContextSecurity> security;
  private final String ca;
  private final String cert;
  private final String key;
  private final String environment;
  private final String host;
  private final int port;
  private ManagedChannel channel;

  public TenantRegistryClient(
      ObjectProvider<RpcContextSecurity> security,
      @Value("${taska.rpc.tls.ca-certificate:}") String ca,
      @Value("${taska.rpc.tls.certificate:}") String cert,
      @Value("${taska.rpc.tls.private-key:}") String key,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment,
      @Value("${taska.rpc.trust.tenant-host:tenant-service}") String host,
      @Value("${taska.rpc.trust.tenant-port:9099}") int port) {
    this.security = security;
    this.ca = ca;
    this.cert = cert;
    this.key = key;
    this.environment = environment;
    this.host = host;
    this.port = port;
  }

  private synchronized ManagedChannel channel() {
    if (channel == null) {
      channel =
          new FileMtlsRpcChannels(Path.of(ca), Path.of(cert), Path.of(key), environment)
              .create(host, port, "tenant-service");
    }
    return channel;
  }

  public Mono<TenantPageDto> listTenants(String cursor, int limit, GatewayContext context) {
    return Mono.defer(
            () -> {
              var guard = security.getIfAvailable();
              var parent = context.verified();
              if (guard == null || parent == null) {
                return Mono.error(Status.UNAVAILABLE.asRuntimeException());
              }
              if (parent.tenantId() != null || !parent.scopes().containsAll(LIST_SCOPES)) {
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              }

              var page = PageRequest.newBuilder().setLimit(limit);
              if (cursor != null) page.setCursor(cursor);
              var request =
                  ListTenantsRpcRequest.newBuilder()
                      .setContext(guard.delegate(parent, "tenant-service", "listTenants", LIST_SCOPES))
                      .setPage(page)
                      .build();
              String rpc = ru.taska.api.b2b.v1.TenantServiceGrpc.getListTenantsMethod().getFullMethodName();
              return signedCall(
                      guard,
                      parent,
                      request,
                      rpc,
                      new RpcContextSecurity.Policy(false, false),
                      headers -> stub(headers).listTenants(request))
                  .map(result -> toRestPage(result, limit));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  public Mono<TenantDto> getTenant(UUID tenantId, GatewayContext context) {
    return Mono.defer(
            () -> {
              var guard = security.getIfAvailable();
              var parent = context.verified();
              if (guard == null || parent == null) {
                return Mono.error(Status.UNAVAILABLE.asRuntimeException());
              }
              if (!tenantId.toString().equals(parent.tenantId())
                  || !parent.scopes().containsAll(READ_SCOPES)) {
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              }

              var request =
                  GetTenantRpcRequest.newBuilder()
                      .setContext(guard.delegate(parent, "tenant-service", "getTenant", READ_SCOPES))
                      .setTenantId(tenantId.toString())
                      .build();
              String rpc = ru.taska.api.b2b.v1.TenantServiceGrpc.getGetTenantMethod().getFullMethodName();
              return signedCall(
                      guard,
                      parent,
                      request,
                      rpc,
                      new RpcContextSecurity.Policy(true, false),
                      headers -> stub(headers).getTenant(request))
                  .map(result -> toRestTenant(result, tenantId));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  public Mono<TenantDto> renameTenant(
      UUID tenantId,
      String name,
      String idempotencyKey,
      long expectedVersion,
      GatewayContext context) {
    return Mono.defer(
            () -> {
              var guard = security.getIfAvailable();
              var parent = context.verified();
              if (guard == null || parent == null) {
                return Mono.error(Status.UNAVAILABLE.asRuntimeException());
              }
              if (!tenantId.toString().equals(parent.tenantId())) {
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              }
              Set<String> renameScopes =
                  parent.scopes().containsAll(RENAME_SCOPES)
                      ? RENAME_SCOPES
                      : parent.scopes().containsAll(WRITE_SCOPES) ? WRITE_SCOPES : Set.of();
              if (renameScopes.isEmpty()) {
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              }

              var request =
                  RenameTenantRpcRequest.newBuilder()
                      .setContext(guard.delegate(parent, "tenant-service", "renameTenant", renameScopes))
                      .setTenantId(tenantId.toString())
                      .setPayload(RenameTenantRequest.newBuilder().setName(name))
                      .setMutation(
                          MutationOptions.newBuilder()
                              .setIdempotencyKey(idempotencyKey)
                              .setExpectedVersion(Long.toString(expectedVersion)))
                      .build();
              String rpc = ru.taska.api.b2b.v1.TenantServiceGrpc.getRenameTenantMethod().getFullMethodName();
              return signedCall(
                      guard,
                      parent,
                      request,
                      rpc,
                      new RpcContextSecurity.Policy(true, true),
                      headers -> stub(headers).renameTenant(request))
                  .map(result -> toRestTenant(result, tenantId));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  private ReactorTenantServiceGrpc.ReactorTenantServiceStub stub(Metadata headers) {
    return ReactorTenantServiceGrpc.newReactorStub(channel())
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private static <T> Mono<T> signedCall(
      RpcContextSecurity guard,
      VerifiedTenantContext parent,
      Message request,
      String rpc,
      RpcContextSecurity.Policy policy,
      Function<Metadata, Mono<T>> invocation) {
    return guard
        .sign(parent, request, rpc, policy)
        .flatMap(
            assertion -> {
              var headers = new Metadata();
              headers.put(
                  Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER), assertion);
              return invocation.apply(headers);
            });
  }

  private static TenantPageDto toRestPage(TenantPage page, int requestedLimit) {
    try {
      if (page.getItemsCount() > requestedLimit || page.getItemsCount() > 100) {
        throw new IllegalArgumentException();
      }
      var items = page.getItemsList().stream().map(TenantRegistryClient::toRestTenant).toList();
      var result = new TenantPageDto().items(items);
      if (page.hasNextCursor()) {
        String cursor = page.getNextCursor();
        if (cursor.isBlank() || cursor.length() > 2048) throw new IllegalArgumentException();
        result.nextCursor(cursor);
      }
      return result;
    } catch (RuntimeException invalid) {
      throw invalidResponse();
    }
  }

  private static TenantDto toRestTenant(Tenant tenant) {
    try {
      if (tenant.getRestrictionsCount() > 20
          || tenant.getName().isEmpty()
          || tenant.getName().codePointCount(0, tenant.getName().length()) > 128) {
        throw new IllegalArgumentException();
      }
      var result =
          new TenantDto()
              .id(canonicalUuid(tenant.getId()))
              .name(tenant.getName())
              .ownerId(canonicalUuid(tenant.getOwnerId()))
              .lifecycle(TenantLifecycle.fromValue(enumSuffix(tenant.getLifecycle().name(), "LIFECYCLE_")))
              .restrictions(
                  tenant.getRestrictionsList().stream()
                      .map(value -> TenantRestrictionCode.fromValue(enumSuffix(value.name(), "RESTRICTION_CODE_")))
                      .toList())
              .createdAt(OffsetDateTime.parse(tenant.getCreatedAt()))
              .version(positiveInt64(tenant.getVersion()))
              .ownerVersion(positiveInt64(tenant.getOwnerVersion()));
      if (tenant.hasCreationDeadline()) {
        result.creationDeadline(OffsetDateTime.parse(tenant.getCreationDeadline()));
      }
      if (tenant.hasCreationClosureReason()) {
        result.creationClosureReason(
            TenantCreationClosureReason.fromValue(
                enumSuffix(tenant.getCreationClosureReason().name(), "CREATION_CLOSURE_REASON_")));
      }
      if (tenant.hasCreationClosedAt()) {
        result.creationClosedAt(OffsetDateTime.parse(tenant.getCreationClosedAt()));
      }
      return result;
    } catch (RuntimeException invalid) {
      throw invalidResponse();
    }
  }

  private static TenantDto toRestTenant(Tenant tenant, UUID expectedTenantId) {
    TenantDto result = toRestTenant(tenant);
    if (!expectedTenantId.equals(result.getId())) throw invalidResponse();
    return result;
  }

  private static UUID canonicalUuid(String value) {
    UUID parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
    return parsed;
  }

  private static String positiveInt64(String value) {
    long parsed = Long.parseLong(value);
    if (parsed < 1 || !Long.toString(parsed).equals(value)) throw new IllegalArgumentException();
    return value;
  }

  private static String enumSuffix(String value, String prefix) {
    if (!value.startsWith(prefix)) throw new IllegalArgumentException();
    return value.substring(prefix.length());
  }

  private static io.grpc.StatusRuntimeException invalidResponse() {
    return Status.UNAVAILABLE.withDescription("Invalid tenant-service response").asRuntimeException();
  }

  @jakarta.annotation.PreDestroy
  public synchronized void close() {
    if (channel != null) channel.shutdownNow();
  }
}