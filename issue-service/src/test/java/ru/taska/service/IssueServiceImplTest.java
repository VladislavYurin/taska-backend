package ru.taska.service;

import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.config.props.IssueListProperties;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;
import ru.taska.service.impl.IssueServiceImpl;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
public class IssueServiceImplTest {

    @Mock
    protected ProjectCounterRepository projectCounterRepository;

    @Mock
    protected IssueRepository issueRepository;

    @Mock
    protected IssueHistoryRepository issueHistoryRepository;

    @Mock
    protected OutboxEventRepository outboxEventRepository;

    @Spy
    protected IssueMapper issueMapper = new IssueMapper(new ObjectMapper());

    @InjectMocks
    protected IssueServiceImpl issueService;

    protected static final int DEFAULT_PAGE_SIZE = 20;
    protected static final int MAX_PAGE_SIZE = 100;

    @Spy
    protected IssueListProperties issueListProperties = new IssueListProperties(DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

    protected static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    protected static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    protected static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    protected static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
}
