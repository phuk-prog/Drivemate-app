package com.drivemate.app;

/**
 * When to recalculate after leaving the route. Pure Java so it can be tested.
 *
 * Mainstream navigation apps reroute a few seconds after the car is clearly
 * off the route. DriveMate used to wait for 90 m off route for 12 s, and then
 * kept the old route unless the new start was over 90 m from it, so in town a
 * wrong turn often never produced a new route.
 */
public final class ReroutePolicy {
    /** Off-route fixes need at least this accuracy to count. */
    public static final float MAX_ACCURACY_METRES = 50f;
    /** Minimum gap between reroute attempts and route requests. */
    public static final long MIN_ATTEMPT_GAP_MS = 20_000L;
    public static final long MIN_REQUEST_GAP_MS = 15_000L;

    private ReroutePolicy() { }

    /** Distance from the route that counts as off it: 40 m, more for poor GPS (max 75 m). */
    public static double offRouteMetres(float accuracyMetres) {
        float accuracy = Float.isFinite(accuracyMetres) ? Math.max(0, accuracyMetres) : 100f;
        return Math.max(40, Math.min(75, accuracy * 1.5));
    }

    /** Whether one fix is clearly off the route. */
    public static boolean offRoute(double offRouteMetres, float accuracyMetres) {
        return Float.isFinite(accuracyMetres) && accuracyMetres <= MAX_ACCURACY_METRES
                && offRouteMetres > offRouteMetres(accuracyMetres);
    }

    /** Off route long enough to reroute: 4 s when moving, 8 s when slow or stopped. */
    public static boolean confirmed(long offSinceMs, long nowMs, float speedMps) {
        return confirmed(offSinceMs, nowMs, speedMps, false);
    }

    /** Driving the opposite way along the route is clear sooner: 2 s. */
    public static final long WRONG_WAY_CONFIRM_MS = 2_000L;
    public static final double WRONG_WAY_DEGREES = 135;

    public static boolean confirmed(long offSinceMs, long nowMs, float speedMps, boolean wrongWay) {
        boolean moving = Float.isFinite(speedMps) && speedMps >= 3f;
        long need = wrongWay && moving ? WRONG_WAY_CONFIRM_MS : moving ? 4_000L : 8_000L;
        return offSinceMs > 0 && nowMs - offSinceMs >= need;
    }

    /** Travelling against the route direction at speed (e.g. after turning round). Needs a real bearing. */
    public static boolean wrongWay(double routeBearing, double travelBearing, float speedMps) {
        if (!Double.isFinite(routeBearing) || !Double.isFinite(travelBearing)
                || !Float.isFinite(speedMps) || speedMps < 3f) return false;
        double d = Math.abs(((travelBearing - routeBearing) % 360 + 540) % 360 - 180);
        return d >= WRONG_WAY_DEGREES;
    }

    /** Retry gap after repeated failures: 20 s, then 40, 80, capped at 120 s. */
    public static long retryGapMs(int failures) {
        if (failures <= 0) return MIN_ATTEMPT_GAP_MS;
        return Math.min(120_000L, MIN_ATTEMPT_GAP_MS << Math.min(failures, 3));
    }

    public static boolean mayRequest(long nowMs, long lastAttemptMs, long lastRequestMs, int failures) {
        return nowMs - lastAttemptMs >= retryGapMs(failures)
                && nowMs - lastRequestMs >= MIN_REQUEST_GAP_MS;
    }

    /** Rate limit so a bad signal can't hammer the routing API. */
    public static boolean mayRequest(long nowMs, long lastAttemptMs, long lastRequestMs) {
        return nowMs - lastAttemptMs >= MIN_ATTEMPT_GAP_MS
                && nowMs - lastRequestMs >= MIN_REQUEST_GAP_MS;
    }
}
