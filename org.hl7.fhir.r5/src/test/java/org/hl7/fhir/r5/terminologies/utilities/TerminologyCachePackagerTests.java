package org.hl7.fhir.r5.terminologies.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.hl7.fhir.r5.formats.IParser.OutputStyle;
import org.hl7.fhir.r5.formats.JsonParser;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.TerminologyCapabilities;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.utilities.validation.ValidationMessage.IssueSeverity;
import org.hl7.fhir.utilities.validation.ValidationOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the answer-pack recording/packaging pipeline:
 * <ul>
 *   <li>the {@code org.hl7.fhir.tx.recordSemanticErrors} store policy (semantic, deterministic
 *       CODESYSTEM_UNSUPPORTED answers persist; transport failures never do);</li>
 *   <li>the poison predicate (transport markers only);</li>
 *   <li>round-trip: a recorded semantic error packaged and re-loaded through the pack seed layer
 *       yields an identical ValidationResult (verified field-wise AND by byte-identical
 *       re-persistence);</li>
 *   <li>capability artifacts (CapabilityStatement / TerminologyCapabilities / servers.ini) flowing
 *       from a cache dir through the packager into the pack seed layer;</li>
 *   <li>the canonical pack diff ({@code TerminologyCachePackager diff}): volatile fields
 *       (step timings, expansion ids/timestamps) normalized away, dir/zip equivalence, real
 *       changes reported.</li>
 * </ul>
 */
class TerminologyCachePackagerTests {

  private static final String UNKNOWN_SYSTEM = "http://example.org/fhir/CodeSystem/acme";
  private static final String UNKNOWN_SYSTEM_MESSAGE = "A definition for CodeSystem '" + UNKNOWN_SYSTEM
      + "' could not be found, so the code cannot be validated";
  private static final String SERVER = "http://localhost:3781/r5";

  private boolean savedRecordSemanticErrors;
  private String savedPackProperty;

  @BeforeEach
  void saveStatics() {
    savedRecordSemanticErrors = TerminologyCache.isRecordSemanticErrors();
    savedPackProperty = System.getProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
  }

