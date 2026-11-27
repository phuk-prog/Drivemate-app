package com.drivemate.app;

/**
 * Route preferences (avoid motorways, ferries, unpaved roads). Disabled: the
 * routing tiles carry no road class on their edges, so the engine cannot honour
 * them. Enabling this needs a Drivemate-map data change (road class per edge)
 * and a new pinned map hash. Until then {@link #availability} says so, and the
 * router must not pretend a preference was applied.
 */
public final class RoutePreferences {
    public final boolean avoidMotorways, avoidFerries, avoidUnpaved;

    public RoutePreferences(boolean avoidMotorways, boolean avoidFerries, boolean avoidUnpaved) {
        this.avoidMotorways = avoidMotorways;
        this.avoidFerries = avoidFerries;
        this.avoidUnpaved = avoidUnpaved;
    }

    public static final RoutePreferences NONE = new RoutePreferences(false, false, false);

    /** Flip to true only when the routing tiles ship a per-edge road class. */
    public static final boolean EDGE_CLASS_IN_TILES = false;

    public boolean any() { return avoidMotorways || avoidFerries || avoidUnpaved; }

    public enum Availability { NOT_REQUESTED, APPLIED, UNAVAILABLE_NEEDS_MAP_UPDATE }

    public Availability availability() {
        if (!any()) return Availability.NOT_REQUESTED;
        return EDGE_CLASS_IN_TILES ? Availability.APPLIED : Availability.UNAVAILABLE_NEEDS_MAP_UPDATE;
    }
}
