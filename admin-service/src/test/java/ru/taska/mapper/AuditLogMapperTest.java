package ru.taska.mapper;


import org.assertj.core.api.Assertions;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.api.admin.v1.ListAuditEntriesResponse;
import ru.taska.domain.PageResult;
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.entity.AuditLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class AuditLogMapperTest {

    private final AuditLogMapper mapper = new AuditLogMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID ACTOR_USER_ID = UUID.randomUUID();
    private static final UUID AUDIT_LOG_ID = UUID.randomUUID();
    private static final String REQUEST_ID = "req-12345";
    private static final String ACTOR_LOGIN = "admin_user";
    private static final String ACTION = "UPDATE_STATUS";
    private static final String TARGET_SERVICE = "issue-service";
    private static final String TARGET_TABLE = "issues";
    private static final String TARGET_ID = "ISSUE-99";
    private static final String REASON = "Manual status update by admin";
    private static final String BODY_REQUEST_ID = "body-request-67890";
    private static final Instant CREATED_AT_FROM = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant CREATED_AT_TO = Instant.parse("2024-12-31T23:59:59Z");

    private JsonNode OLD_VALUE;
    private JsonNode NEW_VALUE;

    @BeforeEach
    void setUp() throws Exception {
        OLD_VALUE = objectMapper.readTree("{\"status\": \"DRAFT\"}");
        NEW_VALUE = objectMapper.readTree("{\"status\": \"PUBLISHED\"}");
    }

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

    @Test
    @DisplayName("Должен корректно мапить все поля в FilterAuditDTO")
    void toFilterDTO_shouldMapAllFields() {
        FilterAuditDTO result = mapper.toFilterDTO(ACTOR_USER_ID, ACTION, TARGET_SERVICE, TARGET_TABLE, TARGET_ID, REQUEST_ID, CREATED_AT_FROM, CREATED_AT_TO);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.actorUserId()).isEqualTo(ACTOR_USER_ID);
        Assertions.assertThat(result.action()).isEqualTo(ACTION);
        Assertions.assertThat(result.targetService()).isEqualTo(TARGET_SERVICE);
        Assertions.assertThat(result.targetTable()).isEqualTo(TARGET_TABLE);
        Assertions.assertThat(result.targetId()).isEqualTo(TARGET_ID);
        Assertions.assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(result.createdAtFrom()).isEqualTo(CREATED_AT_FROM);
        Assertions.assertThat(result.createdAtTo()).isEqualTo(CREATED_AT_TO);
    }

    @Test
    @DisplayName("Должен корректно обрабатывать null-параметры")
    void toFilterDTO_shouldHandleNullParameters() {
        FilterAuditDTO result = mapper.toFilterDTO(null, null, null, null, null, null, null, null);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.actorUserId()).isNull();
        Assertions.assertThat(result.action()).isNull();
        Assertions.assertThat(result.targetService()).isNull();
        Assertions.assertThat(result.targetTable()).isNull();
        Assertions.assertThat(result.targetId()).isNull();
        Assertions.assertThat(result.requestId()).isNull();
        Assertions.assertThat(result.createdAtFrom()).isNull();
        Assertions.assertThat(result.createdAtTo()).isNull();
    }

    @Test
    @DisplayName("Должен корректно мапить все поля из AuditLog в ListAuditEntriesResponseDto")
    void toResponseDto_shouldMapAllFieldsFromAuditLogToDto() throws Exception {
        AuditLog auditLog = createAuditLog();

        AuditEntriesResponseDto result = mapper.toResponseDto(auditLog);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.actorUserId()).isEqualTo(ACTOR_USER_ID);
        Assertions.assertThat(result.actorLogin()).isEqualTo(ACTOR_LOGIN);
        Assertions.assertThat(result.action()).isEqualTo(ACTION);
        Assertions.assertThat(result.targetService()).isEqualTo(TARGET_SERVICE);
        Assertions.assertThat(result.targetTable()).isEqualTo(TARGET_TABLE);
        Assertions.assertThat(result.targetId()).isEqualTo(TARGET_ID);
        Assertions.assertThat(result.oldValue()).isEqualTo(OLD_VALUE);
        Assertions.assertThat(result.newValue()).isEqualTo(NEW_VALUE);
        Assertions.assertThat(result.reason()).isEqualTo(REASON);
        Assertions.assertThat(result.requestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(result.createdAt()).isEqualTo(CREATED_AT_TO);
    }

    @Test
    @DisplayName("Должен корректно обрабатывать AuditLog с null-полями")
    void toResponseDto_shouldMapAuditLogWithNullFields() {
        AuditLog auditLog = createLogNull();

        AuditEntriesResponseDto result = mapper.toResponseDto(auditLog);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.actorUserId()).isNull();
        Assertions.assertThat(result.actorLogin()).isNull();
        Assertions.assertThat(result.action()).isNull();
        Assertions.assertThat(result.targetService()).isNull();
        Assertions.assertThat(result.targetTable()).isNull();
        Assertions.assertThat(result.targetId()).isNull();
        Assertions.assertThat(result.oldValue()).isNull();
        Assertions.assertThat(result.newValue()).isNull();
        Assertions.assertThat(result.reason()).isNull();
        Assertions.assertThat(result.requestId()).isNull();
        Assertions.assertThat(result.createdAt()).isNull();
    }

    @Test
    @DisplayName("Должен корректно обрабатывать пустую страницу")
    void toAuditProto_shouldHandleEmptyPage() {
        PageResult<AuditEntriesResponseDto> pageResult = new PageResult<>(
                List.of(),
                0,
                0,
                0
        );

        ListAuditEntriesResponse result = mapper.toAuditProto(pageResult);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getCurrentPage()).isEqualTo(0);
        Assertions.assertThat(result.getPageSize()).isEqualTo(0);
        Assertions.assertThat(result.getTotalRows()).isEqualTo(0);
        Assertions.assertThat(result.getTotalPages()).isEqualTo(0);
        Assertions.assertThat(result.getEntriesCount()).isEqualTo(0);
        Assertions.assertThat(result.getEntriesList()).isEmpty();
        Assertions.assertThat(result.getHasNext()).isFalse();
        Assertions.assertThat(result.getHasPrev()).isFalse();
    }

    @Test
    @DisplayName("Должен корректно мапить полную страницу в ListAuditEntriesResponse")
    void toAuditProto_shouldMapFullPage() {
        List<AuditEntriesResponseDto> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(AuditEntriesResponseDto.builder()
                    .actorUserId(ACTOR_USER_ID)
                    .actorLogin(ACTOR_LOGIN + "_" + i)
                    .action(ACTION + "_" + i)
                    .targetService(TARGET_SERVICE)
                    .targetTable(TARGET_TABLE)
                    .targetId(TARGET_ID + "-" + i)
                    .oldValue(OLD_VALUE)
                    .newValue(NEW_VALUE)
                    .reason(REASON)
                    .requestId(REQUEST_ID + "-" + i)
                    .createdAt(CREATED_AT_TO.plusSeconds(i))
                    .build());
        }

        PageResult<AuditEntriesResponseDto> pageResult = new PageResult<>(
                items,
                20,
                1,
                20
        );

        ListAuditEntriesResponse result = mapper.toAuditProto(pageResult);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getCurrentPage()).isEqualTo(1);
        Assertions.assertThat(result.getPageSize()).isEqualTo(20);
        Assertions.assertThat(result.getTotalRows()).isEqualTo(20);
        Assertions.assertThat(result.getTotalPages()).isEqualTo(1);
        Assertions.assertThat(result.getEntriesCount()).isEqualTo(20);

        Assertions.assertThat(result.getHasNext()).isFalse();
        Assertions.assertThat(result.getHasPrev()).isTrue();

        for (int i = 0; i < 20; i++) {
            Assertions.assertThat(result.getEntries(i).getActorUserId())
                    .isEqualTo(items.get(i).actorUserId().toString());

            Assertions.assertThat(result.getEntries(i).getAction())
                    .isEqualTo(items.get(i).action());

            Assertions.assertThat(result.getEntries(i).getTargetId())
                    .isEqualTo(items.get(i).targetId());

            Assertions.assertThat(result.getEntries(i).getActorLogin())
                    .isEqualTo(items.get(i).actorLogin());

            Assertions.assertThat(result.getEntries(i).getTargetService())
                    .isEqualTo(items.get(i).targetService());

            Assertions.assertThat(result.getEntries(i).getTargetTable())
                    .isEqualTo(items.get(i).targetTable());

            Assertions.assertThat(result.getEntries(i).getOldValue())
                    .isEqualTo(items.get(i).oldValue().toString());

            Assertions.assertThat(result.getEntries(i).getNewValue())
                    .isEqualTo(items.get(i).newValue().toString());

            Assertions.assertThat(result.getEntries(i).getReason())
                    .isEqualTo(items.get(i).reason());

            Assertions.assertThat(result.getEntries(i).getRequestId())
                    .isEqualTo(items.get(i).requestId());

            Assertions.assertThat(result.getEntries(i).getCreatedAt().getSeconds())
                    .isEqualTo(items.get(i).createdAt().getEpochSecond());

            Assertions.assertThat(result.getEntries(i).getCreatedAt().getNanos())
                    .isEqualTo(items.get(i).createdAt().getNano());
        }
    }

    private @NonNull AuditLog createAuditLog() {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(AUDIT_LOG_ID);
        auditLog.setRequestId(REQUEST_ID);
        auditLog.setActorUserId(ACTOR_USER_ID);
        auditLog.setActorLogin(ACTOR_LOGIN);
        auditLog.setActorRoles(null);
        auditLog.setAction(ACTION);
        auditLog.setTargetService(TARGET_SERVICE);
        auditLog.setTargetTable(TARGET_TABLE);
        auditLog.setTargetId(TARGET_ID);
        auditLog.setOldValue(OLD_VALUE);
        auditLog.setNewValue(NEW_VALUE);
        auditLog.setReason(REASON);
        auditLog.setCreatedAt(CREATED_AT_TO);
        return auditLog;
    }

    private static @NonNull AuditLog createLogNull() {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(null);
        auditLog.setRequestId(null);
        auditLog.setActorUserId(null);
        auditLog.setActorLogin(null);
        auditLog.setActorRoles(null);
        auditLog.setAction(null);
        auditLog.setTargetService(null);
        auditLog.setTargetTable(null);
        auditLog.setTargetId(null);
        auditLog.setOldValue(null);
        auditLog.setNewValue(null);
        auditLog.setReason(null);
        auditLog.setCreatedAt(null);
        return auditLog;
    }
}
