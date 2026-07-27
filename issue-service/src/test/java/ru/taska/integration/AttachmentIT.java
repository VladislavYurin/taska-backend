package ru.taska.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.repository.IssueAttachmentRepository;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.AttachmentService;
import ru.taska.storage.client.StorageClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class AttachmentIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @MockitoBean
    private StorageClient storageClient;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private IssueAttachmentRepository attachmentRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IssueHistoryRepository issueHistoryRepository;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String REQUEST_ID = "req-it-001";
    private static final String NODE_ID = "issue-service";
    private static final String OBJECT_KEY = "key/file.png";
    private static final String FILE_NAME = "file.png";
    private static final String CONTENT_TYPE = "image/png";
    private static final long SIZE_BYTES = 1024L;
    private static final String CHECKSUM = "etag-123";
    private static final String DOWNLOAD_URL = "https://s3.example.com/download";

    private UUID issueId;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll()
                .then(issueHistoryRepository.deleteAll())
                .then(attachmentRepository.deleteAll())
                .then(issueRepository.deleteAll())
                .block();

        Issue issue = Issue.builder()
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueKey("TST-1")
                .summary("Test issue")
                .statusKey("TODO")
                .issueType(IssueType.TASK)
                .priority(IssuePriority.MEDIUM)
                .reporterId(USER_ID)
                .build();
        issueId = issueRepository.save(issue).block().getId();

        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                        .setIsMember(true)
                        .setProjectExists(true)
                        .build()));
    }

    @Test
    void findByIdAndDeletedAtIsNull_shouldFindActiveAndNotFindDeleted() {
        IssueAttachment activeAttachment = IssueAttachment.createNewAttachment(
                issueId, USER_ID, "key/active.txt", "active.txt",
                "text/plain", 100L, "checksum-active");
        UUID activeId = attachmentRepository.save(activeAttachment).block().getId();

        IssueAttachment deletedAttachment = IssueAttachment.createNewAttachment(
                issueId, USER_ID, "key/deleted.txt", "deleted.txt",
                "text/plain", 200L, "checksum-deleted");
        deletedAttachment.setDeletedAt(Instant.now());
        UUID deletedId = attachmentRepository.save(deletedAttachment).block().getId();

        StepVerifier.create(attachmentRepository.findByIdAndDeletedAtIsNull(activeId))
                .assertNext(found -> {
                    Assertions.assertEquals(activeId, found.getId());
                    Assertions.assertNull(found.getDeletedAt());
                })
                .verifyComplete();

        StepVerifier.create(attachmentRepository.findByIdAndDeletedAtIsNull(deletedId))
                .verifyComplete();
    }

    @Test
    void findByIssueIdAndDeletedAtIsNull_shouldReturnOnlyActiveAttachments() {
        IssueAttachment active1 = IssueAttachment.createNewAttachment(
                issueId, USER_ID, "key/file1.txt", "file1.txt",
                "text/plain", 100L, "checksum-1");
        attachmentRepository.save(active1).block();

        IssueAttachment active2 = IssueAttachment.createNewAttachment(
                issueId, USER_ID, "key/file2.txt", "file2.txt",
                "text/plain", 200L, "checksum-2");
        attachmentRepository.save(active2).block();

        IssueAttachment deleted = IssueAttachment.createNewAttachment(
                issueId, USER_ID, "key/deleted.txt", "deleted.txt",
                "text/plain", 300L, "checksum-deleted");
        deleted.setDeletedAt(Instant.now());
        attachmentRepository.save(deleted).block();

        List<IssueAttachment> result = attachmentRepository
                .findByIssueIdAndDeletedAtIsNull(issueId)
                .collectList()
                .block();

        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.stream().allMatch(a -> a.getDeletedAt() == null));
        Assertions.assertTrue(result.stream().anyMatch(a -> a.getFileName().equals("file1.txt")));
        Assertions.assertTrue(result.stream().anyMatch(a -> a.getFileName().equals("file2.txt")));
    }

    @Test
    void confirmUpload_shouldSaveAttachmentAndHistoryAndOutboxEvent() {
        Mockito.when(storageClient.objectExists(OBJECT_KEY)).thenReturn(Mono.just(true));
        Mockito.when(storageClient.validateObjectSizeAndDeleteIfTooLargeAndReturnETag(OBJECT_KEY))
                .thenReturn(Mono.just(CHECKSUM));
        Mockito.when(storageClient.createPresignedDownloadUrl(OBJECT_KEY))
                .thenReturn(Mono.just(DOWNLOAD_URL));

        StepVerifier.create(attachmentService.confirmUpload(
                        REQUEST_ID, NODE_ID, issueId, USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES))
                .assertNext(result -> {
                    Assertions.assertEquals(FILE_NAME, result.issueAttachment().getFileName());
                    Assertions.assertEquals(OBJECT_KEY, result.issueAttachment().getObjectKey());
                    Assertions.assertEquals(CHECKSUM, result.issueAttachment().getChecksum());
                    Assertions.assertEquals(DOWNLOAD_URL, result.url());
                    Assertions.assertNotNull(result.issueAttachment().getId());
                })
                .verifyComplete();

        List<IssueAttachment> attachments = attachmentRepository
                .findByIssueIdAndDeletedAtIsNull(issueId).collectList().block();
        Assertions.assertEquals(1, attachments.size());
        Assertions.assertEquals(FILE_NAME, attachments.get(0).getFileName());

        Assertions.assertEquals(1, outboxEventRepository.count().block());
        Assertions.assertEquals(1, issueHistoryRepository.count().block());
    }

    @Test
    void deleteAttachment_byAuthor_shouldSoftDelete() {
        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                issueId, USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        UUID attachmentId = attachmentRepository.save(attachment).block().getId();

        StepVerifier.create(attachmentService.deleteAttachment(REQUEST_ID, NODE_ID, attachmentId, USER_ID))
                .verifyComplete();

        List<IssueAttachment> active = attachmentRepository
                .findByIssueIdAndDeletedAtIsNull(issueId).collectList().block();
        Assertions.assertTrue(active.isEmpty());

        IssueAttachment softDeleted = attachmentRepository.findById(attachmentId).block();
        Assertions.assertNotNull(softDeleted);
        Assertions.assertNotNull(softDeleted.getDeletedAt());
    }

}
