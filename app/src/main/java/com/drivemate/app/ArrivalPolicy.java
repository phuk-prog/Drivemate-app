package com.drivemate.app;

/**
 * Conservative GPS-based arrival confirmation.
 *
 * A nearby map pin alone is insufficient to end navigation: the vehicle must
 * have genuinely finished the route and remained near the destination across
 * three fresh, distinct, precise, low-speed GPS fixes.
 */
public final class ArrivalPolicy {
    /** Arrival hints start in the last 400 m of the route (or within 120 m of the pin). */
    private static final double APPROACH_ROUTE_METRES = 400;
    private static final double APPROACH_PIN_METRES = 120;
    private int consecutive;
    private long lastFixAt = -1;

    public enum State { EN_ROUTE, APPROACHING, NEARBY, ARRIVED }

    /**
     * Which side of the road the destination is on, from TomTom's own final
     * manoeuvre code (ARRIVE_LEFT / ARRIVE_RIGHT). Returns null when TomTom
     * didn't say, so the app never guesses.
     */
    public static String sideLabel(String finalManeuver) {
        if (finalManeuver == null) return null;
        String m = finalManeuver.trim().toUpperCase(java.util.Locale.UK);
        if (m.equals("ARRIVE_LEFT")) return "Destination on your left";
        if (m.equals("ARRIVE_RIGHT")) return "Destination on your right";
        return null;
    }

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
        // Route distance, not straight-line distance: a destination 600 m away
        // across the railway can still be over a mile by road.
        boolean approaching = remainingRoute <= APPROACH_ROUTE_METRES
                || pinDistance <= APPROACH_PIN_METRES;
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
