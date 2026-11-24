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

        // Fit the *whole* approach, including a little road past the turn.
        // At speed the safe look-ahead expands, even for a very close turn.
        double horizon = clamp(Math.max(
                junctionAhead + Math.max(55, speed * 4.0),
                Math.max(180, speed * 9 + 80)), 180, 1350);
        boolean detailApproach = junctionDetail && junctionAhead <= 420;
        if (detailApproach) {
            // More detail without clipping the turn or the safe exit distance.
            // High-speed driving always reserves a longer visible approach.
            double safeToShow = junctionAhead + Math.max(62, speed * 4.0);
            double highSpeedLookAhead = Math.max(160, speed * 8 + 70);
            horizon = Math.max(safeToShow, Math.max(highSpeedLookAhead,
                    horizon * 0.86));
        }

        // Stronger forward perspective on long approaches; ease toward a
        // clearer overhead junction diagram as the manoeuvre gets close.
        double tilt = clamp(28 + junctionAhead * 0.033
                + Math.min(5, speed * 0.16), 28, 48);
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
                / Math.log(2), 14.2, 19.0);

        // Keep the car in the lower usable viewport and the turn higher up.
        // Include overlay displacement but never look so far past a nearby
        // turn that it disappears underneath the bottom navigation controls.
        double lead = clamp(junctionAhead * 0.50
                + Math.max(28, speed * 1.5), 36, horizon * 0.52);
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
        double beforeTurn = Math.max(8, junctionAhead);
        GeoMath.Point a = GeoMath.pointAtDistance(path,
                progress + Math.min(5, beforeTurn * 0.12));
        GeoMath.Point b = GeoMath.pointAtDistance(path,
                progress + clamp(beforeTurn - 5, 12, 36));
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
