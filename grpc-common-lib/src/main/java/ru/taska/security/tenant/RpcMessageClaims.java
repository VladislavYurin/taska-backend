package ru.taska.security.tenant;

import com.google.protobuf.*;
import io.grpc.Status;
import java.util.*;
import ru.taska.api.b2b.v1.*;

/** Legacy actor/tenant/header fields are assertions about verified provenance, never authority. */
final class RpcMessageClaims {
  private RpcMessageClaims() {}

  static void validate(Message request, String rpc, RequestContext context) {
    var route = RpcRoutePolicies.all().get(rpc);
    if (route == null) return;
    var c = context.getContext();
    if (!route.action().equals(c.getAction()) || !route.audience().equals(c.getAudienceService()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    if (route.scope().equals("ADMIN_EXPLICIT_SCOPE")) {
      if (!c.hasAdministrativeScope()
          || !c.hasAdministrativeLevel()
          || !c.hasGrantId()
          || c.getGrantId().isBlank()
          || !c.hasGrantGeneration()
          || c.getGrantGeneration().isBlank()
          || (c.getAdministrativeScope().getKind() != AdminScope.KindValue.KIND_PLATFORM
              && c.getAdministrativeScope().getKind() != AdminScope.KindValue.KIND_TENANT_SET)
          || (c.getAdministrativeScope().getKind() == AdminScope.KindValue.KIND_TENANT_SET
              && (!c.hasTenantId()
                  || !c.getAdministrativeScope().getTenantIdsList().contains(c.getTenantId())))
          || (c.getAdministrativeLevel() != AdminGrant.LevelValue.LEVEL_READ
              && c.getAdministrativeLevel() != AdminGrant.LevelValue.LEVEL_MODIFY)
          || (route.mutation() && c.getAdministrativeLevel() != AdminGrant.LevelValue.LEVEL_MODIFY))
        throw Status.PERMISSION_DENIED.asRuntimeException();
    }
    if (route.scope().equals("GLOBAL_SELF_SESSION_FOR_MUTATION")
        && c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
        && c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
      throw Status.PERMISSION_DENIED.asRuntimeException();
    String scope = route.mutation() ? "write" : "read";
    if (!c.getScopesList().contains(scope)
        && !(route.scope().equals("GLOBAL_SELF_SESSION_FOR_MUTATION")
            && c.getScopesList().contains(route.mutation() ? "account:write" : "account:read")))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    check(
        request,
        c,
        context.getHeader().getRequestId(),
        rpc,
        (route.scope().equals("TENANT_SELF")
                || route.scope().equals("GLOBAL_SELF_SESSION_FOR_MUTATION"))
            && !java.util.Set.of("getUserProfile", "getUserDetailsByIds", "getAvatarDownloadUrl")
                .contains(route.action()));
  }

  /** Account GLOBAL_ADMIN is not an implicit project/grant bypass. */
  static Message stampRoles(Message message, TrustedTenantContext parent) {
    var builder = message.toBuilder();
    for (var entry : message.getAllFields().entrySet()) {
      var f = entry.getKey();
      if (f.getName().equals("context")) continue;
      if (f.getName().equals("global_role")
          && f.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM
          && !(parent.hasAdministrativeScope() && parent.hasAdministrativeLevel())) {
        var user = f.getEnumType().findValueByName("GLOBAL_ROLE_USER");
        if (user != null) builder.setField(f, user);
      } else if (f.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        if (f.isRepeated()) {
          var values = (java.util.List<?>) entry.getValue();
          for (int i = 0; i < values.size(); i++)
            builder.setRepeatedField(f, i, stampRoles((Message) values.get(i), parent));
        } else builder.setField(f, stampRoles((Message) entry.getValue(), parent));
      }
    }
    return builder.build();
  }

  private static void check(
      Message message, TrustedTenantContext c, String requestId, String rpc, boolean selfUserId) {
    // Proto3 omits default strings from getAllFields(): required actor bindings cannot rely on it.
    for (var f : message.getDescriptorForType().getFields()) {
      boolean user =
          f.getName().equals("user_id")
              && (selfUserId
                  || rpc.endsWith("/CreateProject")
                  || rpc.endsWith("/ListMyProjects")
                  || rpc.endsWith("/HasProjectMembershipInternal")
                  || message
                      .getDescriptorForType()
                      .getFullName()
                      .equals("ru.taska.common.v1.UserContext"));
      boolean actor =
          Set.of("actor_user_id", "author_user_id").contains(f.getName())
              || (rpc.endsWith("/CreateIssue") && f.getName().equals("reporter_id"));
      if ((user || actor)
          && !f.isRepeated()
          && f.getJavaType() == Descriptors.FieldDescriptor.JavaType.STRING
          && (!c.hasActorUserId() || !c.getActorUserId().equals(message.getField(f))))
        throw Status.PERMISSION_DENIED.asRuntimeException();
      if (f.getName().equals("body")
          && f.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE
          && !message.hasField(f))
        check((Message) message.getField(f), c, requestId, rpc, selfUserId);
    }
    for (var entry : message.getAllFields().entrySet()) {
      var f = entry.getKey();
      if (f.getName().equals("context")) continue;
      var values = f.isRepeated() ? (List<?>) entry.getValue() : List.of(entry.getValue());
      for (var v : values) {
        if (v instanceof Message child) {
          if (f.getName().equals("header")) {
            var id = child.getDescriptorForType().findFieldByName("request_id");
            if (id != null && !requestId.equals(child.getField(id)))
              throw Status.PERMISSION_DENIED.asRuntimeException();
          }
          check(child, c, requestId, rpc, selfUserId);
        }
        if (f.getName().equals("global_role")
            && v instanceof Descriptors.EnumValueDescriptor role
            && role.getName().equals("GLOBAL_ROLE_GLOBAL_ADMIN")
            && !(c.hasAdministrativeScope() && c.hasAdministrativeLevel()))
          throw Status.PERMISSION_DENIED.asRuntimeException();
        if (v instanceof String value && !value.isBlank()) {
          if ((f.getName().equals("tenant_id") || f.getName().equals("tenant_ids"))
              && (!c.hasTenantId() || !c.getTenantId().equals(value)))
            throw Status.PERMISSION_DENIED.asRuntimeException();
          if ((Set.of("actor_user_id", "author_user_id").contains(f.getName())
                  || (rpc.endsWith("/CreateIssue") && f.getName().equals("reporter_id")))
              && (!c.hasActorUserId() || !c.getActorUserId().equals(value)))
            throw Status.PERMISSION_DENIED.asRuntimeException();
          if (f.getName().equals("user_id")
              && (selfUserId
                  || rpc.endsWith("/CreateProject")
                  || rpc.endsWith("/ListMyProjects")
                  || rpc.endsWith("/HasProjectMembershipInternal")
                  || message
                      .getDescriptorForType()
                      .getFullName()
                      .equals("ru.taska.common.v1.UserContext"))
              && (!c.hasActorUserId() || !c.getActorUserId().equals(value)))
            throw Status.PERMISSION_DENIED.asRuntimeException();
        }
      }
    }
  }
}
