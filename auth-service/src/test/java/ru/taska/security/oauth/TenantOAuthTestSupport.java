package ru.taska.security.oauth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Package bridge for boundary tests outside ru.taska.security.oauth. */
public final class TenantOAuthTestSupport {
  private TenantOAuthTestSupport() {}

  public static OAuthIntrospectionClient introspection() {
    return mock(OAuthIntrospectionClient.class);
  }

  public static void legacy(
      OAuthIntrospectionClient client, String token, UUID subject, UUID authorization) {
    when(client.introspect(token))
        .thenReturn(
            Mono.just(
                new OAuthGrantIdentity(
                    subject,
                    authorization,
                    UUID.randomUUID(),
                    List.of("read", "write"),
                    Instant.now().plusSeconds(300))));
  }

  public static void bound(
      OAuthIntrospectionClient client,
      String token,
      UUID subject,
      UUID authorization,
      List<String> scopes,
      Instant expires,
      UUID grant,
      UUID tenant,
      long version,
      String clientId) {
    when(client.introspect(token))
        .thenReturn(
            Mono.just(
                new OAuthGrantIdentity(
                    subject,
                    authorization,
                    UUID.randomUUID(),
                    scopes,
                    expires,
                    grant,
                    tenant,
                    version,
                    clientId)));
  }
}
