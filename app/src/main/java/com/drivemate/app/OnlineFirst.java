package com.drivemate.app;

import java.util.List;

/**
 * Online-first search and routing through TomTom when the owner has saved a key and consented. Every method returns
 * null on any failure, never throws, so callers fall back to the offline DriveMate engine.
 */
final class OnlineFirst {
    private OnlineFirst() { }

    /** Last failure text for the status line; empty when the last call worked. */
    static volatile String lastProblem = "";

    static boolean usable(String key) { return key != null && !key.trim().isEmpty() && key.length() <= 256; }

    static List<TomTomApi.Destination> search(String key, String query, GeoMath.Point near, boolean online) {
        if (!online || !usable(key)) return null;
        try {
            List<TomTomApi.Destination> found = TomTomApi.search(key, query, near);
            lastProblem = "";
            return found == null || found.isEmpty() ? null : found;
        } catch (Exception e) {
            lastProblem = String.valueOf(e.getMessage());
            return null;
        }
    }

    static TomTomApi.Route route(String key, GeoMath.Point start, GeoMath.Point end, boolean online) {
        if (!online || !usable(key)) return null;
        try {
            TomTomApi.Route r = TomTomApi.route(key, start, end);
            lastProblem = "";
            return r;
        } catch (Exception e) {
            lastProblem = String.valueOf(e.getMessage());
            return null;
        }
    }

    /** Live suggestions for a partly typed query; null on any failure. */
    static List<TomTomApi.Destination> suggest(String key, String query, GeoMath.Point near) {
        if (!usable(key)) return null;
        try {
            List<TomTomApi.Destination> found = TomTomApi.search(key, query, near, true, 6);
            return found == null || found.isEmpty() ? null : found;
        } catch (Exception e) {
            return null;
        }
    }
}
