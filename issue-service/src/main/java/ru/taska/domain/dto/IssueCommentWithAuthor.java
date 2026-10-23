package ru.taska.domain.dto;

import ru.taska.domain.entity.IssueComment;

public record IssueCommentWithAuthor (
    IssueComment comment,
    UserSummary author
){}
