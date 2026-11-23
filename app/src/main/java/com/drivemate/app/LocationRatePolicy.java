package com.drivemate.app;

/**
 * How often to ask the location providers for fixes. Pure Java so it can be
 * unit tested.
 *
 * Navigating: the fastest rate the receiver supports (0 ms, 0 m), because
 * guidance, the camera and dead reckoning all benefit from every fix.
 * Home screen and route preview: a lighter rate. The map only needs to
 * show roughly where you are, and GNSS at full rate is the app's main
 * battery cost while it sits open.
 */
public final class LocationRatePolicy {
    public static final long NAVIGATING_INTERVAL_MS = 0L;
    public static final float NAVIGATING_MIN_DISTANCE_METRES = 0f;
    public static final long IDLE_INTERVAL_MS = 2_000L;
    public static final float IDLE_MIN_DISTANCE_METRES = 5f;
    public static final long IDLE_NETWORK_INTERVAL_MS = 5_000L;

    private LocationRatePolicy() { }

    public static long gpsIntervalMs(boolean navigating) {
        return navigating ? NAVIGATING_INTERVAL_MS : IDLE_INTERVAL_MS;
    }

    public static float minDistanceMetres(boolean navigating) {
        return navigating ? NAVIGATING_MIN_DISTANCE_METRES : IDLE_MIN_DISTANCE_METRES;
    }

    /** Network (Wi-Fi/cell) fixes only matter as a fallback, so poll them less when idle. */
    public static long networkIntervalMs(boolean navigating) {
        return navigating ? NAVIGATING_INTERVAL_MS : IDLE_NETWORK_INTERVAL_MS;
    }
}
