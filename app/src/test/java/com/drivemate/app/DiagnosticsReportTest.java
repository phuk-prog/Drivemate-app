package com.drivemate.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class DiagnosticsReportTest {
    @Test public void reportsArrowOffsetRelativeToCamera() {
        assertEquals("+0°", DiagnosticsReport.arrowOffset(90, 90));
        assertEquals("-60°", DiagnosticsReport.arrowOffset(30, 90));
        assertEquals("+20°", DiagnosticsReport.arrowOffset(10, 350));
        assertEquals("—", DiagnosticsReport.arrowOffset(Double.NaN, 90));
    }

    @Test public void unknownValuesShowDashNotNaN() {
        String text = DiagnosticsReport.build(new DiagnosticsReport.Snapshot());
        assertFalse(text.contains("NaN"));
        assertTrue(text.contains("Accuracy: —"));
        assertTrue(text.contains("Satellites used: —"));
    }

    @Test public void includesKeyFieldsButNoCoordinateLabels() {
        DiagnosticsReport.Snapshot s = new DiagnosticsReport.Snapshot();
        s.appVersion = "1.7.4-rc1";
        s.navigating = true;
        s.fixSource = "fused";
        s.accuracyMetres = 6f;
        s.satellitesUsed = 11;
        s.cameraBearing = 350;
        s.travelBearing = 10;
        String text = DiagnosticsReport.build(s);
        assertTrue(text.contains("App version: 1.7.4-rc1"));
        assertTrue(text.contains("Accuracy: 6 m"));
        assertTrue(text.contains("Satellites used: 11"));
        assertTrue(text.contains("Arrow vs camera: +20°"));
        String lower = text.toLowerCase();
        assertFalse(lower.contains("latitude"));
        assertFalse(lower.contains("longitude"));
        assertFalse(lower.contains("destination:"));
    }

    @Test public void includesLocationPipelineCountersWithoutCoordinates() {
        DiagnosticsReport.Snapshot s = new DiagnosticsReport.Snapshot();
        s.l5SatellitesUsed = 7;
        s.fixesAccepted = 120;
        s.fixRejections = "POOR_ACCURACY 4, TELEPORT 1";
        s.positionBridges = "2 gaps, 41.0 s";
        s.mockFixes = 0;
        s.estimatedPosition = true;
        s.headingFusion = 92;
        String text = DiagnosticsReport.build(s);
        assertTrue(text.contains("Dual-frequency (L5) satellites used: 7"));
        assertTrue(text.contains("Fixes accepted: 120"));
        assertTrue(text.contains("Fixes rejected: POOR_ACCURACY 4, TELEPORT 1"));
        assertTrue(text.contains("Estimated-position bridges: 2 gaps, 41.0 s"));
        assertTrue(text.contains("Estimated position now: yes"));
        assertTrue(text.contains("Mock location fixes: 0"));
        assertTrue(text.contains("Sensor-fused heading: 92°"));
    }
}
