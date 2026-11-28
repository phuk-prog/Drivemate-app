package com.drivemate.app;

import java.util.List;

/**
 * Pure rules for what the native map shows, so every zoom level looks the same.
 * The base layer (UK vector tiles) is always drawn; Drivemate's exact road data
 * is an overlay on top, never a replacement.
 */
final class MapLayers {
    private MapLayers() { }

    /** Off: in free browsing the base map is drawn alone, so every zoom looks the same. */
    static final boolean BROWSE_OVERLAY = false;
    /** Shortest gap between base map loads; later requests wait, they are never dropped. */
    static final long BASE_MIN_INTERVAL_MS = 350;
    /** When a view has more detail than the budget, load up to this many levels coarser instead of leaving holes. */
    static final int MAX_DETAIL_STEPS_DOWN = 2;
    /** While a finger drags the map, ask for tiles this often so new areas fill in before release. */
    static final long DRAG_LOAD_INTERVAL_MS = 400;
    /** Street zoom where the exact local road overlay would be loaded in free browsing. */
    static final double OVERLAY_MIN_ZOOM = 15;
    /** The overlay box is reloaded once the centre has moved this far. */
    static final double OVERLAY_RELOAD_METRES = 150;
    /** A street name is not repeated within this many pixels. */
    static final float LABEL_REPEAT_PX = 280;
    /** Speed-limit signs on the route closer together than this collapse into one. */
    static final double LIMIT_MARKER_GAP_METRES = 60;
    /** An entrance this close to the destination pin is drawn offset, not underneath it. */
    static final double MARKER_OVERLAP_METRES = 25;

    /** Tiles fetched for one view, and the most geometry one view may hold. */
    static final int MAX_VIEW_TILES = 49, MAX_VIEW_VERTICES = 900_000, MAX_VIEW_SHAPES = 80_000;
    /** Highest zoom stored in the vector tiles; beyond it tiles are drawn enlarged (overzoom). */
    static final int TOP_TILE_ZOOM = 14;

    /**
     * Tile zoom to fetch. The map's zoom counts 256-pixel tiles in physical pixels, so on a phone with
     * density 2.6 a "zoom 15" view covers what a zoom 13.6 view covers on a 1x screen; fetching zoom-15
     * tiles there would mean hundreds of tiles, and the old 16-tile cap then fell back to sparse zoom 12.
     */
    static int sourceZoom(double zoom, double density) {
        double z = Double.isFinite(zoom) ? zoom : 14;
        double dens = Double.isFinite(density) && density > 1 ? density : 1;
        int src = (int) Math.floor(z - Math.log(dens) / Math.log(2) + 0.6);
        return Math.max(2, Math.min(TOP_TILE_ZOOM, src));
    }

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
    /** Road line width in dp for a kind at a view zoom: thin at overview zoom so towns stay readable. */
    static float roadWidth(int kind,double zoom){
        float base=kind==NativeMapScene.MOTORWAY?7:kind==NativeMapScene.MAIN?5:kind==NativeMapScene.LOCAL?3.4f:1.2f;
        float k=(float)Math.max(.3,Math.min(1.6,1+(zoom-16)*.16));
        return Math.max(kind==NativeMapScene.PATH?.6f:.9f,base*k);
    }
    /** Casings only when roads are wide enough to show them; at overview zoom they turn into noise. */
    static boolean casing(int kind,double zoom){return zoom>=14||kind>=NativeMapScene.MAIN&&zoom>=11.5;}

}
