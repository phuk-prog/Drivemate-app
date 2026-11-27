package com.drivemate.app;

/**
 * Which point a route is planned from. The smoothed vehicle position is steadier
 * than a raw fix, but only while it still agrees with the raw fix.
 */
public final class RerouteStart {
    private RerouteStart() { }

    /** Use the smoothed point only when it lies within this distance of the raw fix. */
    public static final double MAX_DIFFERENCE_METRES = 30;

    public static GeoMath.Point choose(GeoMath.Point raw, GeoMath.Point smoothed) {
        if (raw == null) return smoothed;
        if (smoothed == null) return raw;
        return GeoMath.distance(raw, smoothed) <= MAX_DIFFERENCE_METRES ? smoothed : raw;
    }
}
