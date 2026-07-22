package ru.taska.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Данные об аватаре пользователя.
 */
@Data
@NoArgsConstructor
@Table(name = "user_avatars", schema = "taska")
public class UserAvatar {

    /**
     * Первичный ключ.
     */
    @Id
    @Column("id")
    private UUID id;

    /**
     * Идентификатор пользователя-владельца аватара.
     */
    @Column("user_id")
    private UUID userId;

    /**
     * Ключ объекта в хранилище.
     */
    @Column("object_key")
    private String objectKey;

    /**
     * Оригинальное имя файла, переданное клиентом при загрузке.
     */
    @Column("file_name")
    private String fileName;

    /**
     * MIME-тип файла (например, {@code image/png}).
     */
    @Column("content_type")
    private String contentType;

    /**
     * Размер файла в байтах.
     */
    @Column("size_bytes")
    private Long sizeBytes;

    /**
     * Временная метка создания.
     */
    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}
