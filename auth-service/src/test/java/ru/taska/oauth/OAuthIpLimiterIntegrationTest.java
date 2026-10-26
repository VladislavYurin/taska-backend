package ru.taska.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.AbstractIT;
import ru.taska.api.common.v1.Header;
import ru.taska.api.oauth.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.transport.kafka.OutboxEventScheduler;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"taska.oauth.ip-attempts-per-hour=4", "taska.signup.cleanup-interval=1d"})
class OAuthIpLimiterIntegrationTest extends AbstractIT {
  static final Duration WAIT = Duration.ofSeconds(30);
  @MockitoBean OutboxEventScheduler scheduler;
  @Autowired OAuthIpLimiter limiter;
  @Autowired OAuthProperties properties;
  @Autowired OAuthGrpcService grpc;
  @Autowired DatabaseClient database;

  @Autowired
  @Qualifier("transactionalOperator")
  TransactionalOperator transaction;

  @Autowired
  @Qualifier("requiresNewTransactionalOperator")
  TransactionalOperator reservation;

  @Test
  void failedStartCallbackExchangeAndSignupAllConsumeOneSharedDurableBudget() {
    String ip = ip();
    Header header =
        Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("gateway").build();
    var start =
        StartOAuthRequest.newBuilder()
            .setHeader(header)
            .setProvider("unknown")
            .setClientIp(ip)
            .build();
    assertThatThrownBy(() -> grpc.startOAuth(Mono.just(start)).block(WAIT))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
    var callback =
        CompleteOAuthRequest.newBuilder()
            .setHeader(header)
            .setProvider("unknown")
            .setClientIp(ip)
            .build();
    assertThat(grpc.completeOAuth(Mono.just(callback)).block(WAIT).getRedirectUrl())
        .endsWith("error=PROVIDER_UNAVAILABLE");
    var exchange =
        ExchangeOAuthRequest.newBuilder()
            .setHeader(header)
            .setCode("invalid")
            .setClientIp(ip)
            .build();
    assertThatThrownBy(() -> grpc.exchangeOAuth(Mono.just(exchange)).block(WAIT))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            error ->
                assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    var signup =
        SignupOAuthRequest.newBuilder()
            .setHeader(header)
            .setSignupToken("invalid")
            .setDisplayName("bad\nname")
            .setClientIp(ip)
            .build();
    assertThatThrownBy(() -> grpc.signupOAuth(Mono.just(signup)).block(WAIT))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            error ->
                assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    assertThat(attempts(ip)).isEqualTo(4);
    // Even the callback must propagate the quota error instead of a successful error redirect.
    assertThatThrownBy(() -> grpc.completeOAuth(Mono.just(callback)).block(WAIT))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            error -> {
              assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
              long retry =
                  Long.parseLong(
                      GrpcErrorDetails.decode(error.getTrailers()).get("retryAfterSeconds"));
              assertThat(retry).isBetween(1L, 3601L);
            });
    assertThat(attempts(ip)).isEqualTo(4);
  }

  @Test
  void concurrentReservationsAcrossInstancesNeverExceedThePostgresBudget() {
    String ip = ip();
    var otherInstance = new OAuthIpLimiter(database, properties, reservation);
    var results =
        Flux.range(0, 40)
            .flatMap(
                index ->
                    (index % 2 == 0 ? limiter : otherInstance)
                        .reserve(ip)
                        .thenReturn(true)
                        .onErrorResume(
                            DomainException.class,
                            error -> {
                              assertThat(error.getStatus())
                                  .isEqualTo(DomainStatus.RESOURCE_EXHAUSTED);
                              return Mono.just(false);
                            }),
                40)
            .collectList()
            .block(WAIT);
    assertThat(results).hasSize(40).filteredOn(Boolean::booleanValue).hasSize(4);
    assertThat(attempts(ip)).isEqualTo(4);
  }

  @Test
  void reservationCommitsBeforeAnOuterFlowTransactionFails() {
    String ip = ip();
    assertThatThrownBy(
            () ->
                limiter
                    .reserve(ip)
                    .then(Mono.error(new IllegalStateException("flow failed")))
                    .as(transaction::transactional)
                    .block(WAIT))
        .isInstanceOf(IllegalStateException.class);
    assertThat(attempts(ip)).isEqualTo(1);
  }

  @Test
  void equivalentIpv6AddressesShareTheBucketAndAnExpiredHourResetsIt() {
    String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(1, 65535));
    String compressed = "2001:db8::" + suffix;
    String expanded = "2001:0DB8:0000:0000:0000:0000:0000:" + suffix;
    assertThat(OAuthIpLimiter.bucket(compressed))
        .isEqualTo(OAuthIpLimiter.bucket(expanded))
        .startsWith("oauth:");
    assertThat(OAuthIpLimiter.bucket("unknown"))
        .isEqualTo(OAuthIpLimiter.bucket("not-a-host.example"));
    limiter.reserve(compressed).then(limiter.reserve(expanded)).block(WAIT);
    assertThat(attempts(compressed)).isEqualTo(2);
    database
        .sql(
            "UPDATE taska.signup_rate_limits SET started_at=now()-interval '61 minutes' WHERE"
                + " bucket=:bucket")
        .bind("bucket", OAuthIpLimiter.bucket(compressed))
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    limiter.reserve(compressed).block(WAIT);
    assertThat(attempts(compressed)).isEqualTo(1);
    Instant started =
        database
            .sql("SELECT started_at FROM taska.signup_rate_limits WHERE bucket=:bucket")
            .bind("bucket", OAuthIpLimiter.bucket(compressed))
            .map((row, metadata) -> row.get("started_at", Instant.class))
            .one()
            .block(WAIT);
    assertThat(started).isAfter(Instant.now().minusSeconds(10));
    assertThat(OAuthIpLimiter.bucket(compressed)).doesNotContain(compressed);
  }

  int attempts(String ip) {
    return database
        .sql("SELECT attempts FROM taska.signup_rate_limits WHERE bucket=:bucket")
        .bind("bucket", OAuthIpLimiter.bucket(ip))
        .map((row, metadata) -> row.get("attempts", Integer.class))
        .one()
        .block(WAIT);
  }

  private static String ip() {
    return "2001:db8:"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 4)
        + ":"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 4)
        + "::1";
  }
}
