package ru.taska.domain.dto;

import ru.taska.domain.IssueComment;

public record IssueCommentWithAuthor (
    IssueComment comment,
    UserSummary author
){}
