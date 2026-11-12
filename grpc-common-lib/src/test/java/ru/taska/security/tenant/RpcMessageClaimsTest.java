package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.api.admin.v1.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.api.oauth.v1.*;

class RpcMessageClaimsTest {
  RequestContext context(String audience, String action) {
    var now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setActorUserId(UUID.randomUUID().toString())
                .setTenantId(UUID.randomUUID().toString())
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .addScopes("read")
                .addScopes("write")
                .setIssuerService("api-gateway")
                .setAudienceService(audience)
                .setAction(action)
                .setActorGeneration("1")
                .setMembershipGeneration("1")
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }

  @Test
  void emptySelfActorAndOmittedBodyAreRejectedBeforeTheHandler() {
    var c = context("notification-service", "getPreferences");
    String rpc =
        ru.taska.api.notification.v1.NotificationSettingsServiceGrpc.getGetPreferencesMethod()
            .getFullMethodName();
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                ru.taska.api.notification.v1.NotificationSettingsUserRequest.getDefaultInstance(),
                rpc,
                c));
    var project = context("project-service", "createProject");
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                ru.taska.api.project.v1.CreateProjectRequest.getDefaultInstance(),
                ru.taska.api.project.v1.ProjectServiceGrpc.getCreateProjectMethod()
                    .getFullMethodName(),
                project));
  }

  @Test
  void membershipGateCannotSubstituteAnotherAccount() {
    var c = context("project-service", "hasProjectMembershipInternal");
    String rpc =
        ru.taska.api.project.v1.ProjectServiceGrpc.getHasProjectMembershipInternalMethod()
            .getFullMethodName();
    var own =
        ru.taska.api.project.v1.HasProjectMembershipInternalRequest.newBuilder()
            .setUserId(c.getContext().getActorUserId())
            .build();
    assertDoesNotThrow(() -> RpcMessageClaims.validate(own, rpc, c));
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                own.toBuilder().setUserId(UUID.randomUUID().toString()).build(), rpc, c));
  }

  @Test
  void administrativeRpcNeedsExplicitGrantScopeAndSufficientLevel() {
    String rpc = AdminServiceGrpc.getRetryOutboxEventMethod().getFullMethodName();
    var c = context("admin-service", "retryOutboxEvent");
    var request =
        RetryOutboxEventRequest.newBuilder()
            .setHeader(c.getHeader())
            .setBody(
                RetryOutboxEventRequestBody.newBuilder()
                    .setActorUserId(c.getContext().getActorUserId()))
            .build();
    assertThrows(StatusRuntimeException.class, () -> RpcMessageClaims.validate(request, rpc, c));
    var read =
        c.toBuilder()
            .setContext(
                c.getContext().toBuilder()
                    .setGrantId(UUID.randomUUID().toString())
                    .setGrantGeneration("1")
                    .setAdministrativeScope(
                        AdminScope.newBuilder().setKind(AdminScope.KindValue.KIND_PLATFORM))
                    .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ))
            .build();
    assertThrows(StatusRuntimeException.class, () -> RpcMessageClaims.validate(request, rpc, read));
    var modify =
        read.toBuilder()
            .setContext(
                read.getContext().toBuilder()
                    .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_MODIFY))
            .build();
    assertDoesNotThrow(() -> RpcMessageClaims.validate(request, rpc, modify));
    var foreign =
        modify.toBuilder()
            .setContext(
                modify.getContext().toBuilder()
                    .setAdministrativeScope(
                        AdminScope.newBuilder()
                            .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                            .addTenantIds(UUID.randomUUID().toString())))
            .build();
    assertThrows(
        StatusRuntimeException.class, () -> RpcMessageClaims.validate(request, rpc, foreign));
  }

  @Test
  void selfSessionRpcDoesNotAcceptPersonalTokenOrAnotherAccount() {
    var c = context("auth-service", "listConnectedApps");
    var request =
        ListConnectedAppsRequest.newBuilder()
            .setHeader(c.getHeader())
            .setUserId(c.getContext().getActorUserId())
            .build();
    String rpc = ConnectedAppsServiceGrpc.getListConnectedAppsMethod().getFullMethodName();
    assertDoesNotThrow(() -> RpcMessageClaims.validate(request, rpc, c));
    var pat =
        c.toBuilder()
            .setContext(
                c.getContext().toBuilder()
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT))
            .build();
    assertThrows(StatusRuntimeException.class, () -> RpcMessageClaims.validate(request, rpc, pat));
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                request.toBuilder().setUserId(UUID.randomUUID().toString()).build(), rpc, c));
  }

  @Test
  void tenantRegistryReadsAndRenameBindTheVerifiedTargetAndAdminLevel() {
    var ordinary = context("tenant-service", "getTenant");
    var tenantId = ordinary.getContext().getTenantId();
    var getTenantRpc = TenantServiceGrpc.getGetTenantMethod().getFullMethodName();
    var getTenant = GetTenantRpcRequest.newBuilder().setTenantId(tenantId).build();
    assertDoesNotThrow(() -> RpcMessageClaims.validate(getTenant, getTenantRpc, ordinary));
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                getTenant.toBuilder().setTenantId(UUID.randomUUID().toString()).build(),
                getTenantRpc,
                ordinary));

    var readAdmin =
        adminTarget(ordinary, "getTenant", AdminGrant.LevelValue.LEVEL_READ, "read");
    assertDoesNotThrow(() -> RpcMessageClaims.validate(getTenant, getTenantRpc, readAdmin));
    var foreignAdmin =
        readAdmin.toBuilder()
            .setContext(
                readAdmin.getContext().toBuilder()
                    .setAdministrativeScope(
                        AdminScope.newBuilder()
                            .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                            .addTenantIds(UUID.randomUUID().toString())))
            .build();
    assertThrows(
        StatusRuntimeException.class,
        () -> RpcMessageClaims.validate(getTenant, getTenantRpc, foreignAdmin));

    var renameTenantRpc = TenantServiceGrpc.getRenameTenantMethod().getFullMethodName();
    var renameTenant = RenameTenantRpcRequest.newBuilder().setTenantId(tenantId).build();
    var modifyAdmin =
        adminTarget(
            ordinary,
            "renameTenant",
            AdminGrant.LevelValue.LEVEL_MODIFY,
            "read",
            "write");
    assertDoesNotThrow(
        () -> RpcMessageClaims.validate(renameTenant, renameTenantRpc, modifyAdmin));
    var readOnlyRename =
        adminTarget(ordinary, "renameTenant", AdminGrant.LevelValue.LEVEL_READ, "read");
    assertThrows(
        StatusRuntimeException.class,
        () -> RpcMessageClaims.validate(renameTenant, renameTenantRpc, readOnlyRename));
  }

  @Test
  void globalTenantListRequiresGlobalSelectionScopeWithoutTenantOrAdminGrant() {
    var template = context("tenant-service", "listTenants");
    String sessionId = UUID.randomUUID().toString();
    var global =
        template.toBuilder()
            .setContext(
                template.getContext().toBuilder()
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
                    .setSessionId(sessionId)
                    .setCredentialId(sessionId)
                    .clearTenantId()
                    .clearMembershipGeneration()
                    .clearScopes()
                    .addScopes("tenants:select"))
            .build();
    var rpc = TenantServiceGrpc.getListTenantsMethod().getFullMethodName();
    var request = ListTenantsRpcRequest.getDefaultInstance();
    assertDoesNotThrow(() -> RpcMessageClaims.validate(request, rpc, global));
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                request,
                rpc,
                global.toBuilder()
                    .setContext(
                        global.getContext().toBuilder().clearScopes().addScopes("read"))
                    .build()));
    assertThrows(
        StatusRuntimeException.class,
        () ->
            RpcMessageClaims.validate(
                request,
                rpc,
                global.toBuilder()
                    .setContext(global.getContext().toBuilder().setTenantId(UUID.randomUUID().toString()))
                    .build()));
  }

  private static RequestContext adminTarget(
      RequestContext template,
      String action,
      AdminGrant.LevelValue level,
      String... scopes) {
    String sessionId = UUID.randomUUID().toString();
    var scope =
        AdminScope.newBuilder()
            .setKind(AdminScope.KindValue.KIND_TENANT_SET)
            .addTenantIds(template.getContext().getTenantId())
            .build();
    return template.toBuilder()
        .setContext(
            template.getContext().toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
                .setSessionId(sessionId)
                .setCredentialId(sessionId)
                .clearMembershipGeneration()
                .clearGrantId()
                .clearGrantGeneration()
                .clearScopes()
                .addAllScopes(java.util.List.of(scopes))
                .setAdministrativeScope(scope)
                .setAdministrativeLevel(level)
                .setAction(action))
        .build();
  }
}
