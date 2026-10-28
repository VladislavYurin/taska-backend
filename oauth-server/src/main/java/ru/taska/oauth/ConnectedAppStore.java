package ru.taska.oauth;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ConnectedAppStore {
  private final JdbcTemplate jdbc;

  ConnectedAppStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<ConnectedAppController.App> list(String userId) {
    return jdbc.query(
        """
WITH active AS (
  SELECT * FROM oauth2_authorization WHERE principal_name=? AND access_token_value IS NOT NULL
  AND ((access_token_expires_at>now() AND
    COALESCE(access_token_metadata::jsonb->>'metadata.token.invalidated','false')='false')
  OR (refresh_token_value IS NOT NULL AND refresh_token_expires_at>now() AND
    COALESCE(refresh_token_metadata::jsonb->>'metadata.token.invalidated','false')='false'))
), apps AS (
  SELECT registered_client_id, min(authorization_code_issued_at) connected_at,
    max(access_token_issued_at) last_used_at,
    max(greatest(access_token_expires_at,refresh_token_expires_at)) expires_at,
    string_agg(authorized_scopes, ',') scopes FROM active GROUP BY registered_client_id
)
SELECT a.*,c.client_name,c.client_id,c.redirect_uris FROM apps a
JOIN oauth2_registered_client c ON c.id=a.registered_client_id
ORDER BY a.connected_at DESC, a.registered_client_id DESC
""",
        (row, n) -> {
          String clientId = row.getString("client_id");
          String redirect =
              Arrays.stream(row.getString("redirect_uris").split(","))
                  .sorted()
                  .findFirst()
                  .orElseThrow();
          String metadata = clientId.startsWith("https://") ? clientId : null;
          String scopes = row.getString("scopes");
          return new ConnectedAppController.App(
              row.getString("registered_client_id"),
              row.getString("client_name"),
              URI.create(redirect).getHost(),
              metadata,
              scopes == null
                  ? List.of()
                  : Arrays.stream(scopes.split(","))
                      .filter(s -> !s.isBlank())
                      .distinct()
                      .sorted()
                      .toList(),
              instant(row.getTimestamp("connected_at")),
              instant(row.getTimestamp("last_used_at")),
              instant(row.getTimestamp("expires_at")));
        },
        userId);
  }

  @Transactional
  public void revoke(String userId, String appId) {
    jdbc.update(
        "DELETE FROM oauth2_authorization WHERE principal_name=? AND registered_client_id=?",
        userId,
        appId);
    jdbc.update(
        "DELETE FROM oauth2_authorization_consent WHERE principal_name=? AND"
            + " registered_client_id=?",
        userId,
        appId);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
