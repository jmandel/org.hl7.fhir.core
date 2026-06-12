package org.hl7.fhir.r5.terminologies.utilities;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import org.hl7.fhir.utilities.FileUtilities;
import org.hl7.fhir.utilities.Utilities;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Recorder/packager for terminology "answer packs".
 * <p/>
 * The TerminologyCache on-disk format (one named .cache page per system; each entry is the canonical
 * request JSON, a {@code ####} break, and the verbatim response) already IS the pack format. This tool
 * takes a cache directory preserved from a real (cold) run and emits a pack directory that
 * {@link TerminologyCache} can load as a read-only seed layer via {@code -Dorg.hl7.fhir.tx.pack=...}:
 * <ul>
 *   <li>every entry whose response indicates a transport/transient failure (the poison classes:
 *       "Error from http", "Error performing tx", timeouts/connection failures) is filtered out.
 *       Poison entries are unrepresentable in a pack: the single writer through which entries reach
 *       a pack page refuses them (throws), so no code path can emit one;</li>
 *   <li>a manifest.json records entry counts per system, generation timestamp, the source server
 *       URL(s) and the effective terminology edition versions per system - both extracted from the
 *       captured responses themselves (validate-code responses carry "server" and "version"
 *       values) - and the sha256 of the pack content;</li>
 *   <li>the pack directory is named by that sha256 ({@code txpack-<sha256>}).</li>
 * </ul>
 * Usage:
 * <pre>
 *   java ... TerminologyCachePackager build  &lt;sourceCacheDir&gt; &lt;outputParentDir&gt;
 *   java ... TerminologyCachePackager verify &lt;packDirOrZip&gt; [sampleCount]
 * </pre>
 */
public class TerminologyCachePackager {

  /**
   * Response substrings that mark an entry as poison (transport/transient failure captured from a
   * sick run). Pack files must never contain them.
   */
  private static final String[] POISON_MARKERS = {
      "Error from http",
      "Error performing tx",
      "SocketTimeoutException",
      "Read timed out",
      "connect timed out",
      "timed out",
      "Connection reset",
      "Connection refused",
      "UnknownHostException",
      "NoRouteToHostException",
      "ConnectException",
      "Failed to connect",
      "Service unavailable",
  };

  /** thrown if anything attempts to put a poison entry into a pack page */
  public static class PoisonEntryException extends IllegalArgumentException {
    PoisonEntryException(String message) {
      super(message);
    }
  }

  public static boolean isPoison(String response) {
    for (String marker : POISON_MARKERS) {
      if (response.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  private static String poisonMarkerIn(String response) {
    for (String marker : POISON_MARKERS) {
      if (response.contains(marker)) {
        return marker;
      }
    }
    return null;
  }

  /** one request/response pair, with the verbatim chunk text so pack pages stay byte-faithful */
  private static final class RawEntry {
    final String chunk;    // verbatim text between entry markers (as the loader sees it)
    final String request;  // canonical request JSON
    final String response; // response payload, e.g. "v: {...}", "e: {...}", "s: {...}"
    RawEntry(String chunk, String request, String response) {
      this.chunk = chunk;
      this.request = request;
      this.response = response;
    }
  }

  /**
   * The single point through which entries reach a pack page. Poison entries are unrepresentable:
   * {@link #add} throws on them, so a pack page can only ever be assembled from clean entries.
   */
  private static final class PackPageWriter {
    private final StringBuilder b = new StringBuilder();
    private int count = 0;

    PackPageWriter() {
      b.append(TerminologyCache.ENTRY_MARKER).append("\r");
    }

    void add(RawEntry e) {
      String marker = poisonMarkerIn(e.response);
      if (marker != null) {
        throw new PoisonEntryException("Refusing to write poison entry (contains '"+marker+"') to pack: "+e.request.trim());
      }
      // reconstructs the source bytes exactly: page = MARKER + "\r" + chunk + MARKER + "\r" ... + "\n"
      b.append(e.chunk);
      b.append(TerminologyCache.ENTRY_MARKER).append("\r");
      count++;
    }

    int getCount() {
      return count;
    }

    /** page content; byte-identical to the source page when no entry was filtered */
    String close() {
      b.append("\n");
      return b.toString();
    }
  }

  private static final class PageParse {
    final List<RawEntry> entries = new ArrayList<>();
  }

  /** splits a .cache page exactly the way TerminologyCache.loadNamedCache does */
  private static PageParse parsePage(String fn, String src) throws IOException {
    PageParse res = new PageParse();
    if (src.startsWith("?")) {
      src = src.substring(1);
    }
    int i = src.indexOf(TerminologyCache.ENTRY_MARKER);
    if (i != 0) {
      throw new IOException("Malformed cache page "+fn+": does not start with the entry marker");
    }
    src = src.substring(i + TerminologyCache.ENTRY_MARKER.length() + 1);
    i = src.indexOf(TerminologyCache.ENTRY_MARKER);
    while (i > -1) {
      String s = src.substring(0, i);
      src = src.substring(i + TerminologyCache.ENTRY_MARKER.length() + 1);
      i = src.indexOf(TerminologyCache.ENTRY_MARKER);
      if (!Utilities.noString(s)) {
        int j = s.indexOf(TerminologyCache.BREAK);
        if (j < 0) {
          throw new IOException("Malformed cache page "+fn+": entry without request/response break");
        }
        String request = s.substring(0, j);
        String response = s.substring(j + TerminologyCache.BREAK.length() + 1).trim();
        res.entries.add(new RawEntry(s, request, response));
      }
    }
    return res;
  }

  public static class BuildResult {
    public String packPath;
    public String sha256;
    public int entriesIn;
    public int entriesKept;
    public int poisonFiltered;
    public JsonObject manifest;
  }

  public static BuildResult build(String sourceCacheDir, String outputParentDir) throws IOException {
    File src = new File(sourceCacheDir);
    if (!src.isDirectory()) {
      throw new IOException("Source cache directory not found: "+sourceCacheDir);
    }

    Map<String, String> pages = new TreeMap<>();          // page file name -> filtered content
    Map<String, Integer> entryCounts = new TreeMap<>();   // system page name -> kept entry count
    TreeSet<String> servers = new TreeSet<>();
    Map<String, TreeSet<String>> effectiveVersions = new TreeMap<>(); // system url -> distinct versions
    int entriesIn = 0;
    int kept = 0;
    int poison = 0;

    String[] names = src.list();
    java.util.Arrays.sort(names);
    for (String fn : names) {
      if (!fn.endsWith(TerminologyCache.CACHE_FILE_EXTENSION) || fn.startsWith(".")) {
        continue; // skip .capabilityStatement.* / .terminologyCapabilities.* and non-cache files
      }
      String content = FileUtilities.fileToString(Utilities.path(sourceCacheDir, fn));
      PageParse page = parsePage(fn, content);
      PackPageWriter w = new PackPageWriter();
      for (RawEntry e : page.entries) {
        entriesIn++;
        if (isPoison(e.response)) {
          poison++;
          continue; // never offered to the writer; the writer would refuse it anyway
        }
        harvestMetadata(fn, e, servers, effectiveVersions);
        w.add(e);
        kept++;
      }
      if (w.getCount() > 0) {
        pages.put(fn, w.close());
        entryCounts.put(fn.substring(0, fn.length() - TerminologyCache.CACHE_FILE_EXTENSION.length()), w.getCount());
      }
    }

    String sha256 = sha256OfPages(pages);

    JsonObject manifest = new JsonObject();
    manifest.addProperty("format", "fhir-tx-cache-pack/1");
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    manifest.addProperty("generated", df.format(new Date()));
    manifest.addProperty("sourceCacheDir", src.getAbsolutePath());
    File verFile = new File(src, "version.ctl");
    if (verFile.exists()) {
      manifest.addProperty("sourceCacheVersion", FileUtilities.fileToString(verFile).trim());
    }
    JsonArray serverArr = new JsonArray();
    for (String s : servers) {
      serverArr.add(s);
    }
    manifest.add("sourceServers", serverArr);
    addServerSoftware(src, manifest);
    manifest.addProperty("entriesIn", entriesIn);
    manifest.addProperty("entriesKept", kept);
    manifest.addProperty("poisonFiltered", poison);
    JsonObject counts = new JsonObject();
    for (Map.Entry<String, Integer> e : entryCounts.entrySet()) {
      counts.addProperty(e.getKey(), e.getValue());
    }
    manifest.add("entryCountsPerSystem", counts);
    JsonObject versions = new JsonObject();
    for (Map.Entry<String, TreeSet<String>> e : effectiveVersions.entrySet()) {
      JsonArray arr = new JsonArray();
      for (String v : e.getValue()) {
        arr.add(v);
      }
      versions.add(e.getKey(), arr);
    }
    manifest.add("effectiveVersionsPerSystem", versions);
    manifest.addProperty("sha256", sha256);

    String packPath = Utilities.path(outputParentDir, "txpack-"+sha256);
    FileUtilities.createDirectory(packPath);
    for (Map.Entry<String, String> e : pages.entrySet()) {
      Files.write(Paths.get(Utilities.path(packPath, e.getKey())), e.getValue().getBytes(StandardCharsets.UTF_8));
    }
    Files.write(Paths.get(Utilities.path(packPath, "manifest.json")),
        new GsonBuilder().setPrettyPrinting().create().toJson(manifest).getBytes(StandardCharsets.UTF_8));

    BuildResult r = new BuildResult();
    r.packPath = packPath;
    r.sha256 = sha256;
    r.entriesIn = entriesIn;
    r.entriesKept = kept;
    r.poisonFiltered = poison;
    r.manifest = manifest;
    return r;
  }

  /** pulls server URL and effective system/version pairs out of a captured response */
  private static void harvestMetadata(String fn, RawEntry e, TreeSet<String> servers, Map<String, TreeSet<String>> effectiveVersions) throws IOException {
    char kind = e.response.isEmpty() ? '?' : e.response.charAt(0);
    if (kind != 'v' && kind != 'e' && kind != 's') {
      throw new IOException("Malformed cache page "+fn+": unknown response kind '"+kind+"'");
    }
    JsonObject o;
    try {
      o = (JsonObject) new com.google.gson.JsonParser().parse(e.response.substring(3));
    } catch (Exception ex) {
      throw new IOException("Malformed cache page "+fn+": unparseable response payload: "+ex.getMessage(), ex);
    }
    if (o.has("server") && o.get("server").isJsonPrimitive()) {
      servers.add(o.get("server").getAsString());
    }
    if (kind == 'v' && o.has("system") && o.get("system").isJsonPrimitive()) {
      String system = o.get("system").getAsString();
      String version = o.has("version") && o.get("version").isJsonPrimitive() ? o.get("version").getAsString() : "(none)";
      effectiveVersions.computeIfAbsent(system, k -> new TreeSet<>()).add(version);
    }
  }

  /** server software name/version from a captured .capabilityStatement.*.cache page, when one exists */
  private static void addServerSoftware(File src, JsonObject manifest) {
    JsonArray software = new JsonArray();
    String[] names = src.list();
    java.util.Arrays.sort(names);
    for (String fn : names) {
      if (fn.startsWith(".capabilityStatement") && fn.endsWith(TerminologyCache.CACHE_FILE_EXTENSION)) {
        try {
          JsonObject cs = (JsonObject) new com.google.gson.JsonParser().parse(FileUtilities.fileToString(new File(src, fn)));
          if (cs.has("software")) {
            software.add(cs.getAsJsonObject("software"));
          }
        } catch (Exception e) {
          // a stale/corrupt capability page must not block packaging; software stays absent for it
        }
      }
    }
    manifest.add("serverSoftware", software);
  }

  private static String sha256OfPages(Map<String, String> pages) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Map.Entry<String, String> e : pages.entrySet()) { // TreeMap: deterministic order
        digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
      }
      StringBuilder b = new StringBuilder();
      for (byte x : digest.digest()) {
        b.append(String.format("%02x", x));
      }
      return b.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new Error("SHA-256 unavailable", e);
    }
  }

  /**
   * Loads the pack through the real seed-layer code path (a TerminologyCache with no mutable folder)
   * and checks that sampled requests resolve, exactly as a build would resolve them.
   */
  public static boolean verify(String packPath, int sampleCount) throws IOException {
    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, packPath);
    TerminologyCache cache = new TerminologyCache(new Object(), "n/a");
    System.out.println("Pack "+packPath+" loaded: "+cache.getPackEntryCount()+" entries");

    // sample entries spread across pages, looked up via packContains (name + canonical request -> key)
    File pf = new File(packPath);
    List<String> pageNames = new ArrayList<>();
    for (String fn : pf.list()) {
      if (fn.endsWith(TerminologyCache.CACHE_FILE_EXTENSION)) {
        pageNames.add(fn);
      }
    }
    java.util.Collections.sort(pageNames);
    int checked = 0, found = 0;
    outer:
    for (String fn : pageNames) {
      PageParse page = parsePage(fn, FileUtilities.fileToString(Utilities.path(packPath, fn)));
      if (page.entries.isEmpty()) {
        continue;
      }
      String name = fn.substring(0, fn.length() - TerminologyCache.CACHE_FILE_EXTENSION.length());
      RawEntry e = page.entries.get(page.entries.size() / 2);
      boolean hit = cache.packContains(name, e.request);
      System.out.println("  ["+(hit ? "HIT " : "MISS")+"] "+name+" key="+cache.hashJson(e.request)+" request="+e.request.trim().replace("\n", " ").substring(0, Math.min(120, e.request.trim().length())));
      checked++;
      if (hit) {
        found++;
      }
      if (checked >= sampleCount) {
        break outer;
      }
    }
    System.out.println("Verified "+found+"/"+checked+" sampled lookups");
    return checked > 0 && found == checked;
  }

  public static void main(String[] args) throws Exception {
    if (args.length >= 3 && "build".equals(args[0])) {
      BuildResult r = build(args[1], args[2]);
      System.out.println("Pack written to "+r.packPath);
      System.out.println("  entries in: "+r.entriesIn+", kept: "+r.entriesKept+", poison filtered: "+r.poisonFiltered);
      System.out.println("  sha256: "+r.sha256);
      System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(r.manifest));
    } else if (args.length >= 2 && "verify".equals(args[0])) {
      int n = args.length >= 3 ? Integer.parseInt(args[2]) : 5;
      boolean ok = verify(args[1], n);
      if (!ok) {
        System.exit(1);
      }
    } else {
      System.out.println("Usage:");
      System.out.println("  TerminologyCachePackager build <sourceCacheDir> <outputParentDir>");
      System.out.println("  TerminologyCachePackager verify <packDirOrZip> [sampleCount]");
      System.exit(2);
    }
  }
}
