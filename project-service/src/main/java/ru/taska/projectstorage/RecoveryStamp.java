package ru.taska.projectstorage;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Original verified generations are stored for future reauthorization, never treated as a grant. */
record RecoveryStamp(UUID actor, UUID grant, long actorVersion, long membershipVersion) {
  static RecoveryStamp from(VerifiedTenantContext verified) {
    if (verified.actorId()==null) throw new IllegalStateException("Original actor required for a project operation");
    var claims=RpcContextBridge.details(verified).getContext();
    long actor=Long.parseLong(claims.getActorGeneration());
    long membership=Long.parseLong(claims.getMembershipGeneration());
    if (actor<1 || membership<1) throw new IllegalStateException("Original verified generations required");
    return new RecoveryStamp(UUID.fromString(verified.actorId()),claims.hasGrantId()?UUID.fromString(claims.getGrantId()):null,actor,membership);
  }

  void requireActor(UUID value) {
    if (!actor.equals(value)) throw new IllegalStateException("Original actor does not match verified context");
  }

  DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec sql) {
    sql=sql.bind("authVersion",actorVersion).bind("memberVersion",membershipVersion);
    return grant==null?sql.bindNull("grant",UUID.class):sql.bind("grant",grant);
  }
}
