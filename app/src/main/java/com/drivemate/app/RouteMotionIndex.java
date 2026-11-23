package com.drivemate.app;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable route arc-length index. Frame rendering must not scan the entire
 * TomTom polyline or project a GPS fix on every animation frame.
 */
public final class RouteMotionIndex {
    private final GeoMath.Point[] points;
    private final double[] distance;
    public final double lengthMetres;

    public RouteMotionIndex(List<GeoMath.Point> path) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Empty route");
        points = path.toArray(new GeoMath.Point[0]);
        distance = new double[points.length];
        for (int i = 1; i < points.length; i++)
            distance[i] = distance[i - 1] + GeoMath.distance(points[i - 1], points[i]);
        lengthMetres = distance[distance.length - 1];
    }

    public GeoMath.Point pointAt(double metres) {
        if (points.length == 1 || metres <= 0) return points[0];
        if (metres >= lengthMetres) return points[points.length - 1];
        int end = Arrays.binarySearch(distance, metres);
        if (end >= 0) return points[end];
        end = -end - 1;
        int start = Math.max(0, end - 1);
        double span = distance[end] - distance[start];
        if (span < 0.001) return points[end];
        double t = (metres - distance[start]) / span;
        GeoMath.Point a = points[start], b = points[end];
        return new GeoMath.Point(a.lat + (b.lat - a.lat) * t,
                a.lon + (b.lon - a.lon) * t);
    }

    /** A long enough look-ahead softens the heading change entering a bend. */
    public double bearingAt(double metres, double lookAheadMetres) {
        double a = Math.max(0, metres - 8);
        double b = Math.min(lengthMetres, metres + Math.max(12, lookAheadMetres));
        GeoMath.Point start = pointAt(a), finish = pointAt(b);
        return GeoMath.distance(start, finish) < 1 ? 0 :
                GeoMath.bearingDegrees(start, finish);
    }
}
