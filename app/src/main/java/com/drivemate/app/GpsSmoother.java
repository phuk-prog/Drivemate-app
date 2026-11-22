package com.drivemate.app;

/** Visual-only GPS smoothing. Never use filtered values for rerouting or ETA. */
public final class GpsSmoother {
    private GeoMath.Point displayed;
    private double bearing = Double.NaN;
    private long lastTime;

    public void reset() {
        displayed = null;
        bearing = Double.NaN;
        lastTime = 0;
    }

    public GeoMath.Point update(GeoMath.Point raw, float accuracyM,
                                float speedMps, long epochMillis) {
        if (displayed == null || lastTime == 0 || epochMillis - lastTime > 12000) {
            displayed = raw;
            lastTime = epochMillis;
            return displayed;
        }
        if (epochMillis <= lastTime) return displayed;
        double step = GeoMath.distance(displayed, raw);
        double maxPlausible = Math.max(65, speedMps * (epochMillis - lastTime) / 1000.0 + 50);
        if (step > maxPlausible && accuracyM > 18) {
            // Ignore implausible inaccurate jump, without affecting true location.
            return displayed;
        }
        double alpha = speedMps > 8 ? 0.78 : speedMps > 2 ? 0.62 : 0.30;
        if (accuracyM > 35) alpha *= 0.45;
        displayed = new GeoMath.Point(
                displayed.lat + alpha * (raw.lat - displayed.lat),
                displayed.lon + alpha * (raw.lon - displayed.lon));
        lastTime = epochMillis;
        return displayed;
    }

    public double heading(double rawDegrees, float speedMps) {
        if (!Double.isFinite(rawDegrees)) return Double.isFinite(bearing) ? bearing : 0;
        if (!Double.isFinite(bearing)) bearing = rawDegrees;
        if (speedMps < 1.5) return bearing;
        double delta = (rawDegrees - bearing + 540) % 360 - 180;
        bearing = (bearing + delta * 0.35 + 360) % 360;
        return bearing;
    }
}
