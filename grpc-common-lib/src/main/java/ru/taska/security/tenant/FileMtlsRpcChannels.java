package ru.taska.security.tenant;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

/** Configured CA/client material only; peer SPIFFE URI must match the fixed target audience. */
public final class FileMtlsRpcChannels implements TrustedRpcChannels {
  private final Path ca, certificate, key;
  private final String environment;

  public FileMtlsRpcChannels(Path ca, Path certificate, Path key, String environment) {
    for (Path p : new Path[] {ca, certificate, key})
      if (!Files.isRegularFile(p))
        throw new IllegalStateException("mTLS material must be provisioned");
    this.ca = ca;
    this.certificate = certificate;
    this.key = key;
    this.environment = environment;
  }

  public ManagedChannel create(String host, int port, String audience) {
    try {
      var store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null);
      try (var input = Files.newInputStream(ca)) {
        int i = 0;
        for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(input))
          store.setCertificateEntry("ca-" + i++, cert);
      }
      var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      X509TrustManager delegate = null;
      for (var manager : factory.getTrustManagers())
        if (manager instanceof X509TrustManager x) delegate = x;
      if (delegate == null) throw new GeneralSecurityException();
      final var trust = delegate;
      var pinned =
          new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
              return trust.getAcceptedIssuers();
            }

            public void checkClientTrusted(X509Certificate[] chain, String auth)
                throws CertificateException {
              trust.checkClientTrusted(chain, auth);
            }

            public void checkServerTrusted(X509Certificate[] chain, String auth)
                throws CertificateException {
              trust.checkServerTrusted(chain, auth);
              String expected = "spiffe://taska/" + environment + "/" + audience;
              long matches = 0;
              var sans = chain[0].getSubjectAlternativeNames();
              if (sans != null)
                for (var san : sans)
                  if (Integer.valueOf(6).equals(san.get(0))) {
                    if (!expected.equals(san.get(1)))
                      throw new CertificateException("Unexpected RPC server identity");
                    matches++;
                  }
              if (matches != 1) throw new CertificateException("RPC server identity required");
            }
          };
      return NettyChannelBuilder.forAddress(host, port)
          .sslContext(
              GrpcSslContexts.forClient()
                  .trustManager(pinned)
                  .keyManager(certificate.toFile(), key.toFile())
                  .build())
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Invalid mTLS channel configuration");
    }
  }
}
