package ru.taska.mapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.taska.api.issue.attachment.v1.ListAttachmentsResponse;
import ru.taska.api.issue.v1.GetIssueDetailsResponse;
import ru.taska.api.issue.v1.IssueDetailsResponse;
import ru.taska.api.issue.v1.IssueLabelResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.ListLabelsResponse;
import ru.taska.domain.AttachmentDto;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.util.FetchResult;
import ru.taska.domain.aggregate.IssueDetailsAggregate;
import ru.taska.domain.projection.IssueCoreDetails;
import ru.taska.domain.projection.IssueLinkDetails;
import ru.taska.domain.projection.TargetIssue;
import ru.taska.domain.dto.UserSummary;
import ru.taska.domain.dto.labels.LabelResponses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class IssueDetailsMapperTest {

    public static final long COMMENT_COUNT = 3L;
    public static final String STATUS_KEY = "IN_PROGRESS";
    private IssueDetailsMapper mapper;

    @BeforeEach
    void setUp() {
        IssueDetailsMapper impl = new IssueDetailsMapperImpl();
        impl.issueWatcherMapper = new IssueWatcherMapper();
        impl.attachmentMapper = new AttachmentMapper();
        mapper = impl;
    }

    @Test
    void toProto_ShouldReturnDefaultInstance_WhenAggregateIsNull() {
        GetIssueDetailsResponse response = mapper.toProto(null);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(GetIssueDetailsResponse.getDefaultInstance(), response);
    }

    @Test
    void toProto_ShouldMapMinimalAggregate_WhenListsAndProfilesAreNull() {
        UUID issueId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();

        IssueCoreDetails core = createCoreDetails(issueId, null, reporterId);
        IssueDetailsAggregate aggregate =
                new IssueDetailsAggregate(core, FetchResult.failed(), FetchResult.failed(),
                        FetchResult.failed(), FetchResult.failed(), null, Collections.emptyMap());

        GetIssueDetailsResponse response = mapper.toProto(aggregate);

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.hasIssue());

        IssueDetailsResponse issue = response.getIssue();
        Assertions.assertEquals(issueId.toString(), issue.getId());
        Assertions.assertFalse(issue.hasAssignee());
        Assertions.assertTrue(issue.getIsWatching());
        Assertions.assertEquals(COMMENT_COUNT, issue.getCommentCount());
        Assertions.assertTrue(issue.hasReporter());
        Assertions.assertEquals(reporterId.toString(), issue.getReporter().getId());

        Assertions.assertEquals(0, issue.getAttachments().getAttachmentsCount());
        Assertions.assertEquals(0, response.getHistoryCount());
    }

    @Test
    void toProto_ShouldMapFullAggregate_WithUserResolutionAndAttachmentFlattening() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();


        UserSummary user1 = new UserSummary(userId1, "John Doe", "http://avatar1.com");
        UserSummary user2 = new UserSummary(userId2, "Jane Doe", null);
        Map<UUID, UserSummary> profiles = Map.of(userId1, user1, userId2, user2);

        IssueCoreDetails core = createCoreDetails(issueId, userId1, userId2);

        AttachmentDto att1 = createAttachmentDto("file1.png", "image/png", 1024L, userId1, issueId);
        AttachmentDto att2 = createAttachmentDto("file2.pdf", "application/pdf", 2048L, userId2, issueId);
        List<AttachmentDto> attachments = List.of(att1, att2);

        Issue issue = core.issue();
        IssueWatcher w1 = createWatcher(issueId, issue.getProjectId(), userId1);
        IssueWatcher w2 = createWatcher(issueId, issue.getProjectId(), userId2);

        IssueHistory history = createHistory(issueId, userId2);

        TargetIssue targetIssue = new TargetIssue(
                UUID.randomUUID(), "API-7", "Схема БД", projectId, "DONE"
        );
        IssueLink link = new IssueLink();

        Instant linkCreatedAt = Instant.parse("2026-09-01T10:15:30Z");

        link.setId(UUID.randomUUID());
        link.setLinkType(IssueLinkType.BLOCKS);
        link.setSourceIssueId(issueId);
        link.setTargetIssueId(targetIssue.id());
        link.setProjectId(projectId);
        link.setCreatedAt(linkCreatedAt);
        link.setCreatedBy(userId1);

        IssueLinkDetails issueLinkDetails = new IssueLinkDetails(link, targetIssue);

        LabelResponses.ProjectLabelInfo label = new LabelResponses.ProjectLabelInfo(UUID.randomUUID(), projectId, "bug", "#FF0000", userId1, Instant.now(), null);

        IssueDetailsAggregate aggregate = new IssueDetailsAggregate(
                core, FetchResult.ok(List.of(label)),
                FetchResult.ok(List.of(w1, w2)), FetchResult.ok(attachments), FetchResult.ok(List.of(issueLinkDetails)),
                List.of(history), profiles
        );

        GetIssueDetailsResponse response = mapper.toProto(aggregate);

        IssueDetailsResponse issueResponse = response.getIssue();

        // Issue
        Assertions.assertEquals(issue.getId().toString(), issueResponse.getId());
        Assertions.assertEquals(issue.getProjectId().toString(), issueResponse.getProjectId());
        Assertions.assertEquals(issue.getIssueNumber().longValue(), issueResponse.getIssueNumber());
        Assertions.assertEquals(issue.getIssueKey(), issueResponse.getIssueKey());
        Assertions.assertEquals(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK, issueResponse.getIssueType());
        Assertions.assertEquals(issue.getSummary(), issueResponse.getSummary());
        Assertions.assertEquals(issue.getDescription(), issueResponse.getDescription());
        Assertions.assertEquals(issue.getStatusKey(), issueResponse.getStatus());
        Assertions.assertEquals(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_HIGH, issueResponse.getPriority());

        // Users
        Assertions.assertEquals(user1.displayName(), issueResponse.getAssignee().getDisplayName());
        Assertions.assertEquals(user2.displayName(), issueResponse.getReporter().getDisplayName());

        Assertions.assertEquals(userId1.toString(), issueResponse.getAssigneeId());
        Assertions.assertEquals(userId2.toString(), issueResponse.getReporterId());

        // Dates / estimates
        Assertions.assertEquals(IssueMapperUtils.map(issue.getCreatedAt()), issueResponse.getCreatedAt());
        Assertions.assertEquals(IssueMapperUtils.map(issue.getUpdatedAt()), issueResponse.getUpdatedAt());

        Assertions.assertEquals(issue.getVersion(), issueResponse.getVersion());
        Assertions.assertEquals(issue.getStoryPoints().doubleValue(), issueResponse.getStoryPoints());
        Assertions.assertEquals(issue.getStartDate().toString(), issueResponse.getStartDate());
        Assertions.assertEquals(issue.getDueDate().toString(), issueResponse.getDueDate());
        Assertions.assertEquals(
                issue.getOriginalEstimateMinutes(),
                issueResponse.getOriginalEstimateMinutes()
        );
        Assertions.assertEquals(
                issue.getRemainingEstimateMinutes(),
                issueResponse.getRemainingEstimateMinutes()
        );

        // Watching
        Assertions.assertTrue(issueResponse.getIsWatching());
        Assertions.assertEquals(COMMENT_COUNT, issueResponse.getCommentCount());

        // Attachments
        ListAttachmentsResponse attachmentsResponse = issueResponse.getAttachments();
        Assertions.assertEquals(2, attachmentsResponse.getAttachmentsCount());
        Assertions.assertEquals("file1.png", attachmentsResponse.getAttachments(0).getFileName());
        Assertions.assertEquals(user1.displayName(), attachmentsResponse.getAttachments(0).getUploadedByUser().getDisplayName());
        Assertions.assertEquals("file2.pdf", attachmentsResponse.getAttachments(1).getFileName());

        // Labels
        ListLabelsResponse labelsResponse = issueResponse.getLabels();
        Assertions.assertEquals(1, labelsResponse.getLabelsCount());

        IssueLabelResponse labelResponse = labelsResponse.getLabels(0);
        Assertions.assertEquals(label.id().toString(), labelResponse.getId());
        Assertions.assertEquals(label.name(), labelResponse.getName());
        Assertions.assertEquals(label.color(), labelResponse.getColor());

        // Watchers
        ListIssueWatchersResponse watchersResponse = issueResponse.getWatchers();
        Assertions.assertEquals(2, watchersResponse.getWatchersCount());

        Assertions.assertEquals(user1.id().toString(), watchersResponse.getWatchers(0).getUserId());
        Assertions.assertEquals(user1.displayName(), watchersResponse.getWatchers(0).getDisplayName());
        Assertions.assertEquals(user1.avatarUrl(), watchersResponse.getWatchers(0).getAvatarUrl());

        Assertions.assertEquals(user2.id().toString(), watchersResponse.getWatchers(1).getUserId());
        Assertions.assertEquals(user2.displayName(), watchersResponse.getWatchers(1).getDisplayName());
        Assertions.assertEquals("", watchersResponse.getWatchers(1).getAvatarUrl());

        // Links
        ListIssueLinksResponse linksResponse = issueResponse.getLinks();
        Assertions.assertEquals(1, linksResponse.getIssueLinksCount());

        IssueLinkResponse linkResponse = linksResponse.getIssueLinks(0);

        Assertions.assertEquals(link.getId().toString(), linkResponse.getId());
        Assertions.assertEquals(link.getProjectId().toString(), linkResponse.getProjectId());
        Assertions.assertEquals(link.getSourceIssueId().toString(), linkResponse.getSourceIssueId());
        Assertions.assertEquals(link.getTargetIssueId().toString(), linkResponse.getTargetIssueId());
        Assertions.assertEquals(link.getCreatedBy().toString(), linkResponse.getCreatedBy());
        Assertions.assertEquals(IssueMapperUtils.map(linkCreatedAt), linkResponse.getCreatedAt());

        Assertions.assertEquals("API-7", linkResponse.getTarget().getIssueKey());
        Assertions.assertEquals(projectId.toString(), linkResponse.getTarget().getProjectId());
        Assertions.assertEquals(targetIssue.id().toString(), linkResponse.getTarget().getId());

        // History
        Assertions.assertEquals(1, response.getHistoryCount());
    }

    private IssueCoreDetails createCoreDetails(UUID id, UUID assigneeId, UUID reporterId) {
        Issue issue = new Issue();
        issue.setId(id);
        issue.setProjectId(UUID.randomUUID());
        issue.setIssueNumber(101);
        issue.setIssueKey("API-1");
        issue.setIssueType(IssueType.TASK);
        issue.setSummary("Summary");
        issue.setDescription("Description");
        issue.setStatusKey(STATUS_KEY);
        issue.setPriority(IssuePriority.HIGH);
        issue.setAssigneeId(assigneeId);
        issue.setReporterId(reporterId);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setVersion(1);
        issue.setStoryPoints(java.math.BigDecimal.valueOf(8.0));
        issue.setStartDate(LocalDate.now());
        issue.setDueDate(LocalDate.now().plusDays(5));
        issue.setOriginalEstimateMinutes(120);
        issue.setRemainingEstimateMinutes(60);

        return new IssueCoreDetails(issue, COMMENT_COUNT, true);
    }

    private AttachmentDto createAttachmentDto(String fileName, String contentType, Long sizeBytes,
                                              UUID uploadedBy, UUID issueId) {
        IssueAttachment attachment = new IssueAttachment();
        attachment.setId(UUID.randomUUID());
        attachment.setFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setCreatedAt(Instant.now());
        attachment.setUploadedBy(uploadedBy);
        attachment.setIssueId(issueId);
        attachment.setObjectKey("objectKey: " + fileName);
        attachment.setChecksum("test checksum");

        return new AttachmentDto(attachment, "https://s3.storage.com/" + fileName);
    }

    private IssueWatcher createWatcher(UUID issueId, UUID projectId, UUID userId) {
        IssueWatcher watcher = new IssueWatcher();
        watcher.setId(UUID.randomUUID());
        watcher.setIssueId(issueId);
        watcher.setProjectId(projectId);
        watcher.setUserId(userId);
        watcher.setCreatedAt(Instant.now());
        watcher.setCreatedBy(userId);
        return watcher;
    }

    private IssueHistory createHistory(UUID issueId, UUID reporterId) {
        return IssueHistory.builder()
                .id(UUID.randomUUID())
                .issueId(issueId)
                .eventType(IssueEventType.CREATED)
                .actorUserId(reporterId)
                .build();
    }
}
