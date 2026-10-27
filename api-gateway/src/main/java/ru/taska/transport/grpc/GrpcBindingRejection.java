package ru.taska.transport.grpc;

import java.util.Map;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Domain rejections travel in RPC bodies so status lists are not truncated by metadata limits. */
final class GrpcBindingRejection {
    private GrpcBindingRejection() {}

    static DomainException from(String status, String message, Map<String, String> details) {
        DomainStatus parsed;
        try {
            parsed = DomainStatus.valueOf(status);
        } catch (IllegalArgumentException malformed) {
            return new DomainException(DomainStatus.UNAVAILABLE, "Invalid workflow binding response");
        }
        if (parsed.name().equals("OK")) return new DomainException(DomainStatus.UNAVAILABLE, "Invalid workflow binding response");
        return new DomainException(parsed, message.isBlank() ? "Workflow binding was rejected" : message, details);
    }
}
