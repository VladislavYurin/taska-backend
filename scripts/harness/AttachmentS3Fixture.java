import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.json.JsonMapper;

/** Управление только собственным bucket из локального harness; материал не выводится. */
class AttachmentS3Fixture {
  public static void main(String[] args) throws Exception {
    var p=JsonMapper.builder().build().readTree(Files.readString(Path.of(args[0])));
    try(var s3=S3AsyncClient.builder().endpointOverride(URI.create(p.path("endpoint").asString()))
        .region(Region.US_EAST_1).forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
            p.path("access").asString(),p.path("secret").asString()))).build()) {
      String bucket=p.path("bucket").asString();
      try { s3.createBucket(b -> b.bucket(bucket)).get(15,TimeUnit.SECONDS); }
      catch(Exception e) { if(!(e.getCause() instanceof S3Exception s) || s.statusCode()!=409) throw e; }
      s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)))
          .get(15,TimeUnit.SECONDS);
      if(s3.getBucketVersioning(b -> b.bucket(bucket)).get(15,TimeUnit.SECONDS).status()!=BucketVersioningStatus.ENABLED)
        throw new IllegalStateException("Versioning unavailable in owned fixture");
      System.out.println("{\"versioning\":\"PASS\"}");
    }
  }
}
