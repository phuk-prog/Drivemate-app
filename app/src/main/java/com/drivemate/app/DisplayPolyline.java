package com.drivemate.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Simplify only the route line drawn on the map. Routing, manoeuvres, GPS
 * matching, and distances continue to use the full original TomTom geometry.
 */
public final class DisplayPolyline {
    private DisplayPolyline() { }

    public static List<GeoMath.Point> simplify(List<GeoMath.Point> input,
                                                double toleranceMetres) {
        if (input == null || input.size() <= 2 || toleranceMetres <= 0)
            return input;
        int n = input.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        ArrayDeque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[]{0, n - 1});
        while (!pending.isEmpty()) {
            int[] section = pending.pop();
            int start = section[0];
            int end = section[1];
            if (end - start <= 1) continue;
            double furthest = 0;
            int split = -1;
            GeoMath.Point first = input.get(start);
            GeoMath.Point last = input.get(end);
            for (int i = start + 1; i < end; i++) {
                double distance = distanceToSegment(input.get(i), first, last);
                if (distance > furthest) {
                    furthest = distance;
                    split = i;
                }
            }
            if (split >= 0 && furthest > toleranceMetres) {
                keep[split] = true;
                pending.push(new int[]{start, split});
                pending.push(new int[]{split, end});
            }
        }
        List<GeoMath.Point> output = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) output.add(input.get(i));
        return output;
    }

    private static double distanceToSegment(GeoMath.Point p, GeoMath.Point a,
                                            GeoMath.Point b) {
        // Metres in a local projection, suitable for small road segments.
        double metresPerDegree = Math.PI * 6371000.0 / 180.0;
        double longitudeScale = Math.cos(Math.toRadians((a.lat + b.lat) / 2));
        double dx = (b.lon - a.lon) * longitudeScale * metresPerDegree;
        double dy = (b.lat - a.lat) * metresPerDegree;
        double px = (p.lon - a.lon) * longitudeScale * metresPerDegree;
        double py = (p.lat - a.lat) * metresPerDegree;
        double square = dx * dx + dy * dy;
        double t = square == 0 ? 0 : Math.max(0, Math.min(1,
                (px * dx + py * dy) / square));
        return Math.hypot(px - t * dx, py - t * dy);
    }
}
