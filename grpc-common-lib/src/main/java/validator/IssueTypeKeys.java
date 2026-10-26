package validator;

import io.grpc.Status;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueType;

import java.util.Optional;

/** Expand-contract reader shared by issue and workflow protocols. */
public final class IssueTypeKeys {
    private IssueTypeKeys() { }

    public static IssueType read(boolean hasKey, String key, Enum<?> legacy) {
        try {
            if (hasKey) return IssueType.valueOf(key);
            return switch (legacy.name()) {
                case "ISSUE_TYPE_TASK" -> IssueType.TASK;
                case "ISSUE_TYPE_BUG" -> IssueType.BUG;
                case "ISSUE_TYPE_STORY" -> IssueType.STORY;
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid issue type key").asRuntimeException();
        }
    }

    public static Mono<IssueType> required(boolean hasKey, String key, Enum<?> legacy) {
        return Mono.fromCallable(() -> read(hasKey, key, legacy));
    }

    public static Mono<Optional<IssueType>> optional(boolean hasKey, String key, boolean hasLegacy, Enum<?> legacy) {
        return hasKey || hasLegacy ? required(hasKey, key, legacy).map(Optional::of) : Mono.just(Optional.empty());
    }
}
