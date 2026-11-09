import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Java 21 source launcher: temporary Ed25519 material, including on macOS LibreSSL hosts. */
class RpcFixtureKey {
  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException("Expected generate/sign and three owned paths");
    if (args[0].equals("generate")) {
      var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      Files.writeString(
          Path.of(args[1]),
          "-----BEGIN PRIVATE KEY-----\n"
              + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
              + "\n-----END PRIVATE KEY-----\n");
      Files.write(Path.of(args[2]), pair.getPrivate().getEncoded());
      Files.write(Path.of(args[3]), pair.getPublic().getEncoded());
    } else if (args[0].equals("sign")) {
      String pem =
          Files.readString(Path.of(args[1]))
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      var key =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(Files.readAllBytes(Path.of(args[2])));
      Files.write(Path.of(args[3]), signer.sign());
    } else throw new IllegalArgumentException("Unknown fixture operation");
  }
}
