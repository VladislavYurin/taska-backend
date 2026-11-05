package ru.taska.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.PlanVersion;

/**
 * Primary identity/KMS/purpose/disposition providers; none may infer identity from a request email.
 */
public final class FreeEligibilityDependencies {
  private FreeEligibilityDependencies() {}

  public record ConfirmedEmail(UUID userId, String canonicalEmail, String canonicalizationVersion) {
    @Override
    public String toString() {
      return "ConfirmedEmail[redacted]";
    }
  }

  public interface IdentityAuthority {
    Mono<ConfirmedEmail> confirmed(UUID userId);
  }

  public record KeySet(int activeVersion, Set<Integer> matchingVersions) {
    public KeySet {
      matchingVersions = Set.copyOf(matchingVersions);
    }
  }

  public interface HmacAuthority {
    Mono<KeySet> keys();

    /** HMAC-SHA256 using the dedicated FREE key version, normally through KMS. Never a raw hash. */
    Mono<byte[]> sign(int keyVersion, byte[] purposeBoundMessage);
  }

  public record PurposeAssessment(String version, Instant approvedAt, Instant validUntil) {}

  public interface PurposeAuthority {
    Mono<PurposeAssessment> current();
  }

  public interface CreationPlans {
    Mono<List<PlanVersion>> published();
  }

  /**
   * Non-owner administrative admission requires a current primary scoped grant, never a role bit.
   */
  public interface AdmissionAuthority {
    Mono<Boolean> allowed(
        ru.taska.security.tenant.VerifiedTenantContext context, UUID owner, long ownerVersion);
  }

  public enum DispositionAction {
    MINIMIZE_ACCOUNT,
    MINIMIZE_TENANT,
    REVIEW,
    ERASE
  }

  public record MarkerKey(int keyVersion, byte[] digest) {
    public MarkerKey {
      digest = digest.clone();
    }

    @Override
    public byte[] digest() {
      return digest.clone();
    }

    @Override
    public String toString() {
      return "MarkerKey[redacted]";
    }
  }

  /**
   * Closed authorized case, resolved online. Identity proofs are current verified emails under the
   * deletion fence, not old profiles/backup evidence. Tenant cleanup must cover all live user refs.
   * decisionVersion is immutable for the case and binds its exact targets in the primary authority.
   */
  public record Disposition(
      UUID caseId,
      String decisionVersion,
      DispositionAction action,
      UUID subjectId,
      List<ConfirmedEmail> identities,
      List<MarkerKey> markers,
      String purposeVersion,
      Instant reviewDueAt) {
    public Disposition {
      identities = List.copyOf(identities);
      markers = List.copyOf(markers);
    }

    @Override
    public String toString() {
      return "FreeDisposition[redacted]";
    }
  }

  public interface DispositionAuthority {
    Mono<Disposition> resolve(ru.taska.security.tenant.VerifiedTenantContext caller, UUID caseId);
  }
}
