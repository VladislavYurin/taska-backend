package ru.taska.domain.projection;

import org.springframework.data.relational.core.mapping.Embedded;
import ru.taska.domain.IssueLink;


public record IssueLinkDetail(
        @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL)
        IssueLink link,
        @Embedded(onEmpty = Embedded.OnEmpty.USE_NULL, prefix = "target_")
        TargetIssue target
){}
