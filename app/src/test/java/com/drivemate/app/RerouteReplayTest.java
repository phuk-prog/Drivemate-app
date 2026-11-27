package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

/** Replays a synthetic wrong turn and a U-turn through the reroute rules. Not a recording. */
public class RerouteReplayTest {
    /** Simulates seconds of driving off the route and returns when a reroute would start (ms), or -1. */
    private static long firstRerouteMs(double offMetres, float accuracy, float speed,
                                       double routeBearing, double travelBearing, int seconds) {
        long offSince = 0;
        for (int t = 0; t <= seconds; t++) {
            long now = 10_000L + t * 1000L;
            if (ReroutePolicy.offRoute(offMetres, accuracy)) {
                if (offSince == 0) offSince = now;
                boolean wrong = ReroutePolicy.wrongWay(routeBearing, travelBearing, speed);
                if (ReroutePolicy.confirmed(offSince, now, speed, wrong)
                        && ReroutePolicy.mayRequest(now, -100_000L, -100_000L, 0)) return now - 10_000L;
            } else {
                offSince = 0;
            }
        }
        return -1;
    }

    @Test public void wrongTurnReroutesAfterFourSeconds() {
        // Turned right instead of straight on: 60 m off, still moving along a different road.
        assertEquals(4000, firstRerouteMs(60, 8f, 12f, 0, 90, 30));
    }

    @Test public void uTurnReroutesSooner() {
        // Turned round: 60 m off and heading the opposite way along the route.
        assertEquals(2000, firstRerouteMs(60, 8f, 12f, 0, 180, 30));
    }

    @Test public void stoppedOffRouteWaitsLonger() {
        assertEquals(8000, firstRerouteMs(60, 8f, 0.5f, 0, 90, 30));
    }

    @Test public void noisyFixesNeverReroute() {
        assertEquals(-1, firstRerouteMs(200, 70f, 12f, 0, 180, 60));
    }

    @Test public void briefGlitchDoesNotReroute() {
        long offSince = 0;
        boolean rerouted = false;
        for (int t = 0; t < 10; t++) {
            double off = t == 3 ? 90 : 5;                  // one bad fix
            long now = t * 1000L + 1000;
            if (ReroutePolicy.offRoute(off, 8f)) { if (offSince == 0) offSince = now; }
            else offSince = 0;
            rerouted |= ReroutePolicy.confirmed(offSince, now, 12f, false);
        }
        assertFalse(rerouted);
    }

    @Test public void repeatedFailuresSlowTheRetries() {
        long now = 0;
        int attempts = 0;
        int failures = 0;
        long lastAttempt = -1_000_000;
        for (long t = 0; t <= 600_000; t += 1000) {
            if (ReroutePolicy.mayRequest(t, lastAttempt, lastAttempt, failures)) {
                attempts++;
                failures++;
                lastAttempt = t;
            }
        }
        // 20 s fixed would give about 30 attempts in 10 minutes; backoff gives far fewer.
        assertTrue("attempts=" + attempts, attempts <= 9);
        assertTrue(attempts >= 5);
    }
}
