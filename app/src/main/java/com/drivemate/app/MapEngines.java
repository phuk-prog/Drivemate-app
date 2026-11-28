package com.drivemate.app;

/** Which engine draws the map. Pure Java so the choice and its labels are unit tested. */
final class MapEngines {
    private MapEngines() { }

    static final String PREF = "map_engine";
    /** GPU map on MapLibre Native: the default until DriveMate's own GPU engine passes the comparison tests. */
    static final String MAPLIBRE = "maplibre";
    /** DriveMate's own Canvas renderer (CPU). */
    static final String CLASSIC = "classic";
    /** DriveMate's own GPU engine (beta): OpenGL areas and roads, classic names and overlays on top. */
    static final String GPU = "gpu";
    static final String DEFAULT = MAPLIBRE;
    static final String[] ALL = {MAPLIBRE, GPU, CLASSIC};

    static String normalize(String id) {
        for (String e : ALL) if (e.equals(id)) return e;
        return DEFAULT;
    }

    static String label(String id) {
        switch (normalize(id)) {
            case CLASSIC: return "DriveMate classic";
            case GPU: return "DriveMate GPU (beta)";
            default: return "MapLibre (GPU)";
        }
    }

    static String description(String id) {
        switch (normalize(id)) {
            case CLASSIC: return "DriveMate's own renderer, drawn on the main processor";
            case GPU: return "DriveMate's own graphics-chip engine, still being tested";
            default: return "Smooth graphics-chip map (recommended)";
        }
    }
}
