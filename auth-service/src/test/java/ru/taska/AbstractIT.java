package ru.taska;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.net.URI;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
public abstract class AbstractIT {

    private static final String POSTGRES_CONTAINER_VERSION = "postgres:16";
    private static final String RUSTFS_CONTAINER_VERSION = "rustfs/rustfs:1.0.0-rc.6";

    protected static final String BUCKET_NAME = "test-bucket";
    private static final String STORAGE_ACCESS_KEY = "123";
    private static final String STORAGE_SECRET_KEY = "123";
    private static final String STORAGE_REGION = "us-east-1";

    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(POSTGRES_CONTAINER_VERSION);

    protected static final GenericContainer<?> rustfs =
            new GenericContainer<>(RUSTFS_CONTAINER_VERSION)
                    .withExposedPorts(9000, 9001)
                    .withEnv("RUSTFS_ACCESS_KEY", STORAGE_ACCESS_KEY)
                    .withEnv("RUSTFS_SECRET_KEY", STORAGE_SECRET_KEY);

    static {
        postgres.start();
        rustfs.start();
    }

    private static String storageEndpoint() {
        return "http://" + rustfs.getHost() + ":" + rustfs.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Postgres / Liquibase / R2DBC
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);

        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.grpc.server.port", () -> "9090");

        // RustFS / storage
        registry.add("storage.endpoint", AbstractIT::storageEndpoint);
        registry.add("storage.public-url", AbstractIT::storageEndpoint);
        registry.add("storage.access-key", () -> STORAGE_ACCESS_KEY);
        registry.add("storage.secret-key", () -> STORAGE_SECRET_KEY);
        registry.add("storage.region", () -> STORAGE_REGION);
        registry.add("storage.presigned-url-ttl", () -> "PT1H");
        registry.add("storage.allowed-content-types", () -> "image/jpeg,image/png,image/webp");
        registry.add("storage.max-file-size-bytes", () -> "2097152");
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

            // bucket теперь общий для всех IT-классов (контейнер один на весь прогон),
            // поэтому второй и последующий классы получат "already exists" - это ок.
            try {
                adminClient.createBucket(b -> b.bucket(BUCKET_NAME));
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // bucket уже создан предыдущим тестовым классом
            }
        }
    }
}