package ru.taska.transport.grpc.validators;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import validator.GrpcRequestValidators;

import java.time.Instant;

class GrpcRequestValidatorsTest {

    @Test
    @DisplayName("Должен выбросить INVALID_ARGUMENT если storyPoints отрицательный")
    void rejectNegativeStoryPoints() {
        var result = GrpcRequestValidators.validateOptionalStoryPoints(true, -1.5, "story_points");

        StepVerifier.create(result)
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                    StatusRuntimeException exception = (StatusRuntimeException) throwable;
                    Assertions.assertThat(exception.getStatus().getCode()).isEqualTo(Status.INVALID_ARGUMENT.getCode());
                })
                .verify();
    }

    @Test
    @DisplayName("Должен выбросить INVALID_ARGUMENT если оценка времени отрицательная")
    void rejectNegativeEstimates() {
        var result = GrpcRequestValidators.validateOptionalEstimateMinutes(true, -60L, "body.originalEstimateMinutes");

        StepVerifier.create(result)
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                    StatusRuntimeException exception = (StatusRuntimeException) throwable;
                    Assertions.assertThat(exception.getStatus().getCode()).isEqualTo(Status.INVALID_ARGUMENT.getCode());
                })
                .verify();
    }

    @Test
    @DisplayName("Должен выбросить INVALID_ARGUMENT если дата окончания после даты старта")
    void rejectStartDateAfterDueDate() {
        Instant startDate = Instant.parse("2026-07-24T15:00:00Z");
        Instant dueDate = Instant.parse("2026-07-24T14:00:00Z");

        var result = GrpcRequestValidators.validateDateRange(startDate, dueDate);

        StepVerifier.create(result)
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                    StatusRuntimeException exception = (StatusRuntimeException) throwable;
                    Assertions.assertThat(exception.getStatus().getCode()).isEqualTo(Status.INVALID_ARGUMENT.getCode());
                })
                .verify();
    }
}