package ru.taska.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.domain.*;
import ru.taska.filter.*;
import ru.taska.service.TenantCapabilitiesClient;

class TenantCapabilitiesControllerTest {
  final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();
  TenantCapabilitiesClient client;
  WebTestClient http;

  @BeforeEach
  void setup() {
    client = mock(TenantCapabilitiesClient.class);
    var factory = mock(GatewayContextFactory.class);
    when(factory.buildContext(anyString(), any(), any()))
        .thenReturn(
            Mono.just(new GatewayContext(UUID.randomUUID().toString(), "api-gateway", null)));
    var executor = new GatewayRequestExecutor(new RequestIdProvider(), factory);
    http =
        WebTestClient.bindToController(new TenantCapabilitiesController(executor, client))
            .webFilter(new TenantCapabilitiesCacheFilter())
            .build();
  }

  @Test
  void publishesClosedActionsVersionsDurationsAndNoStore() {
    var now = Instant.now();
    var response =
        Capabilities.newBuilder()
            .setTenantId(tenant.toString())
            .setActorUserId(actor.toString())
            .setPolicyVersion(CapabilityEvaluator.POLICY_VERSION)
            .setFlagVersion("opaque-public-source-hash")
            .setEntitlementVersion("2")
            .setMembershipVersion("3")
            .setEvaluatedAt(now.toString())
            .setEffectiveAt(now.minusSeconds(1).toString())
            .setExpiresAt(now.plusSeconds(10).toString())
            .putDurationsSeconds("WIRE_RECONCILIATION_TARGET_SECONDS", 86400)
            .addItems(
                Capability.newBuilder()
                    .setAction("tenant.pause")
                    .setAllowed(false)
                    .addDenialCodes(ErrorCode.ERROR_CODE_CAPABILITY_DISABLED))
            .build();
    when(client.get(eq(tenant), any())).thenReturn(Mono.just(response));
    http.get()
        .uri("/api/v1/tenants/" + tenant + "/capabilities")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueMatches("Cache-Control", ".*private.*no-store.*|.*no-store.*private.*")
        .expectBody()
        .jsonPath("$.tenantId")
        .isEqualTo(tenant.toString())
        .jsonPath("$.flagVersion")
        .isEqualTo("opaque-public-source-hash")
        .jsonPath("$.items[0].action")
        .isEqualTo("tenant.pause")
        .jsonPath("$.items[0].denialCodes[0]")
        .isEqualTo("CAPABILITY_DISABLED")
        .jsonPath("$.durationsSeconds.WIRE_RECONCILIATION_TARGET_SECONDS")
        .isEqualTo(86400);
  }

  @Test
  void failuresAndMalformedTargetsNeverBecomeCacheable() {
    when(client.get(eq(tenant), any()))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
    http.get()
        .uri("/api/v1/tenants/" + tenant + "/capabilities")
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .valueMatches("Cache-Control", ".*no-store.*");
    http.get()
        .uri("/api/v1/tenants/invalid/capabilities?ignored=1")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .valueMatches("Cache-Control", ".*no-store.*");
    http.get()
        .uri("/api/v1/tenants/invalid/capabilities/")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueMatches("Cache-Control", ".*no-store.*");
    verify(client, times(1)).get(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void publicActionRegistryExactlyMatchesTheSinglePolicyInventory() throws Exception {
    try (var input = getClass().getResourceAsStream("/static/openapi.yml")) {
      Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(input);
      Map<String, Object> components = (Map<String, Object>) root.get("components"),
          schemas = (Map<String, Object>) components.get("schemas");
      Map<String, Object> action = (Map<String, Object>) schemas.get("CapabilityAction");
      var actual = new HashSet<>((List<String>) action.get("enum"));
      var expected =
          Arrays.stream(Action.values())
              .map(Action::wire)
              .collect(java.util.stream.Collectors.toSet());
      assertEquals(expected, actual);
      assertTrue(actual.contains("membership.role_update"));
      assertFalse(actual.contains("membership.write"));
    }
  }
}
