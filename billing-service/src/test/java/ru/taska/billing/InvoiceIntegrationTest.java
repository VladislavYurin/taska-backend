package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.InvoiceDependencies.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class InvoiceIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final SubscriptionIntegrationTest f = new SubscriptionIntegrationTest();
  InvoiceStore invoices;
  BillingCatalog catalog;
  AtomicReference<Obligation> obligation;
  AtomicReference<CommercialQuote> quote;
  AtomicBoolean missingAssignment;

  @BeforeAll
  static void setupInvoices() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void closeInvoices() {
    SubscriptionIntegrationTest.close();
  }

  @BeforeEach
  void resetInvoices() {
    f.reset();
    f.init(A, PAID, "init").block(WAIT);
    f.init(B, PAID, "init-b").block(WAIT);
    catalog =
        new BillingCatalog(
            db, tx, new CatalogCursor(Base64.getEncoder().encodeToString(new byte[32]), f.clock));
    obligation =
        new AtomicReference<>(
            new Obligation(
                A,
                UUID.randomUUID(),
                PAID,
                Invoice.PurposeValue.PURPOSE_INITIAL,
                "first",
                null,
                null,
                NOW.plusSeconds(30 * 86400),
                null));
    quote = new AtomicReference<>(commercial("v1", 2000, Money.CurrencyValue.CURRENCY_EUR));
    missingAssignment = new AtomicBoolean();
    invoices =
        new InvoiceStore(
            f.transactions,
            f.readers,
            (db, t, p, now) -> missingAssignment.get() ? Mono.empty() : Mono.just(obligation.get()),
            (db, plan, p, now) -> Mono.just(quote.get()),
            catalog,
            f.clock,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), f.clock));
  }

  @AfterEach
  void noLeak() {
    f.scopeDoesNotLeak();
  }

  static BillingAddress address() {
    return BillingAddress.newBuilder()
        .setCountry("ES")
        .setPostalCode("28001")
        .setCity("Test city")
        .setLine1("Test address")
        .build();
  }

  static Money money(long n, Money.CurrencyValue c) {
    return Money.newBuilder()
        .setAmountMinor(Long.toString(n))
        .setCurrency(c)
        .setExponent(2)
        .build();
  }

  static CommercialQuote commercial(String version, long gross, Money.CurrencyValue currency) {
    return new CommercialQuote(
        version,
        SellerSnapshot.newBuilder()
            .setLegalName("Test seller " + version)
            .setRegistrationId("TEST-ONLY")
            .setAddress(address())
            .build(),
        List.of(
            InvoiceLine.newBuilder()
                .setDescription("Monthly company subscription")
                .setQuantity(1)
                .setNet(money(gross - 200, currency))
                .setTotal(money(gross, currency))
                .addTaxes(
                    TaxLine.newBuilder()
                        .setLabel("Test inclusive tax")
                        .setRate("10")
                        .setAmount(money(200, currency)))
                .build()),
        null,
        null,
        Duration.ofDays(7));
  }

  CreateInvoiceRequest payload() {
    return CreateInvoiceRequest.newBuilder()
        .setPlanVersionId(PAID.toString())
        .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_INITIAL)
        .setCurrency(CreateInvoiceRequest.CurrencyValue.CURRENCY_EUR)
        .setPaymentMethod(CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_PSP)
        .setBuyer(
            BuyerDetails.newBuilder()
                .setKind(BuyerDetails.KindValue.KIND_CONSUMER)
                .setName("Test buyer")
                .setEmail("invoice@example.test")
                .setAddress(address()))
        .build();
  }

  CreateInvoiceRpcRequest request(String key, CreateInvoiceRequest p) {
    return CreateInvoiceRpcRequest.newBuilder()
        .setTenantId(A.toString())
        .setPayload(p)
        .setMutation(MutationOptions.newBuilder().setIdempotencyKey(key))
        .build();
  }

  TrustedTenantContext human(String action) {
    return f.human(OWNER_A, A, action).toBuilder().addScopes("write").build();
  }

  <T> Mono<T> as(String action, Mono<T> work) {
    return RegistryTestContext.withClaims(human(action), work);
  }

  Mono<Invoice> create(String key) {
    return as("createInvoice", invoices.create(request(key, payload())));
  }

  Invoice get(String id) {
    return as(
            "getInvoice",
            invoices.get(
                GetInvoiceRpcRequest.newBuilder()
                    .setTenantId(A.toString())
                    .setInvoiceId(id)
                    .build()))
        .block(WAIT);
  }

  InvoicePage list(int size, String token) {
    var p = PageRequest.newBuilder().setLimit(size);
    if (token != null) p.setCursor(token);
    return as(
            "listInvoices",
            invoices.list(
                ListInvoicesRpcRequest.newBuilder().setTenantId(A.toString()).setPage(p).build()))
        .block(WAIT);
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  void rejects(DomainStatus status, String code, Mono<?> work) {
    var e = assertThrows(DomainException.class, () -> work.block(WAIT));
    assertEquals(status, e.getStatus());
    assertEquals(code, e.getMessage());
  }

  void nextTarget(String target) {
    obligation.set(
        new Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_INITIAL,
            target,
            null,
            null,
            NOW.plusSeconds(30 * 86400),
            null));
  }

  @Test
  void concurrentWorkersAndRepeatedCommandsIssueExactlyOneInvoice() {
    var results =
        Flux.range(0, 12)
            .flatMap(i -> create("worker-" + i).subscribeOn(Schedulers.parallel()), 6)
            .collectList()
            .block(WAIT);
    Invoice first = results.getFirst();
    assertTrue(results.stream().allMatch(first::equals));
    assertEquals(first, create("worker-0").block(WAIT));
    assertEquals(first, get(first.getId()));
    assertEquals(1, count("invoices"));
    assertEquals(1, count("invoice_lines"));
    assertEquals(12, count("invoice_command_receipts"));
    assertEquals(5, count("outbox_events"));
    assertEquals(Invoice.StateValue.STATE_OPEN, first.getState());
    assertEquals("0", first.getAllocated().getAmountMinor());
    assertEquals("2000", first.getRemaining().getAmountMinor());
    assertEquals(0, count("subscription_periods"));
    assertEquals("1", f.get(OWNER_A, A).getVersion());
    var before = count("invoice_command_receipts");
    rejects(
        DomainStatus.ALREADY_EXISTS,
        "IDEMPOTENCY_CONFLICT",
        as(
            "createInvoice",
            invoices.create(
                request(
                    "worker-0",
                    payload().toBuilder()
                        .setBuyer(payload().getBuyer().toBuilder().setName("Changed"))
                        .build()))));
    assertEquals(before, count("invoice_command_receipts"));
    assertEquals(1, count("invoices"));
    // Switching payment transport addresses the same obligation; it never opens a second bill.
    var wire =
        payload().toBuilder()
            .setPaymentMethod(CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE)
            .build();
    assertEquals(first, as("createInvoice", invoices.create(request("wire", wire))).block(WAIT));
  }

  @Test
  void businessUniquenessDoesNotDependOnWorkerOperationId() {
    Invoice first = create("first").block(WAIT);
    var old = obligation.get();
    obligation.set(
        new Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            old.purpose(),
            old.targetKey(),
            null,
            null,
            old.creationDeadline(),
            null));
    assertEquals(first, create("second-worker").block(WAIT));
    assertEquals(1, count("invoices"));
  }

  @Test
  void issuedSnapshotsSurviveCatalogBuyerAndCommercialChangesAndExpiry() {
    Invoice first = create("first").block(WAIT);
    quote.set(commercial("v2", 2000, Money.CurrencyValue.CURRENCY_EUR));
    f.time.set(NOW.plusSeconds(8 * 86400));
    assertEquals(first, get(first.getId()));
    assertEquals(first, create("first").block(WAIT));
    assertEquals(first, create("new-key").block(WAIT));
    sql(
        "INSERT INTO taska.catalog_plan_versions SELECT '"
            + UUID.randomUUID()
            + "',plan_code,2,'Updated"
            + " paid',4000,currency,currency_exponent,period,max_projects,max_members,max_storage_bytes,max_tasks_per_project,max_attachment_bytes,features,now(),now()"
            + " FROM taska.catalog_plan_versions WHERE id='"
            + PAID
            + "'");
    assertEquals(first, get(first.getId()));
    assertEquals(first, list(50, null).getItems(0));
    assertEquals("Test seller v1", first.getSeller().getLegalName());
    assertEquals(
        Invoice.StateValue.STATE_OPEN,
        get(first.getId()).getState()); // elapsed time is not a payment/expiry command
  }

  @Test
  void denialRechecksCurrentOwnerAndScopesBeforeReceiptReplay() {
    Invoice first = create("one").block(WAIT);
    var get =
        GetInvoiceRpcRequest.newBuilder()
            .setTenantId(A.toString())
            .setInvoiceId(first.getId())
            .build();
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        RegistryTestContext.withClaims(f.human(OTHER, A, "getInvoice"), invoices.get(get)));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "TENANT_MISMATCH",
        RegistryTestContext.withClaims(f.human(OWNER_B, B, "getInvoice"), invoices.get(get)));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(
            f.human(OWNER_A, A, "createInvoice"), invoices.create(request("one", payload()))));
    f.owners.put(A.toString(), OTHER.toString());
    f.active.add(OTHER.toString());
    rejects(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED", create("one"));
    rejects(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED", as("getInvoice", invoices.get(get)));
    var readOnly =
        human("createInvoice").toBuilder()
            .setActorUserId(ADMIN.toString())
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
            .setAdministrativeScope(
                AdminScope.newBuilder().setKind(AdminScope.KindValue.KIND_PLATFORM))
            .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ)
            .build();
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(readOnly, invoices.create(request("admin", payload()))));
    var modify =
        readOnly.toBuilder().setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_MODIFY).build();
    assertEquals(
        first,
        RegistryTestContext.withClaims(modify, invoices.create(request("admin", payload())))
            .block(WAIT));
    var readAdmin = modify.toBuilder().setAction("getInvoice").build();
    assertEquals(first, RegistryTestContext.withClaims(readAdmin, invoices.get(get)).block(WAIT));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(
            readAdmin.toBuilder()
                .setAdministrativeScope(
                    AdminScope.newBuilder()
                        .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                        .addTenantIds(B.toString()))
                .build(),
            invoices.get(get)));
    assertEquals(1, count("invoices"));
    assertEquals(0, count("subscription_periods"));
  }

  @Test
  void transactionFailuresLeaveNoHeaderLinesReceiptOrFinancialEffect() {
    long events = count("outbox_events");
    sql(
        "ALTER TABLE taska.outbox_events ADD CONSTRAINT fail_invoice_test"
            + " CHECK(event_type<>'InvoiceIssued')");
    try {
      assertThrows(Exception.class, () -> create("rollback").block(WAIT));
    } finally {
      sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT fail_invoice_test");
    }
    assertEquals(0, count("invoices"));
    assertEquals(0, count("invoice_lines"));
    assertEquals(0, count("invoice_command_receipts"));
    assertEquals(events, count("outbox_events"));
    assertEquals(0, count("subscription_periods"));
    assertEquals("1", f.get(OWNER_A, A).getVersion());
    assertNotNull(create("rollback").block(WAIT));
  }

  @Test
  void validatesExactMinorUnitsTotalsAndProvisionedFx() {
    var q = quote.get();
    var line = q.lines().getFirst();
    for (String amount : List.of("1.5", "-1", "02000", "9223372036854775808")) {
      quote.set(
          new CommercialQuote(
              "bad",
              q.seller(),
              List.of(
                  line.toBuilder()
                      .setTotal(line.getTotal().toBuilder().setAmountMinor(amount))
                      .build()),
              null,
              null,
              q.validity()));
      rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", create("bad-money"));
    }
    quote.set(
        new CommercialQuote(
            "bad",
            q.seller(),
            List.of(
                line.toBuilder().setTotal(money(1999, Money.CurrencyValue.CURRENCY_EUR)).build()),
            null,
            null,
            q.validity()));
    rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", create("bad-total"));
    var usd =
        payload().toBuilder().setCurrency(CreateInvoiceRequest.CurrencyValue.CURRENCY_USD).build();
    quote.set(commercial("usd", 2400, Money.CurrencyValue.CURRENCY_USD));
    rejects(
        DomainStatus.UNAVAILABLE,
        "POLICY_NOT_CONFIGURED",
        as("createInvoice", invoices.create(request("usd", usd))));
    var fx =
        FxQuote.newBuilder()
            .setBaseCurrency(FxQuote.BaseCurrencyValue.BASE_CURRENCY_EUR)
            .setQuoteCurrency(FxQuote.QuoteCurrencyValue.QUOTE_CURRENCY_USD)
            .setRate("1.2")
            .setSource("test-approved")
            .setObservedAt(NOW.minusSeconds(60).toString())
            .setExpiresAt(NOW.toString())
            .build();
    var u = quote.get();
    quote.set(
        new CommercialQuote(u.profileVersion(), u.seller(), u.lines(), fx, null, u.validity()));
    rejects(
        DomainStatus.UNAVAILABLE,
        "POLICY_NOT_CONFIGURED",
        as("createInvoice", invoices.create(request("usd", usd))));
    quote.set(
        new CommercialQuote(
            u.profileVersion(),
            u.seller(),
            u.lines(),
            fx.toBuilder().setExpiresAt(NOW.plusSeconds(60).toString()).build(),
            null,
            u.validity()));
    Invoice result = as("createInvoice", invoices.create(request("usd", usd))).block(WAIT);
    assertEquals("2400", result.getTotal().getAmountMinor());
    assertTrue(result.hasFxQuote());
    assertEquals(1, count("invoices"));
    assertEquals(0, count("subscription_periods"));
  }

  @Test
  void missingPoliciesAndBadAssignmentsFailClosed() {
    missingAssignment.set(true);
    rejects(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE", create("missing"));
    missingAssignment.set(false);
    obligation.set(
        new Obligation(
            B,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_INITIAL,
            "other",
            null,
            null,
            null,
            null));
    rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", create("other"));
    invoices =
        new BillingConfiguration()
            .invoices(
                pool,
                f.readers,
                new BillingConfiguration()
                    .invoiceObligationAuthority(
                        (context, command) ->
                            Mono.error(
                                CatalogErrors.fail(
                                    DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"))),
                new BillingConfiguration().invoiceCommercialPolicy(),
                catalog,
                Base64.getEncoder().encodeToString(new byte[32]),
                new BillingConfiguration().renewalAuthority());
    rejects(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", create("offline"));
    assertEquals(0, count("invoices"));
    assertEquals(0, count("invoice_lines"));
  }

  @Test
  void snapshotsAndLinesAreSealedAndTenantForeignKeysAreEnforced() {
    Invoice first = create("one").block(WAIT);
    UUID id = UUID.fromString(first.getId());
    for (String update :
        List.of(
            "UPDATE taska.invoices SET total_minor=3000 WHERE id='" + id + "'",
            "UPDATE taska.invoices SET state='PAID' WHERE id='" + id + "'",
            "DELETE FROM taska.invoices WHERE id='" + id + "'",
            "DELETE FROM taska.invoice_lines WHERE invoice_id='" + id + "'",
            "UPDATE taska.invoice_lines SET description='Changed' WHERE invoice_id='" + id + "'",
            "INSERT INTO taska.invoice_lines SELECT"
                + " tenant_id,invoice_id,2,description,quantity,net_minor,tax_minor,total_minor,taxes_snapshot,snapshot"
                + " FROM taska.invoice_lines WHERE invoice_id='"
                + id
                + "'")) assertThrows(Exception.class, () -> sql(update));
    assertEquals(first, get(first.getId()));
    var b = f.human(OWNER_B, B, "getInvoice");
    assertEquals(
        0L,
        RegistryTestContext.withClaims(
                b,
                f.transactions.execute(
                    db ->
                        db.sql("SELECT count(*) n FROM taska.invoices WHERE id=:id")
                            .bind("id", id)
                            .map((r, m) -> r.get("n", Long.class))
                            .one()))
            .block(WAIT));
    assertEquals(
        0L,
        RegistryTestContext.withClaims(
                b,
                f.transactions.execute(
                    db ->
                        db.sql("SELECT count(*) n FROM taska.invoice_lines WHERE invoice_id=:id")
                            .bind("id", id)
                            .map((r, m) -> r.get("n", Long.class))
                            .one()))
            .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            sql(
                "INSERT INTO"
                    + " taska.invoice_lines(tenant_id,invoice_id,line_no,description,quantity,net_minor,tax_minor,total_minor,taxes_snapshot,snapshot)"
                    + " VALUES('"
                    + B
                    + "','"
                    + id
                    + "',1,'Foreign',1,0,0,0,'[]','')"));
    assertEquals(1, count("invoice_lines"));
  }

  @Test
  void paginationBindsCurrentCredentialAndKeepsEmptyEndPage() {
    for (int i = 0; i < 3; i++) {
      nextTarget("target-" + i);
      create("key-" + i).block(WAIT);
    }
    var first = list(1, null);
    var second = list(1, first.getNextCursor());
    var third = list(1, second.getNextCursor());
    assertTrue(first.hasNextCursor());
    assertTrue(second.hasNextCursor());
    assertFalse(third.hasNextCursor());
    assertEquals(NOW.toString(), first.getItems(0).getIssuedAt());
    assertEquals(first.getItems(0).getIssuedAt(), second.getItems(0).getIssuedAt());
    assertEquals(second.getItems(0).getIssuedAt(), third.getItems(0).getIssuedAt());
    assertEquals(
        3,
        Set.of(first.getItems(0).getId(), second.getItems(0).getId(), third.getItems(0).getId())
            .size());
    var req =
        ListInvoicesRpcRequest.newBuilder()
            .setTenantId(A.toString())
            .setPage(PageRequest.newBuilder().setLimit(1).setCursor(first.getNextCursor()))
            .build();
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        RegistryTestContext.withClaims(
            human("listInvoices").toBuilder().setSessionId(UUID.randomUUID().toString()).build(),
            invoices.list(req)));
    f.time.set(NOW.plusSeconds(901));
    rejects(
        DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", as("listInvoices", invoices.list(req)));
    assertEquals(
        0,
        RegistryTestContext.withClaims(
                f.human(OWNER_B, B, "listInvoices"),
                invoices.list(
                    req.toBuilder()
                        .setTenantId(B.toString())
                        .setPage(PageRequest.getDefaultInstance())
                        .build()))
            .block(WAIT)
            .getItemsCount());
  }

  @Test
  void realMtlsInvoiceRpcsBindPayloadAndRecheckCurrentAuthority(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path certs) throws Exception {
    BillingCatalogIntegrationTest.openssl(
        certs,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt",
        "-days",
        "1",
        "-subj",
        "/CN=tenant-test-ca");
    for (String name : List.of("api-gateway", "billing-service")) {
      BillingCatalogIntegrationTest.openssl(
          certs,
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr",
          "-subj",
          "/CN=localhost");
      java.nio.file.Files.writeString(
          certs.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      BillingCatalogIntegrationTest.openssl(
          certs,
          "x509",
          "-req",
          "-in",
          name + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-out",
          name + ".crt",
          "-days",
          "1",
          "-extfile",
          name + ".ext");
    }

    var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var keys =
        new ru.taska.security.tenant.RpcContextSecurity.Keys() {
          public ru.taska.security.tenant.RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new ru.taska.security.tenant.RpcContextSecurity.SigningKey(
                "fixture", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return issuer.equals("api-gateway") && kid.equals("fixture") ? pair.getPublic() : null;
          }
        };
    var consumed = ConcurrentHashMap.<String>newKeySet();
    ru.taska.security.tenant.RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + ":" + jti));
    var online = new AtomicBoolean(true);
    ru.taska.security.tenant.RpcContextSecurity.Authority authority =
        (ctx, rpc, policy) ->
            Mono.just(online.get() && ctx.getContext().getIssuerService().equals("api-gateway"));
    var sender =
        new ru.taska.security.tenant.RpcContextSecurity(
            "api-gateway", Clock.systemUTC(), keys, replay, authority);
    var receiver =
        new ru.taska.security.tenant.RpcContextSecurity(
            "billing-service", Clock.systemUTC(), keys, replay, authority);
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("security", receiver);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(
                beans.getBeanProvider(ru.taska.security.tenant.RpcContextSecurity.class), "test");
    var server =
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(
                        certs.resolve("billing-service.crt").toFile(),
                        certs.resolve("billing-service.key").toFile())
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
                    .build())
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new BillingCatalogRpc(catalog, f.store, invoices), interceptor))
            .build()
            .start();
    var channel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("api-gateway.crt").toFile(),
                        certs.resolve("api-gateway.key").toFile())
                    .build())
            .build();

    try {
      var plain =
          BillingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
      var create = request("rpc", payload()).toBuilder().setContext(wire("createInvoice")).build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> plain.createInvoice(create))
              .getStatus()
              .getCode());
      var signed =
          BillingCatalogIntegrationTest.signed(
              sender,
              create,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(true, true),
              BillingServiceGrpc.getCreateInvoiceMethod().getFullMethodName());
      var writer =
          plain.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(signed));
      var invoice = writer.createInvoice(create);
      assertEquals(Invoice.StateValue.STATE_OPEN, invoice.getState());
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> writer.createInvoice(create))
              .getStatus()
              .getCode());
      var retry = create.toBuilder().setContext(wire("createInvoice")).build();
      var retrySigned =
          BillingCatalogIntegrationTest.signed(
              sender,
              retry,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(true, true),
              BillingServiceGrpc.getCreateInvoiceMethod().getFullMethodName());
      assertEquals(
          invoice,
          plain
              .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(retrySigned))
              .createInvoice(retry));
      var get =
          GetInvoiceRpcRequest.newBuilder()
              .setTenantId(A.toString())
              .setInvoiceId(invoice.getId())
              .setContext(wire("getInvoice"))
              .build();
      var reader =
          plain.withInterceptors(
              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                  BillingCatalogIntegrationTest.signed(
                      sender,
                      get,
                      new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false),
                      BillingServiceGrpc.getGetInvoiceMethod().getFullMethodName())));
      assertEquals(invoice, reader.getInvoice(get));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> reader.getInvoice(get.toBuilder().setTenantId(B.toString()).build()))
              .getStatus()
              .getCode());
      var list =
          ListInvoicesRpcRequest.newBuilder()
              .setTenantId(A.toString())
              .setContext(wire("listInvoices"))
              .build();
      assertEquals(
          List.of(invoice),
          plain
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      BillingCatalogIntegrationTest.signed(
                          sender,
                          list,
                          new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false),
                          BillingServiceGrpc.getListInvoicesMethod().getFullMethodName())))
              .listInvoices(list)
              .getItemsList());
      f.active.remove(OWNER_A.toString());
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> reader.getInvoice(get))
              .getStatus()
              .getCode());
      f.active.add(OWNER_A.toString());
      online.set(false);
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> reader.getInvoice(get))
              .getStatus()
              .getCode());
      assertEquals(1, count("invoices"));
      assertEquals(0, count("subscription_periods"));
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private RequestContext wire(String action) {
    Instant now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            human(action).toBuilder()
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }

  @Test
  void wireUsesServerOwnedPeriodAndDeadlineWithoutCoverage() {
    Instant start = NOW.plusSeconds(86400),
        end = start.plusSeconds(28 * 86400),
        deadline = NOW.plusSeconds(2 * 86400);
    obligation.set(
        new Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_RENEWAL,
            "renewal/period-1",
            start,
            end,
            deadline,
            null));
    var p =
        payload().toBuilder()
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
            .setPaymentMethod(CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE)
            .build();
    var q = quote.get();
    quote.set(
        new CommercialQuote(
            q.profileVersion(),
            q.seller(),
            q.lines(),
            null,
            WireInstructions.newBuilder()
                .setBeneficiaryName("Test seller")
                .setIban("ES0000000000000000000000")
                .setBic("TESTESMM")
                .setBankName("Test bank")
                .setReference("SERVER-REFERENCE")
                .build(),
            q.validity()));
    Invoice invoice = as("createInvoice", invoices.create(request("wire-period", p))).block(WAIT);
    assertTrue(invoice.hasWireInstructions());
    assertEquals(deadline.toString(), invoice.getDueAt());
    var row =
        operator
            .sql(
                "SELECT period_starts_at,period_ends_at,creation_deadline_snapshot FROM"
                    + " taska.invoices WHERE id=:id")
            .bind("id", UUID.fromString(invoice.getId()))
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(start, ((OffsetDateTime) row.get("period_starts_at")).toInstant());
    assertEquals(end, ((OffsetDateTime) row.get("period_ends_at")).toInstant());
    assertEquals(0, count("subscription_periods"));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        as(
            "createInvoice",
            invoices.create(
                request(
                    "usd-wire",
                    p.toBuilder()
                        .setCurrency(CreateInvoiceRequest.CurrencyValue.CURRENCY_USD)
                        .build()))));
    assertEquals(1, count("invoices"));
  }

  @Test
  void incompleteDraftCannotCommitAndAbsentCommercialProfileCannotIssue() {
    UUID id = UUID.randomUUID();
    assertThrows(
        Exception.class,
        () ->
            sql(
                "INSERT INTO"
                    + " taska.invoices(id,tenant_id,number,operation_id,target_key,purpose,plan_version_id,method,currency,currency_exponent,subtotal_minor,tax_minor,total_minor,issued_at,payable_until,terms_snapshot,pricing_snapshot,seller_snapshot,buyer_snapshot,snapshot)"
                    + " VALUES('"
                    + id
                    + "','"
                    + A
                    + "','INCOMPLETE','"
                    + UUID.randomUUID()
                    + "','incomplete','INITIAL','"
                    + PAID
                    + "','PSP','EUR',2,2000,0,2000,now(),now()+interval '7"
                    + " days','{}','{}','{}','{}','')"));
    assertEquals(0, count("invoices"));
    invoices =
        new InvoiceStore(
            f.transactions,
            f.readers,
            (db, t, p, now) -> Mono.just(obligation.get()),
            new BillingConfiguration().invoiceCommercialPolicy(),
            catalog,
            f.clock,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), f.clock));
    rejects(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED", create("no-profile"));
    assertEquals(0, count("invoices"));
    assertEquals(0, count("invoice_lines"));
    assertEquals(0, count("invoice_command_receipts"));
  }

  @Test
  void sameKeyChangedPaymentMethodOrCurrencyAlwaysConflicts() {
    Invoice first = create("fixed-key").block(WAIT);
    for (var changed :
        List.of(
            payload().toBuilder()
                .setPaymentMethod(CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE)
                .build(),
            payload().toBuilder()
                .setCurrency(CreateInvoiceRequest.CurrencyValue.CURRENCY_USD)
                .build())) {
      rejects(
          DomainStatus.ALREADY_EXISTS,
          "IDEMPOTENCY_CONFLICT",
          as("createInvoice", invoices.create(request("fixed-key", changed))));
    }
    assertEquals(1, count("invoices"));
    assertEquals(1, count("invoice_lines"));
    assertEquals(1, count("invoice_command_receipts"));
    assertEquals(first, get(first.getId()));
  }

  @Test
  void buyerAndCommercialFieldLimitsFollowBlueprint() {
    var original = payload();
    for (var invalidAddress :
        List.of(
            address().toBuilder().setLine1("a".repeat(201)).build(),
            address().toBuilder().setLine2("b".repeat(201)).build(),
            address().toBuilder().setPostalCode("1".repeat(33)).build())) {
      var bad =
          original.toBuilder()
              .setBuyer(original.getBuyer().toBuilder().setAddress(invalidAddress))
              .build();
      rejects(
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED",
          as("createInvoice", invoices.create(request("limits", bad))));
    }
    var q = quote.get();
    quote.set(
        new CommercialQuote(
            q.profileVersion(),
            q.seller(),
            List.of(
                q.lines().getFirst().toBuilder()
                    .setTaxes(
                        0, q.lines().getFirst().getTaxes(0).toBuilder().setLabel("t".repeat(101)))
                    .build()),
            null,
            null,
            q.validity()));
    rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", create("bad-label"));
    quote.set(
        new CommercialQuote(
            q.profileVersion(),
            q.seller().toBuilder().setTaxId("t".repeat(100)).build(),
            List.of(
                q.lines().getFirst().toBuilder()
                    .setTaxes(
                        0, q.lines().getFirst().getTaxes(0).toBuilder().setRate("10.12345678"))
                    .build()),
            null,
            null,
            q.validity()));
    var valid =
        original.toBuilder()
            .setBuyer(
                original.getBuyer().toBuilder()
                    .setAddress(
                        address().toBuilder()
                            .setPostalCode("1".repeat(32))
                            .setLine1("a".repeat(200))
                            .setLine2("b".repeat(200))))
            .build();
    assertEquals(
        "1".repeat(32),
        as("createInvoice", invoices.create(request("limits", valid)))
            .block(WAIT)
            .getBuyer()
            .getAddress()
            .getPostalCode());
  }
}
