package ru.taska.billing;

import java.util.List;
import ru.taska.api.b2b.v1.CommercialBank;
import ru.taska.api.b2b.v1.CommercialBuyerType;
import ru.taska.api.b2b.v1.CommercialCurrency;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CommercialFxPolicy;
import ru.taska.api.b2b.v1.CommercialMarket;
import ru.taska.api.b2b.v1.CommercialMethod;
import ru.taska.api.b2b.v1.CommercialSeller;
import ru.taska.api.b2b.v1.CommercialStripe;
import ru.taska.api.b2b.v1.CommercialTaxRule;
import ru.taska.api.b2b.v1.MerchantConfiguration;

/** Synthetic private fixtures; never operator/live configuration. */
final class CommercialFixtures {
  static MerchantConfiguration profile() {
    return MerchantConfiguration.newBuilder()
        .setSchemaVersion(1)
        .setEnabled(true)
        .setEnvironment(CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE)
        .setSeller(
            CommercialSeller.newBuilder()
                .setProfileVersion("seller-v1")
                .setLegalName("TEST ONLY seller")
                .setRegistrationId("TEST-REG")
                .setTaxId("TEST-TAX")
                .setAddressLine("TEST ONLY address")
                .setPostalCode("28001")
                .setCity("Madrid")
                .setCountry("ES"))
        .addAllMarkets(
            List.of(
                market(
                    CommercialCurrency.COMMERCIAL_CURRENCY_EUR,
                    CommercialMethod.COMMERCIAL_METHOD_PSP),
                market(
                    CommercialCurrency.COMMERCIAL_CURRENCY_USD,
                    CommercialMethod.COMMERCIAL_METHOD_PSP),
                market(
                    CommercialCurrency.COMMERCIAL_CURRENCY_EUR,
                    CommercialMethod.COMMERCIAL_METHOD_WIRE)))
        .addTaxRules(
            CommercialTaxRule.newBuilder()
                .setVersion("tax-v1")
                .setLabel("TEST TAX")
                .setRate("0.21"))
        .setStripe(
            CommercialStripe.newBuilder()
                .setEnvironment(CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE)
                .setMerchantAccount("TEST-MERCHANT")
                .setApiKeyReference("secret://live/TEST-ONLY/api-key")
                .setWebhookKeyReference("secret://live/TEST-ONLY/webhook-key"))
        .setBank(
            CommercialBank.newBuilder()
                .setBeneficiary("TEST ONLY")
                .setIban("ES9121000418450200051332")
                .setBic("CAIXESBBXXX")
                .setBankName("TEST ONLY bank")
                .setCalendarId("WIRE_RETURN_ES_V1")
                .setCalendarVersion("test-v1")
                .setCalendarDigest("a".repeat(64))
                .setCalendarVerified(true))
        .setFx(
            CommercialFxPolicy.newBuilder()
                .setSource("TEST-FX")
                .setSourceVersion("v1")
                .setMaxAgeSeconds(60))
        .build();
  }

  static CommercialMarket market(CommercialCurrency currency, CommercialMethod method) {
    return CommercialMarket.newBuilder()
        .setCountry("ES")
        .setBuyerType(CommercialBuyerType.COMMERCIAL_BUYER_BUSINESS)
        .setCurrency(currency)
        .setMethod(method)
        .setTaxRuleVersion("tax-v1")
        .setDocumentTemplateVersion("doc-v1")
        .setRefundPolicyVersion("refund-v1")
        .build();
  }
}
