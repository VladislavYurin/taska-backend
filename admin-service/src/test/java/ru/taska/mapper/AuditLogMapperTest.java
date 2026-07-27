package ru.taska.mapper;


import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.dto.AuditEventDto;
import ru.taska.entity.AuditLog;
import ru.taska.mapper.AuditLogMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

class AuditLogMapperTest {

    private final AuditLogMapper mapper = new AuditLogMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Должен корректно мапить все поля из AuditEventDto в AuditLog")
    void toAuditLog_shouldMapAllFieldsFromDtoToAuditLog() throws Exception {
        UUID actorUserId = UUID.randomUUID();
        JsonNode actorRoles = objectMapper.readTree("[\"ROLE_ADMIN\", \"ROLE_USER\"]");
        JsonNode oldValue = objectMapper.readTree("{\"status\": \"DRAFT\"}");
        JsonNode newValue = objectMapper.readTree("{\"status\": \"PUBLISHED\"}");

        AuditEventDto dto = AuditEventDto.builder()
                .requestId("req-12345")
                .actorUserId(actorUserId)
                .actorLogin("admin_user")
                .actorRoles(actorRoles)
                .action("UPDATE_STATUS")
                .targetService("issue-service")
                .targetTable("issues")
                .targetId("ISSUE-99")
                .oldValue(oldValue)
                .newValue(newValue)
                .reason("Manual status update by admin")
                .build();

        AuditLog auditLog = mapper.toAuditLog(dto);

        Assertions.assertThat(auditLog).isNotNull();
        Assertions.assertThat(auditLog.getId()).isNull();
        Assertions.assertThat(auditLog.getCreatedAt()).isNull();

        Assertions.assertThat(auditLog.getRequestId()).isEqualTo("req-12345");
        Assertions.assertThat(auditLog.getActorUserId()).isEqualTo(actorUserId);
        Assertions.assertThat(auditLog.getActorLogin()).isEqualTo("admin_user");
        Assertions.assertThat(auditLog.getActorRoles()).isEqualTo(actorRoles);
        Assertions.assertThat(auditLog.getAction()).isEqualTo("UPDATE_STATUS");
        Assertions.assertThat(auditLog.getTargetService()).isEqualTo("issue-service");
        Assertions.assertThat(auditLog.getTargetTable()).isEqualTo("issues");
        Assertions.assertThat(auditLog.getTargetId()).isEqualTo("ISSUE-99");
        Assertions.assertThat(auditLog.getOldValue()).isEqualTo(oldValue);
        Assertions.assertThat(auditLog.getNewValue()).isEqualTo(newValue);
        Assertions.assertThat(auditLog.getReason()).isEqualTo("Manual status update by admin");
    }

    @Test
    @DisplayName("Должен корректно обрабатывать DTO с null-полями")
    void toAuditLog_shouldMapDtoWithNullFields() {
        AuditEventDto dto = AuditEventDto.builder().build();

        AuditLog auditLog = mapper.toAuditLog(dto);

        Assertions.assertThat(auditLog).isNotNull();
        Assertions.assertThat(auditLog.getRequestId()).isNull();
        Assertions.assertThat(auditLog.getActorUserId()).isNull();
        Assertions.assertThat(auditLog.getActorLogin()).isNull();
        Assertions.assertThat(auditLog.getActorRoles()).isNull();
        Assertions.assertThat(auditLog.getAction()).isNull();
        Assertions.assertThat(auditLog.getTargetService()).isNull();
        Assertions.assertThat(auditLog.getTargetTable()).isNull();
        Assertions.assertThat(auditLog.getTargetId()).isNull();
        Assertions.assertThat(auditLog.getOldValue()).isNull();
        Assertions.assertThat(auditLog.getNewValue()).isNull();
        Assertions.assertThat(auditLog.getReason()).isNull();
    }
}
