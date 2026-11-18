package ru.taska.filter;

import io.grpc.Status;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcErrorDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayTokenKind;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GlobalRole;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

/**
 * Фабрика для построения {@link ru.taska.domain.GatewayContext} в зависимости от типа безопасности
 * эндпоинта.
 */
@Slf4j
@Component
@org.springframework.context.annotation.Import(ru.taska.security.McpMarkerVerifier.class)
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class GatewayContextFactory {

  @Value("${spring.application.name}")
  private String nodeId;

  private static final EndpointAccessPolicy PAT_POLICY = EndpointAccessPolicy.load();

  private final BearerTokenExtractor bearerTokenExtractor;
  private final GrpcAuthServiceClient authServiceClient;
  private final ContextMapper contextMapper;
  private final ru.taska.security.McpMarkerVerifier markerVerifier;

  public GatewayContextFactory(BearerTokenExtractor extractor, GrpcAuthServiceClient auth, ContextMapper mapper) {
    this(extractor, auth, mapper, new ru.taska.security.McpMarkerVerifier("", 10000));
  }

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity> trustedSecurity;
  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.beans.factory.ObjectProvider<ru.taska.api.b2b.v1.ReactorGatewayCredentialServiceGrpc.ReactorGatewayCredentialServiceStub> credentialStubs;
  @Value("${taska.rpc.context.enabled:false}")
  private boolean trustedEnabled;

  /**
   * Строит {@link ru.taska.domain.GatewayContext} в соответствии с типом безопасности эндпоинта.
   *
   * @param requestId идентификатор запроса
   * @param exchange текущий запрос
   * @param secured тип безопасности эндпоинта
   * @return контекст запроса
   */
  public Mono<GatewayContext> buildContext(
      String requestId, ServerWebExchange exchange, EndpointSecurity secured) {
    return switch (secured) {
      case PUBLIC -> Mono.fromSupplier(() -> {
        if (markerVerifier.hasMarker(exchange.getRequest())) throw ru.taska.security.McpMarkerVerifier.rejected();
        return new GatewayContext(requestId, nodeId, null);
      });
      case PROTECTED, JWT_SESSION_REQUIRED -> buildSecuredContext(exchange, requestId, secured);
      case GLOBAL_ADMIN_REQUIRED -> buildAdminContext(exchange, requestId);
    };
  }

  /**
   * Строит контекст для защищённого эндпоинта: извлекает Bearer-токен из запроса и валидирует его
   * через auth-сервис.
   *
   * @param exchange текущий запрос
   * @param requestId идентификатор запроса
   * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayUserContext}
   */
  private Mono<GatewayContext> resolveUserContext(ServerWebExchange exchange, String requestId) {
    if(trustedEnabled)return resolveTrustedUserContext(exchange,requestId);
    return Mono.fromCallable(() -> bearerTokenExtractor.extract(exchange, requestId))
        .flatMap(token -> authServiceClient.validateAccessToken(requestId, nodeId, token)
            .map(response -> {
              var proto = response.getUserContext();
              log.info("[{}] User context resolved: userId={}, tokenKind={}, tokenId={}",
                  requestId, proto.getUserId(), proto.getTokenKind(), proto.hasTokenId() ? proto.getTokenId() : null);
              var user = contextMapper.mapToGatewayUserContext(proto);
              ru.taska.domain.ActionChannel channel;
              if (user.tokenKind() == GatewayTokenKind.DELEGATION) {
                if (markerVerifier.hasMarker(exchange.getRequest())) throw ru.taska.security.McpMarkerVerifier.rejected();
                channel = ru.taska.domain.ActionChannel.MCP;
              } else channel = markerVerifier.resolve(exchange.getRequest(), requestId, token, user.tokenKind() == GatewayTokenKind.PAT);
              return new GatewayContext(requestId, nodeId, user, channel);
            }));
  }

  private record TenantTarget(String id,String action) {}

  private static boolean isTenantListPath(String path,String method){
    return ("GET".equals(method)||"HEAD".equals(method)) && "/api/v1/tenants".equals(path);
  }

  private static TenantTarget tenantTarget(String path,String method){
    if(!"GET".equals(method)&&!"HEAD".equals(method)&&!"PATCH".equals(method))return null;
    var matcher=java.util.regex.Pattern.compile("^/api/v1/tenants/([^/]+)$").matcher(path);
    if(!matcher.matches())return null;
    var id=canonicalTenantId(matcher.group(1));
    return new TenantTarget(id,"PATCH".equals(method)?"renameTenant":"getTenant");
  }

  private static String canonicalTenantId(String supplied){
    try {
      var parsed=java.util.UUID.fromString(supplied);
      if(!parsed.toString().equalsIgnoreCase(supplied))throw new IllegalArgumentException();
      return parsed.toString();
    }catch(IllegalArgumentException e){throw Status.INVALID_ARGUMENT.withDescription("Invalid tenant id").asRuntimeException();}
  }

  private static boolean sameTenant(String bound,String supplied){
    return canonicalTenantId(bound).equals(canonicalTenantId(supplied));
  }
  private Mono<GatewayContext> resolveTrustedUserContext(ServerWebExchange exchange,String requestId) {
    return Mono.defer(()->{
      var stub=credentialStubs.getIfAvailable();var security=trustedSecurity.getIfAvailable();
      if(stub==null || security==null)return Mono.error(Status.UNAVAILABLE.asRuntimeException());
      try {java.util.UUID.fromString(requestId);}catch(IllegalArgumentException e){return Mono.error(Status.INVALID_ARGUMENT.withDescription("Invalid X-Request-Id").asRuntimeException());}
      String token=bearerTokenExtractor.extract(exchange,requestId);
      String path=EndpointAccessPolicy.canonicalPath(exchange.getRequest());
      String method=exchange.getRequest().getMethod().name();
      var target=tenantTarget(path,method);
      boolean tenantList=isTenantListPath(path,method);
      var credentialRequest=ru.taska.api.b2b.v1.ResolveGatewayCredentialRequest.newBuilder().setCredential(token);
      if(target!=null)credentialRequest.setTargetTenantId(target.id()).setTargetAction(target.action());
      return stub.withDeadlineAfter(5,java.util.concurrent.TimeUnit.SECONDS)
        .resolveGatewayCredential(credentialRequest.build())
        .flatMap(resolved->{
          var user=contextMapper.mapToGatewayUserContext(resolved.getUser());
          var binding=resolved.getBinding();
          if(!binding.getActorUserId().equals(user.userId()))return Mono.error(Status.UNAUTHENTICATED.asRuntimeException());
          if(tenantList && (binding.hasTenantId()
              || binding.getCredentialKind()!=ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
              || !binding.getScopesList().contains("tenants:select")))return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
          if(target!=null && (!binding.hasTenantId() || !sameTenant(binding.getTenantId(),target.id())))return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
          if(target!=null && !binding.getScopesList().contains("renameTenant".equals(target.action())?"write":"read"))return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
          var suppliedTenants=exchange.getRequest().getHeaders().getOrEmpty("X-Tenant-Id");
          if(suppliedTenants.size()>1)return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
          String selected=exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
          if(selected!=null && (!binding.hasTenantId() || !sameTenant(binding.getTenantId(),selected)))return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
          // A URL tenant is a target assertion, never an authority source.
          var matcher=java.util.regex.Pattern.compile("^/api/v1/(?:tenants|companies)/([^/]+)(?:/|$)").matcher(path);
          if(matcher.find() && binding.hasTenantId() && !sameTenant(binding.getTenantId(),matcher.group(1)))return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
          var channel=markerVerifier.resolve(exchange.getRequest(),requestId,token,user.tokenKind()==GatewayTokenKind.PAT);
          var now=java.time.Instant.now();
          var claims=binding.toBuilder().setContextVersion(1).setIssuerService("api-gateway").setAudienceService("api-gateway")
            .setAction("gatewayRequest").setIssuedAt(now.toString()).setExpiresAt(now.plusSeconds(30).toString()).setAssertionId(java.util.UUID.randomUUID().toString());
          var header=ru.taska.api.common.v1.Header.newBuilder().setRequestId(requestId).setNodeId(nodeId).setActionChannel(channel.name());
          if(user.tokenId()!=null)header.setTokenId(user.tokenId());
          var context=ru.taska.api.b2b.v1.RequestContext.newBuilder().setHeader(header).setContext(claims).build();
          boolean write=!java.util.Set.of("GET","HEAD","OPTIONS").contains(exchange.getRequest().getMethod().name());
          return security.authorize(context,"gatewayRequest",new ru.taska.security.tenant.RpcContextSecurity.Policy(binding.hasTenantId(),write))
            .map(verified->new GatewayContext(requestId,nodeId,user,channel,verified));
        });
    });
  }

  /**
   * Маппит пользовательский контекст полученный в resloveUserContext
   *
   * @param exchange текущий запрос
   * @param requestId идентификатор запроса
   * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayContext}
   */
  private Mono<GatewayContext> buildSecuredContext(
      ServerWebExchange exchange, String requestId, EndpointSecurity security) {
    return resolveUserContext(exchange, requestId)
        .map(
            context -> {
              enforcePatPolicy(exchange, context.userContext(), security);
              return context;
            });
  }

  /**
   * Проверяет глобальную роль пользователя и маппит пользовательский контекст полученный в
   * resloveUserContext
   *
   * @param exchange текущий запрос
   * @param requestId идентификатор запроса
   * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayContext}
   */
  private Mono<GatewayContext> buildAdminContext(ServerWebExchange exchange, String requestId) {
    return resolveUserContext(exchange, requestId)
        .flatMap(
            context -> {
              var userContext = context.userContext();
              enforcePatPolicy(exchange, userContext, EndpointSecurity.GLOBAL_ADMIN_REQUIRED);
              if (!userContext.globalRole().equals(GlobalRole.GLOBAL_ADMIN)) {
                log.debug("User doesn't have permission: {}", userContext.userId());
                return Mono.error(
                    Status.PERMISSION_DENIED
                        .withDescription("User doesn't have permission")
                        .asRuntimeException());
              }
              return Mono.just(context);
            });
  }

  private static void enforcePatPolicy(
      ServerWebExchange exchange, GatewayUserContext user, EndpointSecurity security) {
    if (security == EndpointSecurity.JWT_SESSION_REQUIRED) {
      exchange.getResponse().getHeaders().setCacheControl("private, no-store");
    }
    if (user.tokenKind() == GatewayTokenKind.JWT) return;
    String path = EndpointAccessPolicy.canonicalPath(exchange.getRequest());
    String scope = PAT_POLICY.scope(exchange.getRequest());
    if (security == EndpointSecurity.GLOBAL_ADMIN_REQUIRED
        || security == EndpointSecurity.JWT_SESSION_REQUIRED
        || "session".equals(scope)
        || path.startsWith("/api/v1/admin/")
        || path.startsWith("/api/v1/readonly/")) {
      throw Status.PERMISSION_DENIED
          .withDescription("A JWT session is required")
          .asRuntimeException(GrpcErrorDetails.encode(Map.of("reason", "SESSION_REQUIRED")));
    }
    if (!user.scopes().contains(scope)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Insufficient token scope")
          .asRuntimeException(
              GrpcErrorDetails.encode(
                  Map.of("reason", "INSUFFICIENT_SCOPE", "requiredScope", scope)));
    }
  }
}
