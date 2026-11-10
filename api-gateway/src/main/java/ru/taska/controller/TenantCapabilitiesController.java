package ru.taska.controller;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.TenantCapabilitiesApi;
import ru.taska.domain.dto.*;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.TenantCapabilitiesClient;

@RestController
public class TenantCapabilitiesController implements TenantCapabilitiesApi {
  private final GatewayRequestExecutor executor;
  private final TenantCapabilitiesClient client;

  public TenantCapabilitiesController(
      GatewayRequestExecutor executor, TenantCapabilitiesClient client) {
    this.executor = executor;
    this.client = client;
  }

  @Override
  public Mono<ResponseEntity<TenantCapabilitiesDto>> getTenantCapabilities(
      UUID tenantId, ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("private, no-store");
    return executor.execute(
        exchange,
        ru.taska.domain.EndpointSecurity.PROTECTED,
        context ->
            client
                .get(tenantId, context)
                .map(
                    result -> {
                      var dto =
                          new TenantCapabilitiesDto()
                              .tenantId(UUID.fromString(result.getTenantId()))
                              .actorUserId(UUID.fromString(result.getActorUserId()))
                              .policyVersion(result.getPolicyVersion())
                              .entitlementVersion(result.getEntitlementVersion())
                              .membershipVersion(result.getMembershipVersion())
                              .flagVersion(result.getFlagVersion())
                              .evaluatedAt(OffsetDateTime.parse(result.getEvaluatedAt()))
                              .effectiveAt(OffsetDateTime.parse(result.getEffectiveAt()))
                              .expiresAt(OffsetDateTime.parse(result.getExpiresAt()));
                      result
                          .getItemsList()
                          .forEach(
                              item ->
                                  dto.addItemsItem(
                                      new TenantCapabilityDto()
                                          .action(CapabilityAction.fromValue(item.getAction()))
                                          .allowed(item.getAllowed())
                                          .denialCodes(
                                              item.getDenialCodesList().stream()
                                                  .map(
                                                      e ->
                                                          CapabilityDenialCode.fromValue(
                                                              e.name().replace("ERROR_CODE_", "")))
                                                  .toList())
                                          .restrictions(
                                              item.getRestrictionsList().stream()
                                                  .map(
                                                      e ->
                                                          CapabilityRestrictionCode.fromValue(
                                                              e.name()
                                                                  .replace(
                                                                      "RESTRICTION_CODE_", "")))
                                                  .toList())));
                      result.getDurationsSecondsMap().forEach(dto::putDurationsSecondsItem);
                      return ResponseEntity.ok()
                          .cacheControl(CacheControl.noStore().cachePrivate())
                          .body(dto);
                    }));
  }
}
