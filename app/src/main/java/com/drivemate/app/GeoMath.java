package com.drivemate.app;

import java.util.List;

/** GPS geometry in metres. No Android dependency, so it can be unit tested. */
public final class GeoMath {
    private static final double EARTH_METRES = 6371000.0;
    private GeoMath() {}

    public static final class Point {
        public final double lat;
        public final double lon;
        public Point(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Progress {
        public final double alongMetres;
        public final double offRouteMetres;
        public final double totalMetres;
        Progress(double alongMetres, double offRouteMetres, double totalMetres) {
            this.alongMetres = alongMetres;
            this.offRouteMetres = offRouteMetres;
            this.totalMetres = totalMetres;
        }
    }

    public static double distance(Point a, Point b) {
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.lon - a.lon);
        double v = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_METRES * Math.atan2(Math.sqrt(v), Math.sqrt(Math.max(0, 1 - v)));
    }

    /** Total road-polyline distance, in metres. */
    public static double length(List<Point> path) {
        if (path == null || path.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += distance(path.get(i - 1), path.get(i));
        }
        return total;
    }

    /** Interpolate a point along the road, clamped to its endpoints. */
    public static Point pointAtDistance(List<Point> path, double targetMetres) {
        if (path == null || path.isEmpty()) return new Point(0, 0);
        if (path.size() == 1 || targetMetres <= 0) return path.get(0);
        double walked = 0;
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1);
            Point b = path.get(i);
            double length = distance(a, b);
            if (targetMetres <= walked + length && length > 0) {
                double t = (targetMetres - walked) / length;
                return new Point(a.lat + t * (b.lat - a.lat),
                        a.lon + t * (b.lon - a.lon));
            }
            walked += length;
        }
        return path.get(path.size() - 1);
    }

    /** Bearing of the road ahead, clockwise from north (degrees). */
    public static double bearingDegrees(Point a, Point b) {
        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);
        double lon = Math.toRadians(b.lon - a.lon);
        double y = Math.sin(lon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(lon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Finds the nearest point on the route, not just the nearest vertex. */
    public static Progress project(List<Point> path, Point gps) {
        if (path == null || path.isEmpty()) {
            return new Progress(0, Double.POSITIVE_INFINITY, 0);
        }
        if (path.size() == 1) {
            return new Progress(0, distance(path.get(0), gps), 0);
        }
        double walked = 0;
        double bestOff = Double.POSITIVE_INFINITY;
        double bestAlong = 0;
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1);
            Point b = path.get(i);
            double latitudeRadians = Math.toRadians((a.lat + b.lat) * 0.5);
            double mPerDegree = Math.PI * EARTH_METRES / 180;
            double ax = (b.lon - a.lon) * Math.cos(latitudeRadians) * mPerDegree;
            double ay = (b.lat - a.lat) * mPerDegree;
            double gx = (gps.lon - a.lon) * Math.cos(latitudeRadians) * mPerDegree;
            double gy = (gps.lat - a.lat) * mPerDegree;
            double square = ax * ax + ay * ay;
            double t = square < 0.001 ? 0 : Math.max(0, Math.min(1, (gx * ax + gy * ay) / square));
            double off = Math.hypot(gx - t * ax, gy - t * ay);
            double segment = distance(a, b);
            if (off < bestOff) {
                bestOff = off;
                bestAlong = walked + t * segment;
            }
            walked += segment;
        }
        return new Progress(bestAlong, bestOff, walked);
    }
}
