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
 *       from a cache dir through the packager into the pack seed layer.</li>
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
