package com.drivemate.app;

/**
 * Extra search cost, in seconds, for awkward turns, so route choice avoids
 * needless U-turns and right turns across oncoming traffic (UK: drive on the
 * left). The penalty only steers the search: it is NOT added to the route's
 * displayed travel time.
 *
 * Values are conservative and have not been validated against real routes.
 * Set {@link #ENABLED} to false to restore plain travel-time routing.
 */
public final class TurnPenalty {
    private TurnPenalty() { }

    public static final boolean ENABLED = true;
    public static final double U_TURN_SECONDS = 45;
    /** Right turn across oncoming traffic (sharper than 60 degrees). */
    public static final double RIGHT_TURN_SECONDS = 6;
    public static final double LEFT_TURN_SECONDS = 2;
    public static final double SLIGHT_TURN_SECONDS = 1;

    /** Signed turn in degrees at {@code at}: positive is a right turn. */
    public static double turnDegrees(GeoMath.Point before, GeoMath.Point at, GeoMath.Point after) {
        double in = GeoMath.bearingDegrees(before, at);
        double out = GeoMath.bearingDegrees(at, after);
        return ((out - in) % 360 + 540) % 360 - 180;
    }

    public static double seconds(GeoMath.Point before, GeoMath.Point at, GeoMath.Point after) {
        if (!ENABLED || before == null || at == null || after == null) return 0;
        return seconds(turnDegrees(before, at, after));
    }

    public static double seconds(double turn) {
        if (!ENABLED || !Double.isFinite(turn)) return 0;
        double a = Math.abs(turn);
        if (a >= 150) return U_TURN_SECONDS;
        if (a >= 60) return turn > 0 ? RIGHT_TURN_SECONDS : LEFT_TURN_SECONDS;
        if (a >= 30) return SLIGHT_TURN_SECONDS;
        return 0;
    }
}
