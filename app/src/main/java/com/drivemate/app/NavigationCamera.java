package com.drivemate.app;

import java.util.List;

/**
 * Route-aware navigation camera planner. The driver and the upcoming junction
 * must both remain in the usable viewport between the top guidance overlay
 * and the bottom controls. Coordinates come from the original TomTom route.
 *
 * The perspective is applied by MapLibre; the osmdroid fallback stays 2D.
 */
public final class NavigationCamera {
    private NavigationCamera() {}

    /** Zoom limits, in this class's osmdroid-style units (see toMapLibreZoom). */
    public static final double MIN_ZOOM = 14.2;
    public static final double MAX_ZOOM = 20.5;

    public static final class Frame {
        public final GeoMath.Point center;
        public final double zoom;
        /** Direction of the road at the driver's current location. */
        public final double roadBearingDegrees;
        public final double junctionMetres;
        /** Exact arc length of the camera centre on the original route. */
        public final double cameraCenterAlongMetres;
        /** MapLibre pitch: 0 = overhead, 60 = strongly tilted. */
        public final double tiltDegrees;

        public Frame(GeoMath.Point center, double zoom, double roadBearingDegrees,
                     double junctionMetres) {
            this(center, zoom, roadBearingDegrees, junctionMetres, Double.NaN, 0);
        }
        public Frame(GeoMath.Point center, double zoom, double roadBearingDegrees,
                     double junctionMetres, double cameraCenterAlongMetres) {
            this(center, zoom, roadBearingDegrees, junctionMetres,
                    cameraCenterAlongMetres, 0);
        }
        public Frame(GeoMath.Point center, double zoom, double roadBearingDegrees,
                     double junctionMetres, double cameraCenterAlongMetres,
                     double tiltDegrees) {
            this.center = center;
            this.zoom = zoom;
            this.roadBearingDegrees = roadBearingDegrees;
            this.junctionMetres = junctionMetres;
            this.cameraCenterAlongMetres = cameraCenterAlongMetres;
            this.tiltDegrees = tiltDegrees;
        }
    }

    public static Frame calculate(List<GeoMath.Point> path,
                                  GeoMath.Point gps, double routeProgressMetres,
                                  double nextJunctionOffsetMetres, float speedMetresPerSecond,
                                  int screenHeightPx, int headerHeightPx, int panelHeightPx) {
        return calculate(path, gps, routeProgressMetres, nextJunctionOffsetMetres,
                speedMetresPerSecond, screenHeightPx, headerHeightPx,
                panelHeightPx, false);
    }

