package ru.taska.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void publishedJsonAndStaticYamlAreTheSameAuthoritativeContract() {
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String yaml = client.get().uri("/openapi.yml").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        String json = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody(String.class).returnResult().getResponseBody();
        Map<String, Object> source = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        var mapper = JsonMapper.builder().build();
        JsonNode contract = mapper.readTree(json);
        assertThat(contract).isEqualTo(mapper.valueToTree(source));
        var schemas = contract.get("components").get("schemas");
        assertThat(schemas.get("IssueResponseDto").get("required").toString()).contains("priority");
        assertThat(schemas.get("IssueResponseDto").get("properties").get("priority").get("$ref").asString())
                .isEqualTo("#/components/schemas/IssuePriorityDto");
        assertThat(schemas.get("NotificationTypeDto").get("enum").size()).isEqualTo(23);
        assertThat(schemas.get("CommentResponseDto").get("properties").get("updatedAt").get("nullable").asBoolean())
                .isFalse();
        var query = contract.get("paths").get("/api/v1/issues/search").get("get").get("parameters").get(0).get("schema");
        assertThat(query.get("minLength").asInt()).isEqualTo(3);
        assertThat(query.has("default")).isFalse();
        // Moving springdoc diagnostics must not break Swagger UI configuration.
        client.get().uri("/v3/api-docs/generated/swagger-config").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.url").isEqualTo("/openapi.yml");
    }
}
