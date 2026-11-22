package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class GpsSmootherTest {
    @Test public void filtersLargeUncertainJumpsButDoesNotFreezeAccurateFixes() {
        GpsSmoother smoother = new GpsSmoother();
        GeoMath.Point a = new GeoMath.Point(53, -2);
        GeoMath.Point impossible = new GeoMath.Point(53.02,-2);
        smoother.update(a, 8, 0, 100000);
        GeoMath.Point visual = smoother.update(impossible, 60, 0, 101000);
        assertEquals(a.lat,visual.lat,1e-8);
        visual=smoother.update(new GeoMath.Point(53.00005,-2), 5, 4, 102000);
        assertTrue(visual.lat>a.lat && visual.lat<53.00005);
    }
    @Test public void compassDoesNotSpinWhileStationary() {
        GpsSmoother smoother = new GpsSmoother();
        assertEquals(90, smoother.heading(90, 5), 0.01);
        assertEquals(90, smoother.heading(270, 0), 0.01);
    }
}
