package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueCommentResponse;
import ru.taska.api.issue.v1.DeleteIssueCommentResponse;
import ru.taska.api.issue.v1.IssueCommentResponse;
import ru.taska.api.issue.v1.UpdateIssueCommentResponse;
import ru.taska.domain.IssueComment;
import ru.taska.domain.IssueEventType;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class CommentMapper {

    public IssueCommentResponse toCommentProto(IssueComment comment) {
        return IssueCommentResponse.newBuilder()
                .setId(comment.getId().toString())
                .setIssueId(comment.getIssueId().toString())
                .setProjectId(comment.getProjectId().toString())
                .setAuthorUserId(comment.getAuthorUserId().toString())
                .setBody(comment.getBody())
                .setCreatedAt(toTimestamp(comment.getCreatedAt()))
                .setUpdatedAt(toTimestamp(comment.getUpdatedAt()))
                .setVersion(comment.getVersion())
                .build();
    }

    public AddIssueCommentResponse toAddCommentResponse(IssueComment comment) {
        return AddIssueCommentResponse.newBuilder()
                .setComment(toCommentProto(comment))
                .build();
    }

    public UpdateIssueCommentResponse toUpdateCommentResponse(IssueComment comment) {
        return UpdateIssueCommentResponse.newBuilder()
                .setComment(toCommentProto(comment))
                .build();
    }

    public DeleteIssueCommentResponse toDeleteCommentResponse(IssueComment comment) {
        return DeleteIssueCommentResponse.newBuilder()
                .setCommentId(comment.getId().toString())
                .build();
    }

    private Timestamp toTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}