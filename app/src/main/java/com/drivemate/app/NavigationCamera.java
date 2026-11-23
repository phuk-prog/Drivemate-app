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
                Double.NaN, speedMetresPerSecond, screenHeightPx,
                headerHeightPx, panelHeightPx, false, false);
    }

    /** Camera target with two-turn framing and explicitly selected detail zoom. */
    public static Frame calculate(List<GeoMath.Point> path,
                                  GeoMath.Point gps, double routeProgressMetres,
                                  double nextJunctionOffsetMetres,
                                  double followingJunctionOffsetMetres,
                                  float speedMetresPerSecond, int screenHeightPx,
                                  int headerHeightPx, int panelHeightPx,
                                  boolean junctionDetailZoom, boolean complexJunction) {
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
        // A closer, more overhead view is useful for a near urban junction.
        // At motorway speeds retain the longer safe look-ahead and never let
        // an optional preference crop the upcoming turning.
        boolean detailedApproach = junctionDetailZoom && junctionAhead <= 650
                && speed <= 22;
        double approachPadding = detailedApproach
                ? Math.max(42, speed * 3.2) : Math.max(55, speed * 4.0);
        double speedHorizon = detailedApproach
                ? Math.max(155, speed * 7.8 + 70) : Math.max(180, speed * 9 + 80);
        double horizon = Math.max(junctionAhead + approachPadding, speedHorizon);

        // A closely following second turn should stay visible rather than
        // being cropped by a tighter close-up of the first one.
        if (Double.isFinite(followingJunctionOffsetMetres)
                && followingJunctionOffsetMetres >= nextJunctionOffsetMetres
                && followingJunctionOffsetMetres - nextJunctionOffsetMetres <= 450
                && junctionAhead <= 650) {
            double secondAhead = followingJunctionOffsetMetres - progress;
            horizon = Math.max(horizon, secondAhead + Math.max(55, speed * 3));
        }
        horizon = clamp(horizon, 155, 1350);

        // Stronger forward perspective on long approaches; ease toward a
        // clearer overhead junction diagram as the manoeuvre gets close.
        double tilt = clamp(28 + junctionAhead * 0.033
                + Math.min(5, speed * 0.16), 28, 48);
        if (complexJunction && junctionAhead < 520) {
            // Roundabouts/forks become more top-down as the car nears them.
            tilt = Math.max(20, tilt - 8 * (1 - junctionAhead / 520));
        }
        if (detailedApproach) {
            tilt = Math.max(18, tilt - 5 * (1 - junctionAhead / 650));
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
        // Never let the camera lead beyond the road a driver must choose.
        // A validated second turn can extend the look-ahead, not teleport the
        // vehicle marker or cause the camera to face an unconfirmed exit.
        double lookAheadLimit = Double.isFinite(followingJunctionOffsetMetres)
                && followingJunctionOffsetMetres >= nextJunctionOffsetMetres
                && followingJunctionOffsetMetres - nextJunctionOffsetMetres <= 450
                ? followingJunctionOffsetMetres : nextJunctionOffsetMetres;
        centerAlong = Math.min(centerAlong,
                lookAheadLimit + Math.max(25, horizon * 0.13));
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

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
