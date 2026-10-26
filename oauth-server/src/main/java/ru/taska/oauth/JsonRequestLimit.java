package ru.taska.oauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound registration/private JSON before parsing; form requests are bounded by Tomcat. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class JsonRequestLimit extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (request.getContentType() == null
        || !request
            .getContentType()
            .toLowerCase(java.util.Locale.ROOT)
            .startsWith("application/json")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > 65536) {
      OAuthProtocolGuard.error(response, 413, "request_too_large");
      return;
    }
    byte[] bytes = request.getInputStream().readNBytes(65537);
    if (bytes.length > 65536) {
      OAuthProtocolGuard.error(response, 413, "request_too_large");
      return;
    }
    var buffer = new ByteArrayInputStream(bytes);
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            return new ServletInputStream() {
              @Override
              public int read() {
                return buffer.read();
              }

              @Override
              public int read(byte[] value, int off, int len) {
                return buffer.read(value, off, len);
              }

              @Override
              public boolean isFinished() {
                return buffer.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous JSON only");
              }
            };
          }
        },
        response);
  }
}
