package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class HeadingFusionTest {
    @Test public void noHeadingBeforeLearning() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(10);
        assertTrue(Double.isNaN(h.fusedHeading(0)));
    }

    @Test public void learnsOffsetAndFollowsSensorChange() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(100);
        h.onTrustedGpsBearing(130, 0);               // offset +30
        assertEquals(130, h.fusedHeading(1000), 0.01);
        h.onSensorAzimuth(145);                      // sensor turned +45
        assertEquals(175, h.fusedHeading(2000), 0.01);
    }

    @Test public void wrapsAroundNorth() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(350);
        h.onTrustedGpsBearing(10, 0);                // offset +20
        h.onSensorAzimuth(355);
        assertEquals(15, h.fusedHeading(1000), 0.01);
    }

    @Test public void offsetSmoothingDoesNotAverageAcrossNorth() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(0);
        h.onTrustedGpsBearing(359, 0);               // offset -1
        h.onTrustedGpsBearing(1, 1000);              // measured +1
        double heading = h.fusedHeading(2000);
        assertTrue(heading > 355 || heading < 5);
    }

    @Test public void offsetExpires() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(10);
        h.onTrustedGpsBearing(20, 0);
        assertFalse(Double.isNaN(h.fusedHeading(LocationThresholds.HEADING_OFFSET_MAX_AGE_MS)));
        assertTrue(Double.isNaN(h.fusedHeading(LocationThresholds.HEADING_OFFSET_MAX_AGE_MS + 1)));
    }

    @Test public void ignoresNonFiniteInput() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(Double.NaN);
        h.onTrustedGpsBearing(Double.NaN, 0);
        assertTrue(Double.isNaN(h.fusedHeading(0)));
    }

    @Test public void resetForgetsOffset() {
        HeadingFusion h = new HeadingFusion();
        h.onSensorAzimuth(10);
        h.onTrustedGpsBearing(20, 0);
        h.reset();
        assertTrue(Double.isNaN(h.fusedHeading(0)));
    }

    @Test public void bearingTrustRules() {
        assertTrue(HeadingFusion.gpsBearingTrusted(true, 10f, 5f, true));
        assertTrue(HeadingFusion.gpsBearingTrusted(true, 10f, 0f, false));
        assertFalse("no bearing", HeadingFusion.gpsBearingTrusted(false, 10f, 5f, true));
        assertFalse("too slow", HeadingFusion.gpsBearingTrusted(true, 1f, 5f, true));
        assertFalse("inaccurate", HeadingFusion.gpsBearingTrusted(true, 10f, 40f, true));
    }
}
