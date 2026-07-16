package ru.taska.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taska.storage.config.StorageAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@SpringBootTest(classes = StorageAutoConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
public abstract class AbstractIT {

    protected static final String BUCKET_NAME = "test-bucket";

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.endpoint", minio::getS3URL);
        registry.add("storage.public-url", minio::getS3URL);
        registry.add("storage.access-key", minio::getUserName);
        registry.add("storage.secret-key", minio::getPassword);
        registry.add("storage.region", () -> "us-east-1");
        registry.add("storage.presigned-url-ttl", () -> "PT1H");
        registry.add("storage.allowed-content-types", () -> "image/jpeg,image/png");
        registry.add("storage.max-file-size-bytes", () -> "100");
        registry.add("storage.bucket", () -> BUCKET_NAME);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client adminClient = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .forcePathStyle(true)
                .build()) {
            adminClient.createBucket(b -> b.bucket(BUCKET_NAME));
        }
    }
}
