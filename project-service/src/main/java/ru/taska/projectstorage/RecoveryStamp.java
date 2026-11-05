package ru.taska.projectstorage;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Original verified generations are stored for future reauthorization, never treated as a grant. */
record RecoveryStamp(UUID actor, UUID grant, long actorVersion, long membershipVersion) {
  static RecoveryStamp from(VerifiedTenantContext verified) {
    if (verified.actorId()==null) throw denied();
    var claims=RpcContextBridge.details(verified).getContext();
    try {
      long actor=Long.parseLong(claims.getActorGeneration());
      long membership=Long.parseLong(claims.getMembershipGeneration());
      if (actor<1 || membership<1) throw denied();
      return new RecoveryStamp(UUID.fromString(verified.actorId()),claims.hasGrantId()?UUID.fromString(claims.getGrantId()):null,actor,membership);
    } catch (IllegalArgumentException invalidIdentity) { throw denied(); }
  }

  void requireActor(UUID value) {
    if (!actor.equals(value)) throw denied();
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED,"Original verified project operation actor required");
  }

  DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec sql) {
    sql=sql.bind("authVersion",actorVersion).bind("memberVersion",membershipVersion);
    return grant==null?sql.bindNull("grant",UUID.class):sql.bind("grant",grant);
  }
}
