package ru.taska.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.users")
public class User {

    @Id
    @Column("id")
    private UUID id;

    @Column("login")
    private String login;

    @Column("email")
    private String email;

    @Column("display_name")
    private String displayName;

    @Column("status")
    private UserStatus status;

    @Column("lockout_until")
    private Instant lockoutUntil;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
