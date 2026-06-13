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
 * Resolver for a repo-committed {@code fhir.lock} file: the content lock that pins the FHIR
 * package dependencies a checkout builds against - first among them the immutable terminology
 * answer pack (see {@link TerminologyCachePackager}).
 * <p/>
 * The lock is a profile of npm's package-lock v3 shape, chosen so no new format exists to
 * specify or learn: a {@code packages} map of package name to {@code {version, resolved,
 * integrity}}, where {@code integrity} is an SSRI string ({@code sha256-<base64>}). Two
 * deliberate divergences from npm: keys are plain package names (no {@code node_modules/}
 * vendor-path prefix - entries resolve into the shared content-addressed store, never a
 * per-project folder), and a top-level {@code expectedOutput} member records the build output
 * signature this content produces, because it changes in the same single-writer commit as the
 * pack it describes.
 * <p/>
 * Registries FIND bytes ({@code resolved}); the lock TRUSTS bytes ({@code integrity}, verified
 * on every use); the store KEEPS bytes ({@code ~/.fhir/tx-packs/<sha256>.zip}, immutable -
 * a corrupt or tampered entry is deleted and refetched once). Builds driven by a lock need no
 * terminology server for any answer the pack carries; misses fall through to the network
 * exactly as without a pack.
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
    JsonObject pack = findTxPack(lock, lockFilePath);
    String url = string(pack, lockFilePath, "resolved");
    String sha = sha256FromIntegrity(string(pack, lockFilePath, "integrity"), lockFilePath);
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
      throw new IOException("Downloaded terminology pack does not match fhir.lock integrity (" + url + ")");
    }
    if (!part.renameTo(cached)) {
      throw new IOException("Unable to install terminology pack into store: " + cached);
    }
    return cached.getAbsolutePath();
  }

  /** the lock's expected output signature as {errors, warnings, information}, or null when absent */
  public static int[] expectedSignature(String lockFilePath) throws IOException {
    JsonObject lock = parse(lockFilePath);
    if (!lock.has("expectedOutput")) {
      return null;
    }
    JsonObject sig = lock.getAsJsonObject("expectedOutput");
    return new int[] { sig.get("errors").getAsInt(), sig.get("warnings").getAsInt(), sig.get("information").getAsInt() };
  }

  /** the terminology answer pack entry: the package whose name ends ".txpack" */
  private static JsonObject findTxPack(JsonObject lock, String lockFilePath) throws IOException {
    JsonObject packages = member(lock, lockFilePath, "packages");
    for (String name : packages.keySet()) {
      if (name.endsWith(".txpack") && packages.get(name).isJsonObject()) {
        return packages.getAsJsonObject(name);
      }
    }
    throw new IOException("fhir.lock has no *.txpack entry in 'packages': " + lockFilePath);
  }

  /** SSRI "sha256-<base64>" -> lowercase hex */
  private static String sha256FromIntegrity(String integrity, String lockFilePath) throws IOException {
    if (!integrity.startsWith("sha256-")) {
      throw new IOException("fhir.lock integrity is not sha256 SSRI: " + lockFilePath);
    }
    byte[] raw;
    try {
      raw = java.util.Base64.getDecoder().decode(integrity.substring("sha256-".length()));
    } catch (IllegalArgumentException e) {
      throw new IOException("fhir.lock integrity is not valid base64: " + lockFilePath);
    }
    if (raw.length != 32) {
      throw new IOException("fhir.lock integrity is not a sha256 digest: " + lockFilePath);
    }
    StringBuilder b = new StringBuilder();
    for (byte x : raw) {
      b.append(String.format("%02x", x));
    }
    return b.toString();
  }

  private static JsonObject parse(String lockFilePath) throws IOException {
    String src = FileUtilities.fileToString(lockFilePath);
    return (JsonObject) new com.google.gson.JsonParser().parse(src);
  }

  private static JsonObject member(JsonObject o, String lockFilePath, String name) throws IOException {
    if (!o.has(name) || !o.get(name).isJsonObject()) {
      throw new IOException("fhir.lock has no '" + name + "' object: " + lockFilePath);
    }
    return o.getAsJsonObject(name);
  }

  private static String string(JsonObject o, String lockFilePath, String name) throws IOException {
    if (!o.has(name)) {
      throw new IOException("fhir.lock is missing '" + name + "': " + lockFilePath);
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
