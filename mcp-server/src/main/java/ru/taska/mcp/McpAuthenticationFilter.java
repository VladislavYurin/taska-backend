package ru.taska.mcp;

import io.grpc.Status;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpAuthenticationFilter implements WebFilter {
  private static final Pattern PAT = Pattern.compile("tsk_pat_[A-Za-z0-9_-]{43}");
  private static final List<String> VERSIONS = List.of("2025-03-26", "2025-06-18", "2025-11-25");
  private static final List<String> CORS_HEADERS =
      List.of("authorization", "content-type", "accept", "mcp-protocol-version", "x-request-id");
  private final PatAuthenticator authenticator;
  private final McpProperties properties;
  private final McpObservability observability;

  public McpAuthenticationFilter(PatAuthenticator authenticator, McpProperties properties) {
    this(authenticator, properties, null);
  }

  @Autowired
  public McpAuthenticationFilter(
      PatAuthenticator authenticator, McpProperties properties, McpObservability observability) {
    this.observability = observability;
    this.authenticator = authenticator;
    this.properties = properties;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!matchesMcp(exchange.getRequest())) return chain.filter(exchange);
    return observability == null
        ? Mono.defer(() -> authenticate(exchange, chain))
        : observability.request(() -> authenticate(exchange, chain));
  }

  private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain) {
    var headers = exchange.getRequest().getHeaders();
    var responseHeaders = exchange.getResponse().getHeaders();
    String requestId = headers.getFirst("X-Request-Id");
    requestId = McpRequestIds.resolve(requestId);
    responseHeaders.set("X-Request-Id", requestId);
    responseHeaders.setCacheControl("private, no-store");
    responseHeaders.setVary(List.of("Origin"));
    List<String> origins = headers.get(HttpHeaders.ORIGIN);
    if (origins != null) {
      if (origins.size() != 1 || !properties.allowedOrigins().contains(origins.getFirst())) {
        return reject(exchange, HttpStatus.FORBIDDEN, null);
      }
      responseHeaders.setAccessControlAllowOrigin(origins.getFirst());
      responseHeaders.setAccessControlExposeHeaders(List.of("X-Request-Id"));
    }
    if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
      if (origins == null
          || !"POST"
              .equals(
                  headers.getAccessControlRequestMethod() == null
                      ? null
                      : headers.getAccessControlRequestMethod().name())
          || !headers.getAccessControlRequestHeaders().stream()
              .allMatch(h -> CORS_HEADERS.contains(h.toLowerCase(java.util.Locale.ROOT)))) {
        return reject(exchange, HttpStatus.FORBIDDEN, null);
      }
      responseHeaders.setAccessControlAllowMethods(List.of(HttpMethod.POST));
      responseHeaders.setAccessControlAllowHeaders(CORS_HEADERS);
      return reject(exchange, HttpStatus.NO_CONTENT, null);
    }
    List<String> authorization = headers.get(HttpHeaders.AUTHORIZATION);
    if (authorization == null
        || authorization.size() != 1
        || !authorization.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)) {
      return reject(exchange, HttpStatus.UNAUTHORIZED, "Bearer");
    }
    String token = authorization.getFirst().substring(7);
    if (!PAT.matcher(token).matches()) {
      return reject(exchange, HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"");
    }
    String finalRequestId = requestId;
    return authenticator
        .authenticate(token, requestId)
        .filter(McpAuthenticationFilter::validPat)
        .materialize()
        .flatMap(
            signal -> {
              if (signal.isOnError()) {
                var code = Status.fromThrowable(signal.getThrowable()).getCode();
                HttpStatus status =
                    code == Status.Code.UNAUTHENTICATED || code == Status.Code.PERMISSION_DENIED
                        ? HttpStatus.UNAUTHORIZED
                        : HttpStatus.SERVICE_UNAVAILABLE;
                return reject(
                    exchange,
                    status,
                    status == HttpStatus.UNAUTHORIZED ? "Bearer error=\"invalid_token\"" : null);
              }
              if (!signal.hasValue()) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"");
              }
              UserContext user = signal.get();
              if (!user.getScopesList().contains("read")) {
                return reject(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    "Bearer error=\"insufficient_scope\", scope=\"read\"");
              }
              String version = headers.getFirst("MCP-Protocol-Version");
              if (version != null && !VERSIONS.contains(version)) {
                // MCP 2025 transport requires HTTP 400 for an unsupported version header.
                return reject(exchange, HttpStatus.BAD_REQUEST, null);
              }
              exchange
                  .getAttributes()
                  .put(McpRequestContext.KEY, new McpRequestContext(finalRequestId, token, user));
              return chain.filter(exchange);
            });
  }

  static boolean matchesMcp(org.springframework.http.server.reactive.ServerHttpRequest request) {
    // Match the same decoded path segments as WebFlux, including matrix parameters.
    String path =
        request.getPath().pathWithinApplication().elements().stream()
            .map(
                element ->
                    element instanceof PathContainer.PathSegment segment
                        ? segment.valueToMatch()
                        : element.value())
            .collect(java.util.stream.Collectors.joining());
    return "/mcp".equals(path);
  }

  private static boolean validPat(UserContext user) {
    return user.getTokenKind() == AccessTokenKind.ACCESS_TOKEN_KIND_PAT
        && user.getStatus() == UserStatus.USER_STATUS_ACTIVE
        && user.hasTokenId()
        && user.hasTokenExpiresAt()
        && Instant.ofEpochSecond(
                user.getTokenExpiresAt().getSeconds(), user.getTokenExpiresAt().getNanos())
            .isAfter(Instant.now());
  }

  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String challenge) {
    if (observability != null
        && (status == HttpStatus.UNAUTHORIZED
            || status == HttpStatus.FORBIDDEN
            || status == HttpStatus.SERVICE_UNAVAILABLE)) {
      String outcome =
          status == HttpStatus.UNAUTHORIZED
              ? "auth"
              : status == HttpStatus.SERVICE_UNAVAILABLE
                  ? "upstream_error"
                  : challenge == null ? "origin" : "scope";
      observability.authRejected(
          exchange.getResponse().getHeaders().getFirst("X-Request-Id"), outcome, status.value());
    }
    exchange.getResponse().setStatusCode(status);
    if (challenge != null) {
      exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
    }
    return exchange.getResponse().setComplete();
  }
}
