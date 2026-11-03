package ru.taska.billing.payment;

/** Stable adapter-level reason; never includes credentials or raw provider payload. */
public final class ProviderException extends RuntimeException {
    public enum Reason { UNKNOWN_OUTCOME, UNSUPPORTED_CAPABILITY, IDEMPOTENCY_CONFLICT, NOT_FOUND, INVALID_WEBHOOK, INVALID_STATE }
    private final Reason reason;
    public ProviderException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
