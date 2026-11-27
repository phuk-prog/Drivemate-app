package com.drivemate.app;

/**
 * What the map knows about the other ways on at one junction of a route, beyond
 * the number of legal choices. Built by the routing engine; pure data.
 */
public final class JunctionDetail {
    /** Other legal exits that are ordinary roads (not service, track or path). */
    public final int countableExits;
    /** The engine could not classify at least one other exit, so counts may be wrong. */
    public final boolean unknownExit;
    /** A real road leaves between 15 and 60 degrees to the left / right of the route. */
    public final boolean forkLeft, forkRight;

    public JunctionDetail(int countableExits, boolean unknownExit, boolean forkLeft, boolean forkRight) {
        this.countableExits = countableExits;
        this.unknownExit = unknownExit;
        this.forkLeft = forkLeft;
        this.forkRight = forkRight;
    }

    public static final JunctionDetail NONE = new JunctionDetail(0, false, false, false);

    /** Highway classes a driver counts as a roundabout exit. */
    public static boolean countableHighway(String highway) {
        if (highway == null) return false;
        switch (highway) {
            case "motorway": case "motorway_link": case "trunk": case "trunk_link":
            case "primary": case "primary_link": case "secondary": case "secondary_link":
            case "tertiary": case "tertiary_link": case "unclassified": case "residential":
            case "living_street":
                return true;
            default:
                return false;
        }
    }

    /** Highway classes known not to count (driveways, paths, tracks). Anything else is unknown. */
    public static boolean ignorableHighway(String highway) {
        if (highway == null) return false;
        switch (highway) {
            case "service": case "track": case "path": case "footway": case "cycleway":
            case "bridleway": case "steps": case "pedestrian": case "corridor":
                return true;
            default:
                return false;
        }
    }

    /** Relative bearing of another exit to the route, in degrees (positive right), wrapped to (-180, 180]. */
    public static double relative(double routeBearing, double otherBearing) {
        return ((otherBearing - routeBearing) % 360 + 540) % 360 - 180;
    }

    public static final double FORK_MIN_DEGREES = 15;
    public static final double FORK_MAX_DEGREES = 60;

    public static boolean isFork(double relativeDegrees) {
        double a = Math.abs(relativeDegrees);
        return a >= FORK_MIN_DEGREES && a <= FORK_MAX_DEGREES;
    }
}
