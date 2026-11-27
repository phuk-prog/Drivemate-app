package com.drivemate.app;

/**
 * Extra search cost, in seconds, for awkward turns, so route choice avoids
 * needless U-turns and right turns across oncoming traffic (UK: drive on the
 * left). The penalty only steers the search: it is NOT added to the route's
 * displayed travel time.
 *
 * DISABLED by default. With penalties on, the emulator route test on real
 * tiles changed a verified 1.54 km route into a 1.63 km one, so the values
 * need validating against real routes first. {@link #compute} always gives the
 * penalty; {@link #seconds} (used by the router) returns 0 while
 * {@link #ENABLED} is false.
 */
public final class TurnPenalty {
    private TurnPenalty() { }

    public static final boolean ENABLED = false;
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

    /** Penalty used by the router: zero while the feature is disabled. */
    public static double seconds(GeoMath.Point before, GeoMath.Point at, GeoMath.Point after) {
        if (!ENABLED || before == null || at == null || after == null) return 0;
        return compute(turnDegrees(before, at, after));
    }

    /** The penalty for a turn of this many degrees, ignoring {@link #ENABLED}. */
    public static double compute(double turn) {
        if (!Double.isFinite(turn)) return 0;
        double a = Math.abs(turn);
        if (a >= 150) return U_TURN_SECONDS;
        if (a >= 60) return turn > 0 ? RIGHT_TURN_SECONDS : LEFT_TURN_SECONDS;
        if (a >= 30) return SLIGHT_TURN_SECONDS;
        return 0;
    }
}
