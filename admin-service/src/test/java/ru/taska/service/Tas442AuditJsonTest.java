package ru.taska.service;

import io.r2dbc.postgresql.codec.Json;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.entity.AuditLog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.repository.AuditLogRepository;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.readonly.AdminReadonlyServiceImpl;
import ru.taska.service.readonly.FilterParser;
import ru.taska.service.readonly.ReadOnlyQueryBuilder;
import ru.taska.service.readonly.SensitiveDataMaskService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Tas442AuditJsonTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMAIL = "fixture@example.invalid";
    private static final String PAYLOAD = """
            {"email":"fixture@example.invalid","rawInviteToken":"root-secret","displayName":"Fixture",
             "nested":{"rawInviteToken":"nested-secret","safe":null},
             "items":[{"rawInviteToken":"array-secret","count":4},
                      [{"rawInviteToken":"deep-array-secret","enabled":true}]]}
            """;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MetadataCatalogProperties.class)
    static class PolicyConfiguration { }

    enum Representation { MAP, JSON_NODE, STRING, R2DBC_JSON }

    @ParameterizedTest
    @EnumSource(Representation.class)
    void masksAllSupportedRepresentationsWithoutChangingInput(Representation representation) throws IOException {
        withPolicy((properties, service) -> {
            Object input = switch (representation) {
                case MAP -> MAPPER.readValue(PAYLOAD, LinkedHashMap.class);
                case JSON_NODE -> MAPPER.readTree(PAYLOAD);
                case STRING -> PAYLOAD;
                case R2DBC_JSON -> Json.of(PAYLOAD);
            };
            JsonNode before = asJson(input);
            Map<String, Object> row = Map.of("payload", input, "attempts", 4);

            Map<String, Object> masked = service.maskSensitiveDataForAudit(
                    row, "auth", "outbox_events", "tas-442", "audit-test");
            Object payload = masked.get("payload");

            switch (representation) {
                case MAP -> assertThat(payload).isInstanceOf(Map.class);
                case JSON_NODE -> assertThat(payload).isInstanceOf(JsonNode.class);
                case STRING -> assertThat(payload).isInstanceOf(String.class);
                case R2DBC_JSON -> assertThat(payload).isInstanceOf(Json.class);
            }
            assertMaskedPayload(asJson(payload));
            assertThat(masked).containsEntry("attempts", 4);
            assertThat(asJson(input)).isEqualTo(before);
            assertThat(asJson(input).has("rawInviteToken")).isTrue();

            var repeated = service.maskSensitiveDataForAudit(
                    masked, "auth", "outbox_events", "tas-442", "audit-test");
            assertThat(asJson(repeated.get("payload"))).isEqualTo(asJson(payload));
        });
    }

    @Test
    void realAuditReaderReturnsStructuredOldAndNewValuesOnRepeatedReads() throws IOException {
        withPolicy((properties, maskService) -> {
            var repository = mock(AuditLogRepository.class);
            var reader = new AdminReadonlyServiceImpl(properties, maskService,
                    mock(MetadataService.class), mock(ReadOnlyRepository.class),
                    mock(ReadOnlyQueryBuilder.class), mock(FilterParser.class),
                    repository, new AuditLogMapper(), MAPPER);
            var log = new AuditLog();
            log.setId(UUID.randomUUID());
            log.setRequestId("tas-442-read");
            log.setActorUserId(UUID.randomUUID());
            log.setActorDisplayName("Fixture Admin");
            log.setActorEmail("admin@example.invalid");
            log.setTargetService("auth");
            log.setTargetTable("outbox_events");
            log.setAction("RETRY_OUTBOX_EVENT");
            log.setCreatedAt(Instant.parse("2026-10-27T00:00:00Z"));
            JsonNode originalOld = MAPPER.readTree("{\"status\":\"FAILED\",\"payload\":" + PAYLOAD + "}");
            JsonNode originalNew = MAPPER.readTree("{\"status\":\"NEW\",\"payload\":" + PAYLOAD + "}");
            log.setOldValue(originalOld);
            log.setNewValue(originalNew);
            when(repository.findByFilter(any(FilterAuditDTO.class), anyInt(), anyLong()))
                    .thenReturn(Flux.just(log));
            when(repository.countByFilter(any(FilterAuditDTO.class))).thenReturn(Mono.just(1L));

            for (int attempt = 0; attempt < 2; attempt++) {
                StepVerifier.create(reader.listAuditEntries(FilterAuditDTO.builder()
                                .requestId("tas-442-read").build(), 0, 20))
                        .assertNext(page -> {
                            assertThat(page.totalCount()).isEqualTo(1);
                            var entry = page.items().getFirst();
                            assertThat(entry.actorEmail()).isEqualTo("admin@example.invalid");
                            assertThat(entry.oldValue().get("status").asString()).isEqualTo("FAILED");
                            assertThat(entry.newValue().get("status").asString()).isEqualTo("NEW");
                            assertMaskedPayload(entry.oldValue().get("payload"));
                            assertMaskedPayload(entry.newValue().get("payload"));
                        }).verifyComplete();
            }
            assertThat(originalOld.get("payload").has("rawInviteToken")).isTrue();
            assertThat(originalNew.get("payload").has("rawInviteToken")).isTrue();
        });
    }

    @Test
    void unsupportedValueFailsClosedInsteadOfReturningSecrets() throws IOException {
        withPolicy((properties, service) -> assertThatThrownBy(() -> service.maskSensitiveDataForAudit(
                Map.of("payload", List.of("do-not-return")), "auth", "outbox_events", "tas-442", "audit-test"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(DomainStatus.INTERNAL)));
    }

    private static void assertMaskedPayload(JsonNode payload) {
        assertThat(payload.isObject()).isTrue();
        assertThat(payload.get("email").asString()).isEqualTo(EMAIL);
        assertThat(payload.get("displayName").asString()).isEqualTo("Fixture");
        assertThat(payload.has("rawInviteToken")).isFalse();
        assertThat(payload.get("nested").has("rawInviteToken")).isFalse();
        assertThat(payload.get("nested").get("safe").isNull()).isTrue();
        assertThat(payload.get("items").get(0).has("rawInviteToken")).isFalse();
        assertThat(payload.get("items").get(0).get("count").asInt()).isEqualTo(4);
        assertThat(payload.get("items").get(1).get(0).has("rawInviteToken")).isFalse();
        assertThat(payload.get("items").get(1).get(0).get("enabled").asBoolean()).isTrue();
        assertThat(payload.toString()).doesNotContain("root-secret", "nested-secret", "array-secret", "deep-array-secret");
    }

    private static JsonNode asJson(Object value) {
        if (value instanceof Json json) {
            return MAPPER.readTree(json.asString());
        }
        if (value instanceof String string) {
            return MAPPER.readTree(string);
        }
        return MAPPER.valueToTree(value);
    }

    private static void withPolicy(BiConsumer<MetadataCatalogProperties, SensitiveDataMaskService> assertions)
            throws IOException {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        new ApplicationContextRunner().withUserConfiguration(PolicyConfiguration.class)
                .withInitializer(context -> sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(MetadataCatalogProperties.class);
                    assertions.accept(properties, new SensitiveDataMaskService(properties));
                });
    }
}
