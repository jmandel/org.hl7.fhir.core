package org.hl7.fhir.r5.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hl7.fhir.r5.context.ContextUtilities;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.StructureDefinition.TypeDerivationRule;
import org.hl7.fhir.r5.test.utils.TestingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the parallel-validation snapshot-generation data race.
 *
 * <p>{@link ContextUtilities#generateSnapshot(StructureDefinition)} mutates the profile's
 * differential AND the shared base {@link StructureDefinition} in place. When the spec build began
 * validating examples on a thread pool, multiple threads generated snapshots over the same shared
 * base concurrently, intermittently tearing a base element's type (observed as
 * "invalid constrained type string from Narrative" on DiagnosticReport.text, ~1 build in 3, never at
 * threads=1). The fix serializes lazy generation on a single reentrant lock. This test reproduces
 * the contention - many profiles sharing one base, generated concurrently - and asserts every result
 * is error-free and byte-identical to a serial reference, so a regression (removing the lock) fails
 * here instead of flaking the build.
 */
class SnapshotGenerationConcurrencyTest {

  private static final String DIAGNOSTIC_REPORT = "http://hl7.org/fhir/StructureDefinition/DiagnosticReport";
  private static final int THREADS = 8;
  private static final int ROUNDS = 12;

  /** a minimal CONSTRAINT profile on the given base - enough to drive snapshot generation (and the
   *  in-place base mutation), without depending on any particular differential content. */
  private static StructureDefinition constraintProfile(StructureDefinition base, String suffix) {
    StructureDefinition p = new StructureDefinition();
    p.setUrl("http://test.org/race/" + suffix);
    p.setName("RaceProfile_" + suffix);
    p.setType(base.getType());
    p.setBaseDefinition(base.getUrl());
    p.setDerivation(TypeDerivationRule.CONSTRAINT);
    p.setAbstract(false);
    p.setKind(base.getKind());
    p.setFhirVersion(base.getFhirVersion());
    p.getDifferential().addElement().setPath(base.getType());
    return p;
  }

  @Test
  void generateSnapshotIsThreadSafeOverASharedBase() throws Exception {
    IWorkerContext ctx = new SimpleWorkerContext(TestingUtilities.getSharedWorkerContext());
    StructureDefinition base = ctx.fetchResource(StructureDefinition.class, DIAGNOSTIC_REPORT,
        IWorkerContext.VersionResolutionRules.defaultRule());
    assertNotNull(base, "base DiagnosticReport must resolve");

    // serial reference: the correct, deterministic snapshot for this profile shape
    StructureDefinition ref = constraintProfile(base, "ref");
    new ContextUtilities(ctx).generateSnapshot(ref);
    assertTrue(ref.hasSnapshot(), "serial reference snapshot must be generated");
    int refCount = ref.getSnapshot().getElement().size();

    for (int round = 0; round < ROUNDS; round++) {
      List<StructureDefinition> profiles = new ArrayList<>();
      for (int i = 0; i < THREADS; i++) {
        profiles.add(constraintProfile(base, "r" + round + "_t" + i));
      }
      List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
      ExecutorService pool = Executors.newFixedThreadPool(THREADS);
      CountDownLatch gun = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (StructureDefinition p : profiles) {
        futures.add(pool.submit(() -> {
          try {
            gun.await();
            new ContextUtilities(ctx).generateSnapshot(p); // all threads share the same base
          } catch (Throwable t) {
            errors.add(t);
          }
        }));
      }
      gun.countDown(); // release simultaneously to maximise contention on the shared base
      for (Future<?> f : futures) {
        f.get(120, TimeUnit.SECONDS);
      }
      pool.shutdown();

      assertTrue(errors.isEmpty(), "round " + round + ": concurrent generateSnapshot threw: " + errors);
      for (StructureDefinition p : profiles) {
        assertTrue(p.hasSnapshot(), "round " + round + ": " + p.getName() + " has no snapshot");
        // determinism: the concurrent snapshot must match the serial reference exactly - any torn
        // read of the shared base would change the element set / types
        assertEquals(refCount, p.getSnapshot().getElement().size(),
            "round " + round + ": " + p.getName() + " snapshot element count diverged from the serial reference");
        assertTrue(ref.getSnapshot().equalsDeep(p.getSnapshot()),
            "round " + round + ": " + p.getName() + " snapshot is not byte-identical to the serial reference");
      }
    }
  }

  /**
   * Regression test for the {@link ContextUtilities#allStructures()} parallel-validation data race.
   *
   * <p>One {@link ContextUtilities} instance is shared across all worker threads (it is cached on the
   * worker context as part of the {@code FHIRPathAnalysis} every per-thread {@code FHIRPathEngine}
   * retrieves). {@code allStructures()} lazily built {@code allStructuresList} with an unsynchronized
   * {@code if (isEmpty()) ... list.add(...)}: two threads first-touching it concurrently both ran the
   * loop and {@code add()}ed into the same {@link ArrayList}, tearing it - leaving a {@code null} slot
   * that an immediate consumer ({@code FHIRPathEngine.addTypeAndDescendents} doing
   * {@code sd.hasBaseDefinition()}) dereferenced into a message-less NPE. That surfaced when validating
   * {@code SearchParameter} "searchparameter-example-constraint" (expression
   * {@code Bundle.entry[0].resource}) as an intermittent extra ERROR (~1 build in 4-10, never at
   * threads=1). The fix builds into a private local list and publishes it in one assignment. This test
   * hammers a fresh (first-touch) shared instance from many threads and asserts no null slot, no
   * exception, and a size identical to a serial reference - so removing the lock fails here.
   */
  @Test
  void allStructuresIsThreadSafeUnderConcurrentFirstTouch() throws Exception {
    IWorkerContext ctx = new SimpleWorkerContext(TestingUtilities.getSharedWorkerContext());
    // serial reference + warm the shared SDs' snapshots once, so the racy part exercised below is the
    // (cheap) concurrent list build itself, letting us run many trials quickly.
    int refSize = new ContextUtilities(ctx).allStructures().size();
    assertTrue(refSize > 0, "serial allStructures() must be non-empty");

    int threads = Math.max(8, Runtime.getRuntime().availableProcessors());
    for (int round = 0; round < 100; round++) {
      // fresh ContextUtilities == fresh empty lazy field == first-touch race window, exactly mirroring
      // analysis.cu being touched for the first time by N worker threads under the validation pool.
      ContextUtilities cu = new ContextUtilities(ctx);
      List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
      List<Integer> sizes = Collections.synchronizedList(new ArrayList<Integer>());
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch gun = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(pool.submit(() -> {
          try {
            gun.await();
            List<StructureDefinition> all = cu.allStructures();
            int n = 0;
            for (StructureDefinition sd : all) {
              assertNotNull(sd, "null slot in shared allStructuresList (torn concurrent ArrayList.add)");
              sd.hasBaseDefinition(); // the exact dereference that NPEs pre-fix
              n++;
            }
            sizes.add(n);
          } catch (Throwable th) {
            errors.add(th);
          }
        }));
      }
      gun.countDown();
      for (Future<?> f : futures) {
        f.get(120, TimeUnit.SECONDS);
      }
      pool.shutdown();

      assertTrue(errors.isEmpty(), "round " + round + ": concurrent allStructures() threw: " + errors);
      for (int n : sizes) {
        assertEquals(refSize, n, "round " + round + ": a thread saw a different allStructures() size -> torn build");
      }
    }
  }
}
