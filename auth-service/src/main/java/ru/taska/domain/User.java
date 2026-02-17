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

/**
 * Учётная запись пользователя системы.
 *
 * Определяет идентификационные данные пользователя и его статус
 * в жизненном цикле аутентификации.</p>
 *
 * <h3>Идентификаторы пользователя:</h3>
 * <ul>
 *   <li><b>login</b> — системный логин пользователя.
 *       Используется для входа в систему и не должен содержать символ '@'.
 *       Предназначен для стабильной идентификации внутри системы.</li>
 *
 *   <li><b>email</b> — адрес электронной почты пользователя.
 *       Используется для уведомлений, восстановления доступа и
 *       альтернативного входа.</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table("taska.users")
public class User {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Логин пользователя (не содержит '@').
     */
    @Column("login")
    private String login;

    /**
     * Email пользователя.
     */
    @Column("email")
    private String email;

    /**
     * Отображаемое имя пользователя.
     */
    @Column("display_name")
    private String displayName;

    /**
     * Текущий статус пользователя (INVITED, ACTIVE, BLOCKED).
     */
    @Column("status")
    private UserStatus status;

    /**
     * Временная метка создания записи (аудит).
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    /**
     * Временная метка последнего изменения записи (аудит).
     */
    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;
}
