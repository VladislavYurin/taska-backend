package ru.taska.domain.projection;

import org.springframework.data.relational.core.mapping.Embedded;
import ru.taska.domain.Issue;

public record IssueCoreDetails(
        @Embedded.Empty
        Issue issue,
        long commentCount,
        boolean isWatching
) {
}
