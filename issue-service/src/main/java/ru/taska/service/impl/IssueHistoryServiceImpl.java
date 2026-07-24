package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.service.IssueHistoryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class IssueHistoryServiceImpl implements IssueHistoryService {

    private final IssueHistoryRepository issueHistoryRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Mono<IssueHistory> saveIssueCreateHistory(String requestId, String nodeId, Issue issue) {

        IssueHistory issueHistory = IssueHistory.builder()
                .issueId(issue.getId())
                .eventType(IssueEventType.CREATED)
                .actorUserId(issue.getReporterId())
                .occurredAt(Instant.now())
                .payload(objectMapper.valueToTree(issue))
                .build();

        log.debug("[{}][{}] Save created history for issue: {}", requestId, nodeId, issue.getId());
        return issueHistoryRepository.save(issueHistory);
    }

    @Transactional
    @Override
    public Mono<IssueHistory> saveIssueHistory(String requestId, String nodeId, UUID issueId, UUID actorUserId, IssueEventType type, JsonNode payload) {
        IssueHistory issueHistory = IssueHistory.builder()
                .issueId(issueId)
                .eventType(type)
                .actorUserId(actorUserId)
                .occurredAt(Instant.now())
                .payload(payload)
                .build();

        log.debug("[{}][{}] Save modified history for issue: {}", requestId, nodeId, issueId);
        return issueHistoryRepository.save(issueHistory);
    }
}


