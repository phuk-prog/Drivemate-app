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
}
