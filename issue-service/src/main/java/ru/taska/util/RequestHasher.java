package ru.taska.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

public final class RequestHasher {
    private static final String HASH_ALGORITHM = "SHA-256";

    private RequestHasher() {}

    public static String hashIssueCreateRequest(
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    ) {
        String data = String.join("\u001f",
                                  projectId.toString(),
                                  issueType.name(),
                                  summary,
                                  description,
                                  priority.name(),
                                  reporterId.toString()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new DomainException(DomainStatus.INTERNAL, "Hashing algorithm not available");
        }
    }
}
