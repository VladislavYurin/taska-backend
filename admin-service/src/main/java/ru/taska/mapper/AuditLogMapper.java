package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.ListAuditEntriesResponse;
import ru.taska.api.admin.v1.ListAuditEntry;
import ru.taska.domain.PageResult;
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.entity.AuditLog;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class AuditLogMapper {

    /**
     * Преобразует DTO события аудита {@link AuditEventDto} в сущность {@link AuditLog} для сохранения в БД.
     *
     * @param dto объект передачи данных события аудита
     * @return сущность записи журнала аудита
     */
    public AuditLog toAuditLog(AuditEventDto dto) {
        AuditLog auditLog = new AuditLog();

        auditLog.setRequestId(dto.getRequestId());
        auditLog.setActorUserId(dto.getActorUserId());
        auditLog.setActorLogin(dto.getActorLogin());
        auditLog.setActorRoles(dto.getActorRoles());
        auditLog.setAction(dto.getAction());
        auditLog.setTargetService(dto.getTargetService());
        auditLog.setTargetTable(dto.getTargetTable());
        auditLog.setTargetId(dto.getTargetId());
        auditLog.setOldValue(dto.getOldValue());
        auditLog.setNewValue(dto.getNewValue());
        auditLog.setReason(dto.getReason());

        return auditLog;
    }

    public FilterAuditDTO toFilterDTO(UUID actorUserId,
                                      String action,
                                      String targetService,
                                      String targetTable,
                                      String targetId,
                                      String requestId,
                                      Instant createdAtFrom,
                                      Instant createdAtTo) {
        return FilterAuditDTO.builder()
                .actorUserId(actorUserId)
                .action(hasText(action) ? action : null)
                .targetService(hasText(targetService) ? targetService : null)
                .targetTable(hasText(targetTable) ? targetTable : null)
                .targetId(hasText(targetId) ? targetId : null)
                .requestId(hasText(requestId) ? requestId : null)
                .createdAtFrom(createdAtFrom)
                .createdAtTo(createdAtTo)
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public AuditEntriesResponseDto toResponseDto(AuditLog auditLog) {
        if (auditLog == null) {
            return null;
        }
        return new AuditEntriesResponseDto(
                auditLog.getActorUserId(),
                auditLog.getActorLogin(),
                auditLog.getAction(),
                auditLog.getTargetService(),
                auditLog.getTargetTable(),
                auditLog.getTargetId(),
                auditLog.getOldValue(),
                auditLog.getNewValue(),
                auditLog.getReason(),
                auditLog.getRequestId(),
                auditLog.getCreatedAt()
        );
    }

    public ListAuditEntriesResponse toAuditProto(PageResult<AuditEntriesResponseDto> pageResult) {
        ListAuditEntriesResponse.Builder builder = ListAuditEntriesResponse.newBuilder()
                .addAllEntries(pageResult.items().stream()
                        .map(this::toAuditEntryProto)
                        .collect(Collectors.toList()))
                .setTotalRows(pageResult.totalCount());

        if (pageResult.page() != null) {
            builder.setCurrentPage(pageResult.page());
        } else {
            builder.setCurrentPage(0);
        }

        if (pageResult.pageSize() != null) {
            if (pageResult.pageSize() <= 0) {
                log.warn("Invalid pageSize {} in audit page result, defaulting to 0", pageResult.pageSize());
                builder.setPageSize(0);
            } else {
                builder.setPageSize(pageResult.pageSize());
            }
        } else {
            builder.setPageSize(0);
        }

        int totalPages = 0;
        if (pageResult.pageSize() != null && pageResult.pageSize() > 0) {
            totalPages = (int) Math.ceil((double) pageResult.totalCount() / pageResult.pageSize());
            builder.setTotalPages(totalPages);
        } else {
            log.warn("Invalid or missing pageSize, cannot calculate totalPages");
            builder.setTotalPages(0);
        }

        int currentPage = pageResult.page() != null ? pageResult.page() : 0;
        builder.setHasNext(currentPage < totalPages - 1);
        builder.setHasPrev(currentPage > 0);

        return builder.build();
    }

    private ListAuditEntry toAuditEntryProto(AuditEntriesResponseDto dto) {
        ListAuditEntry.Builder entryBuilder = ListAuditEntry.newBuilder();

        if (dto.actorUserId() != null) entryBuilder.setActorUserId(dto.actorUserId().toString());
        if (dto.actorLogin() != null) entryBuilder.setActorLogin(dto.actorLogin());
        if (dto.action() != null) entryBuilder.setAction(dto.action());
        if (dto.targetService() != null) entryBuilder.setTargetService(dto.targetService());
        if (dto.targetTable() != null) entryBuilder.setTargetTable(dto.targetTable());
        if (dto.targetId() != null) entryBuilder.setTargetId(dto.targetId());
        if (dto.oldValue() != null) entryBuilder.setOldValue(dto.oldValue().toString());
        if (dto.newValue() != null) entryBuilder.setNewValue(dto.newValue().toString());
        if (dto.reason() != null) entryBuilder.setReason(dto.reason());
        if (dto.requestId() != null) entryBuilder.setRequestId(dto.requestId());
        if (dto.createdAt() != null) {
            entryBuilder.setCreatedAt(Timestamp.newBuilder()
                    .setSeconds(dto.createdAt().getEpochSecond())
                    .setNanos(dto.createdAt().getNano())
                    .build());
        }

        return entryBuilder.build();
    }
}
