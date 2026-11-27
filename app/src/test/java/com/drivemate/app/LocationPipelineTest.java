package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class LocationPipelineTest {
    private static FixQualityPolicy.Sample gps(double lat, double lon, long t, float acc) {
        return new FixQualityPolicy.Sample(lat, lon, t, acc, FixQualityPolicy.SOURCE_GPS);
    }

    @Test public void acceptsGoodFixAndTracksLast() {
        LocationPipeline p = new LocationPipeline();
        LocationPipeline.Decision d = p.offer(gps(53, -2, 1000, 5), false);
        assertTrue(d.accepted);
        assertEquals(FixQualityPolicy.Reason.ACCEPTED, d.reason);
        assertNotNull(p.lastAccepted());
        assertEquals(1, p.accepted());
    }

    @Test public void countsRejectionsByReason() {
        LocationPipeline p = new LocationPipeline();
        p.offer(gps(53, -2, 10_000, 5), false);
        p.offer(gps(53, -2, 10_000, 5), false);          // duplicate time
        p.offer(gps(53.00001, -2, 12_000, 70), false);   // poor, too soon
        p.offer(gps(53.00001, -2, 13_000, 500), false);  // far too poor
        assertEquals(1, p.count(FixQualityPolicy.Reason.STALE_OR_DUPLICATE));
        assertEquals(1, p.count(FixQualityPolicy.Reason.POOR_ACCURACY));
        assertEquals(1, p.count(FixQualityPolicy.Reason.TOO_POOR));
        assertEquals(3, p.rejected());
        assertTrue(p.rejectionSummary().contains("TOO_POOR 1"));
    }

    @Test public void rejectionSummaryIsNoneWhenClean() {
        LocationPipeline p = new LocationPipeline();
        p.offer(gps(53, -2, 1000, 5), false);
        assertEquals("none", p.rejectionSummary());
    }

    @Test public void rejectionSummaryContainsNoCoordinates() {
        LocationPipeline p = new LocationPipeline();
        p.offer(gps(53.123456, -2.654321, 1000, 5), false);
        p.offer(gps(53.123456, -2.654321, 1000, 5), false);
        String text = p.rejectionSummary() + p.bridgeSummary();
        assertFalse(text.contains("53.12"));
        assertFalse(text.contains("2.65"));
    }

    @Test public void mockIsCountedAndFlaggedOnce() {
        LocationPipeline p = new LocationPipeline();
        assertTrue(p.offer(gps(53, -2, 1000, 5), true).firstMock);
        assertFalse(p.offer(gps(53.00001, -2, 2000, 5), true).firstMock);
        assertEquals(2, p.mockFixes());
    }

    @Test public void mockFixesAreNotBlocked() {
        LocationPipeline p = new LocationPipeline();
        assertTrue(p.offer(gps(53, -2, 1000, 5), true).accepted);
    }

    @Test public void bridgeTimeAccumulates() {
        LocationPipeline p = new LocationPipeline();
        p.recordBridge(4000);
        p.recordBridge(2500);
        p.recordBridge(0);
        assertEquals(6500, p.bridgedMs());
        assertEquals(2, p.bridgeEpisodes());
        assertEquals("2 gaps, 6.5 s", p.bridgeSummary());
    }

    @Test public void resetClearsEverything() {
        LocationPipeline p = new LocationPipeline();
        p.offer(gps(53, -2, 1000, 5), true);
        p.recordBridge(1000);
        p.reset();
        assertNull(p.lastAccepted());
        assertEquals(0, p.accepted());
        assertEquals(0, p.mockFixes());
        assertEquals(0, p.bridgedMs());
    }

    /** Moderately poor fix: rejected at 3 s, accepted at 6 s when the speed is plausible. */
    @Test public void moderatelyPoorFixAcceptedAfterFiveSeconds() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        assertEquals(FixQualityPolicy.Reason.POOR_ACCURACY,
                FixQualityPolicy.decide(prev, gps(53.00005, -2, 3000, 70)));
        assertEquals(FixQualityPolicy.Reason.ACCEPTED,
                FixQualityPolicy.decide(prev, gps(53.00005, -2, 6000, 70)));
    }

    @Test public void moderatelyPoorFixRejectedWhenSpeedImplausible() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        // About 1.1 km in 6 s is far above 60 m/s.
        assertEquals(FixQualityPolicy.Reason.TELEPORT,
                FixQualityPolicy.decide(prev, gps(53.01, -2, 6000, 70)));
    }

    @Test public void veryPoorFixStillNeedsTenSeconds() {
        FixQualityPolicy.Sample prev = gps(53, -2, 0, 5);
        assertEquals(FixQualityPolicy.Reason.POOR_ACCURACY,
                FixQualityPolicy.decide(prev, gps(53.00005, -2, 6000, 90)));
        assertEquals(FixQualityPolicy.Reason.ACCEPTED,
                FixQualityPolicy.decide(prev, gps(53.00005, -2, 11_000, 90)));
    }

    @Test public void thresholdsAreSharedByPolicies() {
        assertEquals(LocationThresholds.MAX_NAVIGATION_FIX_AGE_MS, MapStartupPolicy.MAX_NAVIGATION_FIX_MS);
        assertEquals(LocationThresholds.GOOD_ACCURACY_METRES, FixQualityPolicy.GOOD_ACCURACY_METRES, 0f);
        assertEquals(LocationThresholds.FALLBACK_AFTER_MS, FixQualityPolicy.FALLBACK_AFTER_MS);
    }
}
