package eu.wohlben.qits.edge.wireguard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.XECPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link WireguardKey}: RFC 7748's vectors, and the key file's life cycle. */
class WireguardKeyTest {

  private static final HexFormat HEX = HexFormat.of();

  @Test
  void rfc7748Section61PublicKeys() {
    assertEquals(
        "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
        HEX.formatHex(
            WireguardKey.of(
                    HEX.parseHex(
                        "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
                .publicKey()));
    assertEquals(
        "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
        HEX.formatHex(
            WireguardKey.of(
                    HEX.parseHex(
                        "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
                .publicKey()));
  }

  @Test
  void rfc7748Section61SharedSecret() {
    assertEquals(
        "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
        HEX.formatHex(
            WireguardKey.x25519(
                HEX.parseHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
                HEX.parseHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"))));
  }

  @Test
  void theRawBytesAreTheJdksOwnKeyPair() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
    byte[] raw = WireguardKey.raw(pair.getPrivate());
    byte[] expected = littleEndian(((XECPublicKey) pair.getPublic()).getU());
    assertArrayEquals(expected, WireguardKey.of(raw).publicKey());
  }

  @Test
  void aMissingFileIsGeneratedOnceWithMode0600(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("private.key");
    WireguardKey first = WireguardKey.loadOrCreate(file);
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertArrayEquals(
        first.privateKey(),
        Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip()));
    WireguardKey again = WireguardKey.loadOrCreate(file);
    assertArrayEquals(first.privateKey(), again.privateKey());
    assertEquals(first.publicKeyBase64(), again.publicKeyBase64());
    assertEquals(44, first.publicKeyBase64().length());
  }

  @Test
  void aFileThatIsNotAKeyIsNeverOverwritten(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("private.key");
    Files.writeString(file, "not a key\n");
    assertThrows(java.io.IOException.class, () -> WireguardKey.loadOrCreate(file));
    assertEquals("not a key\n", Files.readString(file));
  }

  private static byte[] littleEndian(BigInteger u) {
    byte[] big = u.toByteArray();
    byte[] out = new byte[32];
    for (int i = 0; i < 32 && i < big.length; i++) {
      out[i] = big[big.length - 1 - i];
    }
    return out;
  }
}
