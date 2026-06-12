package org.hl7.fhir.utilities.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.hl7.fhir.utilities.http.ManagedFhirWebAccessor.AdaptiveThrottle;
import org.junit.jupiter.api.Test;

class AdaptiveThrottleTests {

  @Test
  void startsAtMinOfTwelveAndMax() {
    assertEquals(12, new AdaptiveThrottle(64).currentPermits());
    assertEquals(8, new AdaptiveThrottle(8).currentPermits());
    assertEquals(2, new AdaptiveThrottle(1).currentPermits()); // floor of 2 applies to max too
  }

  @Test
  void throttledResponseHalvesDownToFloorOfTwo() throws IOException {
    AdaptiveThrottle t = new AdaptiveThrottle(64); // starts at 12
    throttleOnce(t);
    assertEquals(6, t.currentPermits());
    throttleOnce(t);
    assertEquals(3, t.currentPermits());
    throttleOnce(t);
    assertEquals(2, t.currentPermits());
    throttleOnce(t);
    assertEquals(2, t.currentPermits()); // floor
  }

  @Test
  void fiftyConsecutiveSuccessesCreepPermitsUpByOne() throws IOException {
    AdaptiveThrottle t = new AdaptiveThrottle(64);
    throttleOnce(t); // 12 -> 6
    for (int i = 0; i < 49; i++) {
      successOnce(t);
    }
    assertEquals(6, t.currentPermits()); // streak not yet complete
    successOnce(t);
    assertEquals(7, t.currentPermits());
    // a throttle resets the streak
    for (int i = 0; i < 49; i++) {
      successOnce(t);
    }
    throttleOnce(t);
    assertEquals(3, t.currentPermits());
    successOnce(t);
    assertEquals(3, t.currentPermits()); // streak restarted from zero
  }

  @Test
  void recoveryNeverExceedsConfiguredMax() throws IOException {
    AdaptiveThrottle t = new AdaptiveThrottle(4);
    assertEquals(4, t.currentPermits());
    for (int i = 0; i < 200; i++) {
      successOnce(t);
    }
    assertEquals(4, t.currentPermits());
  }

  private void throttleOnce(AdaptiveThrottle t) throws IOException {
    t.acquire();
    t.release(true, false);
  }

  private void successOnce(AdaptiveThrottle t) throws IOException {
    t.acquire();
    t.release(false, true);
  }
}
