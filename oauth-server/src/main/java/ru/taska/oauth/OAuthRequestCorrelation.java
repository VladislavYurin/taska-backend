package ru.taska.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OAuthRequestCorrelation extends OncePerRequestFilter {
  private static final String ATTRIBUTE = OAuthRequestCorrelation.class.getName();

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var values = java.util.Collections.list(request.getHeaders("X-Request-Id"));
    String id = values.size() == 1 && values.getFirst().matches("[A-Za-z0-9._:-]{1,128}")
        ? values.getFirst() : UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader("X-Request-Id", id);
    String previous = MDC.get("request_id");
    MDC.put("request_id", id);
    try { chain.doFilter(request, response); }
    finally { if (previous == null) MDC.remove("request_id"); else MDC.put("request_id", previous); }
  }

  static String current() {
    String correlation = MDC.get("request_id");
    if (correlation != null && correlation.matches("[A-Za-z0-9._:-]{1,128}")) return correlation;
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servlet
        && servlet.getRequest().getAttribute(ATTRIBUTE) instanceof String id) return id;
    return UUID.randomUUID().toString();
  }
}
