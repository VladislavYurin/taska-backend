package ru.taska.controller;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.notification.v1.NotificationPreferencesResponse;
import ru.taska.api.notification.v1.TelegramLinkResponse;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcNotificationSettingsClient;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@WebFluxTest(controllers={NotificationSettingsController.class,TelegramWebhookController.class})
@Import({GatewayRequestExecutor.class,GatewayContextFactory.class,RequestIdProvider.class,
        BearerTokenExtractor.class,GatewayErrorHandler.class,RestErrorMapper.class})
@TestPropertySource(properties={"NOTIFICATION_TELEGRAM_ENABLED=true","NOTIFICATION_TELEGRAM_WEBHOOK_SECRET=test-secret"})
class NotificationSettingsControllerTest {
    @Autowired WebTestClient http;
    @MockitoBean GrpcNotificationSettingsClient settings;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper mapper;
    private final String user=UUID.randomUUID().toString();
    private final NotificationPreferencesResponse prefs=NotificationPreferencesResponse.newBuilder().setEmailEnabled(true)
            .setEmail("verified@example.test").setTelegramStatus("NOT_CONNECTED").build();
    @BeforeEach
    void setup() {
        when(auth.validateAccessToken(anyString(),anyString(),eq("valid"))).thenReturn(Mono.just(
                ValidateAccessTokenResponse.newBuilder().setUserContext(UserContext.newBuilder().setUserId(user)).build()));
        when(mapper.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder().userId(user)
                .email("verified@example.test").status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER).build());
    }
    @Test
    void anonymousCannotReadPreferences() {
        http.get().uri("/api/v1/users/me/notification-preferences").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(settings);
    }
    @Test
    void getUsesVerifiedIdentityAndReturnsStatus() {
        when(settings.get(any())).thenReturn(Mono.just(prefs));
        http.get().uri("/api/v1/users/me/notification-preferences?userId=foreign").headers(h -> h.setBearerAuth("valid"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.telegramStatus").isEqualTo("NOT_CONNECTED")
                .jsonPath("$.email").isEqualTo("verified@example.test");
        verify(settings).get(argThat(c -> c.userContext().userId().equals(user) && c.userContext().email().equals("verified@example.test")));
    }
    @Test
    void patchPassesOnlySuppliedBooleanWithVerifiedIdentity() {
        when(settings.update(any(),eq(false),isNull())).thenReturn(Mono.just(prefs));
        http.patch().uri("/api/v1/users/me/notification-preferences").headers(h -> h.setBearerAuth("valid"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"emailEnabled\":false}")
                .exchange().expectStatus().isOk();
        verify(settings).update(argThat(c -> c.userContext().userId().equals(user)),eq(false),isNull());
    }
    @ParameterizedTest
    @ValueSource(strings={"FUTURE_STATUS", ""})
    void unknownBackendStatusDoesNotBreakSettingsOrInventBindingState(String status) {
        when(settings.get(any())).thenReturn(Mono.just(prefs.toBuilder().setTelegramStatus(status)
                .setTelegramError("FUTURE_ERROR").build()));
        http.get().uri("/api/v1/users/me/notification-preferences").headers(h -> h.setBearerAuth("valid"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.telegramStatus").isEqualTo("UNKNOWN")
                .jsonPath("$.telegramError").isEqualTo("FUTURE_ERROR");
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true", "false"})
    void linkReturnsCommittedSettingsSnapshotWhenSupportedAndWorksWithOlderService(boolean withPreferences) {
        String link=UUID.randomUUID().toString();
        var response=TelegramLinkResponse.newBuilder().setLinkId(link)
                .setUrl("https://t.me/test_bot?start=test").setExpiresAt("2026-10-23T12:00:00Z");
        if(withPreferences) response.setPreferences(prefs.toBuilder().setTelegramStatus("PENDING").setTelegramLinkId(link));
        when(settings.link(any())).thenReturn(Mono.just(response.build()));
        var body=http.post().uri("/api/v1/users/me/telegram/link").headers(h -> h.setBearerAuth("valid"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.linkId").isEqualTo(link);
        if(withPreferences) body.jsonPath("$.preferences.telegramStatus").isEqualTo("PENDING")
                .jsonPath("$.preferences.telegramLinkId").isEqualTo(link);
        else body.jsonPath("$.preferences").doesNotExist();
        verify(settings,never()).get(any());
    }
    @ParameterizedTest
    @ValueSource(strings={"{\"emailEnabled\":null}","{\"telegramEnabled\":\"true\"}","{\"emailEnabled\":1}",
            "{\"emailEnabled\":false,\"email\":\"attacker@example.test\"}","{\"telegramEnabled\":true,\"userId\":\"foreign\"}"})
    void invalidPatchIsRejectedBeforeDownstream(String body) {
        http.patch().uri("/api/v1/users/me/notification-preferences").headers(h -> h.setBearerAuth("valid"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
        verifyNoInteractions(settings);
    }
    @Test
    void linkConflictIs409AndKeepsRequestId() {
        when(settings.link(any())).thenReturn(Mono.error(io.grpc.Status.ABORTED.withDescription("Already bound").asRuntimeException()));
        http.post().uri("/api/v1/users/me/telegram/link").headers(h -> h.setBearerAuth("valid"))
                .header("X-Request-Id","link-conflict").exchange().expectStatus().isEqualTo(409)
                .expectHeader().valueEquals("X-Request-Id","link-conflict");
    }
    @Test
    void webhookAuthenticatesItsOwnSecretWithoutJwtAndBoundsSize() {
        when(settings.webhook(eq("test-secret"),anyString())).thenReturn(Mono.empty());
        http.post().uri("/api/v1/integrations/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token","wrong").bodyValue("{\"update_id\":1}")
                .exchange().expectStatus().isForbidden();
        verifyNoInteractions(settings);
        http.post().uri("/api/v1/integrations/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token","test-secret").bodyValue("{\"update_id\":1}")
                .exchange().expectStatus().isOk();
        verifyNoInteractions(auth);
        http.post().uri("/api/v1/integrations/telegram/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-Telegram-Bot-Api-Secret-Token","test-secret").bodyValue("x".repeat(32769))
                .exchange().expectStatus().isEqualTo(413);
        verify(settings,times(1)).webhook(anyString(),anyString());
    }
}
