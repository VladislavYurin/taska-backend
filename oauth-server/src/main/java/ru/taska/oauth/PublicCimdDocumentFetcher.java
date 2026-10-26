package ru.taska.oauth;

import io.netty.channel.ChannelOption;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
final class PublicCimdDocumentFetcher implements CimdDocumentFetcher {
  private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age=([0-9]{1,9})(?:,|$)");
  private final OAuthServerProperties policy;
  private final HttpClient http;
  private reactor.netty.resources.LoopResources loops;
  private PublicAddressResolverGroup resolver;
  private final Semaphore inFlight = new Semaphore(2);
  private long windowStarted = System.nanoTime();
  private int attempts;

  @org.springframework.beans.factory.annotation.Autowired
  PublicCimdDocumentFetcher(OAuthServerProperties policy) {
    this.policy = policy;
    loops = reactor.netty.resources.LoopResources.create("oauth-cimd", 1, true);
    resolver =
        new PublicAddressResolverGroup(
            new io.netty.resolver.dns.DnsAddressResolverGroup(
                new io.netty.resolver.dns.DnsNameResolverBuilder()
                    .channelType(io.netty.channel.socket.nio.NioDatagramChannel.class)
                    .socketChannelType(io.netty.channel.socket.nio.NioSocketChannel.class)
                    .queryTimeoutMillis(2000)
                    .maxQueriesPerResolve(4)
                    .completeOncePreferredResolved(false)));
    http = configure(HttpClient.create().runOn(loops, false).resolver(resolver));
  }

  PublicCimdDocumentFetcher(OAuthServerProperties policy, HttpClient client) {
    this.policy = policy;
    http = configure(client);
  }

  private static HttpClient configure(HttpClient client) {
    return client
        .followRedirect(false)
        .disableRetry(true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
        .responseTimeout(Duration.ofSeconds(5))
        .headers(headers -> headers.set("Accept", "application/json"));
  }

  @jakarta.annotation.PreDestroy
  void close() {
    if (resolver != null) resolver.close();
    if (loops != null) loops.dispose();
  }

  @Override
  public Document fetch(URI uri) {
    if (!inFlight.tryAcquire()) throw new IllegalArgumentException("CIMD fetch capacity reached");
    try {
      reserveAttempt();
      return http.get()
          .uri(uri.toString())
          .response(
              (response, content) -> {
                if (response.status().code() != 200)
                  return Mono.error(new IllegalArgumentException("CIMD response rejected"));
                String type = response.responseHeaders().get("Content-Type", "");
                if (!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                  return Mono.error(new IllegalArgumentException("CIMD requires JSON"));
                Duration cache = policy.metadataTtl();
                String control =
                    response
                        .responseHeaders()
                        .get("Cache-Control", "")
                        .toLowerCase(java.util.Locale.ROOT);
                if (control.contains("no-store") || control.contains("no-cache"))
                  cache = Duration.ZERO;
                var match = MAX_AGE.matcher(control);
                if (match.find())
                  cache =
                      Duration.ofSeconds(
                          Math.min(cache.toSeconds(), Long.parseLong(match.group(1))));
                Duration ttl = cache;
                return content
                    .reduceWith(
                        ByteArrayOutputStream::new,
                        (output, buffer) -> {
                          int size = buffer.readableBytes();
                          if (size > 65536 - output.size())
                            throw new IllegalArgumentException("CIMD document too large");
                          byte[] bytes = new byte[size];
                          buffer.readBytes(bytes);
                          output.writeBytes(bytes);
                          return output;
                        })
                    .map(output -> new Document(output.toByteArray(), ttl));
              })
          .single()
          .timeout(Duration.ofSeconds(5))
          .block();
    } finally {
      inFlight.release();
    }
  }

  private synchronized void reserveAttempt() {
    long now = System.nanoTime();
    if (now - windowStarted >= Duration.ofMinutes(1).toNanos()) {
      windowStarted = now;
      attempts = 0;
    }
    if (++attempts > 20) throw new IllegalArgumentException("CIMD fetch rate reached");
  }
}
