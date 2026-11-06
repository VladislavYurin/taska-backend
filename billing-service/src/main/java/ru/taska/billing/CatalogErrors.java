package ru.taska.billing;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

final class CatalogErrors {
  private CatalogErrors() {}

  static DomainException fail(DomainStatus status, String code) {
    return new DomainException(status, code, java.util.Map.of("code", code));
  }

  static DomainException invalid() {
    return fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
  }
}
