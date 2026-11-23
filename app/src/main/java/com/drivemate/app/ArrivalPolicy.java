package com.drivemate.app;

/**
 * Conservative GPS-based arrival confirmation.
 *
 * A nearby map pin alone is insufficient to end navigation: the vehicle must
 * have genuinely finished the route and remained near the destination across
 * three fresh, distinct, precise, low-speed GPS fixes.
 */
public final class ArrivalPolicy {
    private static final double APPROACH_METRES = 700;
    private int consecutive;
    private long lastFixAt = -1;

    public enum State { EN_ROUTE, APPROACHING, NEARBY, ARRIVED }

    public void reset() { consecutive = 0; lastFixAt = -1; }

    public State observe(double pinDistance, double remainingRoute, double offRoute,
                         float accuracy, float speedMps, long fixAt, long now) {
        if (!Double.isFinite(pinDistance) || !Double.isFinite(remainingRoute)
                || !Double.isFinite(offRoute) || !Float.isFinite(accuracy)
                || pinDistance < 0
                || remainingRoute < 0 || fixAt <= 0 || now < fixAt
                || now - fixAt > 5000) {
            consecutive = 0;
            return State.EN_ROUTE;
        }
        boolean approaching = pinDistance <= APPROACH_METRES || remainingRoute <= APPROACH_METRES;
        if (!approaching) { consecutive = 0; return State.EN_ROUTE; }
        boolean near = pinDistance <= 90 && remainingRoute <= 180
                && accuracy <= 45 && offRoute <= 55;
        boolean valid = pinDistance <= 28 && remainingRoute <= 95
                && offRoute <= 23 && accuracy <= 28 && speedMps <= 1.5f;
        if (valid && fixAt > lastFixAt && (lastFixAt < 0 || fixAt - lastFixAt >= 900)) {
            consecutive++;
            lastFixAt = fixAt;
        } else if (!valid) {
            consecutive = 0;
            lastFixAt = -1;
        }
        if (consecutive >= 3) return State.ARRIVED;
        return near ? State.NEARBY : State.APPROACHING;
    }

    public static boolean trustedEntrance(GeoMath.Point point, GeoMath.Point place) {
        return point != null && place != null
                && Double.isFinite(point.lat) && Double.isFinite(point.lon)
                && Math.abs(point.lat) <= 90 && Math.abs(point.lon) <= 180
                && GeoMath.distance(point, place) <= 200;
    }

    /** Never offer to save a parking position from old, imprecise or moving GPS. */
    public static boolean canSaveParking(float accuracy, float speedMps,
                                         long fixAt, long now) {
        return Float.isFinite(accuracy) && accuracy <= 35
                && Float.isFinite(speedMps) && speedMps <= 1.2f && speedMps >= 0
                && fixAt > 0 && now >= fixAt && now - fixAt <= 7000;
    }
}
