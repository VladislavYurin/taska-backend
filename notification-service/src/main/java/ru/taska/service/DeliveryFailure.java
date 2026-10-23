package ru.taska.service;

/** Safe error codes only: provider messages and request URLs must never escape the transport. */
public final class DeliveryFailure extends RuntimeException {
    private final boolean permanent;
    private final boolean disconnected;
    private final long retryAfter;
    public DeliveryFailure(String code, boolean permanent, boolean disconnected, long retryAfter) {
        super(code, null, false, false);
        this.permanent = permanent;
        this.disconnected = disconnected;
        this.retryAfter = retryAfter;
    }
    public boolean permanent() { return permanent; }
    public boolean disconnected() { return disconnected; }
    public long retryAfter() { return retryAfter; }
}
