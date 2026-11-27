package com.drivemate.app;

/**
 * Chooses which road junction a route should start from, using the direction
 * the car is travelling. The nearest junction is not always the right one: a
 * junction just behind the car can be closer than the one ahead, and starting
 * there produces a route that opens with a U-turn.
 *
 * Pure Java. The score is distance plus a penalty in metres when none of the
 * junction's exits points roughly the way the car is heading.
 */
public final class StartSnap {
    private StartSnap() { }

    /** Exit within this many degrees of travel direction: no penalty. */
    public static final double ALIGNED_DEGREES = 45;
    /** Exit within this many degrees: small penalty. */
    public static final double ROUGHLY_ALIGNED_DEGREES = 90;
    public static final double ROUGH_PENALTY_METRES = 40;
    public static final double OPPOSED_PENALTY_METRES = 150;

    /**
     * @param travelBearing car heading in degrees, or NaN when unknown
     * @param exitBearings bearings of the junction's usable exits; may be empty
     * @return extra metres to add to the junction's distance (0 when the heading is unknown)
     */
    public static double penaltyMetres(double travelBearing, double[] exitBearings) {
        if (!Double.isFinite(travelBearing) || exitBearings == null || exitBearings.length == 0) return 0;
        double best = 180;
        for (double exit : exitBearings) {
            double d = Math.abs(((exit - travelBearing) % 360 + 540) % 360 - 180);
            best = Math.min(best, d);
        }
        if (best <= ALIGNED_DEGREES) return 0;
        if (best <= ROUGHLY_ALIGNED_DEGREES) return ROUGH_PENALTY_METRES;
        return OPPOSED_PENALTY_METRES;
    }
}