  @AfterEach
  void restoreStatics() {
    TerminologyCache.setRecordSemanticErrors(savedRecordSemanticErrors);
    if (savedPackProperty == null) {
      System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    } else {
      System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, savedPackProperty);
    }
  }

  private static Path tempDir(String label) throws IOException {
    Path p = Files.createTempDirectory(label);
    p.toFile().deleteOnExit();
    return p;
  }

  private static Coding unknownCoding() {
    return new Coding().setSystem(UNKNOWN_SYSTEM).setCode("x1");
  }

  /** an unknown-system server answer, shaped the way the live route shapes them */
  private static ValidationResult unknownSystemResult() {
    OperationOutcome.OperationOutcomeIssueComponent iss = new OperationOutcome.OperationOutcomeIssueComponent();
    iss.setSeverity(OperationOutcome.IssueSeverity.ERROR);
    iss.setCode(OperationOutcome.IssueType.NOTFOUND);
    iss.getDetails().setText(UNKNOWN_SYSTEM_MESSAGE);
    List<OperationOutcome.OperationOutcomeIssueComponent> issues = new ArrayList<>(Arrays.asList(iss));
    ValidationResult vr = new ValidationResult(IssueSeverity.ERROR, UNKNOWN_SYSTEM_MESSAGE,
        TerminologyServiceErrorClass.CODESYSTEM_UNSUPPORTED, issues);
    vr.setUnknownSystems(new HashSet<>(Arrays.asList(UNKNOWN_SYSTEM)));
    vr.setServer(SERVER);
    return vr;
  }

  private static List<File> cachePages(Path dir) {
    List<File> pages = new ArrayList<>();
    for (File f : dir.toFile().listFiles()) {
      if (f.getName().endsWith(".cache") && !f.getName().startsWith(".")) {
        pages.add(f);
      }
    }
    return pages;
  }

  @Test
  void defaultPolicyStillDropsUnversionedCodesystemUnsupported() throws IOException {
    TerminologyCache.setRecordSemanticErrors(false);
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path dir = tempDir("txcache-control");
    TerminologyCache cache = new TerminologyCache(new Object(), dir.toString());
    TerminologyCache.CacheToken token = cache.generateValidationToken(new ValidationOptions(), unknownCoding(),
        (ValueSet) null, null);
    cache.cacheValidation(token, unknownSystemResult(), TerminologyCache.PERMANENT);
    assertTrue(cachePages(dir).isEmpty(), "default policy must not persist CODESYSTEM_UNSUPPORTED without version");
  }

  @Test
  void recordSemanticErrorsNeverPersistsTransportFailures() throws IOException {
    TerminologyCache.setRecordSemanticErrors(true);
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path dir = tempDir("txcache-poisoned");
    TerminologyCache cache = new TerminologyCache(new Object(), dir.toString());
    TerminologyCache.CacheToken token = cache.generateValidationToken(new ValidationOptions(), unknownCoding(),
        (ValueSet) null, null);
    // a transport failure mislabeled with the semantic error class must still not persist
    ValidationResult transport = new ValidationResult(IssueSeverity.ERROR,
        "Error from http://localhost:3781/r5: Read timed out", TerminologyServiceErrorClass.CODESYSTEM_UNSUPPORTED, null);
    cache.cacheValidation(token, transport, TerminologyCache.PERMANENT);
    assertTrue(cachePages(dir).isEmpty(), "transport failures must never be persisted by recordSemanticErrors");
  }

  @Test
  void recordedSemanticErrorRoundTripsThroughPack() throws IOException {
    TerminologyCache.setRecordSemanticErrors(true);
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);

    // 1. recording run: the semantic error persists
    Path recordDir = tempDir("txcache-record");
    TerminologyCache recording = new TerminologyCache(new Object(), recordDir.toString());
    TerminologyCache.CacheToken token = recording.generateValidationToken(new ValidationOptions(), unknownCoding(),
        (ValueSet) null, null);
    ValidationResult original = unknownSystemResult();
    recording.cacheValidation(token, original, TerminologyCache.PERMANENT);
    List<File> pages = cachePages(recordDir);
    assertEquals(1, pages.size(), "recording run must persist exactly one page");
    byte[] recordedPage = Files.readAllBytes(pages.get(0).toPath());
    assertTrue(new String(recordedPage, StandardCharsets.UTF_8).contains("CODESYSTEM_UNSUPPORTED"));

    // 2. packaging: the semantic-error entry is not poison and is kept
    Path packParent = tempDir("txpack-out");
    TerminologyCachePackager.BuildResult build = TerminologyCachePackager.build(recordDir.toString(), packParent.toString());
    assertEquals(1, build.entriesKept, "CODESYSTEM_UNSUPPORTED entry must pass the poison filter");
    assertEquals(0, build.poisonFiltered);

    // 3. pack seed layer: the same request resolves to an identical ValidationResult
    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, build.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    TerminologyCache.CacheToken token2 = packSeeded.generateValidationToken(new ValidationOptions(), unknownCoding(),
        (ValueSet) null, null);
    ValidationResult loaded = packSeeded.getValidation(token2);
    assertNotNull(loaded, "pack must answer the recorded request");
    assertEquals(1, packSeeded.getPackHitCount());

    assertEquals(original.getErrorClass(), loaded.getErrorClass());
    assertEquals(original.getSeverity(), loaded.getSeverity());
    assertEquals(original.getMessage(), loaded.getMessage());
    assertEquals(original.getServer(), loaded.getServer());
    assertEquals(original.getUnknownSystems(), loaded.getUnknownSystems());
    assertEquals(original.isInactive(), loaded.isInactive());
    assertEquals(original.getStatus(), loaded.getStatus());
    assertEquals(original.getDiagnostics(), loaded.getDiagnostics());
    assertNull(loaded.getCode());
    assertNull(loaded.getSystem());
    assertEquals(original.getIssues().size(), loaded.getIssues().size());
    for (int i = 0; i < original.getIssues().size(); i++) {
      assertTrue(original.getIssues().get(i).equalsDeep(loaded.getIssues().get(i)),
          "issue " + i + " must survive the round trip unchanged");
    }

    // 4. strongest identity check: re-persisting the pack-served result produces a byte-identical page
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path replayDir = tempDir("txcache-replay");
    TerminologyCache replay = new TerminologyCache(new Object(), replayDir.toString());
    TerminologyCache.CacheToken token3 = replay.generateValidationToken(new ValidationOptions(), unknownCoding(),
        (ValueSet) null, null);
    replay.cacheValidation(token3, loaded, TerminologyCache.PERMANENT);
    List<File> replayPages = cachePages(replayDir);
    assertEquals(1, replayPages.size());
    byte[] replayedPage = Files.readAllBytes(replayPages.get(0).toPath());
    assertEquals(new String(recordedPage, StandardCharsets.UTF_8), new String(replayedPage, StandardCharsets.UTF_8),
        "pack-served result must re-persist byte-identically to the live-recorded entry");
  }

  @Test
  void poisonPredicateMatchesTransportMarkersOnly() {
    // transport failures (the poison classes) are filtered
    assertTrue(TerminologyCachePackager.isPoison("e: {\"error\" : \"Error from http://localhost:3781/r5: boom\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"Error performing tx5 request: 500\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"java.net.SocketTimeoutException\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"Read timed out\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"connect timed out\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"Connection reset\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"Connection refused\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"java.net.UnknownHostException: tx.fhir.org\"}"));
    assertTrue(TerminologyCachePackager.isPoison("v: {\"error\" : \"Failed to connect to tx.fhir.org\"}"));

    // semantic, deterministic answers pass
    assertFalse(TerminologyCachePackager.isPoison("v: {\n  \"severity\" : \"error\",\n  \"error\" : \""
        + UNKNOWN_SYSTEM_MESSAGE + "\",\n  \"class\" : \"CODESYSTEM_UNSUPPORTED\",\n  \"server\" : \"" + SERVER + "\"\n}"));
    // the dropped over-broad markers no longer poison semantic text on their own
    assertFalse(TerminologyCachePackager.isPoison("v: {\"error\" : \"The code 'timed out' is not valid\"}"));
    assertFalse(TerminologyCachePackager.isPoison("v: {\"error\" : \"Display is 'Service unavailable'\"}"));
  }

  @Test
  void deterministicExpansionRefusalsAreNotPoison() {
    // the three refusal classes the server answers identically on every ask, as captured from the
    // live corpora: the EFhirClientException wrapper around an HTTP 422 + OperationOutcome
    String grammar = "e: {\n  \"from-server\" : true,\n  \"error\" : \"Error from " + SERVER
        + ": Error: The code System \\\"urn:ietf:bcp:47\\\" has a grammar, and cannot be enumerated directly\\r\\n\"\n}";
    String notFound = "e: {\n  \"from-server\" : true,\n  \"error\" : \"Error from " + SERVER
        + ": Error: A definition for CodeSystem 'urn:oid:1.2.36.1.2001.1005.17' could not be found, so the value set cannot be expanded\\r\\n\"\n}";
    String tooCostly = "e: {\n  \"from-server\" : true,\n  \"error\" : \"Error from " + SERVER
        + ": Error: The value set is too costly to expand\\r\\n\"\n}";
    // the client-composed compound wrapper around the same refusal
    String compound = "e: {\n  \"error\" : \"Unable to expand included value set 'http://x': Unable to expand imported value set: Error from "
        + SERVER + ": Error: A definition for CodeSystem 'doi:x' could not be found, so the value set cannot be expanded\\r\\n\"\n}";
    assertFalse(TerminologyCachePackager.isPoison(grammar), "grammar refusal must be pack-representable");
    assertFalse(TerminologyCachePackager.isPoison(notFound), "unknown-codesystem expansion refusal must be pack-representable");
    assertFalse(TerminologyCachePackager.isPoison(tooCostly), "too-costly refusal must be pack-representable");
    assertFalse(TerminologyCachePackager.isPoison(compound), "compound include-refusal must be pack-representable");
    assertTrue(TerminologyCachePackager.isDeterministicRefusal(grammar));

    // an HTTP wrapper around anything else stays poison
    assertTrue(TerminologyCachePackager.isPoison("e: {\"error\" : \"Error from " + SERVER + ": Internal Server Error\"}"));
    // a refusal text accompanied by a transport failure stays poison: transport markers win
    assertTrue(TerminologyCachePackager.isPoison("e: {\"error\" : \"Error from " + SERVER
        + ": has a grammar, and cannot be enumerated directly; Read timed out\"}"));
  }

  @Test
  void expansionRefusalRoundTripsThroughPack() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    String refusal = "Error from " + SERVER
        + ": Error: The code System \"urn:ietf:bcp:47\" has a grammar, and cannot be enumerated directly\r\n";

    // 1. a run persists the deterministic refusal (the expansion store path has no error-class gate)
    Path recordDir = tempDir("txcache-refusal");
    TerminologyCache recording = new TerminologyCache(new Object(), recordDir.toString());
    TerminologyCache.CacheToken token = recording.generateExpandToken("http://hl7.org/fhir/ValueSet/all-languages",
        org.hl7.fhir.r5.context.ExpansionOptions.cacheNoHeirarchy());
    recording.cacheExpansion(token, new org.hl7.fhir.r5.terminologies.expansion.ValueSetExpansionOutcome(
        refusal, TerminologyServiceErrorClass.UNKNOWN, true), TerminologyCache.PERMANENT);
    assertEquals(1, cachePages(recordDir).size(), "expansion refusal must persist");

    // 2. the packager keeps it (deterministic refusal, not poison)
    Path packParent = tempDir("txpack-refusal-out");
    TerminologyCachePackager.BuildResult build = TerminologyCachePackager.build(recordDir.toString(), packParent.toString());
    assertEquals(1, build.entriesKept, "deterministic refusal must pass the poison filter");
    assertEquals(0, build.poisonFiltered);

    // 3. the pack seed layer answers the same expansion request without the network
    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, build.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    TerminologyCache.CacheToken token2 = packSeeded.generateExpandToken("http://hl7.org/fhir/ValueSet/all-languages",
        org.hl7.fhir.r5.context.ExpansionOptions.cacheNoHeirarchy());
    org.hl7.fhir.r5.terminologies.expansion.ValueSetExpansionOutcome loaded = packSeeded.getExpansion(token2);
    assertNotNull(loaded, "pack must answer the recorded expansion refusal");
    assertEquals(refusal.trim(), loaded.getError().trim());
    assertTrue(loaded.isFromServer());
    assertEquals(1, packSeeded.getPackHitCount());
  }

  /** writes a minimal externals + system-map corpus into a cache dir; returns the positive ValueSet */
  private static ValueSet writeExternalsCorpus(Path cacheDir) throws IOException {
    ValueSet vs = new ValueSet();
    vs.setId("yesnodontknow");
    vs.setUrl("http://hl7.org/fhir/ValueSet/yesnodontknow");
    vs.setVersion("6.0.0");
    JsonParser json = new JsonParser();
    json.setOutputStyle(OutputStyle.PRETTY);
    Files.write(cacheDir.resolve("vs-0001.json"), json.composeString(vs).getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve("vs-externals.json"), ("{\n"
        + "  \"http://hl7.org/fhir/ValueSet/yesnodontknow\" : {\n"
        + "    \"server\" : \"" + SERVER + "\",\n"
        + "    \"filename\" : \"vs-0001.json\"\n"
        + "  },\n"
        + "  \"http://example.org/fhir/not-on-server\" : null\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve("cs-externals.json"), ("{\n"
        + "  \"http://www.whocc.no/atc\" : null\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve("system-map.json"), ("{\n"
        + "  \"systems\" : [{\n"
        + "    \"system\" : \"http://www.whocc.no/atc\",\n"
        + "    \"url\" : \"http://www.whocc.no/atc\",\n"
        + "    \"authoritative\" : [],\n"
        + "    \"candidates\" : [\"" + SERVER + "\"]\n"
        + "  }]\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));
    return vs;
  }

  @Test
  void externalsAndSystemMapFlowThroughPackToSeedLayer() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path cacheDir = tempDir("txcache-externals");
    ValueSet vs = writeExternalsCorpus(cacheDir);

    Path packParent = tempDir("txpack-externals-out");
    TerminologyCachePackager.BuildResult build = TerminologyCachePackager.build(cacheDir.toString(), packParent.toString());
    assertTrue(new File(build.packPath, "vs-externals.json").exists());
    assertTrue(new File(build.packPath, "cs-externals.json").exists());
    assertTrue(new File(build.packPath, "vs-0001.json").exists());
    assertTrue(new File(build.packPath, "system-map.json").exists());
    com.google.gson.JsonObject ext = build.manifest.getAsJsonObject("externalArtifacts");
    assertEquals(2, ext.get("valueSets").getAsInt());
    assertEquals(1, ext.get("valueSetsNegative").getAsInt());
    assertEquals(1, ext.get("codeSystems").getAsInt());
    assertEquals(1, ext.get("codeSystemsNegative").getAsInt());
    assertEquals(1, ext.get("systemMapEntries").getAsInt());

    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, build.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    assertEquals(2, packSeeded.getPackVsExternalsCount());
    assertEquals(1, packSeeded.getPackCsExternalsCount());

    // positive entry: known, and served as a parsed resource (a fresh copy per ask)
    assertTrue(packSeeded.hasValueSet("http://hl7.org/fhir/ValueSet/yesnodontknow"));
    TerminologyCache.SourcedValueSet svs = packSeeded.getValueSet("http://hl7.org/fhir/ValueSet/yesnodontknow");
    assertNotNull(svs);
    assertEquals(SERVER, svs.getServer());
    assertTrue(vs.equalsDeep(svs.getVs()), "pack-served ValueSet must round-trip");
    TerminologyCache.SourcedValueSet svs2 = packSeeded.getValueSet("http://hl7.org/fhir/ValueSet/yesnodontknow");
    assertTrue(svs.getVs() != svs2.getVs(), "each ask must get a defensive copy");

    // negative entries: known ("don't re-ask the server") but resolve to null
    assertTrue(packSeeded.hasValueSet("http://example.org/fhir/not-on-server"));
    assertNull(packSeeded.getValueSet("http://example.org/fhir/not-on-server"));
    assertTrue(packSeeded.hasCodeSystem("http://www.whocc.no/atc"),
        "negative CS answer must be known so findTxResource does not re-ask");
    assertNull(packSeeded.getCodeSystem("http://www.whocc.no/atc"));
    // unknown canonicals stay unknown
    assertFalse(packSeeded.hasValueSet("http://example.org/fhir/never-seen"));

    // system map text is exposed for the TerminologyClientManager seed layer
    assertNotNull(packSeeded.getPackSystemMapSource());
    org.hl7.fhir.r5.terminologies.client.TerminologyClientManager manager =
        new org.hl7.fhir.r5.terminologies.client.TerminologyClientManager(null, "test", null);
    manager.setCache(packSeeded);
    assertEquals(1, manager.getPackResolutionCount());
    assertTrue(manager.hasPackResolution("http://www.whocc.no/atc"),
        "pack resolution must be seeded so no /tx-reg/resolve call happens");
  }

  @Test
  void buildRefusesDanglingExternalsReference() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path cacheDir = tempDir("txcache-dangling");
    Files.write(cacheDir.resolve("vs-externals.json"), ("{\n"
        + "  \"http://example.org/vs\" : { \"server\" : \"" + SERVER + "\", \"filename\" : \"vs-missing.json\" }\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));
    Path packParent = tempDir("txpack-dangling-out");
    IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
        () -> TerminologyCachePackager.build(cacheDir.toString(), packParent.toString()));
    assertTrue(e.getMessage().contains("vs-missing.json"), "must name the dangling reference: " + e.getMessage());
  }

  @Test
  void mergeUnionsExternalsWithLaterSourceWinning() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path dirA = tempDir("txcache-ext-merge-a");
    writeExternalsCorpus(dirA);

    // source B: flips the negative VS answer to a positive one, and adds a new system-map entry
    Path dirB = tempDir("txcache-ext-merge-b");
    ValueSet vsB = new ValueSet();
    vsB.setId("now-on-server");
    vsB.setUrl("http://example.org/fhir/not-on-server");
    JsonParser json = new JsonParser();
    json.setOutputStyle(OutputStyle.PRETTY);
    Files.write(dirB.resolve("vs-0002.json"), json.composeString(vsB).getBytes(StandardCharsets.UTF_8));
    Files.write(dirB.resolve("vs-externals.json"), ("{\n"
        + "  \"http://example.org/fhir/not-on-server\" : {\n"
        + "    \"server\" : \"" + SERVER + "\",\n"
        + "    \"filename\" : \"vs-0002.json\"\n"
        + "  }\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));
    Files.write(dirB.resolve("system-map.json"), ("{\n"
        + "  \"systems\" : [{\n"
        + "    \"system\" : \"http://example.org/other\",\n"
        + "    \"url\" : \"http://example.org/other\",\n"
        + "    \"authoritative\" : [],\n"
        + "    \"candidates\" : []\n"
        + "  }]\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));

    Path packParent = tempDir("txpack-ext-merge-out");
    TerminologyCachePackager.BuildResult merge = TerminologyCachePackager.merge(
        Arrays.asList(dirA.toString(), dirB.toString()), packParent.toString());

    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, merge.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    // A's positive entry survives
    assertNotNull(packSeeded.getValueSet("http://hl7.org/fhir/ValueSet/yesnodontknow"));
    // B's later answer supersedes A's negative
    TerminologyCache.SourcedValueSet flipped = packSeeded.getValueSet("http://example.org/fhir/not-on-server");
    assertNotNull(flipped, "later source must supersede the earlier negative answer");
    assertEquals("now-on-server", flipped.getVs().getIdBase());
    // system maps are unioned
    org.hl7.fhir.r5.terminologies.client.TerminologyClientManager manager =
        new org.hl7.fhir.r5.terminologies.client.TerminologyClientManager(null, "test", null);
    manager.setCache(packSeeded);
    assertEquals(2, manager.getPackResolutionCount());
    assertTrue(manager.hasPackResolution("http://www.whocc.no/atc"));
    assertTrue(manager.hasPackResolution("http://example.org/other"));
  }

  /**
   * Regression: merging a PACK directory (made by build) with a delta cache dir must keep every
   * external resolution from the pack - above all the NEGATIVE entries, which are stored as json
   * nulls and were silently dropped when the merged index was re-serialized without serializeNulls
   * (Gson's default omits null members). A hermetic run consuming such a merged pack died at its
   * first negative-externals lookup.
   */
  @Test
  void mergeFromPackSourceKeepsAllExternalEntries() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    // fixture cache dir: N=2 ValueSet externals (1 positive with a file, 1 negative) + 1 negative CodeSystem external
    Path cacheDir = tempDir("txcache-packmerge-src");
    writeExternalsCorpus(cacheDir);
    Path baseParent = tempDir("txpack-packmerge-base");
    TerminologyCachePackager.BuildResult base = TerminologyCachePackager.build(cacheDir.toString(), baseParent.toString());
    assertEquals(2, base.manifest.getAsJsonObject("externalArtifacts").get("valueSets").getAsInt());

    // delta cache dir: 1 different external resolution
    Path delta = tempDir("txcache-packmerge-delta");
    Files.write(delta.resolve("vs-externals.json"), ("{\n"
        + "  \"http://example.org/fhir/delta-only\" : null\n"
        + "}\n").getBytes(StandardCharsets.UTF_8));

    Path mergeParent = tempDir("txpack-packmerge-out");
    TerminologyCachePackager.BuildResult merge = TerminologyCachePackager.merge(
        Arrays.asList(base.packPath, delta.toString()), mergeParent.toString());

    // the WRITTEN indexes (not just the manifest counts) must hold the union: N+1 ValueSets, negatives included
    com.google.gson.JsonObject vsIndex = readJson(new File(merge.packPath, "vs-externals.json"));
    assertEquals(3, vsIndex.entrySet().size(), "merged vs-externals.json must union the pack's N entries with the delta's 1");
    assertTrue(vsIndex.has("http://hl7.org/fhir/ValueSet/yesnodontknow"));
    assertTrue(vsIndex.has("http://example.org/fhir/not-on-server"), "the pack's negative entries must survive the merge");
    assertTrue(vsIndex.get("http://example.org/fhir/not-on-server").isJsonNull());
    assertTrue(vsIndex.has("http://example.org/fhir/delta-only"));
    com.google.gson.JsonObject csIndex = readJson(new File(merge.packPath, "cs-externals.json"));
    assertEquals(1, csIndex.entrySet().size(), "merged cs-externals.json must keep the pack's negative entry");
    assertTrue(csIndex.get("http://www.whocc.no/atc").isJsonNull());
    // every per-resource file the merged indexes reference must exist in the merged pack
    assertReferencedFilesExist(merge.packPath, vsIndex);
    assertReferencedFilesExist(merge.packPath, csIndex);
    assertTrue(new File(merge.packPath, "vs-0001.json").exists());

    // and the seed layer serves all of them, negatives included, without the network
    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, merge.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    assertEquals(3, packSeeded.getPackVsExternalsCount());
    assertEquals(1, packSeeded.getPackCsExternalsCount());
    assertNotNull(packSeeded.getValueSet("http://hl7.org/fhir/ValueSet/yesnodontknow"));
    assertTrue(packSeeded.hasValueSet("http://example.org/fhir/not-on-server"),
        "negative answer must stay known so a hermetic run does not re-ask the server");
    assertNull(packSeeded.getValueSet("http://example.org/fhir/not-on-server"));
    assertTrue(packSeeded.hasCodeSystem("http://www.whocc.no/atc"));
    assertNull(packSeeded.getCodeSystem("http://www.whocc.no/atc"));
    assertTrue(packSeeded.hasValueSet("http://example.org/fhir/delta-only"));
  }

  private static com.google.gson.JsonObject readJson(File f) throws IOException {
    assertTrue(f.exists(), "missing " + f);
    return (com.google.gson.JsonObject) new com.google.gson.JsonParser()
        .parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
  }

  private static void assertReferencedFilesExist(String packPath, com.google.gson.JsonObject index) {
    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : index.entrySet()) {
      if (e.getValue().isJsonNull()) {
        continue;
      }
      com.google.gson.JsonElement fn = e.getValue().getAsJsonObject().get("filename");
      if (fn != null && !fn.isJsonNull()) {
        assertTrue(new File(packPath, fn.getAsString()).exists(),
            "merged pack must carry '" + fn.getAsString() + "' referenced by '" + e.getKey() + "'");
      }
    }
  }

  /** a synthetic answer page in the on-disk format (entries between markers, request #### response) */
  private static String syntheticPage(String... requestResponsePairs) {
    StringBuilder b = new StringBuilder(TerminologyCache.ENTRY_MARKER).append("\r");
    for (int i = 0; i < requestResponsePairs.length; i += 2) {
      b.append("\n").append(requestResponsePairs[i]).append("\n").append(TerminologyCache.BREAK).append("\n")
          .append(requestResponsePairs[i + 1]).append("\n").append(TerminologyCache.ENTRY_MARKER).append("\r");
    }
    return b.append("\n").toString();
  }

  private static Path syntheticPack(String label, String pageContent) throws IOException {
    Path pack = tempDir(label);
    Files.write(pack.resolve("validation.cache"), pageContent.getBytes(StandardCharsets.UTF_8));
    return pack;
  }

  @Test
  void diffSelfComparesCanonicallyIdentical() throws IOException {
    Path pack = syntheticPack("txdiff-self", syntheticPage(
        "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"1234-5\"}}", "v: {\"result\" : true}",
        "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"5678-9\"}}", "v: {\"result\" : false}"));
    TerminologyCachePackager.DiffResult r = TerminologyCachePackager.diffPacks(pack.toString(), pack.toString());
    assertTrue(r.isIdentical(), "a pack must compare canonically identical to itself");
    assertTrue(r.added.isEmpty() && r.removed.isEmpty() && r.changed.isEmpty());
    assertTrue(r.renderMarkdown().contains("canonically identical"));
  }

  @Test
  void diffNormalizesVolatileFieldsAway() throws IOException {
    // the same logical entry from two recordings: step timings, expansion uuid and timestamps differ
    // in both the request key and the response value
    Path oldPack = syntheticPack("txdiff-volatile-old", syntheticPage(
        "{\"url\" : \"http://x/vs\", \"date\" : \"2026-01-01T00:00:00Z\"}",
        "e: {\"identifier\" : \"urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeffff0000\","
            + " \"timestamp\" : \"2026-01-02T03:04:05.123Z\", \"diagnostics\" : \"lookup took 12ms total\"}"));
    Path newPack = syntheticPack("txdiff-volatile-new", syntheticPage(
        "{\"url\" : \"http://x/vs\", \"date\" : \"2026-05-05T05:05:05+10:00\"}",
        "e: {\"identifier\" : \"urn:uuid:11111111-2222-3333-4444-555566667777\","
            + " \"timestamp\" : \"2026-02-03T04:05:06Z\", \"diagnostics\" : \"lookup took 7ms total\"}"));
    TerminologyCachePackager.DiffResult r = TerminologyCachePackager.diffPacks(oldPack.toString(), newPack.toString());
    assertTrue(r.isIdentical(), "volatile-only differences must compare canonically identical");
  }

  @Test
  void diffReportsRealResponseChange() throws IOException {
    String request = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"1234-5\"}}";
    Path oldPack = syntheticPack("txdiff-change-old", syntheticPage(request, "v: {\"result\" : true}"));
    Path newPack = syntheticPack("txdiff-change-new", syntheticPage(request, "v: {\"result\" : false}"));
    TerminologyCachePackager.DiffResult r = TerminologyCachePackager.diffPacks(oldPack.toString(), newPack.toString());
    assertFalse(r.isIdentical());
    assertEquals(1, r.changed.size(), "a real response change must be reported as changed");
    assertEquals(0, r.added.size());
    assertEquals(0, r.removed.size());
    assertEquals("validation.cache", r.changed.get(0).page);
    String md = r.renderMarkdown();
    assertTrue(md.contains("| changed | 1 |"));
    assertTrue(md.contains("**validation.cache**"));
    assertTrue(md.contains("was: `v: {\"result\" : true}`"));
    assertTrue(md.contains("now: `v: {\"result\" : false}`"));
  }

  // ---- recorder server-nondeterminism defenses: carry-forward + reproduce ----

  @Test
  void carryForwardKeepsPinnedAnswerWhenFreshRecordingMissesIt() throws IOException {
    // a transient server failure means the fresh recording lacks an answer the pinned pack has;
    // merge([pinned, fresh]) (pinned first, fresh wins on overlap) carries the pinned answer
    // forward so a flake can never become a spurious "removed".
    String keyA = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"1234-5\"}}";
    String keyB = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"5678-9\"}}";
    Path pinned = syntheticPack("cf-pinned",
        syntheticPage(keyA, "v: {\"result\" : true}", keyB, "v: {\"result\" : true}"));
    Path fresh = syntheticPack("cf-fresh", syntheticPage(keyA, "v: {\"result\" : true}")); // keyB missing (flake)
    Path out = tempDir("cf-out");
    TerminologyCachePackager.BuildResult candidate = TerminologyCachePackager.merge(
        Arrays.asList(pinned.toString(), fresh.toString()), out.toString());
    TerminologyCachePackager.DiffResult diff = TerminologyCachePackager.diffPacks(pinned.toString(), candidate.packPath);
    assertTrue(diff.isIdentical(), "a transiently-missing answer must be carried forward, not reported as removed");
  }

  @Test
  void reproduceFilterKeepsConfirmedChangeAndDropsUnreproducedFlap() throws IOException {
    String changed = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"1234-5\"}}";
    String flapped = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"5678-9\"}}";
    String stable = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"0000-0\"}}";
    Path pinned = syntheticPack("rf-pinned", syntheticPage(
        changed, "v: {\"result\" : true}", flapped, "v: {\"result\" : true}", stable, "v: {\"result\" : true}"));
    // build A: changed flips (real), flapped flips (a load blip), stable unchanged
    Path freshA = syntheticPack("rf-A", syntheticPage(
        changed, "v: {\"result\" : false}", flapped, "v: {\"result\" : false}", stable, "v: {\"result\" : true}"));
    // build B: changed reproduces the flip, flapped reverts (so it does NOT reproduce A), stable unchanged
    Path freshB = syntheticPack("rf-B", syntheticPage(
        changed, "v: {\"result\" : false}", flapped, "v: {\"result\" : true}", stable, "v: {\"result\" : true}"));
    Path filtered = tempDir("rf-filtered");
    int dropped = TerminologyCachePackager.reproduceFilter(
        freshA.toString(), freshB.toString(), pinned.toString(), filtered.toString());
    assertEquals(1, dropped, "only the unreproduced flap is dropped");
    Path out = tempDir("rf-out");
    TerminologyCachePackager.BuildResult candidate = TerminologyCachePackager.merge(
        Arrays.asList(pinned.toString(), filtered.toString()), out.toString());
    TerminologyCachePackager.DiffResult diff = TerminologyCachePackager.diffPacks(pinned.toString(), candidate.packPath);
    assertEquals(1, diff.changed.size(), "the reproduced change is proposed");
    assertEquals(0, diff.added.size());
    assertEquals(0, diff.removed.size(), "the flap is carried forward from pinned, never proposed as a change");
  }

  @Test
  void reproduceFilterConfirmsAdditionsAndDropsUnconfirmedOnes() throws IOException {
    String existing = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"1111-1\"}}";
    String addReal = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"2222-2\"}}";
    String addFlap = "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"3333-3\"}}";
    Path pinned = syntheticPack("ra-pinned", syntheticPage(existing, "v: {\"result\" : true}"));
    Path freshA = syntheticPack("ra-A", syntheticPage(
        existing, "v: {\"result\" : true}", addReal, "v: {\"result\" : true}", addFlap, "v: {\"result\" : true}"));
    Path freshB = syntheticPack("ra-B", syntheticPage(
        existing, "v: {\"result\" : true}", addReal, "v: {\"result\" : true}")); // addFlap not reproduced
    Path filtered = tempDir("ra-filtered");
    int dropped = TerminologyCachePackager.reproduceFilter(
        freshA.toString(), freshB.toString(), pinned.toString(), filtered.toString());
    assertEquals(1, dropped, "the unconfirmed addition is dropped");
    Path out = tempDir("ra-out");
    TerminologyCachePackager.BuildResult candidate = TerminologyCachePackager.merge(
        Arrays.asList(pinned.toString(), filtered.toString()), out.toString());
    TerminologyCachePackager.DiffResult diff = TerminologyCachePackager.diffPacks(pinned.toString(), candidate.packPath);
    assertEquals(1, diff.added.size(), "only the reproduced addition is proposed");
    assertEquals(0, diff.changed.size());
    assertEquals(0, diff.removed.size());
  }

  @Test
  void diffDirVersusZipOfEquivalentContentComparesIdentical() throws IOException {
    String page = syntheticPage(
        "{\"code\" : {\"system\" : \"http://loinc.org\", \"code\" : \"1234-5\"}}", "v: {\"result\" : true}");
    Path dirPack = syntheticPack("txdiff-dir", page);
    // the zip carries the same page CRLF-encoded under a nested path: base-name mapping and
    // CRLF normalization must make it compare identical to the LF directory read
    Path zipDir = tempDir("txdiff-zip");
    File zip = zipDir.resolve("pack.zip").toFile();
    try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
      zos.putNextEntry(new java.util.zip.ZipEntry("txpack-x/validation.cache"));
      zos.write(page.replace("\r", "").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
    }
    TerminologyCachePackager.DiffResult r = TerminologyCachePackager.diffPacks(dirPack.toString(), zip.getAbsolutePath());
    assertTrue(r.isIdentical(), "dir and zip of equivalent content must compare canonically identical");
  }

  @Test
  void capabilityArtifactsFlowThroughPackToSeedLayer() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    Path cacheDir = tempDir("txcache-caps");
    String serverId = "localhost:3781.r5";

    CapabilityStatement cs = new CapabilityStatement();
    cs.setUrl("https://localhost/r5/CapabilityStatement/tx");
    cs.getSoftware().setName("FHIRTerminologyServer").setVersion("0.9.5");
    TerminologyCapabilities tc = new TerminologyCapabilities();
    tc.setUrl("https://localhost/r5/TerminologyCapabilities/tx");
    tc.getSoftware().setName("FHIRTerminologyServer").setVersion("0.9.5");

    JsonParser json = new JsonParser();
    json.setOutputStyle(OutputStyle.PRETTY);
    Files.write(cacheDir.resolve("servers.ini"),
        ("[servers]\r\n" + serverId + " = " + SERVER + "\r\n").getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve(".capabilityStatement." + serverId + ".cache"),
        json.composeString(cs).trim().getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve(".terminologyCapabilities." + serverId + ".cache"),
        json.composeString(tc).trim().getBytes(StandardCharsets.UTF_8));

    Path packParent = tempDir("txpack-caps-out");
    TerminologyCachePackager.BuildResult build = TerminologyCachePackager.build(cacheDir.toString(), packParent.toString());
    assertEquals(3, build.manifest.getAsJsonArray("capabilityArtifacts").size(),
        "pack must carry both capability pages plus servers.ini");
    assertTrue(new File(build.packPath, ".capabilityStatement." + serverId + ".cache").exists());
    assertTrue(new File(build.packPath, ".terminologyCapabilities." + serverId + ".cache").exists());
    assertTrue(new File(build.packPath, "servers.ini").exists());

    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, build.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    assertTrue(packSeeded.getPackCapabilityAddresses().contains(SERVER));
    CapabilityStatement packCs = packSeeded.getPackCapabilityStatement(SERVER);
    assertNotNull(packCs, "pack must serve the CapabilityStatement for client init");
    assertEquals("0.9.5", packCs.getSoftware().getVersion());
    assertTrue(packSeeded.hasCapabilityStatement(SERVER));
    assertTrue(packSeeded.hasTerminologyCapabilities(SERVER));
    TerminologyCapabilities packTc = packSeeded.getTerminologyCapabilities(SERVER);
    assertNotNull(packTc);
    assertEquals("https://localhost/r5/TerminologyCapabilities/tx", packTc.getUrl());
  }

  @Test
  void mergeCarriesCapabilityArtifactsAndMergesServersIni() throws IOException {
    System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    JsonParser json = new JsonParser();
    json.setOutputStyle(OutputStyle.PRETTY);

    Path dirA = tempDir("txcache-merge-a");
    Files.write(dirA.resolve("servers.ini"),
        "[servers]\r\nlocalhost:3781.r5 = http://localhost:3781/r5\r\n".getBytes(StandardCharsets.UTF_8));
    TerminologyCapabilities tcA = new TerminologyCapabilities();
    tcA.setUrl("https://localhost/r5/TerminologyCapabilities/tx");
    Files.write(dirA.resolve(".terminologyCapabilities.localhost:3781.r5.cache"),
        json.composeString(tcA).trim().getBytes(StandardCharsets.UTF_8));

    Path dirB = tempDir("txcache-merge-b");
    Files.write(dirB.resolve("servers.ini"),
        "[servers]\r\ntx.fhir.org.r5 = http://tx.fhir.org/r5\r\n".getBytes(StandardCharsets.UTF_8));
    CapabilityStatement csB = new CapabilityStatement();
    csB.setUrl("http://tx.fhir.org/r5/CapabilityStatement/tx");
    Files.write(dirB.resolve(".capabilityStatement.tx.fhir.org.r5.cache"),
        json.composeString(csB).trim().getBytes(StandardCharsets.UTF_8));

    Path packParent = tempDir("txpack-merge-out");
    TerminologyCachePackager.BuildResult merge = TerminologyCachePackager.merge(
        Arrays.asList(dirA.toString(), dirB.toString()), packParent.toString());

    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, merge.packPath);
    TerminologyCache packSeeded = new TerminologyCache(new Object(), "n/a");
    assertTrue(packSeeded.hasTerminologyCapabilities("http://localhost:3781/r5"));
    assertNotNull(packSeeded.getPackCapabilityStatement("http://tx.fhir.org/r5"));
  }
}
