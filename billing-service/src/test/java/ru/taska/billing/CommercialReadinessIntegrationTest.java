package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CommercialBuyerType;
import ru.taska.api.b2b.v1.CommercialCurrency;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CommercialMethod;
import ru.taska.api.b2b.v1.MerchantConfiguration;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;
import ru.taska.api.b2b.v1.Money;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.billing.commercial.CommercialDependencies;
import ru.taska.billing.commercial.CommercialReadiness;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

@Testcontainers
@Timeout(90)
class CommercialReadinessIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static final UUID ACTOR = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
  static final Duration WAIT = Duration.ofSeconds(20);
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static ConnectionPool pool;
  static DatabaseClient db, operator;
  static TransactionalOperator tx;
  BillingCatalog catalog;
  CatalogCursor cursor;

  static ConnectionFactory factory(String user, String password) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, pg.getHost())
            .option(ConnectionFactoryOptions.PORT, pg.getMappedPort(5432))
            .option(ConnectionFactoryOptions.DATABASE, pg.getDatabaseName())
            .option(ConnectionFactoryOptions.USER, user)
            .option(ConnectionFactoryOptions.PASSWORD, password)
            .build());
  }

  @BeforeAll
  static void setup() throws Exception {
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    var result =
        pg.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            pg.getUsername(),
            "-d",
            pg.getDatabaseName(),
            "-v",
            "service=billing",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, result.getExitCode(), result.getStderr());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA billing_liquibase AUTHORIZATION taska_billing_migrator");
    sql("REVOKE ALL ON SCHEMA billing_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_billing_app PASSWORD '" + APP_PASSWORD + "'");
    sql("ALTER ROLE taska_billing_deployer PASSWORD '" + DEPLOY_PASSWORD + "'");
    migrate();
    migrate();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_billing_app", APP_PASSWORD))
                .maxSize(5)
                .initialSize(1)
                .build());
    db = DatabaseClient.create(pool);
    tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
    new BillingConfiguration().safeRuntimeRole(pool).afterPropertiesSet();
  }

  static void migrate() throws Exception {
    new BillingConfiguration()
        .registryLiquibase(pg.getJdbcUrl(), "taska_billing_deployer", DEPLOY_PASSWORD)
        .afterPropertiesSet();
  }

  static void sql(String s) {
    operator.sql(s).fetch().rowsUpdated().block(WAIT);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  AtomicReference<Instant> time;
  AtomicReference<MerchantConfigurationSnapshot> profile;
  AtomicReference<CommercialDependencies.FxObservation> observation;
  Map<String, MerchantConfigurationSnapshot> history;
  AtomicInteger fxCalls, providerCalls;
  AtomicReference<Throwable> historyFailure, fxFailure;
  CommercialReadiness guard;
  PlanVersion plan;

  @BeforeEach
  void reset() {
    sql("TRUNCATE taska.fx_quotes");
    time = new AtomicReference<>(NOW);
    historyFailure = new AtomicReference<>();
    fxFailure = new AtomicReference<>();
    fxCalls = new AtomicInteger();
    providerCalls = new AtomicInteger();
    history = new HashMap<>();
    profile = new AtomicReference<>(snapshot("2", CommercialFixtures.profile()));
    history.put("2", profile.get());
    observation =
        new AtomicReference<>(
            new CommercialDependencies.FxObservation(
                new BigDecimal("1.10025"),
                "TEST-FX",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                NOW.minusSeconds(5),
                NOW.plusSeconds(30)));
    plan =
        PlanVersion.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setKind(PlanVersion.KindValue.KIND_PAID)
            .setState(PlanVersion.StateValue.STATE_PUBLISHED)
            .setPrice(
                Money.newBuilder()
                    .setAmountMinor("2000")
                    .setCurrency(Money.CurrencyValue.CURRENCY_EUR)
                    .setExponent(2))
            .build();
    guard =
        guard(
            s ->
                Mono.just(
                    new CommercialDependencies.CredentialBinding(
                        s.getEnvironment(),
                        s.getMerchantAccount(),
                        s.getApiKeyReference(),
                        s.getWebhookKeyReference())));
  }

  static MerchantConfigurationSnapshot snapshot(String version, MerchantConfiguration c) {
    return MerchantConfigurationSnapshot.newBuilder()
        .setVersion(version)
        .setEffectiveAt(NOW.minusSeconds(60).toString())
        .setConfiguration(c)
        .build();
  }

  CommercialReadiness guard(CommercialDependencies.CredentialAuthority credentials) {
    var source =
        new CommercialDependencies.ConfigurationSource() {
          public Mono<MerchantConfigurationSnapshot> current() {
            return Mono.justOrEmpty(profile.get());
          }

          public Mono<MerchantConfigurationSnapshot> version(String v) {
            if (historyFailure.get() != null) return Mono.error(historyFailure.get());
            return Mono.justOrEmpty(history.get(v));
          }
        };
    return new CommercialReadiness(
        source,
        credentials,
        (env, p) ->
            Mono.defer(
                () -> {
                  fxCalls.incrementAndGet();
                  if (fxFailure.get() != null) return Mono.error(fxFailure.get());
                  return Mono.justOrEmpty(observation.get());
                }),
        new Clock() {
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return time.get();
          }
        });
  }

  static CommercialReadiness.Selection selection(CommercialCurrency currency) {
    return new CommercialReadiness.Selection(
        "ES",
        CommercialBuyerType.COMMERCIAL_BUYER_BUSINESS,
        currency,
        CommercialMethod.COMMERCIAL_METHOD_PSP);
  }

  Mono<CommercialReadiness.InvoiceTerms> invoice(CommercialCurrency currency) {
    return tx.transactional(guard.invoice(db, plan, selection(currency)));
  }

  static DomainException failure(Mono<?> work) {
    return assertThrows(DomainException.class, () -> work.block(WAIT));
  }

  static long quoteCount() {
    return operator
        .sql("SELECT count(*) n FROM taska.fx_quotes")
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void eurUsesServerGrossWithoutFxAndMissingProfileNeverReachesPsp() {
    var eur = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_EUR).block(WAIT);
    assertEquals(2000, eur.grossMinor());
    assertEquals(eur.grossMinor(), eur.netMinor() + eur.taxMinor());
    assertEquals(0, fxCalls.get());
    assertEquals(0, quoteCount());
    assertTrue(
        guard
            .livePaymentEnabled(selection(CommercialCurrency.COMMERCIAL_CURRENCY_EUR))
            .block(WAIT));
    profile.set(null);
    assertFalse(
        guard
            .livePaymentEnabled(selection(CommercialCurrency.COMMERCIAL_CURRENCY_EUR))
            .block(WAIT));
    failure(
        guard.checkout(
            eur,
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
            a -> {
              providerCalls.incrementAndGet();
              return Mono.just("sent");
            }));
    assertEquals(0, providerCalls.get());
    assertEquals(2000, eur.grossMinor());
  }

  @Test
  void usdConvertsGrossOnceHalfUpAndStoresExactImmutableQuoteAndTerms() {
    var usd = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD).block(WAIT);
    assertEquals(2201, usd.grossMinor());
    assertEquals(2000, usd.baseGrossMinor());
    assertEquals(usd.grossMinor(), usd.netMinor() + usd.taxMinor());
    assertEquals("2", usd.configurationVersion());
    assertEquals("TEST ONLY seller", usd.seller().getLegalName());
    assertEquals(1, quoteCount());
    var rate =
        db.sql("SELECT rate FROM taska.fx_quotes WHERE id=:id")
            .bind("id", usd.fx().id())
            .map((r, m) -> r.get("rate", BigDecimal.class))
            .one()
            .block(WAIT);
    assertEquals(0, new BigDecimal("1.10025").compareTo(rate));
    assertThrows(
        Exception.class,
        () ->
            db.sql("UPDATE taska.fx_quotes SET rate=2 WHERE id=:id")
                .bind("id", usd.fx().id())
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            db.sql("DELETE FROM taska.fx_quotes WHERE id=:id")
                .bind("id", usd.fx().id())
                .fetch()
                .rowsUpdated()
                .block(WAIT));
  }

  @Test
  void staleWrongSourceFutureAndMixedEnvironmentNeverCreateUsdOrFallbackSilently() {
    for (var q :
        List.of(
            new CommercialDependencies.FxObservation(
                new BigDecimal("1.1"),
                "TEST-FX",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                NOW.minusSeconds(60),
                NOW.plusSeconds(30)),
            new CommercialDependencies.FxObservation(
                new BigDecimal("1.1"),
                "TEST-FX",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                NOW.minusSeconds(5),
                NOW),
            new CommercialDependencies.FxObservation(
                new BigDecimal("1.1"),
                "TEST-FX",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                NOW.plusSeconds(1),
                NOW.plusSeconds(30)),
            new CommercialDependencies.FxObservation(
                new BigDecimal("1.1"),
                "UNAPPROVED",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                NOW.minusSeconds(5),
                NOW.plusSeconds(30)),
            new CommercialDependencies.FxObservation(
                new BigDecimal("1.1"),
                "TEST-FX",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX,
                NOW.minusSeconds(5),
                NOW.plusSeconds(30)),
            new CommercialDependencies.FxObservation(
                BigDecimal.ZERO,
                "TEST-FX",
                "v1",
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                NOW.minusSeconds(5),
                NOW.plusSeconds(30)))) {
      observation.set(q);
      var error = failure(invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD));
      assertEquals("POLICY_NOT_CONFIGURED", error.getMessage());
      assertEquals("EUR", error.getDetails().get("offeredCurrency"));
      assertFalse(
          guard
              .livePaymentEnabled(selection(CommercialCurrency.COMMERCIAL_CURRENCY_USD))
              .block(WAIT));
    }
    observation.set(null);
    assertEquals(
        "EUR",
        failure(invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD))
            .getDetails()
            .get("offeredCurrency"));
    assertEquals(0, quoteCount());
    assertEquals(
        2000, invoice(CommercialCurrency.COMMERCIAL_CURRENCY_EUR).block(WAIT).grossMinor());
  }

  @Test
  void unsupportedCountryOrMethodNeverChangesPriceOrCallsFx() {
    var wrong =
        new CommercialReadiness.Selection(
            "DE",
            CommercialBuyerType.COMMERCIAL_BUYER_BUSINESS,
            CommercialCurrency.COMMERCIAL_CURRENCY_USD,
            CommercialMethod.COMMERCIAL_METHOD_PSP);
    assertEquals(
        DomainStatus.FAILED_PRECONDITION,
        failure(tx.transactional(guard.invoice(db, plan, wrong))).getStatus());
    var wireUsd =
        new CommercialReadiness.Selection(
            "ES",
            CommercialBuyerType.COMMERCIAL_BUYER_BUSINESS,
            CommercialCurrency.COMMERCIAL_CURRENCY_USD,
            CommercialMethod.COMMERCIAL_METHOD_WIRE);
    failure(tx.transactional(guard.invoice(db, plan, wireUsd)));
    assertEquals(0, fxCalls.get());
    assertEquals(0, quoteCount());
    assertEquals("2000", plan.getPrice().getAmountMinor());
  }

  @Test
  void credentialsMustMatchActualMerchantEnvironmentAndReferencesBeforeProvider() {
    var invoice = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_EUR).block(WAIT);
    guard =
        guard(
            s ->
                Mono.just(
                    new CommercialDependencies.CredentialBinding(
                        CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX,
                        s.getMerchantAccount(),
                        s.getApiKeyReference(),
                        s.getWebhookKeyReference())));
    assertFalse(
        guard
            .livePaymentEnabled(selection(CommercialCurrency.COMMERCIAL_CURRENCY_EUR))
            .block(WAIT));
    failure(
        guard.checkout(
            invoice,
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
            a -> {
              providerCalls.incrementAndGet();
              return Mono.just("sent");
            }));
    guard = guard(s -> Mono.empty());
    failure(
        guard.checkout(
            invoice,
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
            a -> {
              providerCalls.incrementAndGet();
              return Mono.just("sent");
            }));
    assertEquals(0, providerCalls.get());
  }

  @Test
  void oldInvoiceKeepsSellerAmountAndFxWhenNewConfigurationAndRateArrive() {
    var old = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD).block(WAIT);
    time.set(NOW.plusSeconds(120));
    var updated =
        CommercialFixtures.profile().toBuilder()
            .setSeller(
                CommercialFixtures.profile().getSeller().toBuilder()
                    .setLegalName("NEW TEST seller"))
            .build();
    profile.set(snapshot("3", updated));
    history.put("3", profile.get());
    observation.set(
        new CommercialDependencies.FxObservation(
            new BigDecimal("1.3"),
            "TEST-FX",
            "v1",
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
            time.get().minusSeconds(1),
            time.get().plusSeconds(30)));
    var response =
        guard
            .checkout(
                old,
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                a -> {
                  providerCalls.incrementAndGet();
                  assertEquals(2201, a.amountMinor());
                  assertEquals("2", a.configurationVersion());
                  assertFalse(a.toString().contains("secret://"));
                  return Mono.just("sent");
                })
            .block(WAIT);
    assertEquals("sent", response);
    assertEquals(1, providerCalls.get());
    assertEquals(1, fxCalls.get());
    assertEquals("TEST ONLY seller", old.seller().getLegalName());
    assertEquals(2201, old.grossMinor());
    var next = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD).block(WAIT);
    assertEquals(2600, next.grossMinor());
    assertEquals("3", next.configurationVersion());
    assertEquals("NEW TEST seller", next.seller().getLegalName());
  }

  @Test
  void disabledProfileAndMixedProviderEnvironmentBlockNewCheckoutWithoutRewritingTerms() {
    var old = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_EUR).block(WAIT);
    failure(
        guard.checkout(
            old,
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX,
            a -> {
              providerCalls.incrementAndGet();
              return Mono.just("sent");
            }));
    profile.set(snapshot("3", CommercialFixtures.profile().toBuilder().setEnabled(false).build()));
    failure(
        guard.checkout(
            old,
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
            a -> {
              providerCalls.incrementAndGet();
              return Mono.just("sent");
            }));
    assertEquals(0, providerCalls.get());
    assertEquals(2000, old.grossMinor());
  }

  @Test
  void outerFailureRollsBackQuoteAndTinyOrOverflowAmountsNeverLeaveOrphanQuotes() {
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.transactional(
                    guard
                        .invoice(db, plan, selection(CommercialCurrency.COMMERCIAL_CURRENCY_USD))
                        .flatMap(v -> Mono.error(new IllegalStateException("test rollback"))))
                .block(WAIT));
    assertEquals(0, quoteCount());
    observation.set(
        new CommercialDependencies.FxObservation(
            new BigDecimal("0.0000000001"),
            "TEST-FX",
            "v1",
            CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
            NOW.minusSeconds(1),
            NOW.plusSeconds(30)));
    failure(invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD));
    assertEquals(0, quoteCount());
  }

  @Test
  void historicalTransportFailureIsSanitizedAndMissingVersionNeverCallsProvider() {
    var terms = invoice(CommercialCurrency.COMMERCIAL_CURRENCY_EUR).block(WAIT);
    historyFailure.set(new IllegalStateException("PRIVATE TRANSPORT DETAILS"));
    var error =
        failure(
            guard.checkout(
                terms,
                CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                a -> {
                  providerCalls.incrementAndGet();
                  return Mono.just("sent");
                }));
    assertEquals("DEPENDENCY_UNAVAILABLE", error.getMessage());
    assertFalse(error.toString().contains("PRIVATE"));
    assertEquals(null, error.getCause());
    historyFailure.set(null);
    history.clear();
    assertEquals(
        "POLICY_NOT_CONFIGURED",
        failure(
                guard.checkout(
                    terms,
                    CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE,
                    a -> {
                      providerCalls.incrementAndGet();
                      return Mono.just("sent");
                    }))
            .getMessage());
    assertEquals(0, providerCalls.get());
    assertEquals(2000, terms.grossMinor());
  }

  @Test
  void fxProviderOutageOffersExplicitApprovedEurWithoutRepricingOrWrites() {
    fxFailure.set(new IllegalStateException("PRIVATE FX RESPONSE"));
    var error = failure(invoice(CommercialCurrency.COMMERCIAL_CURRENCY_USD));
    assertEquals("DEPENDENCY_UNAVAILABLE", error.getMessage());
    assertEquals("EUR", error.getDetails().get("offeredCurrency"));
    assertFalse(error.toString().contains("PRIVATE"));
    assertEquals(0, quoteCount());
    assertEquals("2000", plan.getPrice().getAmountMinor());
    assertEquals(
        2000, invoice(CommercialCurrency.COMMERCIAL_CURRENCY_EUR).block(WAIT).grossMinor());
  }
}
