package com.drivemate.app;

import java.util.List;

/**
 * Small pure-Java navigation-camera planner. The driving map rotates so the
 * next road runs up the display, with the driver below the usable map centre.
 * Android overlays are accounted for in the available viewport height.
 */
public final class NavigationCamera {
    private NavigationCamera() {}

    public static final class Frame {
        public final GeoMath.Point center;
        public final double zoom;
        public final double roadBearingDegrees;
        public final double junctionMetres;
        public Frame(GeoMath.Point center, double zoom, double roadBearingDegrees,
                     double junctionMetres) {
            this.center = center;
            this.zoom = zoom;
            this.roadBearingDegrees = roadBearingDegrees;
            this.junctionMetres = junctionMetres;
        }
    }

    public static Frame calculate(List<GeoMath.Point> path,
                                  GeoMath.Point gps, double routeProgressMetres,
                                  double nextJunctionOffsetMetres, float speedMetresPerSecond,
                                  int screenHeightPx, int headerHeightPx, int panelHeightPx) {
        if (path == null || path.size() < 2) {
            return new Frame(gps, 16.5, 0, 0);
        }

        double progress = clamp(routeProgressMetres, 0, GeoMath.length(path));
        double junctionAhead = Math.max(0, nextJunctionOffsetMetres - progress);
        // At walking/stopped speed show junction detail; at motorway speed look
        // further ahead to allow time for safe decisions.
        double horizon = clamp(Math.max(junctionAhead + 100,
                Math.max(170, Math.max(0, speedMetresPerSecond) * 12)), 170, 1100);
        double viewHeight = Math.max(220, screenHeightPx - headerHeightPx - panelHeightPx);
        double metresPerPixel = horizon / (viewHeight * 0.75);
        double latFactor = Math.max(0.1, Math.cos(Math.toRadians(gps.lat)));
        double zoom = clamp(Math.log(156543.03392 * latFactor / metresPerPixel)
                / Math.log(2), 14.2, 19.0);

        // Centre the map on a road point ahead of the driver, then account
        // for the usable area between the header and navigation panel.
        double leadMetres = clamp(junctionAhead * 0.53 + 40, 65, horizon * 0.52);
        double visibleCenterY = headerHeightPx + viewHeight * 0.5;
        double screenCenterY = screenHeightPx * 0.5;
        double mapCenterAlong = progress + leadMetres
                + (visibleCenterY - screenCenterY) * metresPerPixel;
        GeoMath.Point center = GeoMath.pointAtDistance(path, Math.max(0, mapCenterAlong));

        GeoMath.Point a = GeoMath.pointAtDistance(path, progress + 10);
        GeoMath.Point b = GeoMath.pointAtDistance(path, progress + 70);
        if (GeoMath.distance(a, b) < 2) {
            a = GeoMath.pointAtDistance(path, Math.max(0, progress - 60));
            b = GeoMath.pointAtDistance(path, progress);
        }
        double bearing = GeoMath.bearingDegrees(a, b);
        return new Frame(center, zoom, bearing, junctionAhead);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
