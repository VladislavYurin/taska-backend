package ru.taska.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.ReadAttachmentContentResponse;
import ru.taska.domain.EndpointSecurity;
import ru.taska.filter.*;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.transport.grpc.GrpcIssueAttachmentServiceClient;

/** Each lazy chunk is authorized anew from the original credential, including long streams. */
@RestController
@ConditionalOnProperty(name = "taska.rpc.context.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AttachmentContentController {
  private static final int CHUNK = 1024 * 1024;
  private final GatewayRequestExecutor executor;
  private final GatewayContextFactory contexts;
  private final GrpcIssueAttachmentServiceClient client;

  @RequestMapping(
      path = "/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}/content",
      method = {RequestMethod.GET, RequestMethod.HEAD})
  public Mono<Void> content(
      @PathVariable UUID projectId,
      @PathVariable UUID issueId,
      @PathVariable UUID attachmentId,
      ServerWebExchange exchange) {
    String p = projectId.toString(), i = issueId.toString(), a = attachmentId.toString();
    return executor.execute(
        exchange,
        EndpointSecurity.PROTECTED,
        c ->
            client
                .readContent(p, i, a, 0, 0, "", true, c)
                .flatMap(
                    meta -> {
                      var response = exchange.getResponse();
                      var headers = response.getHeaders();
                      headers.setCacheControl("private, no-store");
                      headers.set("X-Content-Type-Options", "nosniff");
                      headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
                      AttachmentByteRange range;
                      try {
                        range =
                            AttachmentByteRange.parse(
                                exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE),
                                meta.getTotalSize());
                        if (exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.RANGE).size()
                            > 1) throw new IllegalArgumentException();
                      } catch (IllegalArgumentException e) {
                        response.setStatusCode(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                        headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + meta.getTotalSize());
                        return response.setComplete();
                      }
                      response.setStatusCode(
                          range.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
                      headers.setContentType(MediaType.parseMediaType(meta.getContentType()));
                      headers.setContentLength(range.length());
                      headers.setContentDisposition(
                          ContentDisposition.attachment()
                              .filename(safeName(meta.getFileName()), StandardCharsets.UTF_8)
                              .build());
                      if (range.partial())
                        headers.set(
                            HttpHeaders.CONTENT_RANGE,
                            "bytes "
                                + range.start()
                                + "-"
                                + range.end()
                                + "/"
                                + meta.getTotalSize());
                      if (exchange.getRequest().getMethod() == HttpMethod.HEAD)
                        return response.setComplete();
                      Flux<Mono<DataBuffer>> chunks =
                          Flux.<Long, Long>generate(
                                  () -> range.start(),
                                  (offset, sink) -> {
                                    if (offset > range.end()) {
                                      sink.complete();
                                      return offset;
                                    }
                                    sink.next(offset);
                                    long count = Math.min(CHUNK, range.end() - offset + 1);
                                    return offset + count;
                                  })
                              .map(
                                  offset ->
                                      Mono.defer(
                                              () ->
                                                  contexts
                                                      .buildContext(
                                                          c.requestId(),
                                                          exchange,
                                                          EndpointSecurity.PROTECTED)
                                                      .flatMap(
                                                          fresh -> {
                                                            if (fresh.verified() == null)
                                                              return Mono.error(
                                                                  new IllegalStateException(
                                                                      "Native download authority"
                                                                          + " unavailable"));
                                                            int length =
                                                                (int)
                                                                    Math.min(
                                                                        CHUNK,
                                                                        range.end() - offset + 1);
                                                            return RpcContextBridge.install(
                                                                    client.readContent(
                                                                        p,
                                                                        i,
                                                                        a,
                                                                        offset,
                                                                        length,
                                                                        meta.getChecksum(),
                                                                        false,
                                                                        fresh),
                                                                    fresh.verified())
                                                                .map(
                                                                    chunk -> {
                                                                      validate(
                                                                          meta, chunk, offset,
                                                                          length);
                                                                      return response
                                                                          .bufferFactory()
                                                                          .wrap(
                                                                              chunk
                                                                                  .getData()
                                                                                  .toByteArray());
                                                                    });
                                                          }))
                                          .doOnDiscard(DataBuffer.class, DataBufferUtils::release));
                      // A slow client can keep a single chunk in flight beyond the access lease.
                      // Check independently of body demand and cancel the write on revocation.
                      Mono<Void> watchdog =
                          Flux.interval(java.time.Duration.ofSeconds(15))
                              .concatMap(
                                  tick ->
                                      contexts
                                          .buildContext(
                                              c.requestId(), exchange, EndpointSecurity.PROTECTED)
                                          .flatMap(
                                              fresh -> {
                                                if (fresh.verified() == null)
                                                  return Mono.error(
                                                      new IllegalStateException(
                                                          "Native download authority unavailable"));
                                                return RpcContextBridge.install(
                                                    client.readContent(
                                                        p, i, a, 0, 0, "", true, fresh),
                                                    fresh.verified());
                                              })
                                          .timeout(java.time.Duration.ofSeconds(10))
                                          .doOnNext(
                                              current -> {
                                                if (current.getTotalSize() != meta.getTotalSize()
                                                    || !current
                                                        .getChecksum()
                                                        .equals(meta.getChecksum()))
                                                  throw new IllegalStateException(
                                                      "Attachment content mismatch");
                                              }),
                                  1)
                              .then();
                      return Mono.firstWithSignal(response.writeAndFlushWith(chunks), watchdog);
                    }));
  }

  static String safeName(String name) {
    String safe = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
    return safe.isBlank() ? "attachment" : safe;
  }

  private static void validate(
      ReadAttachmentContentResponse meta,
      ReadAttachmentContentResponse chunk,
      long offset,
      int length) {
    if (chunk.getOffset() != offset
        || chunk.getData().size() != length
        || chunk.getTotalSize() != meta.getTotalSize()
        || !chunk.getChecksum().equals(meta.getChecksum()))
      throw new IllegalStateException("Attachment content mismatch");
  }
}
