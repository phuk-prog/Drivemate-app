package com.drivemate.app;

/**
 * Separates a useful visual startup map from a verified driving GPS fix.
 * An old remembered location must NEVER become the route origin.
 * Testable without Android location services.
 */
public final class MapStartupPolicy {
    private MapStartupPolicy() { }

    public static final long MAX_REMEMBERED_MS = 30L * 24 * 60 * 60 * 1000;
    public static final long MAX_SYSTEM_CACHED_MS = 7L * 24 * 60 * 60 * 1000;
    public static final long MAX_NAVIGATION_FIX_MS = 20_000L;
    public static final float MAX_NAVIGATION_ACCURACY_METRES = 100f;

    public static final class Viewport {
        public final GeoMath.Point center;
        public final double zoom;
        /** True if from a saved area, not a new live GPS fix. */
        public final boolean remembered;

        public Viewport(GeoMath.Point center, double zoom, boolean remembered) {
            this.center = center;
            this.zoom = zoom;
            this.remembered = remembered;
        }
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -85.0 && lat <= 85.0
                && lon >= -180.0 && lon <= 180.0;
    }

    private static boolean acceptableAge(long when, long now, long maxAge) {
        return when > 0 && now >= when && now - when <= maxAge;
    }

    /** World view is never an acceptable default for a UK-focused navigation app. */
    public static Viewport initial(double lastLat, double lastLon,
                                   long savedAtMs, long nowMs) {
        if (validCoordinate(lastLat, lastLon)
                && acceptableAge(savedAtMs, nowMs, MAX_REMEMBERED_MS)) {
            return new Viewport(new GeoMath.Point(lastLat, lastLon), 13.5, true);
        }
        // Entire Great Britain fits; do not mistake the overview for GPS.
        return new Viewport(new GeoMath.Point(54.4, -2.5), 5.6, false);
    }

    /** A system cached GPS/network fix may restore an approximate map area. */
    public static boolean usableCachedMapArea(double lat, double lon,
                                               long timestampMs, long nowMs,
                                               float accuracyMetres) {
        return validCoordinate(lat, lon)
                && acceptableAge(timestampMs, nowMs, MAX_SYSTEM_CACHED_MS)
                && Float.isFinite(accuracyMetres)
                && accuracyMetres >= 0 && accuracyMetres <= 2500;
    }

    /** Navigation requires a recent, reasonably accurate fix — never saved map centre. */
    public static boolean freshForRouting(double lat, double lon, long fixTimeMs,
                                          long nowMs, float accuracyMetres) {
        return validCoordinate(lat, lon)
                && acceptableAge(fixTimeMs, nowMs, MAX_NAVIGATION_FIX_MS)
                && Float.isFinite(accuracyMetres)
                && accuracyMetres >= 0
                && accuracyMetres <= MAX_NAVIGATION_ACCURACY_METRES;
    }
}
