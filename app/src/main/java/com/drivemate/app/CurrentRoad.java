package com.drivemate.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Name of the road the vehicle is on, for the street-name pill under the car.
 * Uses the route's own way identities and names from the verified route scene;
 * falls back to the street of the last manoeuvre passed. Pure Java.
 */
final class CurrentRoad {
    private final double[] cumulative;
    private final List<Long> ways;
    private final Map<Long, String> names;

    private CurrentRoad(double[] cumulative, List<Long> ways, Map<Long, String> names) {
        this.cumulative = cumulative; this.ways = ways; this.names = names;
    }

    static final CurrentRoad EMPTY = new CurrentRoad(new double[0], Collections.emptyList(), Collections.emptyMap());

    /** @param wayNames way id to road name (blank names ignored) */
    static CurrentRoad of(List<GeoMath.Point> points, List<Long> ways, Map<Long, String> wayNames) {
        if (points == null || ways == null || wayNames == null || points.size() < 2 || ways.size() != points.size() - 1)
            return EMPTY;
        double[] d = new double[points.size()];
        for (int i = 1; i < d.length; i++) d[i] = d[i - 1] + GeoMath.distance(points.get(i - 1), points.get(i));
        Map<Long, String> clean = new HashMap<>();
        for (Map.Entry<Long, String> e : wayNames.entrySet()) {
            String n = e.getValue() == null ? "" : e.getValue().trim();
            if (e.getKey() != null && !n.isEmpty() && n.length() <= 60) clean.put(e.getKey(), n);
        }
        return new CurrentRoad(d, ways, clean);
    }

    static CurrentRoad of(List<GeoMath.Point> points, List<Long> ways, NativeMapScene scene) {
        if (scene == null) return EMPTY;
        Map<Long, String> names = new HashMap<>();
        for (NativeMapScene.Shape s : scene.shapes) if (!s.area && s.name != null && !s.name.isEmpty()) names.put(s.id, s.name);
        return of(points, ways, names);
    }

    /** Road name at a route offset, or "" when unknown. */
    String at(double along) {
        if (cumulative.length < 2 || !Double.isFinite(along)) return "";
        int lo = 0, hi = cumulative.length - 1;
        double a = Math.max(0, Math.min(cumulative[hi], along));
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (cumulative[mid] <= a) lo = mid; else hi = mid; }
        String n = names.get(ways.get(Math.min(lo, ways.size() - 1)));
        return n == null ? "" : n;
    }

    /** Pill text: the mapped name, else the street of the manoeuvre just passed, else "". */
    static String label(String mapped, String lastStreet) {
        String m = mapped == null ? "" : mapped.trim();
        if (!m.isEmpty()) return m;
        String s = lastStreet == null ? "" : lastStreet.trim();
        return s;
    }
}
