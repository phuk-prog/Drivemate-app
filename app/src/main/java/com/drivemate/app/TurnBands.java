package com.drivemate.app;

/**
 * Turns an angle into instruction wording and a maneuver code the display and
 * icon logic already understand (BEAR_*, TURN_*, SHARP_*, UTURN_*, KEEP_*).
 * Pure Java. Angles: positive is a right turn.
 */
public final class TurnBands {
    private TurnBands() { }

    public static final double BEAR_FROM = 30;
    public static final double TURN_FROM = 60;
    public static final double SHARP_FROM = 120;
    public static final double REVERSE_FROM = 150;

    public static final class Band {
        public final String code;
        public final String message;
        Band(String code, String message) { this.code = code; this.message = message; }
    }

    /**
     * @param turn signed turn in degrees (positive right)
     * @param reversesSameRoad the route comes back along the road it arrived on (same non-empty name)
     * @param hasChoice more than one legal way on at this junction
     * @return the band, or null when the angle is under {@link #BEAR_FROM}
     */
    public static Band classify(double turn, boolean reversesSameRoad, boolean hasChoice) {
        if (!Double.isFinite(turn)) return null;
        double a = Math.abs(turn);
        boolean left = turn < 0;
        if (a >= REVERSE_FROM) {
            if (reversesSameRoad) return new Band(left ? "UTURN_LEFT" : "UTURN_RIGHT", "Make a U-turn");
            // A hairpin onto a different road. Without a real choice the geometry is not trustworthy.
            return hasChoice ? sharp(left) : null;
        }
        if (a >= SHARP_FROM) return sharp(left);
        if (a >= TURN_FROM) return new Band(left ? "TURN_LEFT" : "TURN_RIGHT", left ? "Turn left" : "Turn right");
        if (a >= BEAR_FROM) return new Band(left ? "BEAR_LEFT" : "BEAR_RIGHT", left ? "Bear left" : "Bear right");
        return null;
    }

    private static Band sharp(boolean left) {
        return new Band(left ? "SHARP_LEFT" : "SHARP_RIGHT", left ? "Turn sharp left" : "Turn sharp right");
    }

    /** Keep-left / keep-right at a fork: the other road leaves on {@code otherRoadOnRight}'s side. */
    public static Band keep(boolean otherRoadOnRight) {
        return otherRoadOnRight ? new Band("KEEP_LEFT", "Keep left") : new Band("KEEP_RIGHT", "Keep right");
    }
}
