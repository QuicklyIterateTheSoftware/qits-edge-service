package eu.wohlben.qits.edge.wireguard;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.XECPrivateKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * The private edge's WireGuard key pair: a 32-byte X25519 private key, kept as base64 of its raw
 * bytes in {@code qits.edge.wireguard.key-file} (mode 0600, on the private volume), and the public
 * key derived from it.
 *
 * <p>The public key is computed here, by RFC 7748's Montgomery ladder over {@link BigInteger},
 * rather than through a JCA {@code KeyFactory}/{@code KeyAgreement}: which provider answers {@code
 * X25519} differs between the JVM and the native image (Bouncy Castle is registered too), and a
 * forty-line pure function has no such question. It runs once per start on this process' own key,
 * so its timing is not a side channel anyone can observe.
 */
public record WireguardKey(byte[] privateKey, byte[] publicKey) {

  static final int KEY_BYTES = 32;

  /** The base point's u-coordinate, 9: X25519(k, 9) is k's public key. */
  private static final byte[] BASE_POINT = new byte[KEY_BYTES];

  static {
    BASE_POINT[0] = 9;
  }

  private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

  private static final BigInteger A24 = BigInteger.valueOf(121665);

  /** The pair for {@code privateKey}. */
  public static WireguardKey of(byte[] privateKey) {
    if (privateKey.length != KEY_BYTES) {
      throw new IllegalArgumentException("a WireGuard private key is 32 bytes");
    }
    return new WireguardKey(privateKey.clone(), x25519(privateKey, BASE_POINT));
  }

  /**
   * The key in {@code file}, or a new one written there when the file does not exist. A file that
   * exists but does not hold a key is an error, never overwritten: replacing it would silently
   * change the edge's public key under every configured peer.
   */
  public static WireguardKey loadOrCreate(Path file) throws IOException, GeneralSecurityException {
    if (!Files.exists(file)) {
      byte[] generated = generate();
      String encoded = Base64.getEncoder().encodeToString(generated) + "\n";
      try {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, encoded, StandardCharsets.US_ASCII);
        return of(generated);
      } catch (FileAlreadyExistsException raced) {
        // someone wrote it first: read theirs
      }
    }
    String stored = Files.readString(file, StandardCharsets.US_ASCII).strip();
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(stored);
    } catch (IllegalArgumentException notBase64) {
      raw = new byte[0];
    }
    if (raw.length != KEY_BYTES) {
      throw new IOException(
          file + " does not hold a WireGuard private key (base64 of 32 bytes); it is left as is");
    }
    return of(raw);
  }

  /** A fresh private key's raw 32 bytes, from the JDK's X25519 key pair generator. */
  static byte[] generate() throws GeneralSecurityException {
    PrivateKey key = KeyPairGenerator.getInstance("X25519").generateKeyPair().getPrivate();
    return raw(key);
  }

  /**
   * The raw 32 bytes of an X25519 private key: its scalar where the provider exposes one ({@link
   * XECPrivateKey}), else the {@code OCTET STRING (32)} inside its PKCS#8 encoding (RFC 8410:
   * {@code 04 22 04 20 <32 bytes>}), which holds whether or not the encoding also carries the
   * public key after it.
   */
  static byte[] raw(PrivateKey key) throws GeneralSecurityException {
    if (key instanceof XECPrivateKey xec) {
      byte[] scalar = xec.getScalar().orElse(null);
      if (scalar != null && scalar.length == KEY_BYTES) {
        return scalar.clone();
      }
    }
    byte[] encoded = key.getEncoded();
    for (int i = 0; encoded != null && i + 4 + KEY_BYTES <= encoded.length; i++) {
      if (encoded[i] == 0x04
          && encoded[i + 1] == 0x22
          && encoded[i + 2] == 0x04
          && encoded[i + 3] == 0x20) {
        return Arrays.copyOfRange(encoded, i + 4, i + 4 + KEY_BYTES);
      }
    }
    throw new GeneralSecurityException("cannot read the raw bytes of an X25519 private key");
  }

  /** The public key, base64: what a peer configures as this edge's {@code PublicKey}. */
  public String publicKeyBase64() {
    return Base64.getEncoder().encodeToString(publicKey);
  }

  /** RFC 7748 §5's X25519(k, u), both and the result little-endian 32 bytes. */
  static byte[] x25519(byte[] scalar, byte[] u) {
    byte[] k = scalar.clone();
    k[0] &= (byte) 248;
    k[31] &= 127;
    k[31] |= 64;
    byte[] uc = u.clone();
    uc[31] &= 127;
    BigInteger kn = littleEndian(k);
    BigInteger x1 = littleEndian(uc).mod(P);
    BigInteger x2 = BigInteger.ONE;
    BigInteger z2 = BigInteger.ZERO;
    BigInteger x3 = x1;
    BigInteger z3 = BigInteger.ONE;
    boolean swap = false;
    for (int t = 254; t >= 0; t--) {
      boolean bit = kn.testBit(t);
      if (swap ^ bit) {
        BigInteger x = x2;
        x2 = x3;
        x3 = x;
        BigInteger z = z2;
        z2 = z3;
        z3 = z;
      }
      swap = bit;
      BigInteger a = x2.add(z2).mod(P);
      BigInteger aa = a.multiply(a).mod(P);
      BigInteger b = x2.subtract(z2).mod(P);
      BigInteger bb = b.multiply(b).mod(P);
      BigInteger e = aa.subtract(bb).mod(P);
      BigInteger c = x3.add(z3).mod(P);
      BigInteger d = x3.subtract(z3).mod(P);
      BigInteger da = d.multiply(a).mod(P);
      BigInteger cb = c.multiply(b).mod(P);
      BigInteger sum = da.add(cb).mod(P);
      BigInteger diff = da.subtract(cb).mod(P);
      x3 = sum.multiply(sum).mod(P);
      z3 = x1.multiply(diff.multiply(diff)).mod(P);
      x2 = aa.multiply(bb).mod(P);
      z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
    }
    if (swap) {
      x2 = x3;
      z2 = z3;
    }
    return toLittleEndian(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P));
  }

  private static BigInteger littleEndian(byte[] bytes) {
    byte[] big = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      big[i] = bytes[bytes.length - 1 - i];
    }
    return new BigInteger(1, big);
  }

  private static byte[] toLittleEndian(BigInteger value) {
    byte[] big = value.toByteArray();
    byte[] out = new byte[KEY_BYTES];
    for (int i = 0; i < KEY_BYTES && i < big.length; i++) {
      out[i] = big[big.length - 1 - i];
    }
    return out;
  }
}
