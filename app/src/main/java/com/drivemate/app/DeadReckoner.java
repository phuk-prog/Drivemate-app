package com.drivemate.app;

/**
 * Keeps the vehicle marker moving through short GPS gaps (tunnels, cuttings,
 * multi-storey car parks) by extrapolating the last accepted fix along its own
 * speed and bearing. Pure Java so the maths can be unit tested.
 *
 * This is display-only. It never replaces a GPS fix for routing, off-route
 * detection or guidance decisions.
 */
public final class DeadReckoner {
    /** Only extrapolate once a gap is clearly longer than normal fix spacing. */
    public static final long MIN_GAP_MS = 1500L;
    /** Stop extrapolating after this long; hold the last estimate instead of drifting. */
    public static final long MAX_BRIDGE_MS = 6000L;
    /** Below walking pace the bearing is noise, so hold position. */
    public static final float MIN_SPEED_MPS = 2.0f;

    private static final double EARTH_METRES = 6371000.0;

    private GeoMath.Point anchor;
    private long anchorMs;
    private float speedMps;
    private double bearingDeg;
    private boolean moving;

    /**
     * Record an accepted fix as the new anchor.
     *
     * @param timeMs fix time in epoch milliseconds
     * @param bearingDeg direction of travel, degrees clockwise from north
     * @param hasBearing whether the fix carries a valid bearing
     */
    public void onFix(GeoMath.Point point, long timeMs, float speedMps,
                      double bearingDeg, boolean hasBearing) {
        anchor = point;
        anchorMs = timeMs;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        moving = hasBearing && speedMps >= MIN_SPEED_MPS;
    }

    /**
     * @param nowMs current time in epoch milliseconds
     * @return an estimated position, or null when there is nothing sensible to show
     */
    public GeoMath.Point estimate(long nowMs) {
        if (anchor == null || !moving) return null;
        long gap = nowMs - anchorMs;
        if (gap < MIN_GAP_MS || gap > MAX_BRIDGE_MS) return null;
        double metres = speedMps * (gap / 1000.0);
        return destination(anchor, bearingDeg, metres);
    }

    public void reset() {
        anchor = null;
        anchorMs = 0;
        speedMps = 0f;
        bearingDeg = 0;
        moving = false;
    }

    /** Point reached by travelling {@code metres} from {@code from} on {@code bearingDeg}. */
    static GeoMath.Point destination(GeoMath.Point from, double bearingDeg, double metres) {
        double delta = metres / EARTH_METRES;
        double theta = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(from.lat);
        double lon1 = Math.toRadians(from.lon);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(delta)
                + Math.cos(lat1) * Math.sin(delta) * Math.cos(theta));
        double lon2 = lon1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(lat1),
                Math.cos(delta) - Math.sin(lat1) * Math.sin(lat2));
        return new GeoMath.Point(Math.toDegrees(lat2), Math.toDegrees(lon2));
    }
}
