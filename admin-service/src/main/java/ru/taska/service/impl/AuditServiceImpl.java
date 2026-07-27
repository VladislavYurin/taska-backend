package ru.taska.service.impl;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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

import java.util.Set;
import java.util.stream.Collectors;

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
    private final Validator validator;

    @Override
    public Mono<Void> logAudit(AuditEventDto dto) {
        Set<ConstraintViolation<AuditEventDto>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                                    .collect(Collectors.joining("; "));
            log.warn("[{}] Audit validation failed: {}", dto.getRequestId(), message);
            return Mono.error(new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    message));
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
