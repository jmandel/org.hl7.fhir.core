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

  private String ssri(String hexSha) {
    byte[] raw = new byte[32];
    for (int i = 0; i < 32; i++) {
      raw[i] = (byte) Integer.parseInt(hexSha.substring(i * 2, i * 2 + 2), 16);
    }
    return "sha256-" + java.util.Base64.getEncoder().encodeToString(raw);
  }

  private File writeLock(String sha, String url, boolean withSignature) throws IOException {
    File lock = new File(tmp, "fhir.lock");
    String sig = withSignature ? ",\n  \"expectedOutput\": {\"errors\": 0, \"warnings\": 3693, \"information\": 345}" : "";
    String integrity = sha.matches("[0-9a-f]{64}") ? ssri(sha) : sha; // pass malformed values through for negative tests
    Files.write(lock.toPath(), ("{\n  \"lockfileVersion\": 3,\n  \"packages\": {\n    \"hl7.fhir.r6.txpack\": {\"version\": \"20260612\", \"resolved\": \""
        + url + "\", \"integrity\": \"" + integrity + "\"}\n  }" + sig + "\n}").getBytes(StandardCharsets.UTF_8));
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
  void malformedIntegrityIsRejected() throws Exception {
    File lock = writeLock("sha256-not!!base64", "https://example.invalid/x.zip", false);
    IOException e = assertThrows(IOException.class, () -> TxLock.resolvePackPath(lock.getAbsolutePath()));
    assertTrue(e.getMessage().contains("integrity"));
    File lock2 = writeLock("md5-abcd", "https://example.invalid/x.zip", false);
    IOException e2 = assertThrows(IOException.class, () -> TxLock.resolvePackPath(lock2.getAbsolutePath()));
    assertTrue(e2.getMessage().contains("integrity"));
  }

  @Test
  void missingTxpackEntryIsRejected() throws Exception {
    File lock = new File(tmp, "fhir.lock");
    Files.write(lock.toPath(), "{\"lockfileVersion\": 3, \"packages\": {\"hl7.terminology\": {\"version\": \"7.1.0\"}}}".getBytes(StandardCharsets.UTF_8));
    IOException e = assertThrows(IOException.class, () -> TxLock.resolvePackPath(lock.getAbsolutePath()));
    assertTrue(e.getMessage().contains(".txpack"));
  }

  @Test
  void signatureAbsentReturnsNull() throws Exception {
    File lock = writeLock("0".repeat(64), "https://example.invalid/x.zip", false);
    assertNull(TxLock.expectedSignature(lock.getAbsolutePath()));
  }
}
