package eu.wohlben.qits.edge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one reading of the Hetzner DNS token, for both of its users: the certificate's DNS-01 orders
 * ({@link EdgeCertificateManager}) and the private host records ({@link PrivateHostRecords}).
 *
 * <p>The secret FILE wins over the value: when {@link AcmeConfig#hetznerTokenFile()} is set it is
 * read, stripped, and an empty file is a refusal rather than a fall-back to {@link
 * AcmeConfig#hetznerToken()} — a mounted secret that came up empty is a broken deployment, not an
 * absent one. Otherwise the by-value key, stripped, with blank reading as absent. Read on every
 * call, so a rotated file is picked up without a restart.
 */
final class HetznerToken {

  private HetznerToken() {}

  /**
   * @return the token, or empty when neither the file nor the value states one
   * @throws IllegalStateException when the configured file is empty
   * @throws IOException when the configured file cannot be read
   */
  static Optional<String> of(AcmeConfig acme) throws IOException {
    if (acme.hetznerTokenFile().isPresent()) {
      Path file = acme.hetznerTokenFile().orElseThrow();
      String token = Files.readString(file).strip();
      if (!token.isEmpty()) {
        return Optional.of(token);
      }
      throw new IllegalStateException("The configured Hetzner token secret file is empty");
    }
    return acme.hetznerToken().map(String::strip).filter(value -> !value.isEmpty());
  }
}
