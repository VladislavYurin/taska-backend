import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks only this owned runner's bucket/keys. Never scans a global bucket or prints capabilities.
 */
class AttachmentLifecycleS3Probe {
  public static void main(String[] args) throws Exception {
    var p = JsonMapper.builder().build().readTree(Files.readString(Path.of(args[0])));
    try (var s3 =
        S3AsyncClient.builder()
            .endpointOverride(URI.create(p.path("endpoint").asString()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        p.path("access").asString(), p.path("secret").asString())))
            .build()) {
      String bucket = p.path("bucket").asString(), avatar = "avatars/owned-lifecycle-sentinel";
      byte[] sentinel = "owned-avatar-sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (p.path("mode").asString().equals("seed"))
        s3.putObject(b -> b.bucket(bucket).key(avatar), AsyncRequestBody.fromBytes(sentinel))
            .get(15, TimeUnit.SECONDS);
      else {
        var bytes =
            s3.getObject(b -> b.bucket(bucket).key(avatar), AsyncResponseTransformer.toBytes())
                .get(15, TimeUnit.SECONDS)
                .asByteArray();
        if (!java.util.Arrays.equals(sentinel, bytes))
          throw new IllegalStateException("Owned avatar sentinel changed");
        for (var k : p.path("deletedKeys")) {
          String key = k.asString();
          if (!key.startsWith("tenant/"))
            throw new IllegalArgumentException("Owned manifest key required");
          var objects =
              s3.listObjectVersions(b -> b.bucket(bucket).prefix(key).maxKeys(100))
                  .get(15, TimeUnit.SECONDS);
          if (objects.versions().stream().anyMatch(v -> v.key().equals(key))
              || objects.deleteMarkers().stream().anyMatch(v -> v.key().equals(key)))
            throw new IllegalStateException("Owned deleted key still has versions");
        }
      }
      System.out.println("{\"ownedObjects\":\"PASS\"}");
    }
  }
}
