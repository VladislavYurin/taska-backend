package ru.taska.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.r2dbc.spi.R2dbcException;
import org.junit.jupiter.api.Test;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

class ProjectCleanupFailuresTest {
  @Test
  void onlyTheProjectFenceBecomesHiddenNotFoundAndRetainsSqlCause() {
    var sql = new R2dbcException("PROJECT_DELETING", "23514", 0) {};
    var outer = new IllegalStateException("Техническая обёртка", sql);
    var mapped = assertInstanceOf(DomainException.class, ProjectCleanupFailures.map(outer));
    assertEquals(DomainStatus.NOT_FOUND, mapped.getStatus());
    assertSame(outer, mapped.getCause());
    var foreign = new R2dbcException("RLS отказ", "42501", 0) {};
    var constraint = new R2dbcException("Другой CHECK", "23514", 0) {};
    assertSame(foreign, ProjectCleanupFailures.map(foreign));
    assertSame(constraint, ProjectCleanupFailures.map(constraint));
  }
}
