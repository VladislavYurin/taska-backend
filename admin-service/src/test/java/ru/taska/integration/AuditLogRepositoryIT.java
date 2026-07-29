package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.entity.AuditLog;
import ru.taska.repository.AuditLogRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

class AuditLogRepositoryIT extends AbstractIT {

    @Autowired
    private AuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Должен успешно сохранить AuditLog в БД и прочитать jsonb поля")
    void shouldSaveAndReadAuditLogWithJsonb() throws Exception {
        JsonNode actorRoles = objectMapper.readTree("[\"ROLE_ADMIN\"]");
        JsonNode oldValue = objectMapper.readTree("{\"status\": \"OLD\"}");
        JsonNode newValue = objectMapper.readTree("{\"status\": \"NEW\"}");

        AuditLog auditLog = new AuditLog();
        auditLog.setRequestId("req-test-123");
        auditLog.setActorUserId(UUID.randomUUID());
        auditLog.setActorLogin("admin");
        auditLog.setActorRoles(actorRoles);
        auditLog.setAction("UPDATE_TASK");
        auditLog.setTargetService("issue-service");
        auditLog.setTargetTable("issues");
        auditLog.setTargetId("TAS-98");
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setReason("Task updated");

        auditLogRepository.save(auditLog)
                .flatMap(saved -> auditLogRepository.findById(saved.getId()))
                .as(StepVerifier::create)
                .expectNextMatches(found -> {
                    Assertions.assertThat(found.getId()).isNotNull();
                    Assertions.assertThat(found.getCreatedAt()).isNotNull();
                    Assertions.assertThat(found.getRequestId()).isEqualTo("req-test-123");
                    Assertions.assertThat(found.getActorRoles()).isEqualTo(actorRoles);
                    Assertions.assertThat(found.getOldValue()).isEqualTo(oldValue);
                    Assertions.assertThat(found.getNewValue()).isEqualTo(newValue);
                    return true;
                })
                .verifyComplete();
    }
}