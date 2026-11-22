package com.drivemate.app;

/**
 * Display-only smoothing; never used to rewrite routing geometry or override
 * the raw GPS accuracy/position for off-route decisions.
 */
public final class VehicleFilter {
    private GeoMath.Point previous;
    private long previousMillis;
    public GeoMath.Point apply(GeoMath.Point fix, long timestamp, float accuracyMetres,
                               float speedMetresPerSecond) {
        if (previous == null || timestamp < previousMillis
                || timestamp - previousMillis > 12000 || accuracyMetres > 65) {
            previous = fix;
        } else {
            double gap = GeoMath.distance(previous, fix);
            // A significant movement or discontinuity must not be hidden.
            if (gap > Math.max(50, speedMetresPerSecond * 4 + 30)) {
                previous = fix;
            } else {
                double alpha = speedMetresPerSecond < 2 ? 0.28 : 0.64;
                previous = new GeoMath.Point(
                        previous.lat + (fix.lat - previous.lat) * alpha,
                        previous.lon + (fix.lon - previous.lon) * alpha);
            }
        }
        previousMillis = timestamp;
        return previous;
    }
    public void reset() {
        previous = null;
        previousMillis = 0;
    }
}
