package security;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

/** Separate service credential, never inferred from a user Bearer or caller-controlled node_id. */
public final class OAuthServiceCredentials {
  public static final Metadata.Key<String> KEY =
      Metadata.Key.of("x-taska-oauth-service-credential", Metadata.ASCII_STRING_MARSHALLER);

  private OAuthServiceCredentials() {}

  public static io.grpc.ClientInterceptor interceptor(String credential) {
    Metadata headers = new Metadata();
    headers.put(KEY, credential);
    return MetadataUtils.newAttachHeadersInterceptor(headers);
  }
}
