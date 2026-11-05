package ru.taska.commercial;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.taska.api.b2b.v1.CommercialBuyerType;
import ru.taska.api.b2b.v1.CommercialCurrency;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CommercialMethod;
import ru.taska.api.b2b.v1.MerchantConfiguration;

/** Closed commercial DTO validation shared by its publisher and payment guard. No tax inference. */
public final class CommercialConfigurationRules {
  private CommercialConfigurationRules() {}

  private static final Set<String> COUNTRIES = Set.of(java.util.Locale.getISOCountries());

  private static void require(boolean ok) {
    if (!ok) throw new IllegalArgumentException("Invalid commercial configuration");
  }

  private static boolean text(String s, int max) {
    return !s.isBlank()
        && s.equals(s.strip())
        && s.codePointCount(0, s.length()) <= max
        && s.codePoints().noneMatch(Character::isISOControl);
  }

  private static boolean id(String s) {
    return s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
  }

  private static void known(Message m) {
    require(m.getUnknownFields().asMap().isEmpty());
    m.getAllFields()
        .forEach(
            (field, value) -> {
              for (Object item : field.isRepeated() ? (List<?>) value : List.of(value)) {
                if (item instanceof Message child) known(child);
                if (item instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e)
                  require(e.getIndex() >= 0);
              }
            });
  }

  public static void validate(MerchantConfiguration c) {
    known(c);
    require(c.getSchemaVersion() == 1);
    require(
        c.getEnvironment() == CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE
            || c.getEnvironment() == CommercialEnvironment.COMMERCIAL_ENVIRONMENT_SANDBOX);
    require(c.getMarketsCount() <= 100 && c.getTaxRulesCount() <= 100);
    Set<String> taxes = new HashSet<>(), markets = new HashSet<>();
    for (var t : c.getTaxRulesList()) {
      require(id(t.getVersion()) && taxes.add(t.getVersion()) && text(t.getLabel(), 120));
      require(t.getRate().matches("(0|1)(\\.[0-9]{1,10})?"));
      require(new BigDecimal(t.getRate()).compareTo(BigDecimal.ONE) <= 0);
    }
    for (var m : c.getMarketsList()) {
      require(
          COUNTRIES.contains(m.getCountry())
              && m.getBuyerType() != CommercialBuyerType.COMMERCIAL_BUYER_UNSPECIFIED
              && m.getBuyerType() != CommercialBuyerType.UNRECOGNIZED);
      require(
          m.getCurrency() == CommercialCurrency.COMMERCIAL_CURRENCY_EUR
              || m.getCurrency() == CommercialCurrency.COMMERCIAL_CURRENCY_USD);
      require(
          m.getMethod() == CommercialMethod.COMMERCIAL_METHOD_PSP
              || m.getMethod() == CommercialMethod.COMMERCIAL_METHOD_WIRE);
      require(
          m.getMethod() != CommercialMethod.COMMERCIAL_METHOD_WIRE
              || m.getCurrency() == CommercialCurrency.COMMERCIAL_CURRENCY_EUR);
      require(
          id(m.getTaxRuleVersion())
              && id(m.getDocumentTemplateVersion())
              && id(m.getRefundPolicyVersion()));
      require(
          markets.add(
              m.getCountry()
                  + ":"
                  + m.getBuyerType()
                  + ":"
                  + m.getCurrency()
                  + ":"
                  + m.getMethod()));
    }
    if (c.hasSeller()) {
      var s = c.getSeller();
      require(
          id(s.getProfileVersion())
              && text(s.getLegalName(), 200)
              && text(s.getRegistrationId(), 100)
              && text(s.getTaxId(), 100)
              && text(s.getAddressLine(), 300)
              && text(s.getPostalCode(), 32)
              && text(s.getCity(), 120)
              && COUNTRIES.contains(s.getCountry()));
    }
    if (c.hasStripe()) {
      var s = c.getStripe();
      String env =
          c.getEnvironment() == CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE
              ? "live"
              : "sandbox";
      require(s.getEnvironment() == c.getEnvironment() && id(s.getMerchantAccount()));
      require(
          s.getApiKeyReference().matches("secret://" + env + "/[A-Za-z0-9][A-Za-z0-9/_-]{0,199}")
              && s.getWebhookKeyReference()
                  .matches("secret://" + env + "/[A-Za-z0-9][A-Za-z0-9/_-]{0,199}"));
      require(!s.getApiKeyReference().equals(s.getWebhookKeyReference()));
    }
    if (c.hasBank()) {
      var b = c.getBank();
      require(
          text(b.getBeneficiary(), 200)
              && b.getIban().matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")
              && b.getBic().matches("[A-Z0-9]{8}([A-Z0-9]{3})?")
              && text(b.getBankName(), 200));
      require(
          b.getCalendarId().equals("WIRE_RETURN_ES_V1")
              && id(b.getCalendarVersion())
              && b.getCalendarDigest().matches("[0-9a-f]{64}"));
      var rearranged = b.getIban().substring(4) + b.getIban().substring(0, 4);
      var digits = new StringBuilder();
      for (char ch : rearranged.toCharArray())
        digits.append(
            ch >= 'A' && ch <= 'Z' ? Integer.toString(ch - 'A' + 10) : Character.toString(ch));
      require(
          new java.math.BigInteger(digits.toString())
                  .mod(java.math.BigInteger.valueOf(97))
                  .intValue()
              == 1);
      require(
          c.getEnvironment() != CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE
              || (!b.getFixtureCalendar() && b.getCalendarVerified()));
    }
    if (c.hasFx())
      require(
          id(c.getFx().getSource())
              && id(c.getFx().getSourceVersion())
              && c.getFx().getMaxAgeSeconds() > 0
              && c.getFx().getMaxAgeSeconds() <= 86400);
    if (c.getEnabled()) require(missing(c).isEmpty());
  }

  public static List<String> missing(MerchantConfiguration c) {
    var out = new ArrayList<String>();
    if (c.getEnvironment() == CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE && !c.hasSeller())
      out.add("SELLER_PROFILE");
    if (c.getMarketsCount() == 0) out.add("APPROVED_MARKETS");
    var taxes =
        c.getTaxRulesList().stream()
            .map(t -> t.getVersion())
            .collect(java.util.stream.Collectors.toSet());
    if (c.getMarketsList().stream().anyMatch(m -> !taxes.contains(m.getTaxRuleVersion())))
      out.add("TAX_RULE");
    if (c.getMarketsList().stream()
            .anyMatch(m -> m.getMethod() == CommercialMethod.COMMERCIAL_METHOD_PSP)
        && !c.hasStripe()) out.add("STRIPE_MERCHANT");
    if (c.getMarketsList().stream()
            .anyMatch(m -> m.getMethod() == CommercialMethod.COMMERCIAL_METHOD_WIRE)
        && !c.hasBank()) out.add("BANK_PROFILE");
    if (c.getMarketsList().stream()
            .anyMatch(m -> m.getCurrency() == CommercialCurrency.COMMERCIAL_CURRENCY_USD)
        && !c.hasFx()) out.add("FX_POLICY");
    return List.copyOf(out);
  }

  public static String json(MerchantConfiguration c) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(c);
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid commercial configuration");
    }
  }

  public static MerchantConfiguration parse(String value) {
    try {
      var out = MerchantConfiguration.newBuilder();
      JsonFormat.parser().merge(value, out);
      var c = out.build();
      validate(c);
      return c;
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalArgumentException("Invalid commercial configuration");
    }
  }
}
