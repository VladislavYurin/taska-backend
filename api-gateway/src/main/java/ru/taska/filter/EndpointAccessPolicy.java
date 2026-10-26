package ru.taska.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** PAT scopes are explicit operation policy in the same authoritative contract as routing. */
final class EndpointAccessPolicy {
  private static final Set<String> METHODS =
      Set.of("get", "head", "post", "put", "patch", "delete", "options");
  private final List<Route> routes;

  EndpointAccessPolicy(Map<String, Object> contract) {
    List<Route> parsed = new ArrayList<>();
    Map<?, ?> paths = (Map<?, ?>) contract.get("paths");
    paths.forEach(
        (path, value) ->
            ((Map<?, ?>) value)
                .forEach(
                    (method, operation) -> {
                      if (!METHODS.contains(method.toString())
                          || !(operation instanceof Map<?, ?> details)
                          || !(details.get("security") instanceof List<?> security)
                          || security.isEmpty()) {
                        return;
                      }
                      Object scope = details.get("x-pat-scope");
                      if (scope == null || !Set.of("read", "write", "session").contains(scope)) {
                        throw new IllegalStateException(
                            "Missing PAT operation policy: " + method + " " + path);
                      }
                      parsed.add(
                          new Route(
                              HttpMethod.valueOf(
                                  method.toString().toUpperCase(java.util.Locale.ROOT)),
                              PathPatternParser.defaultInstance.parse(path.toString()),
                              scope.toString()));
                    }));
    parsed.sort(Comparator.comparing(Route::pattern, PathPattern.SPECIFICITY_COMPARATOR));
    routes = List.copyOf(parsed);
  }

  static EndpointAccessPolicy load() {
    try (var input = EndpointAccessPolicy.class.getResourceAsStream("/static/openapi.yml")) {
      if (input == null) {
        throw new IllegalStateException("Authoritative OpenAPI is unavailable");
      }
      return new EndpointAccessPolicy(
          new Yaml(new SafeConstructor(new LoaderOptions())).load(input));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot load PAT endpoint policy", error);
    }
  }

  String scope(ServerHttpRequest request) {
    HttpMethod method =
        request.getMethod() == HttpMethod.HEAD ? HttpMethod.GET : request.getMethod();
    return routes.stream()
        .filter(
            route ->
                route.method().equals(method)
                    && route.pattern().matches(request.getPath().pathWithinApplication()))
        .findFirst()
        .map(Route::scope)
        .orElse(method == HttpMethod.GET ? "read" : "write");
  }

  static String canonicalPath(ServerHttpRequest request) {
    return request.getPath().pathWithinApplication().elements().stream()
        .map(
            element ->
                element instanceof PathContainer.PathSegment segment
                    ? segment.valueToMatch()
                    : element.value())
        .collect(java.util.stream.Collectors.joining());
  }

  private record Route(HttpMethod method, PathPattern pattern, String scope) {}
}
