package ru.taska.mapper;

import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueLinkViewType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;

public final class ProtoEnumMapper {

    private ProtoEnumMapper() {
    }

    /**
     * Общий механизм конвертации domain-enum → proto-enum: proto-константы
     * везде именуются как <ПРЕФИКС> + доменное имя (ISSUE_TYPE_TASK, ISSUE_PRIORITY_HIGH и т.д.),
     * поэтому конвертация сводится к конкатенации префикса и Enum.valueOf.
     */
    private static <T extends Enum<T>> T resolve(Enum<?> source, Class<T> protoType, String prefix) {
        if (source == null) {
            return null;
        }
        try {
            return Enum.valueOf(protoType, prefix + source.name());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "No proto enum constant %s%s in %s".formatted(prefix, source.name(), protoType.getSimpleName()), e);
        }
    }

    public static ru.taska.api.issue.v1.IssueType toProto(IssueType domain) {
        return resolve(domain, ru.taska.api.issue.v1.IssueType.class, "ISSUE_TYPE_");
    }

    public static ru.taska.api.issue.v1.IssuePriority toProto(IssuePriority domain) {
        return resolve(domain, ru.taska.api.issue.v1.IssuePriority.class, "ISSUE_PRIORITY_");
    }

    public static ru.taska.api.issue.v1.IssueLinkViewType toProto(IssueLinkViewType domain) {
        return resolve(domain, ru.taska.api.issue.v1.IssueLinkViewType.class, "ISSUE_LINK_VIEW_TYPE_");
    }

    public static ru.taska.api.issue.v1.IssueEventType toProto(IssueEventType domain) {
        return resolve(domain, ru.taska.api.issue.v1.IssueEventType.class, "ISSUE_EVENT_TYPE_");
    }
}
