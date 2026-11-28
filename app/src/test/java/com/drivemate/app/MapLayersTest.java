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

    @Test public void sourceZoomFollowsScreenDensity() {
        // 2.625x phone at "zoom 14.7": zoom-13 tiles, never the sparse zoom-12 fallback of the old 16-tile cap.
        assertEquals(13, MapLayers.sourceZoom(14.7, 2.625));
        assertEquals(14, MapLayers.sourceZoom(17, 2.625));   // capped: tiles drawn enlarged beyond 14
        assertEquals(14, MapLayers.sourceZoom(14.5, 1));
        assertEquals(2, MapLayers.sourceZoom(1, 3));
        assertEquals(14, MapLayers.sourceZoom(14.7, Double.NaN));
        assertFalse(MapLayers.BROWSE_OVERLAY);
    }

    @Test public void roadsThinOutAtOverviewZoomAndKeepClassOrder() {
        float far = MapLayers.roadWidth(NativeMapScene.MOTORWAY, 11), near = MapLayers.roadWidth(NativeMapScene.MOTORWAY, 16);
        org.junit.Assert.assertTrue(far < near);
        for (double z = 8; z <= 20; z += .5) {
            org.junit.Assert.assertTrue(MapLayers.roadWidth(NativeMapScene.MOTORWAY, z) >= MapLayers.roadWidth(NativeMapScene.MAIN, z));
            org.junit.Assert.assertTrue(MapLayers.roadWidth(NativeMapScene.MAIN, z) >= MapLayers.roadWidth(NativeMapScene.LOCAL, z));
            org.junit.Assert.assertTrue(MapLayers.roadWidth(NativeMapScene.PATH, z) > 0);
        }
    }

    @Test public void casingsOnlyWhereRoadsAreWideEnough() {
        org.junit.Assert.assertFalse(MapLayers.casing(NativeMapScene.LOCAL, 12));
        org.junit.Assert.assertTrue(MapLayers.casing(NativeMapScene.MAIN, 12));
        org.junit.Assert.assertTrue(MapLayers.casing(NativeMapScene.LOCAL, 15));
    }

    @Test public void mapLoadsAreThrottledButFrequentWhileDragging() {
        org.junit.Assert.assertTrue(MapLayers.BASE_MIN_INTERVAL_MS <= 500);
        org.junit.Assert.assertTrue(MapLayers.DRAG_LOAD_INTERVAL_MS >= MapLayers.BASE_MIN_INTERVAL_MS);
    }
}
