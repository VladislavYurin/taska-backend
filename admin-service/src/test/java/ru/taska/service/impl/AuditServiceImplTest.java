package ru.taska.service.impl;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.dto.AuditEventDto;
import ru.taska.entity.AuditLog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.repository.AuditLogRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AuditServiceImpl")
public class AuditServiceImplTest {

    private static final String REQUEST_ID = "test-request-id";

    @Mock
    private AuditLogMapper mapper;

    @Mock
    private AuditLogRepository repository;

    @InjectMocks
    private AuditServiceImpl service;

    @Test
    @DisplayName("Должен успешно записать аудит, если reason валидный")
    void logAudit_shouldLogAuditSuccessfullyWhenReasonIsValid() {
        AuditEventDto dto = AuditEventDto.builder()
                .requestId("req-123")
                .action("CREATE_USER")
                .targetService("admin-service")
                .reason("User creation request")
                .build();

        AuditLog auditLog = new AuditLog();
        auditLog.setRequestId(REQUEST_ID);

        Mockito.when(mapper.toAuditLog(dto)).thenReturn(auditLog);
        Mockito.when(repository.save(auditLog)).thenReturn(Mono.just(auditLog));

        StepVerifier.create(service.logAudit(dto))
                .verifyComplete();

        Mockito.verify(mapper).toAuditLog(dto);
        Mockito.verify(repository).save(auditLog);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("Должен выбросить INVALID_ARGUMENT, если reason пустой, null или состоит из пробелов")
    void logAudit_shouldThrowInvalidArgumentWhenReasonIsBlank(String invalidReason) {
        AuditEventDto dto = AuditEventDto.builder()
                .requestId(REQUEST_ID)
                .action("CREATE_USER")
                .targetService("admin-service")
                .reason(invalidReason)
                .build();

        StepVerifier.create(service.logAudit(dto))
                .expectErrorSatisfies(throwable -> {
                    AssertionsForClassTypes.assertThat(throwable).isInstanceOf(DomainException.class);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertThat(exception.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(exception.getMessage()).isEqualTo("Reason is required");
                });

        Mockito.verifyNoInteractions(mapper);
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Должен пробрасывать ошибку сохранения из репозитория")
    void logAudit_shouldPropagateErrorWhenRepositoryFails() {
        AuditEventDto dto = AuditEventDto.builder()
                .requestId(REQUEST_ID)
                .action("CREATE_USER")
                .targetService("admin-service")
                .reason("User creation request")
                .build();

        AuditLog auditLog = new AuditLog();
        RuntimeException dbError = new RuntimeException("Database error");

        Mockito.when(mapper.toAuditLog(dto)).thenReturn(auditLog);
        Mockito.when(repository.save(auditLog)).thenReturn(Mono.error(dbError));

        StepVerifier.create(service.logAudit(dto))
                .expectErrorMatches(throwable -> throwable == dbError)
                .verify();

        Mockito.verify(repository).save(auditLog);
    }
}
