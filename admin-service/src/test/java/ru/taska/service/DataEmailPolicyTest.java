package ru.taska.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.service.readonly.SensitiveDataMaskService;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DataEmailPolicyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MetadataCatalogProperties.class)
    static class PolicyConfiguration { }

    @Test
    void configuredPolicyShowsEmailConsistentlyWhileHidingSecrets() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        new ApplicationContextRunner().withUserConfiguration(PolicyConfiguration.class)
                .withInitializer(context -> sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var service = new SensitiveDataMaskService(context.getBean(MetadataCatalogProperties.class));
                    for (var scope : List.of(
                            List.of("auth", "users", "email"),
                            List.of("admin", "admin_audit_log", "actor_email"),
                            List.of("notification", "notification_preferences", "email"),
                            List.of("notification", "invitation_email_deliveries", "email"),
                            List.of("notification", "email_delivery_attempts", "to_email"))) {
                        var row = service.maskSensitiveData(List.of(Map.of(scope.get(2), (Object) "person@example.test")),
                                scope.get(0), scope.get(1), "policy", "test").getFirst();
                        assertThat(row).containsEntry(scope.get(2), "person@example.test");
                    }
                    var invitation = service.maskSensitiveData(List.of(Map.of("email", (Object) "person@example.test", "token", "secret")),
                            "notification", "invitation_email_deliveries", "policy", "test").getFirst();
                    assertThat(invitation).containsEntry("email", "person@example.test").doesNotContainKey("token");
                    var snapshot = service.maskSensitiveDataForAudit(Map.of("payload",
                            "{\"email\":\"person@example.test\",\"rawInviteToken\":\"secret\"}"),
                            "auth", "outbox_events", "policy", "test");
                    var payload = new ObjectMapper().readTree((String) snapshot.get("payload"));
                    assertThat(payload.get("email").asString()).isEqualTo("person@example.test");
                    assertThat(payload.has("rawInviteToken")).isFalse();
                    var refresh = service.maskSensitiveData(List.of(Map.of("token_hash", (Object) "hash")),
                            "auth", "refresh_tokens", "policy", "test").getFirst();
                    assertThat(refresh).doesNotContainKey("token_hash");
                });
    }
}
