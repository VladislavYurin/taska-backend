package ru.taska.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.*;
import ru.taska.repository.NotificationRepository;
import ru.taska.security.tenant.*;
import ru.taska.service.NotificationInboxService;

class TrustedNotificationScopeIntegrationTest extends AbstractIT {
  @Autowired NotificationRepository notifications;
  @Autowired NotificationInboxService inbox;
  final UUID actor = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
  RpcContextSecurity security;

  @BeforeEach
  void prepare() throws Exception {
    ReflectionTestUtils.setField((Object)AopTestUtils.getTargetObject(inbox), "trustedEnabled", true);
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    security =
        new RpcContextSecurity(
            "api-gateway",
            Clock.systemUTC(),
            new RpcContextSecurity.Keys() {
              public RpcContextSecurity.SigningKey signingKey(String issuer) {
                return new RpcContextSecurity.SigningKey("owned", pair.getPrivate());
              }

              public PublicKey verificationKey(String issuer, String kid) {
                return pair.getPublic();
              }
            },
            (i, j, e) -> Mono.just(true),
            (c, r, p) -> Mono.just(actor.toString().equals(c.getContext().getActorUserId())));
  }

  @AfterEach
  void restore() {
    ReflectionTestUtils.setField((Object)AopTestUtils.getTargetObject(inbox), "trustedEnabled", false);
  }

  Notification add(UUID tenant) {
    return notifications
        .save(
            Notification.builder()
                .tenantId(tenant)
                .userId(actor)
                .notificationType(NotificationType.PROJECT_CREATED)
                .title("Owned scope fixture")
                .createdAt(Instant.now())
                .sourceEventId(UUID.randomUUID())
                .build())
        .block(Duration.ofSeconds(10));
  }

  <T> T scoped(UUID tenant, Mono<T> work) {
    var now = Instant.now();
    var context =
        RequestContext.newBuilder()
            .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setContextVersion(1)
                    .setActorUserId(actor.toString())
                    .setTenantId(tenant.toString())
                    .setActorGeneration("1")
                    .setMembershipGeneration("1")
                    .setSessionId(UUID.randomUUID().toString())
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                    .addScopes("read")
                    .addScopes("write")
                    .setAction("gatewayRequest")
                    .setIssuerService("api-gateway")
                    .setAudienceService("api-gateway")
                    .setIssuedAt(now.toString())
                    .setExpiresAt(now.plusSeconds(30).toString())
                    .setAssertionId(UUID.randomUUID().toString()))
            .build();
    return RpcContextBridge.install(
            work,
            security
                .authorize(context, "gatewayRequest", new RpcContextSecurity.Policy(true, true))
                .block())
        .block(Duration.ofSeconds(10));
  }

  @Test
  void sameUserInboxCountsAndReadMutationsStayInTheirCompany() {
    var na = add(a);
    var nb = add(b);
    var old = add(null);
    var list = scoped(a, inbox.listNotifications(actor, false, 100, 0));
    assertEquals(
        List.of(na.getId()), list.notifications().stream().map(Notification::getId).toList());
    assertEquals(1, list.unreadCount());
    assertThrows(
        ru.taska.exception.DomainException.class,
        () -> scoped(a, inbox.markAsRead(nb.getId(), actor)));
    assertEquals(1, scoped(a, inbox.markAllAsRead(actor)));
    assertNull(notifications.findById(nb.getId()).block().getReadAt());
    assertNull(notifications.findById(old.getId()).block().getReadAt());
    assertEquals(1, scoped(b, inbox.listNotifications(actor, true, 100, 0)).unreadCount());
    assertThrows(
        ru.taska.exception.DomainException.class,
        () -> scoped(a, inbox.listNotifications(UUID.randomUUID(), false, 100, 0)));
    notifications.deleteAll(List.of(na, nb, old)).block();
  }
}
