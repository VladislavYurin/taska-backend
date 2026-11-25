package ru.taska.error;

import java.util.List;
import java.util.Map;
import org.springframework.web.server.ServerWebExchange;

/** Явные семейства новых REST-операций; проверяются по опубликованному OpenAPI. */
final class RestErrorContract {
  private RestErrorContract() {}

  static final List<String> COMPANY_PATHS =
      List.of(
          "/api/v1/tenants(?:/.*)?",
          "/api/v1/admin/tenants/.*",
          "/api/v1/auth/tenant-sessions(?:/.*)?",
          "/api/v1/auth/web-sessions(?:/refresh)?",
          "/api/v1/account/tenant-tokens",
          "/api/v1/auth/tenant-oauth-consents");

  static boolean isUploadDependencyError(ServerWebExchange exchange, io.grpc.Status.Code status) {
    return (status == io.grpc.Status.Code.UNAVAILABLE
            || status == io.grpc.Status.Code.DEADLINE_EXCEEDED)
        && exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.POST
        && exchange
            .getRequest()
            .getPath()
            .pathWithinApplication()
            .value()
            .matches("/api/v1/projects/[^/]+/issues/[^/]+/attachments/upload-url");
  }

  /** Формат ошибки задаёт REST-контракт маршрута, а не способ передачи actor в RPC. */
  static boolean usesProblemContract(ServerWebExchange exchange, Map<String, String> details) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (COMPANY_PATHS.stream().anyMatch(path::matches)) return true;

    // Эти новые ошибки lifecycle опубликованы рядом с прежним контрактом проекта.
    String code = details.getOrDefault("code", details.get("reason"));
    if (exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.POST
        && path.matches("/api/v1/projects/[^/]+/issues/[^/]+/attachments/upload-url")
        && "QUOTA_EXCEEDED".equals(code)) return true;
    boolean lifecycle =
        (exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.DELETE
                && path.matches("/api/v1/projects/[^/]+"))
            || (exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.POST
                && path.matches("/api/v1/projects/[^/]+/restore"));
    return lifecycle && ("VERSION_CONFLICT".equals(code) || "IDEMPOTENCY_CONFLICT".equals(code));
  }
}
