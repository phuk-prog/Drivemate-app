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
        // about 130 m of road ahead at walking pace, growing with speed (about
        // 235 m at 30 mph, 385 m at 70 mph). A distant next turn never zooms
        // the map out to fit the whole approach.
        double cruise = cruiseHorizon(speed);
        double horizon = cruise;
        // Slightly widen only to bring a turn that is just out of view onto the screen.
        if(junctionAhead>cruise&&junctionAhead<=CLOSE_JUNCTION_METRES) horizon=junctionAhead+65;
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
        // Moderate tilt: a steep perspective shows mostly empty horizon when zoomed out.
        double tilt = clamp(32 + Math.min(10, speed * 0.30), 32, MAX_TILT);
        // Ease toward overhead over the last 150 m so the junction reads like a plan.
        if (junctionAhead < 150) tilt -= 8 * (1 - junctionAhead / 150.0);
        if (detailApproach) {
            // More overhead than the default 3D view, especially for exits.
            double blend = 1 - junctionAhead / 420.0;
            tilt = Math.max(23, tilt - 12 * blend);
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
        // The renderer draws the camera centre at its anchor (62% down when tilted, 50% flat).
        // Place the centre so the car lands at CAR_SCREEN_FRACTION of the usable map: above the
        // bottom panel and the speed cluster (included in panelHeightPx), never under them.
        double anchorY = screenHeightPx * (tilt > 0 ? NativeMapProjection.TILTED_ANCHOR : 0.5);
        double carY = headerHeightPx + viewHeight * CAR_SCREEN_FRACTION;
        double groundPerPixel = metresPerPixel / Math.max(0.5, Math.cos(Math.toRadians(tilt)));
        double centerAlong = progress + Math.max(0, carY - anchorY) * groundPerPixel;
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
            if (GeoMath.distance(a, b) < 2) {
                // At departure no road exists behind the vehicle on this route.
                a = GeoMath.pointAtDistance(path, progress);
                b = GeoMath.pointAtDistance(path, Math.min(routeLength, progress + 12));
            }
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

    /** Junctions closer than this may widen the cruise view slightly to stay on screen. */
    static final double CLOSE_JUNCTION_METRES = 250;
    static final double MAX_TILT = 42;
    /** Where the vehicle sits, as a fraction of the usable map height from its top. */
    static final double CAR_SCREEN_FRACTION = 0.80;

    /** Metres of road ahead shown while cruising at this speed (m/s). */
    static double cruiseHorizon(double speedMetresPerSecond) {
        double v = Double.isFinite(speedMetresPerSecond) ? Math.max(0, speedMetresPerSecond) : 0;
        return clamp(120 + v * 8.5, 130, 600);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
