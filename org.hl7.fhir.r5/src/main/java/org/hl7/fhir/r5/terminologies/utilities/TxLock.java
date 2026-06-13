package org.hl7.fhir.r5.terminologies.utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.hl7.fhir.utilities.FileUtilities;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.filesystem.ManagedFileAccess;
import org.hl7.fhir.utilities.http.HTTPResult;
import org.hl7.fhir.utilities.http.ManagedWebAccess;

import com.google.gson.JsonObject;

/**
 * Resolver for a repo-committed {@code tx.lock} file: the ~1KB pointer that pins the immutable
 * terminology answer pack (see {@link TerminologyCachePackager}) a checkout builds against.
 * <p/>
 * The lock names the pack by content hash and fetch URL. Resolution is content-addressed:
 * the pack zip lives at {@code ~/.fhir/tx-packs/<sha256>.zip}, is verified against its hash on
 * every use (a corrupt or tampered file is deleted and refetched once), and is never mutated.
 * Builds driven by a lock therefore need no terminology server and no mutable per-machine
 * cache state for any answer the pack carries; misses fall through to the network exactly as
 * without a pack.
 * <p/>
 * Lock format (JSON): {@code {"pack": {"zipSha256": "<64 hex>", "url": "https://..."}}} -
 * other members are informational. The expected output signature, when present
 * ({@code {"expectedSignature": {"errors": N, "warnings": N, "information": N}}}), can be read
 * with {@link #expectedSignature(String)} by drivers that want to assert it.
 */
public class TxLock {

  /** set to "ignore" to disable lock resolution even when a tx.lock file is present */
  public static final String LOCK_SYSTEM_PROPERTY = "org.hl7.fhir.tx.lock";

  /**
   * Resolves the lock's pack into the content-addressed store, downloading and verifying as
   * needed, and returns the local path to hand to the pack loader
   * ({@link TerminologyCache#PACK_SYSTEM_PROPERTY}).
   */
  public static String resolvePackPath(String lockFilePath) throws IOException {
    JsonObject lock = parse(lockFilePath);
    JsonObject pack = member(lock, lockFilePath, "pack");
    String sha = string(pack, lockFilePath, "zipSha256");
    String url = string(pack, lockFilePath, "url");
    if (!sha.matches("[0-9a-f]{64}")) {
      throw new IOException("tx.lock pack.zipSha256 is not a sha256 hex string: " + lockFilePath);
    }
    File store = ManagedFileAccess.file(Utilities.path(System.getProperty("user.home"), ".fhir", "tx-packs"));
    FileUtilities.createDirectory(store.getAbsolutePath());
    File cached = ManagedFileAccess.file(store, sha + ".zip");
    if (cached.exists()) {
      if (sha.equals(sha256(cached))) {
        return cached.getAbsolutePath();
      }
      // corrupt store entries are deleted and refetched - the store is content-addressed,
      // so a failed verification can only mean local damage, never a legitimate update
      if (!cached.delete()) {
        throw new IOException("Terminology pack store entry failed verification and could not be removed: " + cached);
      }
    }
    HTTPResult res = ManagedWebAccess.get(Utilities.strings("web"), url);
    res.checkThrowException();
    File part = ManagedFileAccess.file(store, sha + ".zip.download");
    try (FileOutputStream fs = new FileOutputStream(part)) {
      fs.write(res.getContent());
    }
    if (!sha.equals(sha256(part))) {
      part.delete();
      throw new IOException("Downloaded terminology pack does not match tx.lock pack.zipSha256 (" + url + ")");
    }
    if (!part.renameTo(cached)) {
      throw new IOException("Unable to install terminology pack into store: " + cached);
    }
    return cached.getAbsolutePath();
  }

  /** the lock's expected output signature as {errors, warnings, information}, or null when absent */
  public static int[] expectedSignature(String lockFilePath) throws IOException {
    JsonObject lock = parse(lockFilePath);
    if (!lock.has("expectedSignature")) {
      return null;
    }
    JsonObject sig = lock.getAsJsonObject("expectedSignature");
    return new int[] { sig.get("errors").getAsInt(), sig.get("warnings").getAsInt(), sig.get("information").getAsInt() };
  }

  private static JsonObject parse(String lockFilePath) throws IOException {
    String src = FileUtilities.fileToString(lockFilePath);
    return (JsonObject) new com.google.gson.JsonParser().parse(src);
  }

  private static JsonObject member(JsonObject o, String lockFilePath, String name) throws IOException {
    if (!o.has(name) || !o.get(name).isJsonObject()) {
      throw new IOException("tx.lock has no '" + name + "' object: " + lockFilePath);
    }
    return o.getAsJsonObject(name);
  }

  private static String string(JsonObject o, String lockFilePath, String name) throws IOException {
    if (!o.has(name)) {
      throw new IOException("tx.lock is missing '" + name + "': " + lockFilePath);
    }
    return o.get(name).getAsString();
  }

  private static String sha256(File f) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(FileUtilities.fileToBytes(f.getAbsolutePath()));
      StringBuilder b = new StringBuilder();
      for (byte x : hash) {
        b.append(String.format("%02x", x));
      }
      return b.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }
}
