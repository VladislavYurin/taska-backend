package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.dto.AuditEventDto;
import ru.taska.entity.AuditLog;

@Component
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
}
