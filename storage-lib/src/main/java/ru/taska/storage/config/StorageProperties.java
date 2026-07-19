package ru.taska.storage.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Конфигурация S3-совместимого хранилища.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * Адрес сервера хранилища, на который клиент отправляет запросы.
     * Для MinIO в Docker — это адрес контейнера, например {@code http://minio:9000}.
     * Для AWS S3 не нужен: SDK определяет адрес сам по региону.
     */
    @NotBlank
    private String endpoint;

    /**
     * Публичный адрес хранилища, доступный из браузера.
     * Используется при генерации presigned URL, которые передаются фронтенду.
     * Отличается от {@link #endpoint} тем, что доступен снаружи Docker-сети,
     * например {@code http://127.0.0.1:9000} при локальной разработке.
     */
    @NotBlank
    private String publicUrl;

    /** Access key для аутентификации в хранилище. */
    @NotBlank
    private String accessKey;

    /** Secret key для аутентификации в хранилище. */
    @NotBlank
    private String secretKey;

    /**
     * Регион хранилища.
     * MinIO игнорирует это значение, но AWS SDK требует его указать.
     * Для MinIO можно использовать любую строку, например {@code us-east-1}.
     */
    @NotBlank
    private String region;

    /**
     * Время жизни presigned URL.
     * По истечении этого времени ссылка становится недействительной
     * и фронтенд не сможет загрузить или скачать файл по ней.
     */
    @NotNull
    private Duration presignedUrlTtl;

    /** Допустимые MIME-типы файлов. */
    @NotEmpty
    private List<String> allowedContentTypes;

    /** Максимальный размер файла в байтах. */
    @Positive
    private long maxFileSizeBytes;

    /** Название бакета. */
    @NotBlank
    private String bucket;
}
