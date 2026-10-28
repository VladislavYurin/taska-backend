package ru.taska.oauth;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import java.util.Map;
import java.util.Set;

public final class OAuthErrors {
    private OAuthErrors() { }
    public static final Set<String> REASONS = Set.of("PROVIDER_CANCELLED", "STATE_INVALID", "EMAIL_NOT_VERIFIED", "ACCOUNT_BLOCKED", "ACCOUNT_LOCKED", "SIGNUP_DISABLED", "PROVIDER_UNAVAILABLE");
    public static DomainException failure(String reason) {
        DomainStatus status = switch (reason) {
            case "STATE_INVALID" -> DomainStatus.UNAUTHENTICATED;
            case "PROVIDER_UNAVAILABLE" -> DomainStatus.UNAVAILABLE;
            case "PROVIDER_CANCELLED" -> DomainStatus.INVALID_ARGUMENT;
            default -> DomainStatus.PERMISSION_DENIED;
        };
        return new DomainException(status, switch (reason) {
            case "STATE_INVALID" -> "The authentication request has expired or was already used";
            case "EMAIL_NOT_VERIFIED" -> "A verified email address is required";
            case "ACCOUNT_BLOCKED" -> "This account is blocked";
            case "ACCOUNT_LOCKED" -> "This account is temporarily locked";
            case "SIGNUP_DISABLED" -> "Registration is disabled";
            case "PROVIDER_CANCELLED" -> "Authentication was cancelled";
            default -> "The authentication provider is unavailable";
        }, Map.of("reason", reason));
    }
    public static DomainException locked(java.time.Instant until) {
        return new DomainException(DomainStatus.PERMISSION_DENIED, "Account is temporarily locked",
                Map.of("reason", "ACCOUNT_LOCKED", "lockedUntil", until.toString()));
    }
    public static String reason(Throwable error) {
        if (error instanceof DomainException domain && domain.getDetails().get("reason") != null && REASONS.contains(domain.getDetails().get("reason"))) return domain.getDetails().get("reason");
        return "PROVIDER_UNAVAILABLE";
    }
}
