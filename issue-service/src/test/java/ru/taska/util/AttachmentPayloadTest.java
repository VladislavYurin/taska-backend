package ru.taska.util;

import org.junit.jupiter.api.Test;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueAttachment;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentPayloadTest {
    @Test
    void uploadAndDelete_keepIssueNavigationAndWatcherSnapshot() {
        var issue = Issue.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID()).issueKey("TAS-12").build();
        var actor = UUID.randomUUID();
        var watcher = UUID.randomUUID();
        var attachment = IssueAttachment.createNewAttachment(issue.getId(), actor, "key", "report.pdf",
                "application/pdf", 1024L, "checksum");
        attachment.setId(UUID.randomUUID());
        attachment.setCreatedAt(Instant.now());
        attachment.setDeletedAt(Instant.now());
        var serializer = new PayloadSerializer(new ObjectMapper());
        var uploaded = serializer.createAttachmentUploadedPayload(issue, attachment, List.of(watcher));
        var deleted = serializer.createAttachmentDeletedPayload(issue, attachment, actor, List.of(watcher));

        for (var payload : List.of(uploaded, deleted)) {
            assertThat(payload.path("issueId").asString()).isEqualTo(issue.getId().toString());
            assertThat(payload.path("issueKey").asString()).isEqualTo("TAS-12");
            assertThat(payload.path("projectId").asString()).isEqualTo(issue.getProjectId().toString());
            assertThat(payload.path("fileName").asString()).isEqualTo("report.pdf");
            assertThat(payload.path("watcherIds").get(0).asString()).isEqualTo(watcher.toString());
        }
        assertThat(uploaded.path("uploadedBy").asString()).isEqualTo(actor.toString());
        assertThat(deleted.path("deletedBy").asString()).isEqualTo(actor.toString());
    }
}
