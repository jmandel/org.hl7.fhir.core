package org.hl7.fhir.r5.terminologies.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TxLockTests {

  @TempDir
  File tmp;

  private String sha256(byte[] data) throws Exception {
    StringBuilder b = new StringBuilder();
    for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) {
      b.append(String.format("%02x", x));
    }
    return b.toString();
  }

  private File writeLock(String sha, String url, boolean withSignature) throws IOException {
    File lock = new File(tmp, "tx.lock");
    String sig = withSignature ? ",\n  \"expectedSignature\": {\"errors\": 0, \"warnings\": 3693, \"information\": 345}" : "";
    Files.write(lock.toPath(), ("{\n  \"pack\": {\"zipSha256\": \"" + sha + "\", \"url\": \"" + url + "\"}" + sig + "\n}")
        .getBytes(StandardCharsets.UTF_8));
    return lock;
  }

  @Test
  void cacheHitResolvesWithoutNetwork() throws Exception {
    byte[] pack = "not really a zip, but content-addressed all the same".getBytes(StandardCharsets.UTF_8);
    String sha = sha256(pack);
    File store = new File(new File(new File(System.getProperty("user.home"), ".fhir"), "tx-packs"), sha + ".zip");
    store.getParentFile().mkdirs();
    Files.write(store.toPath(), pack);
    try {
      File lock = writeLock(sha, "https://example.invalid/never-fetched.zip", true);
      String resolved = TxLock.resolvePackPath(lock.getAbsolutePath());
      assertEquals(store.getAbsolutePath(), resolved);
      int[] sig = TxLock.expectedSignature(lock.getAbsolutePath());
      assertEquals(0, sig[0]);
      assertEquals(3693, sig[1]);
      assertEquals(345, sig[2]);
    } finally {
      store.delete();
    }
  }

  @Test
  void malformedShaIsRejected() throws Exception {
    File lock = writeLock("nothex", "https://example.invalid/x.zip", false);
    IOException e = assertThrows(IOException.class, () -> TxLock.resolvePackPath(lock.getAbsolutePath()));
    assertTrue(e.getMessage().contains("zipSha256"));
  }

  @Test
  void missingPackObjectIsRejected() throws Exception {
    File lock = new File(tmp, "tx.lock");
    Files.write(lock.toPath(), "{\"tooling\": {}}".getBytes(StandardCharsets.UTF_8));
    IOException e = assertThrows(IOException.class, () -> TxLock.resolvePackPath(lock.getAbsolutePath()));
    assertTrue(e.getMessage().contains("'pack'"));
  }

  @Test
  void signatureAbsentReturnsNull() throws Exception {
    File lock = writeLock("0".repeat(64), "https://example.invalid/x.zip", false);
    assertNull(TxLock.expectedSignature(lock.getAbsolutePath()));
  }
}
