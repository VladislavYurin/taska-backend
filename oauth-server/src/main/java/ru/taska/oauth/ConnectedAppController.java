package ru.taska.oauth;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Service-authenticated owner-scoped app management; tokens are never returned. */
@RestController
@RequestMapping("/internal/users/{userId}/apps")
public class ConnectedAppController {
  private final ConnectedAppStore store;

  public ConnectedAppController(ConnectedAppStore store) {
    this.store = store;
  }

  @GetMapping
  public List<App> list(@PathVariable UUID userId) {
    return store.list(userId.toString());
  }

  @DeleteMapping("/{appId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID userId, @PathVariable String appId) {
    store.revoke(userId.toString(), appId);
  }

  public record App(
      String appId,
      String name,
      String host,
      String metadataUrl,
      List<String> scopes,
      Instant connectedAt,
      Instant lastUsedAt,
      Instant expiresAt) {}
}
