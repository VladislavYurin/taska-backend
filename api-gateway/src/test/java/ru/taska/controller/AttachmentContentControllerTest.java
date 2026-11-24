package ru.taska.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.issue.attachment.v1.ReadAttachmentContentResponse;
import ru.taska.domain.*;
import ru.taska.filter.*;
import ru.taska.security.tenant.*;
import ru.taska.transport.grpc.GrpcIssueAttachmentServiceClient;

class AttachmentContentControllerTest {
  final UUID project = UUID.randomUUID(), issue = UUID.randomUUID(), attachment = UUID.randomUUID();
  final GatewayContextFactory contexts = mock(GatewayContextFactory.class);
  final GrpcIssueAttachmentServiceClient client = mock(GrpcIssueAttachmentServiceClient.class);
  AttachmentContentController controller;
  GatewayContext context;

  @BeforeEach
  void setup() {
    var verified = DownloadTestAuthority.verified();
    var user = mock(GatewayUserContext.class);
    when(user.userId()).thenReturn(verified.actorId());
    context =
        new GatewayContext(
            UUID.randomUUID().toString(), "api-gateway", user, ActionChannel.UI, verified);
    when(contexts.buildContext(anyString(), any(), eq(EndpointSecurity.PROTECTED)))
        .thenReturn(Mono.just(context));
    controller =
        new AttachmentContentController(
            new GatewayRequestExecutor(new RequestIdProvider(), contexts), contexts, client);
  }

  ReadAttachmentContentResponse metadata(long size) {
    return ReadAttachmentContentResponse.newBuilder()
        .setTotalSize(size)
        .setChecksum("a".repeat(64))
        .setFileName("../report\r\n.txt")
        .setContentType("text/plain")
        .build();
  }

  void stubMetadata(long size) {
    when(client.readContent(
            anyString(), anyString(), anyString(), eq(0L), eq(0), eq(""), eq(true), any()))
        .thenReturn(Mono.just(metadata(size)));
  }

