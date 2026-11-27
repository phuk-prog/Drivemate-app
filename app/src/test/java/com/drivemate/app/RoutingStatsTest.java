package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class RoutingStatsTest {
    @Test public void summarisesCountsFailuresAndTimes() {
        RoutingStats s = new RoutingStats();
        s.recordRequest(); s.recordSuccess(1200, 340);
        s.recordRequest(); s.recordFailure("coverage");
        s.recordRequest(); s.recordFailure("coverage");
        s.recordSnap(12.4, 30.2);
        s.recordAlternative(true);
        s.recordAlternative(false);
        String text = s.summary();
        assertTrue(text, text.contains("3 searches, 1 found"));
        assertTrue(text, text.contains("failed: coverage 2"));
        assertTrue(text, text.contains("last 1200 nodes in 340 ms"));
        assertTrue(text, text.contains("snaps 12 m / 30 m"));
        assertTrue(text, text.contains("alternatives 1/2"));
    }

    @Test public void emptySummaryIsShort() {
        assertEquals("0 searches, 0 found", new RoutingStats().summary());
    }

    @Test public void blankFailureKindIsOther() {
        RoutingStats s = new RoutingStats();
        s.recordFailure("");
        assertTrue(s.summary().contains("other 1"));
    }

    @Test public void resetClears() {
        RoutingStats s = new RoutingStats();
        s.recordRequest(); s.recordFailure("x");
        s.reset();
        assertEquals("0 searches, 0 found", s.summary());
    }
}
