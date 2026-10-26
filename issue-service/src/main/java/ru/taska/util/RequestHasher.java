package ru.taska.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

public final class RequestHasher {
  private RequestHasher() {}

  /** Legacy hash, retained solely to verify persisted keys created before TAS-428. */
  public static String hashIssueCreateRequest(
      UUID projectId,
      IssueType issueType,
      String summary,
      String description,
      IssuePriority priority,
      UUID reporterId) {
    return digest(
        String.join(
                "\u001f",
                projectId.toString(),
                issueType.name(),
                summary,
                description,
                priority.name(),
                reporterId.toString())
            .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Length framing prevents separator collisions; values follow the persisted creation semantics.
   */
  public static String hashIssueCreateRequest(
      UUID projectId,
      IssueType issueType,
      String summary,
      String description,
      IssuePriority priority,
      UUID reporterId,
      BigDecimal storyPoints,
      LocalDate startDate,
      LocalDate dueDate,
      Integer originalEstimateMinutes,
      Integer remainingEstimateMinutes,
      String statusKey) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      Object[] fields = {
        projectId,
        issueType.name(),
        summary,
        Objects.requireNonNullElse(description, ""),
        priority.name(),
        reporterId,
        StoryPointsNormalizer.normalize(storyPoints),
        startDate,
        dueDate,
        originalEstimateMinutes,
        remainingEstimateMinutes,
        Objects.requireNonNullElse(statusKey, "TODO")
      };
      for (Object field : fields) {
        if (field == null) {
          output.writeInt(-1);
          continue;
        }
        byte[] value = field.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(value.length);
        output.write(value);
      }
      return "v2:" + digest(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digest(byte[] data) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException impossible) {
      throw new DomainException(DomainStatus.INTERNAL, "Hashing algorithm not available");
    }
  }
}