  @Test
  void rangeAndHeadHaveSafeHeadersAndExactBytes() {
    stubMetadata(10);
    when(client.readContent(
            anyString(), anyString(), anyString(), eq(3L), eq(4), anyString(), eq(false), any()))
        .thenReturn(
            Mono.just(
                metadata(10).toBuilder()
                    .setOffset(3)
                    .setData(ByteString.copyFromUtf8("3456"))
                    .build()));
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/content").header("Range", "bytes=3-6"));
    controller.content(project, issue, attachment, exchange).block(Duration.ofSeconds(5));
    assertEquals(HttpStatus.PARTIAL_CONTENT, exchange.getResponse().getStatusCode());
    assertEquals("bytes 3-6/10", exchange.getResponse().getHeaders().getFirst("Content-Range"));
    assertEquals("private, no-store", exchange.getResponse().getHeaders().getCacheControl());
    assertEquals(4, exchange.getResponse().getHeaders().getContentLength());
    assertEquals("3456", exchange.getResponse().getBodyAsString().block());
    String disposition = exchange.getResponse().getHeaders().getFirst("Content-Disposition");
    assertFalse(disposition.contains("\r"));
    assertFalse(disposition.contains("\n"));
    verify(contexts, times(2))
        .buildContext(anyString(), same(exchange), eq(EndpointSecurity.PROTECTED));
    clearInvocations(client);
    var head =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.HEAD, "/content"));
    controller.content(project, issue, attachment, head).block(Duration.ofSeconds(5));
    assertEquals(10, head.getResponse().getHeaders().getContentLength());
    verify(client, times(1))
        .readContent(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyInt(),
            anyString(),
            eq(true),
            any());
    verify(client, never())
        .readContent(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyInt(),
            anyString(),
            eq(false),
            any());
  }

  @Test
  void invalidRangeIsResolvedAfterAuthorizationAndDoesNotReadBytes() {
    stubMetadata(10);
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/content").header("Range", "bytes=0-1,3-4"));
    controller.content(project, issue, attachment, exchange).block(Duration.ofSeconds(5));
    assertEquals(
        HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, exchange.getResponse().getStatusCode());
    assertEquals("bytes */10", exchange.getResponse().getHeaders().getFirst("Content-Range"));
    verify(client, never())
        .readContent(
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyInt(),
            anyString(),
            eq(false),
            any());
  }

  @Test
  void revocationStopsLongStreamBeforeNextChunk() {
    int chunk = 1024 * 1024;
    stubMetadata(2L * chunk);
    AtomicInteger authorizations = new AtomicInteger();
    when(contexts.buildContext(anyString(), any(), any()))
        .thenAnswer(
            inv ->
                authorizations.incrementAndGet() <= 2
                    ? Mono.just(context)
                    : Mono.error(io.grpc.Status.PERMISSION_DENIED.asRuntimeException()));
    when(client.readContent(
            anyString(),
            anyString(),
            anyString(),
            eq(0L),
            eq(chunk),
            anyString(),
            eq(false),
            any()))
        .thenReturn(
            Mono.just(
                metadata(2L * chunk).toBuilder()
                    .setData(ByteString.copyFrom(new byte[chunk]))
                    .build()));
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/content"));
    AtomicInteger sent = new AtomicInteger();
    exchange
        .getResponse()
        .setWriteHandler(
            body ->
                body.doOnNext(
                        b -> {
                          sent.addAndGet(b.readableByteCount());
                          org.springframework.core.io.buffer.DataBufferUtils.release(b);
                        })
                    .then());
    assertThrows(
        io.grpc.StatusRuntimeException.class,
        () ->
            controller.content(project, issue, attachment, exchange).block(Duration.ofSeconds(5)));
    assertEquals(chunk, sent.get());
    assertEquals(3, authorizations.get());
    verify(client, never())
        .readContent(
            anyString(),
            anyString(),
            anyString(),
            eq((long) chunk),
            anyInt(),
            anyString(),
            eq(false),
            any());
  }

  @Test
  void rangesAreBoundedAndOverflowSafe() {
    assertEquals(new AttachmentByteRange(7, 9, true), AttachmentByteRange.parse("bytes=-3", 10));
    assertEquals(new AttachmentByteRange(3, 9, true), AttachmentByteRange.parse("bytes=3-", 10));
    assertEquals(new AttachmentByteRange(0, 9, true), AttachmentByteRange.parse("bytes=0-100", 10));
    for (String invalid :
        List.of(
            "bytes=-0",
            "bytes=10-",
            "bytes=4-3",
            "bytes=-",
            "items=0-1",
            "bytes=9223372036854775808-"))
      assertThrows(
          IllegalArgumentException.class, () -> AttachmentByteRange.parse(invalid, 10), invalid);
    assertEquals(Long.MAX_VALUE, AttachmentByteRange.parse(null, Long.MAX_VALUE).length());
  }

  @Test
  void revocationStopsAStreamStalledByClientBackpressureWithinLease() {
    int chunk = 1024 * 1024;
    stubMetadata(2L * chunk);
    AtomicInteger checks = new AtomicInteger();
    when(contexts.buildContext(anyString(), any(), any()))
        .thenAnswer(
            inv ->
                checks.incrementAndGet() <= 2
                    ? Mono.just(context)
                    : Mono.error(io.grpc.Status.PERMISSION_DENIED.asRuntimeException()));
    when(client.readContent(
            anyString(),
            anyString(),
            anyString(),
            eq(0L),
            eq(chunk),
            anyString(),
            eq(false),
            any()))
        .thenReturn(
            Mono.just(
                metadata(2L * chunk).toBuilder()
                    .setData(ByteString.copyFrom(new byte[chunk]))
                    .build()));
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/content"));
    exchange
        .getResponse()
        .setWriteHandler(
            body ->
                body.take(1)
                    .doOnNext(org.springframework.core.io.buffer.DataBufferUtils::release)
                    .then(Mono.never()));
    reactor.test.StepVerifier.withVirtualTime(
            () -> controller.content(project, issue, attachment, exchange))
        .thenAwait(Duration.ofSeconds(16))
        .expectError(io.grpc.StatusRuntimeException.class)
        .verify(Duration.ofSeconds(5));
    assertEquals(3, checks.get());
  }
}
