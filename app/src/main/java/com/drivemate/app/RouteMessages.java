package com.drivemate.app;

import java.util.Locale;

/** Plain-English route messages for drivers; technical detail stays in diagnostics. Pure Java. */
final class RouteMessages {
    private RouteMessages() { }

    static final String OUTSIDE_COVERAGE =
            "Offline routes cover Stockport and Cheadle for now. Connect to the internet for routes elsewhere.";
    static final String NO_ROAD_NEAR =
            "Couldn't find a road near that place. Try a nearby street or a postcode.";
    static final String ALREADY_THERE = "You're already at this destination.";
    static final String GENERIC = "Couldn't plan a route just now. Check the destination and try again.";

    /** Message for a failed route; {@code coverage} when the engine reported it is outside its map. */
    static String friendly(String raw, boolean coverage, boolean online) {
        String m = raw == null ? "" : raw.toLowerCase(Locale.UK);
        if (m.contains("same snapped junction")) return ALREADY_THERE;
        if (m.contains("too far from") || m.contains("no snap") || m.contains("nearest")) return NO_ROAD_NEAR;
        if (coverage || m.contains("coverage") || m.contains("regional") || m.contains("pilot limits")
                || m.contains("boundary") || m.contains("outside"))
            return online ? GENERIC : OUTSIDE_COVERAGE;
        if (m.contains("internet") || m.contains("network") || m.contains("timeout") || m.contains("unable to resolve"))
            return "No internet connection. Connect and try again.";
        return GENERIC;
    }

    /** Short traffic note for the route summary. */
    static String traffic(boolean live) { return live ? " · live traffic" : " · no live traffic"; }
}