    /**
     * Driver-selectable junction detail. A clear overhead view is favoured
     * near turns without sacrificing route coverage at road speed.
     */
    public static Frame calculate(List<GeoMath.Point> path,
                                  GeoMath.Point gps, double routeProgressMetres,
                                  double nextJunctionOffsetMetres, float speedMetresPerSecond,
                                  int screenHeightPx, int headerHeightPx, int panelHeightPx,
                                  boolean junctionDetail) {
        if (path == null || path.size() < 2) {
            return new Frame(gps, 16.5, 0, 0);
        }

        double routeLength = GeoMath.length(path);
        double progress = clamp(routeProgressMetres, 0, routeLength);
        double junctionAhead = Math.max(0, nextJunctionOffsetMetres - progress);
        double speed = Math.max(0, speedMetresPerSecond);
        double viewHeight = Math.max(220, screenHeightPx - headerHeightPx - panelHeightPx);

        // Like mainstream navigation apps, the cruising view is set by speed:
        // about 110 m of road ahead at walking pace, growing with speed (about
        // 200 m at 30 mph, 370 m at 70 mph). A distant next turn no longer
        // zooms the map out to fit the whole approach.
        double cruise = clamp(250 + speed * 12.0, 250, 1000);
        double horizon = cruise;
        // At walking pace, show a useful urban approach without fitting a distant journey.
        if(junctionAhead>cruise&&junctionAhead<=480) horizon=Math.min(520,junctionAhead+65);
        if (junctionAhead <= cruise) {
            // Approaching the manoeuvre: zoom in so the turn and a short stretch
            // of road beyond it fill the view, keeping more at higher speed.
            horizon = clamp(junctionAhead + Math.max(65, speed * 4.0), 105, cruise);
        }
        boolean detailApproach = junctionDetail && junctionAhead <= 420;
        if (detailApproach) {
            // Junction detail: a little closer still, never cropping the turn
            // itself plus a short exit (longer at speed).
            double tight = Math.max(60, junctionAhead + Math.max(30, speed * 2.5));
            horizon = Math.min(horizon, Math.max(tight, horizon * 0.82));
        }

        // Stronger forward perspective on long approaches; ease toward a
        // clearer overhead junction diagram as the manoeuvre gets close.
        double tilt = clamp(31 + junctionAhead * 0.046
                + Math.min(6, speed * 0.18), 31, 54);
        if (detailApproach) {
            // More overhead than the default 3D view, especially for exits.
            double blend = 1 - junctionAhead / 420.0;
            tilt = Math.max(17, tilt - 13 * blend);
        }

        // Pitch foreshortens the near/far road. Reserve extra world-space
        // height so tilting does not crop the approaching junction.
        double effectiveHorizon = horizon
                * (1 + 0.32 * Math.sin(Math.toRadians(tilt)));
        double metresPerPixel = effectiveHorizon / (viewHeight * 0.76);
        double latFactor = Math.max(0.1, Math.cos(Math.toRadians(gps.lat)));
        double zoom = clamp(Math.log(156543.03392 * latFactor / metresPerPixel)
                / Math.log(2), MIN_ZOOM, MAX_ZOOM);

        // Keep the car in the lower usable viewport and the turn higher up.
        // Include overlay displacement but never look so far past a nearby
        // turn that it disappears underneath the bottom navigation controls.
        double lead = clamp(effectiveHorizon * 0.40,
                Math.min(35, horizon * 0.30), horizon * 0.60);
        double visibleCenterY = headerHeightPx + viewHeight * 0.5;
        double screenCenterY = screenHeightPx * 0.5;
        double centerAlong = progress + lead
                + (visibleCenterY - screenCenterY) * metresPerPixel;
        centerAlong = Math.min(centerAlong,
                nextJunctionOffsetMetres + Math.max(detailApproach ? 34 : 25,
                        horizon * (detailApproach ? 0.17 : 0.13)));
        centerAlong = clamp(centerAlong, progress, routeLength);
        GeoMath.Point center = GeoMath.pointAtDistance(path, centerAlong);

        // Heading must describe the approach road, not the exit road.
        // Do not rotate the car into a turn before it has reached it.
        double beforeTurn = Math.max(0, junctionAhead);
        GeoMath.Point a = GeoMath.pointAtDistance(path,
                progress + Math.min(5, beforeTurn * 0.12));
        GeoMath.Point b = GeoMath.pointAtDistance(path,
                progress + Math.min(beforeTurn, clamp(beforeTurn - 5, 0, 36)));
        if (GeoMath.distance(a, b) < 2) {
            a = GeoMath.pointAtDistance(path, Math.max(0, progress - 25));
            b = GeoMath.pointAtDistance(path, progress);
        }
        double bearing = GeoMath.bearingDegrees(a, b);
        return new Frame(center, zoom, bearing, junctionAhead, centerAlong, tilt);
    }

    /**
     * Zoom levels here assume 256-pixel tiles measured in physical screen
     * pixels, which is what the osmdroid raster map draws. MapLibre measures
     * zoom with 512-pixel tiles in density-independent pixels, so the same
     * view is 1 + log2(density) levels lower there. Without this conversion
     * the vector camera was about 2.4 levels (roughly 5x) too close on a
     * typical phone, which pushed the car off-screen at the start of a route.
     */
    public static double toMapLibreZoom(double zoom, double density) {
        double d = density > 0 && Double.isFinite(density) ? density : 1;
        return zoom - 1 - Math.log(d) / Math.log(2);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
