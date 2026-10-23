package ru.taska.service;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.TelegramProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import static org.assertj.core.api.Assertions.*;

class TelegramSenderTest {
    private TelegramProperties properties() {
        var p=new TelegramProperties();p.setEnabled(true);p.setBotToken("123:secret");return p;
    }
    private WebClient response(int code,String body) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.valueOf(code))
                .header("Content-Type","application/json").body(body).build())).build();
    }
    @Test
    void successRequiresProviderOkAndFailureDoesNotExposeTokenOrResponse() {
        new TelegramSender(properties(),"https://taska.ozero.dev/#",response(200,"{\"ok\":true}"))
                .reply(123,"Hello").block();
        StepVerifier.create(new TelegramSender(properties(),"https://taska.ozero.dev/#",response(403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"secret private provider content\"}" )).reply(123,"Hello"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(DeliveryFailure.class);
                    assertThat(e.getMessage()).isEqualTo("TELEGRAM_DELIVERY_UNAVAILABLE");
                    assertThat(e.getCause()).isNull();assertThat(((DeliveryFailure)e).disconnected()).isTrue();
                }).verify();
    }
    @Test
    void rateLimitPreservesProviderDelayAndNetworkFailuresAreSanitized() {
        StepVerifier.create(new TelegramSender(properties(),"https://taska.ozero.dev/#",response(429,
                "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":123}}" )).reply(123,"Hello"))
                .expectErrorSatisfies(e -> assertThat(((DeliveryFailure)e).retryAfter()).isEqualTo(123)).verify();
        var network=WebClient.builder().exchangeFunction(request -> Mono.error(new RuntimeException("https://api.telegram.org/bot123:secret"))).build();
        StepVerifier.create(new TelegramSender(properties(),"https://taska.ozero.dev/#",network).reply(123,"Hello"))
                .expectErrorSatisfies(e -> { assertThat(e.getMessage()).isEqualTo("TELEGRAM_TEMPORARY");assertThat(e.getCause()).isNull(); }).verify();
    }
    @Test
    void commentPrivacyAndPayloadArePlainTextWithOptionalSafeButton() {
        AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> seen=new AtomicReference<>();
        var client=WebClient.builder().exchangeFunction(request -> { seen.set(request);return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type","application/json").body("{\"ok\":true}").build()); }).build();
        var n=Notification.builder().notificationType(NotificationType.ISSUE_COMMENT_CREATED).title("Comment <script>")
                .body("DELETED COMMENT SECRET").issueKey("TAS-387").issueId(java.util.UUID.randomUUID()).projectId(java.util.UUID.randomUUID()).build();
        new TelegramSender(properties(),"https://taska.ozero.dev/#",client).notification(123,n).block();
        // Render the actual BodyInserter into a mock request, then inspect the serialized wire payload.
        var output=new org.springframework.mock.http.client.reactive.MockClientHttpRequest(org.springframework.http.HttpMethod.POST,"http://localhost/");
        seen.get().body().insert(output,new org.springframework.web.reactive.function.BodyInserter.Context() {
            public java.util.List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults().messageWriters();
            }
            public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() { return java.util.Optional.empty(); }
            public java.util.Map<String,Object> hints() { return java.util.Map.of(); }
        }).block();
        String json=output.getBodyAsString().block();
        assertThat(json).doesNotContain("DELETED COMMENT SECRET","parse_mode").contains("Comment <script>","https://taska.ozero.dev/#/browse/TAS-387");
    }
}
