package ru.taska.service.impl;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для AuditServiceImpl")
class AuditServiceImplTest {

    private static final String REQUEST_ID = "test-request-id";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private AuditLogMapper mapper;

    @Mock
    private AuditLogRepository repository;

    private AuditServiceImpl service;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new AuditServiceImpl(mapper, repository, validator);
    }

    /**
     * Валидный DTO, удовлетворяющий всем @NotNull/@NotBlank ограничениям.
     * Используется как база, чтобы в конкретных тестах портить только одно поле.
     */
    private static AuditEventDto.AuditEventDtoBuilder validDtoBuilder() {
        JsonNode actorRoles = OBJECT_MAPPER.readTree("[\"ROLE_ADMIN\"]");
        return AuditEventDto.builder()
                .requestId(REQUEST_ID)
                .actorUserId(UUID.randomUUID())
                .actorLogin("admin_user")
                .actorRoles(actorRoles)
                .action("CREATE_USER")
                .targetService("admin-service")
                .targetTable("users")
                .targetId("USER-1")
                .reason("User creation request");
    }

    @Test
    @DisplayName("Должен успешно записать аудит, если DTO валиден")
    void logAudit_shouldLogAuditSuccessfullyWhenDtoIsValid() {
        AuditEventDto dto = validDtoBuilder().build();

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
        AuditEventDto dto = validDtoBuilder()
                .reason(invalidReason)
                .build();

        StepVerifier.create(service.logAudit(dto))
                .expectErrorSatisfies(throwable -> {
                    AssertionsForClassTypes.assertThat(throwable).isInstanceOf(DomainException.class);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertThat(exception.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(exception.getMessage()).isEqualTo("reason: Reason is required");
                });

        Mockito.verifyNoInteractions(mapper);
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Должен выбросить INVALID_ARGUMENT, если обязательное поле actorUserId не заполнено")
    void logAudit_shouldThrowInvalidArgumentWhenActorUserIdIsMissing() {
        AuditEventDto dto = validDtoBuilder()
                .actorUserId(null)
                .build();

        StepVerifier.create(service.logAudit(dto))
                .expectErrorSatisfies(throwable -> {
                    AssertionsForClassTypes.assertThat(throwable).isInstanceOf(DomainException.class);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertThat(exception.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    Assertions.assertThat(exception.getMessage()).contains("actorUserId");
                });

        Mockito.verifyNoInteractions(mapper);
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Должен пробрасывать ошибку сохранения из репозитория")
    void logAudit_shouldPropagateErrorWhenRepositoryFails() {
        AuditEventDto dto = validDtoBuilder().build();

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