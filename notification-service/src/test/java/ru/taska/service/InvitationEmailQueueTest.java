package ru.taska.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InvitationEmailQueueTest {
    @Test
    void activationUrlRequiresTrustedHttpsExceptForLocalDevelopment() {
        var queue = new InvitationEmailQueue(mock(DatabaseClient.class), mock(JavaMailSender.class), mock(EmailMessageFactory.class), new SimpleMeterRegistry());
        for (String valid : List.of("https://taska.example.test/login", "http://localhost:3001/login", "http://127.0.0.1/login?mode=invite")) {
            ReflectionTestUtils.setField(queue, "acceptUrl", valid);
            assertThatCode(queue::validateAcceptUrl).doesNotThrowAnyException();
        }
        for (String invalid : List.of("http://external.example.test/login", "javascript:alert(1)",
                "https://user:password@taska.example.test/login", "https://taska.example.test/login#fragment",
                "https://taska.example.test/login?token=old", "//taska.example.test/login")) {
            ReflectionTestUtils.setField(queue, "acceptUrl", invalid);
            assertThatThrownBy(queue::validateAcceptUrl).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
