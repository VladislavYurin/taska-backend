package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.ProjectRole;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueAttachmentRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.attachment.AttachmentServiceImpl;
import ru.taska.service.attachment.AttachmentTransactionExecutor;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;
import ru.taska.storage.dto.StoredObjectMetadata;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class AttachmentTest {

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueAttachmentRepository issueAttachmentRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private StorageClient storageClient;

    @Mock
    private IssueHistoryService issueHistoryService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private PayloadSerializer payloadSerializer;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IssueProperties issueProperties;

    private AttachmentServiceImpl attachmentService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String CONTENT_TYPE = "image/png";
    private static final long SIZE_BYTES = 1024L;
    private static final String OBJECT_KEY = "object-key-123";
    private static final String FILE_NAME = "screenshot.png";
    private static final String CHECKSUM = "etag-abc123";
    private static final String DOWNLOAD_URL = "https://s3.example.com/download";
    private static final UUID ATTACHMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private Set<ProjectRole> allowedUploadRoles;
    private Set<ProjectRole> allowedViewRoles;
    private Issue issue;

    @BeforeEach
    void setUp() {
        AttachmentTransactionExecutor transactionExecutor = new AttachmentTransactionExecutor(
                issueAttachmentRepository, issueHistoryService, outboxEventService, payloadSerializer);
        attachmentService = new AttachmentServiceImpl(issueProperties, issueRepository, issueAttachmentRepository,
                projectRoleChecker, storageClient, transactionExecutor);

        allowedUploadRoles = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER);
        allowedViewRoles = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER);

        issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setProjectId(PROJECT_ID);

        Mockito.lenient()
                .when(issueProperties.allowedRoles().uploadAttachmentRoles())
                .thenReturn(allowedUploadRoles);

        Mockito.lenient()
                .when(issueProperties.allowedRoles().viewAttachmentRoles())
                .thenReturn(allowedViewRoles);

        Mockito.lenient()
                .when(storageClient.createPresignedUploadUrl(Mockito.anyString(), Mockito.anyLong()))
                .thenReturn(Mono.empty());

        Mockito.lenient()
                .when(storageClient.validateAndGetUploadedObjectMetadata(Mockito.anyString()))
                .thenReturn(Mono.empty());
    }

    // ===== createUploadUrl =====

    @Test
    void testCreateUploadUrl_Success() {
        PresignedUploadResult expectedResult = new PresignedUploadResult("object-key-123", "https://s3.example.com/upload");

        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));

        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles))
                .thenReturn(Mono.empty());

        Mockito.when(storageClient.createPresignedUploadUrl(CONTENT_TYPE, SIZE_BYTES)).thenReturn(Mono.just(expectedResult));

        StepVerifier.create(attachmentService.createUploadUrl(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, CONTENT_TYPE, SIZE_BYTES))
                .assertNext(result -> {
                    Assertions.assertEquals("object-key-123", result.objectKey());
                    Assertions.assertEquals("https://s3.example.com/upload", result.url());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles);
        Mockito.verify(storageClient).createPresignedUploadUrl(CONTENT_TYPE, SIZE_BYTES);
    }

    @Test
    void testCreateUploadUrl_IssueNotFound() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(attachmentService.createUploadUrl(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, CONTENT_TYPE, SIZE_BYTES))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verifyNoInteractions(projectRoleChecker);
    }

    @Test
    void testCreateUploadUrl_PermissionDenied() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID))
                .thenReturn(Mono.just(PROJECT_ID));

        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));

        StepVerifier.create(attachmentService.createUploadUrl(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, CONTENT_TYPE, SIZE_BYTES))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles);
    }

    // ===== confirmUpload =====

    @Test
    void testConfirmUpload_Success() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        IssueAttachment savedAttachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        savedAttachment.setId(UUID.randomUUID());

        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles))
                .thenReturn(Mono.empty());
        Mockito.when(storageClient.objectExists(OBJECT_KEY)).thenReturn(Mono.just(true));
        Mockito.when(storageClient.validateAndGetUploadedObjectMetadata(OBJECT_KEY))
                .thenReturn(Mono.just(new StoredObjectMetadata(CHECKSUM, SIZE_BYTES)));
        Mockito.when(issueAttachmentRepository.save(Mockito.any(IssueAttachment.class)))
                .thenReturn(Mono.just(savedAttachment));
        Mockito.when(payloadSerializer.createAttachmentUploadedPayload(savedAttachment))
                .thenReturn(payload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, IssueEventType.ATTACHMENT_UPLOADED, payload))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(REQUEST_ID, NODE_ID, ISSUE_ID, EventType.ATTACHMENT_ADDED, payload))
                .thenReturn(Mono.empty());
        Mockito.when(storageClient.createPresignedDownloadUrl(OBJECT_KEY))
                .thenReturn(Mono.just(DOWNLOAD_URL));

        StepVerifier.create(attachmentService.confirmUpload(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                .assertNext(result -> {
                    Assertions.assertEquals(savedAttachment, result.issueAttachment());
                    Assertions.assertEquals(DOWNLOAD_URL, result.url());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles);
        Mockito.verify(storageClient).objectExists(OBJECT_KEY);
        Mockito.verify(storageClient).validateAndGetUploadedObjectMetadata(OBJECT_KEY);
        Mockito.verify(issueAttachmentRepository).save(Mockito.any(IssueAttachment.class));
        Mockito.verify(issueHistoryService).saveIssueHistory(
                REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, IssueEventType.ATTACHMENT_UPLOADED, payload);
        Mockito.verify(outboxEventService).saveOutboxEvent(
                REQUEST_ID, NODE_ID, ISSUE_ID, EventType.ATTACHMENT_ADDED, payload);
        Mockito.verify(storageClient).createPresignedDownloadUrl(OBJECT_KEY);
    }

    @Test
    void testConfirmUpload_ObjectNotFound() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles))
                .thenReturn(Mono.empty());
        Mockito.when(storageClient.objectExists(OBJECT_KEY)).thenReturn(Mono.just(false));

        StepVerifier.create(attachmentService.confirmUpload(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(storageClient).objectExists(OBJECT_KEY);
        Mockito.verifyNoMoreInteractions(issueAttachmentRepository);
    }

    @Test
    void testConfirmUpload_ValidationFailed() {
        DomainException validationError = new DomainException(DomainStatus.OUT_OF_RANGE, "File too large");

        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles))
                .thenReturn(Mono.empty());
        Mockito.when(storageClient.objectExists(OBJECT_KEY)).thenReturn(Mono.just(true));
        Mockito.when(storageClient.validateAndGetUploadedObjectMetadata(OBJECT_KEY))
                .thenReturn(Mono.error(validationError));

        StepVerifier.create(attachmentService.confirmUpload(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.OUT_OF_RANGE, exception.getStatus());
                    Assertions.assertEquals("File too large", exception.getMessage());
                })
                .verify();

        Mockito.verify(storageClient).objectExists(OBJECT_KEY);
        Mockito.verify(storageClient).validateAndGetUploadedObjectMetadata(OBJECT_KEY);
        Mockito.verifyNoInteractions(issueAttachmentRepository);
    }

    @Test
    void testConfirmUpload_IssueNotFound() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.empty());
        Mockito.when(storageClient.objectExists(OBJECT_KEY)).thenReturn(Mono.just(true));

        StepVerifier.create(attachmentService.confirmUpload(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verifyNoInteractions(projectRoleChecker, issueAttachmentRepository);
    }

    @Test
    void testConfirmUpload_PermissionDenied() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));
        Mockito.when(storageClient.objectExists(OBJECT_KEY)).thenReturn(Mono.just(true));

        StepVerifier.create(attachmentService.confirmUpload(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedUploadRoles);
        Mockito.verifyNoInteractions(issueAttachmentRepository);
    }

    // ===== listAttachments =====

    @Test
    void testListAttachments_Success() {
        IssueAttachment attachment1 = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, "key-1", "file1.png", CONTENT_TYPE, SIZE_BYTES, "checksum-1");
        attachment1.setId(UUID.randomUUID());

        IssueAttachment attachment2 = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, "key-2", "file2.png", CONTENT_TYPE, SIZE_BYTES, "checksum-2");
        attachment2.setId(UUID.randomUUID());

        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueAttachmentRepository.findAllByIssueIdAndDeletedAtIsNull(ISSUE_ID))
                .thenReturn(Flux.just(attachment1, attachment2));
        Mockito.when(storageClient.createPresignedDownloadUrl("key-1")).thenReturn(Mono.just("https://url1"));
        Mockito.when(storageClient.createPresignedDownloadUrl("key-2")).thenReturn(Mono.just("https://url2"));

        StepVerifier.create(attachmentService.listAttachments(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID))
                .assertNext(list -> {
                    Assertions.assertEquals(2, list.size());
                    Assertions.assertEquals(attachment1, list.get(0).issueAttachment());
                    Assertions.assertEquals("https://url1", list.get(0).url());
                    Assertions.assertEquals(attachment2, list.get(1).issueAttachment());
                    Assertions.assertEquals("https://url2", list.get(1).url());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles);
        Mockito.verify(issueAttachmentRepository).findAllByIssueIdAndDeletedAtIsNull(ISSUE_ID);
        Mockito.verify(storageClient).createPresignedDownloadUrl("key-1");
        Mockito.verify(storageClient).createPresignedDownloadUrl("key-2");
    }

    @Test
    void testListAttachments_Empty() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueAttachmentRepository.findAllByIssueIdAndDeletedAtIsNull(ISSUE_ID))
                .thenReturn(Flux.empty());

        StepVerifier.create(attachmentService.listAttachments(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID))
                .assertNext(list -> Assertions.assertTrue(list.isEmpty()))
                .expectComplete()
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles);
        Mockito.verify(issueAttachmentRepository).findAllByIssueIdAndDeletedAtIsNull(ISSUE_ID);
        Mockito.verifyNoInteractions(storageClient);
    }

    @Test
    void testListAttachments_IssueNotFound() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.empty());
        Mockito.when(issueAttachmentRepository.findAllByIssueIdAndDeletedAtIsNull(ISSUE_ID))
                .thenReturn(Flux.empty());

        StepVerifier.create(attachmentService.listAttachments(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueRepository).findProjectIdByActiveIssueId(ISSUE_ID);
        Mockito.verifyNoInteractions(projectRoleChecker, storageClient);
    }

    @Test
    void testListAttachments_PermissionDenied() {
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));
        Mockito.when(issueAttachmentRepository.findAllByIssueIdAndDeletedAtIsNull(ISSUE_ID))
                .thenReturn(Flux.empty());

        StepVerifier.create(attachmentService.listAttachments(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles);
        Mockito.verifyNoInteractions(storageClient);
    }

    // ===== getDownloadUrl =====

    @Test
    void testGetDownloadUrl_Success() {
        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        attachment.setId(ATTACHMENT_ID);
        attachment.setIssueId(ISSUE_ID);

        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.just(attachment));
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles))
                .thenReturn(Mono.empty());
        Mockito.when(storageClient.createPresignedDownloadUrl(OBJECT_KEY)).thenReturn(Mono.just(DOWNLOAD_URL));

        StepVerifier.create(attachmentService.getDownloadUrl(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .assertNext(result -> {
                    Assertions.assertEquals(DOWNLOAD_URL, result.url());
                    Assertions.assertEquals(CHECKSUM, result.checksum());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueAttachmentRepository).findByIdAndDeletedAtIsNull(ATTACHMENT_ID);
        Mockito.verify(storageClient).createPresignedDownloadUrl(OBJECT_KEY);
    }

    @Test
    void testGetDownloadUrl_AttachmentNotFound() {
        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(attachmentService.getDownloadUrl(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueAttachmentRepository).findByIdAndDeletedAtIsNull(ATTACHMENT_ID);
        Mockito.verifyNoInteractions(storageClient, projectRoleChecker);
    }

    @Test
    void testGetDownloadUrl_PermissionDenied() {
        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        attachment.setId(ATTACHMENT_ID);
        attachment.setIssueId(ISSUE_ID);

        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.just(attachment));
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));

        StepVerifier.create(attachmentService.getDownloadUrl(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(issueAttachmentRepository).findByIdAndDeletedAtIsNull(ATTACHMENT_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedViewRoles);
        Mockito.verifyNoMoreInteractions(storageClient);
    }

    // ===== deleteAttachment =====

    @Test
    void testDeleteAttachment_ByAuthor() {
        Set<ProjectRole> deleteOwnRoles = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER);
        Mockito.when(issueProperties.allowedRoles().deleteOwnAttachmentRoles()).thenReturn(deleteOwnRoles);

        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        attachment.setId(ATTACHMENT_ID);
        attachment.setUploadedBy(ACTOR_USER_ID);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.just(attachment));
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteOwnRoles))
                .thenReturn(Mono.empty());
        IssueAttachment deletedAttachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        deletedAttachment.setId(ATTACHMENT_ID);
        deletedAttachment.setDeletedAt(Instant.now());

        Mockito.when(issueAttachmentRepository.softDelete(ATTACHMENT_ID))
                .thenReturn(Mono.just(deletedAttachment));
        Mockito.when(payloadSerializer.createAttachmentDeletedPayload(Mockito.eq(deletedAttachment), Mockito.eq(ACTOR_USER_ID)))
                .thenReturn(payload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, IssueEventType.ATTACHMENT_DELETED, payload))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(REQUEST_ID, NODE_ID, ISSUE_ID, EventType.ATTACHMENT_DELETED, payload))
                .thenReturn(Mono.empty());

        StepVerifier.create(attachmentService.deleteAttachment(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .expectComplete()
                .verify();

        Mockito.verify(issueAttachmentRepository).findByIdAndDeletedAtIsNull(ATTACHMENT_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteOwnRoles);
        Mockito.verify(issueAttachmentRepository).softDelete(ATTACHMENT_ID);
    }

    @Test
    void testDeleteAttachment_NotAuthorButHasRole() {
        Set<ProjectRole> deleteRoles = Set.of(ProjectRole.ADMIN);
        Mockito.when(issueProperties.allowedRoles().deleteAttachmentRoles()).thenReturn(deleteRoles);

        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, OTHER_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        attachment.setId(ATTACHMENT_ID);
        attachment.setUploadedBy(OTHER_USER_ID);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();

        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.just(attachment));
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteRoles))
                .thenReturn(Mono.empty());
        IssueAttachment deletedAttachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, OTHER_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        deletedAttachment.setId(ATTACHMENT_ID);
        deletedAttachment.setDeletedAt(Instant.now());

        Mockito.when(issueAttachmentRepository.softDelete(ATTACHMENT_ID))
                .thenReturn(Mono.just(deletedAttachment));
        Mockito.when(payloadSerializer.createAttachmentDeletedPayload(Mockito.eq(deletedAttachment), Mockito.eq(ACTOR_USER_ID)))
                .thenReturn(payload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, IssueEventType.ATTACHMENT_DELETED, payload))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(REQUEST_ID, NODE_ID, ISSUE_ID, EventType.ATTACHMENT_DELETED, payload))
                .thenReturn(Mono.empty());

        StepVerifier.create(attachmentService.deleteAttachment(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .expectComplete()
                .verify();

        Mockito.verify(projectRoleChecker).checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteRoles);
        Mockito.verify(issueAttachmentRepository).softDelete(ATTACHMENT_ID);
    }

    @Test
    void testDeleteAttachment_NotAuthorNoPermission() {
        Set<ProjectRole> deleteRoles = Set.of(ProjectRole.ADMIN);
        Mockito.when(issueProperties.allowedRoles().deleteAttachmentRoles()).thenReturn(deleteRoles);

        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, OTHER_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        attachment.setId(ATTACHMENT_ID);
        attachment.setUploadedBy(OTHER_USER_ID);

        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.just(attachment));
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteRoles))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied")));

        StepVerifier.create(attachmentService.deleteAttachment(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                })
                .verify();

        Mockito.verify(projectRoleChecker).checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteRoles);
        Mockito.verify(issueAttachmentRepository, Mockito.never()).softDelete(Mockito.any());
    }

    @Test
    void testDeleteAttachment_RaceCondition_IdempotentSuccess() {
        Set<ProjectRole> deleteOwnRoles = Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER);
        Mockito.when(issueProperties.allowedRoles().deleteOwnAttachmentRoles()).thenReturn(deleteOwnRoles);

        IssueAttachment attachment = IssueAttachment.createNewAttachment(
                ISSUE_ID, ACTOR_USER_ID, OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, CHECKSUM);
        attachment.setId(ATTACHMENT_ID);
        attachment.setUploadedBy(ACTOR_USER_ID);

        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.just(attachment));
        Mockito.when(issueRepository.findProjectIdByActiveIssueId(ISSUE_ID)).thenReturn(Mono.just(PROJECT_ID));
        Mockito.when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, deleteOwnRoles))
                .thenReturn(Mono.empty());
        Mockito.when(issueAttachmentRepository.softDelete(ATTACHMENT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(attachmentService.deleteAttachment(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .verifyComplete();

        Mockito.verify(issueAttachmentRepository).softDelete(ATTACHMENT_ID);
        Mockito.verifyNoInteractions(issueHistoryService, outboxEventService);
    }

    @Test
    void testDeleteAttachment_AttachmentNotFound_IdempotentSuccess() {
        Mockito.when(issueAttachmentRepository.findByIdAndDeletedAtIsNull(ATTACHMENT_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(attachmentService.deleteAttachment(REQUEST_ID, NODE_ID, ATTACHMENT_ID, ACTOR_USER_ID))
                .verifyComplete();

        Mockito.verify(issueAttachmentRepository).findByIdAndDeletedAtIsNull(ATTACHMENT_ID);
        Mockito.verify(issueAttachmentRepository, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(projectRoleChecker, issueHistoryService, outboxEventService);
    }
}
