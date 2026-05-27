package ru.taska.service;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

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

    protected static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    protected static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    protected static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
}
