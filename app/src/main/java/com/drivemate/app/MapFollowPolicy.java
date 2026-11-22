package com.drivemate.app;

/**
 * Predictable, testable rules for when GPS should move the onscreen map.
 * Avoids the long-running map animations that made the vehicle appear to lag.
 */
public final class MapFollowPolicy {
    private MapFollowPolicy() { }

    public static boolean shouldMove(long lastUpdateMillis, long nowMillis,
                                     double metresMoved, float speedMetresPerSecond,
                                     double zoomDifference, double headingDifference) {
        if (lastUpdateMillis <= 0 || nowMillis < lastUpdateMillis) return true;
        boolean moving = speedMetresPerSecond >= 1.8f;
        long minInterval = moving ? 650 : 1500;
        if (nowMillis - lastUpdateMillis < minInterval) return false;
        if (moving && metresMoved >= 3.0) return true;
        if (!moving && metresMoved >= 12.0) return true;
        if (Math.abs(zoomDifference) >= 0.40) return true;
        return moving && Math.abs(headingDifference) >= 12;
    }

    public static double shortestHeadingDifference(double desired, double current) {
        return (desired - current + 540.0) % 360.0 - 180.0;
    }
}
