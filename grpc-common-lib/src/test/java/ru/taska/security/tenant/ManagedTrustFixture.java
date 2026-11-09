package ru.taska.security.tenant;

import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Temporary operator-owned material for native transport tests; contains no production keys. */
public final class ManagedTrustFixture {
  public final Path directory;
  public final Map<String, KeyPair> pairs = new LinkedHashMap<>();
  public final Map<String, ManagedRpcTrust.Service> services = new LinkedHashMap<>();
  public final List<ManagedRpcTrust.Key> keys = new ArrayList<>();
  public final List<ManagedRpcTrust.Edge> edges = new ArrayList<>();
  public long generation = 1;

  public ManagedTrustFixture(Path directory, List<String> names) throws Exception {
    this.directory = directory;
    Files.createDirectories(directory);
    openssl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt",
        "-days",
        "1",
        "-subj",
        "/CN=owned-rpc-trust");
    for (String name : names) {
      openssl(
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr",
          "-subj",
          "/CN=localhost");
      Files.writeString(
          path(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      openssl(
          "x509",
          "-req",
          "-in",
          name + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-out",
          name + ".crt",
          "-days",
          "1",
          "-extfile",
          name + ".ext");
      try (var input = Files.newInputStream(path(name + ".crt"))) {
        var cert = CertificateFactory.getInstance("X.509").generateCertificate(input);
        services.put(
            name,
            new ManagedRpcTrust.Service(
                "1",
                Set.of(
                    HexFormat.of()
                        .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())))));
      }
      addKey(name, "one", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
      activate(name, "one", pairs.get(name + "/one"));
    }
    write();
  }

  public Path path(String name) {
    return directory.resolve(name);
  }

  public void openssl(String... args) throws Exception {
    var command = new ArrayList<>(List.of("openssl"));
    command.addAll(List.of(args));
    var p =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    byte[] output = p.getInputStream().readAllBytes();
    if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0)
      throw new IllegalStateException("Test certificate generation failed");
  }

  public void addKey(String issuer, String kid, KeyPair pair) {
    pairs.put(issuer + "/" + kid, pair);
    var now = Instant.now();
    keys.add(
        new ManagedRpcTrust.Key(
            issuer,
            kid,
            Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
            now.minusSeconds(60),
            now.plusSeconds(3600),
            false));
  }

  public void activate(String issuer, String kid, KeyPair pair) throws Exception {
    var material =
        new ManagedRpcTrust.SigningMaterial(
            kid, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    var tmp = path(issuer + ".signing.next");
    Files.writeString(tmp, JsonMapper.builder().build().writeValueAsString(material));
    Files.move(
        tmp,
        path(issuer + ".signing.json"),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  public void write() throws Exception {
    write(Instant.now().plusSeconds(120));
  }

  public void write(Instant until) throws Exception {
    var s =
        new ManagedRpcTrust.Snapshot(
            1, generation++, "test", Instant.now(), until, services, keys, edges);
    var tmp = path("trust.next");
    Files.writeString(tmp, JsonMapper.builder().build().writeValueAsString(s));
    Files.move(
        tmp,
        path("trust.json"),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  public ManagedRpcTrust trust(String service) {
    return new ManagedRpcTrust(
        path("trust.json"), path(service + ".signing.json"), service, "test", Clock.systemUTC());
  }

  public void userEdge(String issuer, String audience, String rpc, String action) {
    edges.add(
        new ManagedRpcTrust.Edge(
            issuer,
            audience,
            rpc,
            action,
            Set.of("WEB_TENANT", "USER_PAT"),
            Set.of("read", "write"),
            "USER",
            Set.of(),
            "BOUND",
            Set.of()));
  }
}
