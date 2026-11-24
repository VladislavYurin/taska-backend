package ru.taska.error;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import ru.taska.filter.RequestIdProvider;

class AttachmentStreamErrorTest {
  @Test
  void committedStreamPropagatesErrorWithoutWritingJsonOrChangingHeaders() {
    var errors = mock(GatewayErrorHandler.class);
    var ids = mock(RequestIdProvider.class);
    var handler = new GatewayWebExceptionHandler(errors, ids);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/projects/p/issues/i/attachments/a/content"));
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
    exchange.getResponse().setComplete().block();
    assertTrue(exchange.getResponse().isCommitted());
    var revoked = io.grpc.Status.PERMISSION_DENIED.asRuntimeException();
    StepVerifier.create(handler.handle(exchange, revoked))
        .expectErrorMatches(e -> e == revoked)
        .verify();
    assertEquals(
        MediaType.APPLICATION_OCTET_STREAM, exchange.getResponse().getHeaders().getContentType());
    verifyNoInteractions(errors, ids);
  }
}
