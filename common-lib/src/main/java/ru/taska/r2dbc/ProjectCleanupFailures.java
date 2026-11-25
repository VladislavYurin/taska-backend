package ru.taska.r2dbc;

import io.r2dbc.spi.R2dbcException;
import java.util.Map;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Поздняя запись, остановленная SQL fence, сохраняет скрытый 404 вместо технического 500. */
public final class ProjectCleanupFailures {
  private ProjectCleanupFailures() {}

  public static Throwable map(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof R2dbcException sql
          && "23514".equals(sql.getSqlState())
          && "PROJECT_DELETING".equals(sql.getMessage())) {
        var mapped =
            new DomainException(
                DomainStatus.NOT_FOUND, "Project not found", Map.of("code", "PROJECT_NOT_FOUND"));
        mapped.initCause(error);
        return mapped;
      }
    }
    return error;
  }
}
