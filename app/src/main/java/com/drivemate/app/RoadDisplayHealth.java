package com.drivemate.app;

/**
 * Verifies map readiness from rendered road geometry, not network responses.
 * A successful PMTiles request, loaded style, or GPS marker is NOT proof that
 * the navigable street map is visible.
 */
public final class RoadDisplayHealth {
    public enum State { NOT_APPLICABLE, LOADING, READY, FAILED }

    public static final long ROAD_TIMEOUT_MS = 12_000L;
    private long missingSince = -1;
    private State lastState = State.NOT_APPLICABLE;

    /** The source catalogue describes the UK map's real geographic bounds. */
    public static boolean expectsUkRoads(double latitude, double longitude, double zoom) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && Double.isFinite(zoom)
                && latitude >= 49.4191999 && latitude <= 61.13564
                && longitude >= -14.858498 && longitude <= 2.641786
                && zoom >= 9;
    }

    /**
     * Check on a rendered frame. 'roadCount' MUST be from MapLibre
     * queryRenderedFeatures of the visible street line layers; it must not
     * be a tile download count or a count of GPS/route overlay features.
     */
    public State check(long elapsedRealtime, double latitude, double longitude,
                       double zoom, boolean styleLoaded, int renderedRoadCount) {
        if (!expectsUkRoads(latitude, longitude, zoom)) {
            missingSince = -1;
            lastState = State.NOT_APPLICABLE;
            return lastState;
        }
        if (styleLoaded && renderedRoadCount > 0) {
            missingSince = -1;
            lastState = State.READY;
            return lastState;
        }
        if (missingSince < 0 || elapsedRealtime < missingSince)
            missingSince = elapsedRealtime;
        lastState = elapsedRealtime - missingSince >= ROAD_TIMEOUT_MS
                ? State.FAILED : State.LOADING;
        return lastState;
    }

    public void reset() {
        missingSince = -1;
        lastState = State.NOT_APPLICABLE;
    }

    public State lastState() { return lastState; }
}
