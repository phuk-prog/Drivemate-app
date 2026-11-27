package com.drivemate.app;

import java.util.List;

/**
 * Rules for offering a second route. The alternative is found by searching
 * again with the first route's roads made more expensive; this class decides
 * whether the result is worth showing.
 */
public final class AlternativeRoutes {
    private AlternativeRoutes() { }

    /** Search-cost multiplier for roads the primary route already uses. */
    public static final double PENALTY_FACTOR = 1.6;
    /** An alternative may be at most this much slower than the primary. */
    public static final double MAX_SLOWER_FRACTION = 0.25;

    /** True when the alternative is genuinely different and not much slower. */
    public static boolean accept(List<GeoMath.Point> primary, double primarySeconds,
                                 List<GeoMath.Point> alternative, double alternativeSeconds) {
        if (primary == null || alternative == null || primarySeconds <= 0 || alternativeSeconds <= 0) return false;
        if (alternativeSeconds > primarySeconds * (1 + MAX_SLOWER_FRACTION)) return false;
        return RouteIntelligence.genuinelyDifferent(primary, 0, alternative);
    }

    /** "+2 min" style label for the extra time, or "same time". */
    public static String slowerLabel(double primarySeconds, double alternativeSeconds) {
        long extra = Math.round((alternativeSeconds - primarySeconds) / 60.0);
        if (extra <= 0) return extra < 0 ? Math.abs(extra) + " min faster" : "same time";
        return "+" + extra + " min";
    }
}
