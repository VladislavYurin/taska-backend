package ru.taska.security.pat;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "personal_access_tokens", schema = "taska")
public class PersonalAccessToken {
    @Id private UUID id;
    private UUID userId;
    private String name;
    private String tokenPrefix;
    @ToString.Exclude private String tokenHash;
    private String[] scopes;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private String revokedReason;
}
