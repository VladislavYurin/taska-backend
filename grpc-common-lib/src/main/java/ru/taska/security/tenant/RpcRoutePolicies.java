package ru.taska.security.tenant;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit versioned legacy route inventory; unrelated B2B/public RPCs keep their own policies. */
public final class RpcRoutePolicies {
  public record Route(
      String audience, String scope, boolean tenantRequired, boolean mutation, String action) {
    public RpcContextSecurity.Policy policy() {
      return new RpcContextSecurity.Policy(tenantRequired, mutation);
    }
  }

  private static final Map<String, Route> ROUTES = load();

  private RpcRoutePolicies() {}

  private static Map<String, Route> load() {
    try (var input =
        RpcRoutePolicies.class.getResourceAsStream("/security/rpc-route-policies.json")) {
      if (input == null) throw new IllegalStateException("RPC policy inventory missing");
      var document = JsonMapper.builder().build().readTree(input);
      var routes = new HashMap<String, Route>();
      for (var r : document.path("routes")) {
        var route =
            new Route(
                r.path("audience").asText(),
                r.path("scope").asText(),
                r.path("tenantRequired").asBoolean(),
                r.path("mutation").asBoolean(),
                r.path("action").asText());
        if (routes.put(r.path("rpc").asText(), route) != null)
          throw new IllegalStateException("Duplicate RPC policy");
      }
      return Map.copyOf(routes);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid RPC policy inventory", e);
    }
  }

  public static Map<String, Route> all() {
    return ROUTES;
  }

  public static Map<String, RpcContextSecurity.Policy> policies(String audience) {
    var result = new HashMap<String, RpcContextSecurity.Policy>();
    ROUTES.forEach(
        (rpc, r) -> {
          if (r.audience().equals(audience)) result.put(rpc, r.policy());
        });
    return Map.copyOf(result);
  }
}
