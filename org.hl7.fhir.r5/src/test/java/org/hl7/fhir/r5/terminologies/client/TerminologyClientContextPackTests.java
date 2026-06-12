package org.hl7.fhir.r5.terminologies.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.hl7.fhir.r5.formats.IParser.OutputStyle;
import org.hl7.fhir.r5.formats.JsonParser;
import org.hl7.fhir.r5.model.CapabilityStatement;
import org.hl7.fhir.r5.model.TerminologyCapabilities;
import org.hl7.fhir.r5.terminologies.utilities.TerminologyCache;
import org.hl7.fhir.r5.terminologies.utilities.TerminologyCachePackager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves that when an answer pack provides the server's CapabilityStatement and
 * TerminologyCapabilities, {@link TerminologyClientContext} initialization completes fully offline:
 * the terminology client stub throws on ANY metadata/capabilities call, so a single network attempt
 * fails the test (this is the client-init side of hermetic mode, where
 * {@code -Dorg.hl7.fhir.tx.hermetic=true} would block the HTTP request anyway - but blocked requests
 * fail the build, so init must not even try).
 */
class TerminologyClientContextPackTests {

  private static final String ADDRESS = "http://localhost:3781/r5";
  private static final String SERVER_ID = "localhost:3781.r5";

  private String savedPackProperty;
  private boolean savedAllowNonConformant;

  @BeforeEach
  void saveStatics() {
    savedPackProperty = System.getProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    savedAllowNonConformant = TerminologyClientContext.isAllowNonConformantServers();
  }

  @AfterEach
  void restoreStatics() {
    if (savedPackProperty == null) {
      System.clearProperty(TerminologyCache.PACK_SYSTEM_PROPERTY);
    } else {
      System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, savedPackProperty);
    }
    TerminologyClientContext.setAllowNonConformantServers(savedAllowNonConformant);
  }

  private ITerminologyClient networkRefusingClient() {
    return (ITerminologyClient) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] { ITerminologyClient.class }, (proxy, method, args) -> {
          switch (method.getName()) {
          case "getAddress":
            return ADDRESS;
          case "getCapabilitiesStatement":
          case "getCapabilitiesStatementQuick":
          case "getTerminologyCapabilities":
            throw new AssertionError("offline client init must not fetch " + method.getName());
          default:
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
              return false;
            }
            if (rt == int.class) {
              return 0;
            }
            if (rt == long.class) {
              return 0L;
            }
            return null;
          }
        });
  }

  @Test
  void clientContextInitializesOfflineFromPackCapabilities() throws IOException {
    // a recorded cache dir with the two capability artifacts and the servers.ini that names them
    Path cacheDir = Files.createTempDirectory("txcache-ctx-caps");
    cacheDir.toFile().deleteOnExit();
    CapabilityStatement cs = new CapabilityStatement();
    cs.getSoftware().setName("FHIRTerminologyServer").setVersion("0.9.5");
    TerminologyCapabilities tc = new TerminologyCapabilities();
    tc.setUrl("https://localhost/r5/TerminologyCapabilities/tx");
    tc.getSoftware().setName("FHIRTerminologyServer").setVersion("0.9.5");
    JsonParser json = new JsonParser();
    json.setOutputStyle(OutputStyle.PRETTY);
    Files.write(cacheDir.resolve("servers.ini"),
        ("[servers]\r\n" + SERVER_ID + " = " + ADDRESS + "\r\n").getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve(".capabilityStatement." + SERVER_ID + ".cache"),
        json.composeString(cs).trim().getBytes(StandardCharsets.UTF_8));
    Files.write(cacheDir.resolve(".terminologyCapabilities." + SERVER_ID + ".cache"),
        json.composeString(tc).trim().getBytes(StandardCharsets.UTF_8));

    Path packParent = Files.createTempDirectory("txpack-ctx-out");
    packParent.toFile().deleteOnExit();
    TerminologyCachePackager.BuildResult build = TerminologyCachePackager.build(cacheDir.toString(), packParent.toString());

    System.setProperty(TerminologyCache.PACK_SYSTEM_PROPERTY, build.packPath);
    TerminologyCache cache = new TerminologyCache(new Object(), "n/a");

    TerminologyClientContext.setAllowNonConformantServers(true); // the synthesized CS carries no feature extensions
    TerminologyClientContext context = new TerminologyClientContext(networkRefusingClient(), cache, "test-cache-id", true);

    assertNotNull(context.getTxCapabilities(), "TerminologyCapabilities must come from the pack");
    assertEquals("https://localhost/r5/TerminologyCapabilities/tx", context.getTxCapabilities().getUrl());
    assertEquals("0.9.5", context.getTxCapabilities().getSoftware().getVersion());
  }
}
