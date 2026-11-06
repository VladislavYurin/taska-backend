package ru.taska.billing.ingress;

import static ru.taska.billing.ingress.ProviderWebhookRegistry.failure;

import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Admission authenticates raw bytes; processing happens after the durable receipt commits. */
public final class ProviderInbox {
  public interface SettlementDelivery {
    Mono<Void> apply(UUID tenant, UUID evidence);
  }

  private final ProviderInboxStore store;
  private final ProviderWebhookRegistry registry;
  private final SettlementDelivery settlement;
  private final int maxBody, perMinute;

  public ProviderInbox(
      ProviderInboxStore store,
      ProviderWebhookRegistry registry,
      SettlementDelivery settlement,
      int maxBody,
      int perMinute) {
    if (maxBody < 1 || maxBody > 262144 || perMinute < 1 || perMinute > 10000)
      throw new IllegalArgumentException("Bounded ingress limits required");
    this.store = store;
    this.registry = registry;
    this.settlement = settlement;
    this.maxBody = maxBody;
    this.perMinute = perMinute;
  }

  private Mono<VerifiedTenantContext> service(
      UUID tenant, String action, String issuer, String scope) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> failure(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            c -> {
              var v = RpcContextBridge.details(c).getContext();
              if (!java.util.Objects.equals(c.tenantId(), tenant == null ? null : tenant.toString())
                  || c.actorId() != null
                  || !action.equals(c.action())
                  || v.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !issuer.equals(v.getIssuerService())
                  || !c.scopes().contains("write")
                  || !c.scopes().contains(scope))
                throw failure(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return c;
            });
  }

  public Mono<ProviderInboxStore.Receipt> receive(
      ProviderWebhookRegistry.Identity identity, byte[] body, String signature) {
    return service(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest")
        .flatMap(
            c ->
                Mono.defer(
                    () -> {
                      if (body == null
                          || body.length == 0
                          || body.length > maxBody
                          || signature == null
                          || signature.isBlank()
                          || signature.length() > 8192)
                        return Mono.error(
                            failure(DomainStatus.INVALID_ARGUMENT, "WEBHOOK_LIMIT_EXCEEDED"));
                      byte[] raw = body.clone();
                      registry.require(identity);
                      // No PSP business flag here: previously accepted payments continue to deliver
                      // callbacks.
                      return store
                          .admit(identity, perMinute)
                          .then(
                              registry
                                  .verify(identity, raw, signature)
                                  .timeout(Duration.ofSeconds(5))
                                  .onErrorMap(
                                      error ->
                                          !(error instanceof ru.taska.exception.DomainException),
                                      error ->
                                          failure(
                                              DomainStatus.UNAVAILABLE,
                                              "PROVIDER_VERIFICATION_UNAVAILABLE")))
                          .flatMap(observation -> store.accept(identity, observation, raw));
                    }));
  }

  public Mono<Void> bind(
      UUID tenant, UUID invoice, ProviderWebhookRegistry.Identity identity, String payment) {
    return service(tenant, "registerProviderPayment", "billing-service", "inbox:map")
        .flatMap(
            c ->
                Mono.defer(() -> store.bind(tenant, invoice, registry.require(identity), payment)));
  }

  public Mono<ProviderInboxStore.Receipt> process(UUID tenant, UUID receipt) {
    return service(tenant, "processProviderWebhook", "billing-service", "inbox:process")
        .flatMap(c -> store.prepare(tenant, receipt))
        .flatMap(
            r ->
                !r.state().equals("VERIFIED")
                    ? Mono.just(r)
                    : settlement
                        .apply(tenant, r.evidenceId())
                        .then(store.applied(tenant, receipt)));
  }
}
