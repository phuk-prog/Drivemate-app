package com.drivemate.app;

import java.util.List;

/**
 * Pure rules for what the native map shows, so every zoom level looks the same.
 * The base layer (UK vector tiles) is always drawn; Drivemate's exact road data
 * is an overlay on top, never a replacement.
 */
final class MapLayers {
    private MapLayers() { }

    /** Street zoom where the exact local road overlay is loaded in free browsing. */
    static final double OVERLAY_MIN_ZOOM = 15;
    /** The overlay box is reloaded once the centre has moved this far. */
    static final double OVERLAY_RELOAD_METRES = 150;
    /** A street name is not repeated within this many pixels. */
    static final float LABEL_REPEAT_PX = 280;
    /** Speed-limit signs on the route closer together than this collapse into one. */
    static final double LIMIT_MARKER_GAP_METRES = 60;
    /** An entrance this close to the destination pin is drawn offset, not underneath it. */
    static final double MARKER_OVERLAP_METRES = 25;

    /** How far the centre may move before the base tiles are requested again. */
    static double baseReloadMetres(double zoom) {
        double z = Double.isFinite(zoom) ? zoom : 16;
        return Math.max(100, 4000 / Math.pow(2, Math.max(0, z - 13)));
    }

    /** Label density by zoom: places always, main roads from 14, local streets from 16. */
    static boolean labelVisible(int kind, double zoom) {
        if (kind == NativeMapScene.LABEL) return true;
        if (kind == NativeMapScene.MOTORWAY || kind == NativeMapScene.MAIN) return zoom >= 14;
        if (kind == NativeMapScene.LOCAL) return zoom >= 16;
        return false;
    }

    /** True when the same name already has a label within {@link #LABEL_REPEAT_PX}. */
    static boolean repeatsNearby(float x, float y, List<float[]> sameName) {
        if (sameName == null) return false;
        for (float[] q : sameName) if (Math.hypot(q[0] - x, q[1] - y) < LABEL_REPEAT_PX) return true;
        return false;
    }

    /**
     * Should a speed-limit sign be drawn on the route at a section start?
     * Not at or just ahead of the car (the speed cluster shows the current limit), not when it
     * repeats the previous section's limit, and not within {@link #LIMIT_MARKER_GAP_METRES} of
     * the last sign drawn.
     */
    static boolean showLimitMarker(int kmh, int previousKmh, double start, double lastShownStart, double progress) {
        if (!Double.isFinite(start) || start <= progress + 30) return false;
        if (kmh == previousKmh) return false;
        return !(Double.isFinite(lastShownStart) && start - lastShownStart < LIMIT_MARKER_GAP_METRES);
    }
}
