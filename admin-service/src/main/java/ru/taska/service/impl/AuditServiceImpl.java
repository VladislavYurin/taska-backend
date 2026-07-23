package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.dto.AuditEventDto;
import ru.taska.entity.AuditLog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.repository.AuditLogRepository;
import ru.taska.service.AuditService;

/**
 * Реализация сервиса аудита.
 * <p>Проверяет корректность заполнения причины действия (reason)
 * и сохраняет события аудита в базу данных.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;
    private final AuditLogRepository auditLogRepository;

    @Override
    public Mono<Void> logAudit(AuditEventDto dto) {
        if (dto.getReason() == null || dto.getReason().isBlank()    ) {
            log.warn("[{}] Audit record creation failed: reason is missing. Action: {}, Target: {}",
                    dto.getRequestId(), dto.getAction(), dto.getTargetService());
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "Reason is required"));
        }

        AuditLog auditLog = auditLogMapper.toAuditLog(dto);

        return auditLogRepository.save(auditLog)
                .doOnError(ex -> log.error(
                        "[{}] Failed to save audit record. Action: {}, TargetService: {}",
                        dto.getRequestId(), dto.getAction(), dto.getTargetService(), ex
                ))
                .then();
    }
}
