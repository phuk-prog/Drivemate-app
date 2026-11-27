package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class MapLoadHealthTest {
    @Test public void firstFrameBeforeAnyLoadReportsNothing() {
        // This is the bug: an empty first frame used to report FAILED and show "detail unavailable".
        assertEquals(MapLoadHealth.Report.NONE, MapLoadHealth.report(false, false, 0));
        assertEquals(MapLoadHealth.Report.NONE, MapLoadHealth.report(false, true, 0));
    }

    @Test public void loadingInProgressReportsNothing() {
        assertEquals(MapLoadHealth.Report.NONE, MapLoadHealth.report(true, true, 0));
    }

    @Test public void failedOnlyAfterALoadFinishedWithNothingDrawn() {
        assertEquals(MapLoadHealth.Report.FAILED, MapLoadHealth.report(true, false, 0));
    }

    @Test public void roadsDrawnIsAlwaysReady() {
        assertEquals(MapLoadHealth.Report.READY, MapLoadHealth.report(false, true, 12));
        assertEquals(MapLoadHealth.Report.READY, MapLoadHealth.report(true, false, 1));
    }

    @Test public void retryBackoffIsBoundedAndCapped() {
        assertEquals(4_000, MapLoadHealth.retryDelayMs(1));
        assertEquals(8_000, MapLoadHealth.retryDelayMs(2));
        assertEquals(24_000, MapLoadHealth.retryDelayMs(6));
        assertEquals(-1, MapLoadHealth.retryDelayMs(0));
        assertEquals(-1, MapLoadHealth.retryDelayMs(MapLoadHealth.MAX_RETRIES + 1));
        for (int i = 1; i <= MapLoadHealth.MAX_RETRIES; i++) assertTrue(MapLoadHealth.retryDelayMs(i) <= 30_000);
    }

    @Test public void reasonIsShortAndCoordinateFree() {
        assertEquals("", MapLoadHealth.reason(null));
        assertEquals("IOException: Read timed out", MapLoadHealth.reason(new java.io.IOException("Read timed out")));
        assertEquals("IOException", MapLoadHealth.reason(new java.io.IOException()));
        String longText = new String(new char[500]).replace('\0', 'x');
        assertTrue(MapLoadHealth.reason(new RuntimeException(longText)).length() <= 120);
    }

    @Test public void partialReasonCountsTiles() {
        assertEquals("partial view, 1 tile still loading", MapLoadHealth.partialReason(1));
        assertEquals("partial view, 5 tiles still loading", MapLoadHealth.partialReason(5));
        assertEquals("partial view, 1 tile still loading", MapLoadHealth.partialReason(0));
    }
}
