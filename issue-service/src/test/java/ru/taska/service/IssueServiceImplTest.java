package ru.taska.service;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.config.props.IssueProperties;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IdempotencyKeyRepository;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.service.impl.IssueServiceImpl;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.workflow.IssueTransitionValidator;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class IssueServiceImplTest {

    @Mock
    protected GrpcProjectServiceClient grpcProjectServiceClient;

    @Mock
    protected ProjectCounterRepository projectCounterRepository;

    @Mock
    protected IssueRepository issueRepository;

    @Mock
    protected IssueHistoryRepository issueHistoryRepository;

    @Mock
    protected OutboxEventRepository outboxEventRepository;

    @Mock
    protected IssueHistoryService issueHistoryService;

    @Mock
    protected OutboxEventService outboxEventService;

    @Mock
    protected IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    protected ProjectRoleChecker projectRoleChecker;

    @Mock
    protected IssueLabelsRepository issueLabelsRepository;

    @Mock
    protected IssueTransitionValidator validator;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    protected IssueProperties issueProperties;

    @Spy
    protected IssueMapper issueMapper = new IssueMapper(new ObjectMapper());

    @Spy
    protected ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    protected PayloadSerializer payloadSerializer = new PayloadSerializer(objectMapper);

    @InjectMocks
    protected IssueServiceImpl issueService;

    protected static final int DEFAULT_PAGE_SIZE = 20;
    protected static final int MAX_PAGE_SIZE = 100;

    @Spy
    protected IssueProperties.List issueListProperties = new IssueProperties.List(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

    protected static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    protected static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    protected static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    protected static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    protected static final String REQUEST_ID = "req-001";
    protected static final String NODE_ID = "issue-service";
    protected static final String IDEMPOTENCY_KEY_1 = "00000000-0000-0000-0000-000000000006";
    protected static final String IDEMPOTENCY_KEY_2 = "00000000-0000-0000-0000-000000000007";
    protected static final UUID TRANSITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    protected static final String PAYLOAD = "some-kind-of-payload";
}
