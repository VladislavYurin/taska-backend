package ru.taska.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taska.storage.config.StorageAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@SpringBootTest(
        classes = StorageAutoConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Testcontainers
public abstract class AbstractIT {

    private static final String RUSTFS_CONTAINER_VERSION = "rustfs/rustfs:1.0.0-rc.6";

    protected static final String BUCKET_NAME = "test-bucket";
    private static final String STORAGE_ACCESS_KEY = "123";
    private static final String STORAGE_SECRET_KEY = "123";
    private static final String STORAGE_REGION = "us-east-1";

    // Намеренно маленький лимит — нужен для проверки OUT_OF_RANGE
    // на границе (см. S3StorageClientIT), а не реальный прод-лимит.
    private static final String MAX_FILE_SIZE_BYTES = "100";

    @Container
    static GenericContainer<?> rustfs = new GenericContainer<>(RUSTFS_CONTAINER_VERSION)
            .withExposedPorts(9000)
            .withEnv("RUSTFS_ACCESS_KEY", STORAGE_ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", STORAGE_SECRET_KEY);

    private static String storageEndpoint() {
        return "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.endpoint", AbstractIT::storageEndpoint);
        registry.add("storage.public-url", AbstractIT::storageEndpoint);
        registry.add("storage.access-key", () -> STORAGE_ACCESS_KEY);
        registry.add("storage.secret-key", () -> STORAGE_SECRET_KEY);
        registry.add("storage.region", () -> STORAGE_REGION);
        registry.add("storage.presigned-url-ttl", () -> "PT1H");
        registry.add("storage.allowed-content-types", () -> "image/jpeg,image/png");
        registry.add("storage.max-file-size-bytes", () -> MAX_FILE_SIZE_BYTES);
        registry.add("storage.bucket", () -> BUCKET_NAME);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client adminClient = S3Client.builder()
                .endpointOverride(URI.create(storageEndpoint()))
                .region(Region.of(STORAGE_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY)))
                .forcePathStyle(true)
                .build()) {

            adminClient.createBucket(b -> b.bucket(BUCKET_NAME));
        }
    }
}