package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class MapLayersTest {
    @Test public void labelDensityByZoom() {
        assertTrue(MapLayers.labelVisible(NativeMapScene.LABEL, 10));
        assertFalse(MapLayers.labelVisible(NativeMapScene.MAIN, 13.5));
        assertTrue(MapLayers.labelVisible(NativeMapScene.MAIN, 14));
        assertFalse(MapLayers.labelVisible(NativeMapScene.LOCAL, 15));
        assertTrue(MapLayers.labelVisible(NativeMapScene.LOCAL, 16.5));
        assertFalse(MapLayers.labelVisible(NativeMapScene.BUILDING, 18));
    }

    @Test public void repeatedNamesAreDropped() {
        List<float[]> shown = new ArrayList<>();
        shown.add(new float[]{100, 100});
        assertTrue(MapLayers.repeatsNearby(200, 150, shown));
        assertFalse(MapLayers.repeatsNearby(600, 100, shown));
        assertFalse(MapLayers.repeatsNearby(200, 150, null));
    }

    @Test public void speedSignsAreNotStacked() {
        // At the car: the cluster shows it.
        assertFalse(MapLayers.showLimitMarker(32, -1, 10, Double.NaN, 0));
        // Same limit as before: no sign.
        assertFalse(MapLayers.showLimitMarker(32, 32, 200, Double.NaN, 0));
        assertTrue(MapLayers.showLimitMarker(48, 32, 200, Double.NaN, 0));
        // Two changes 20 m apart: only the first.
        assertFalse(MapLayers.showLimitMarker(64, 48, 220, 200, 0));
        assertTrue(MapLayers.showLimitMarker(64, 48, 300, 200, 0));
    }

    @Test public void baseReloadShrinksWithZoom() {
        assertTrue(MapLayers.baseReloadMetres(13) > MapLayers.baseReloadMetres(17));
        assertEquals(100, MapLayers.baseReloadMetres(20), 1e-9);
    }
}
