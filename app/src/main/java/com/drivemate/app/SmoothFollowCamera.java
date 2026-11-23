package com.drivemate.app;

/**
 * Frame-driven navigation motion independent of GPS callback frequency.
 *
 * GPS remains authoritative. For at most 1.15 seconds after a trustworthy fix,
 * the visible vehicle is gently projected along the *existing TomTom route*.
 * Speed, error and route-progress guards prevent multi-road jumps or unlimited
 * dead reckoning. Visual motion never changes route instructions or rerouting.
 *
 * Camera position, zoom and bearing are smoothed independently. Changes in the
 * next manoeuvre cannot force instantaneous zoom jumps or spinning.
 *
 * This class deliberately has no Android dependency and is unit tested.
 */
public final class SmoothFollowCamera {
    public static final class Frame {
        public final GeoMath.Point center;
        public final GeoMath.Point vehicle;
        public final double zoom;
        public final double bearing;
        public final boolean predicted;
        Frame(GeoMath.Point center, GeoMath.Point vehicle, double zoom,
              double bearing, boolean predicted) {
            this.center = center;
            this.vehicle = vehicle;
            this.zoom = zoom;
            this.bearing = bearing;
            this.predicted = predicted;
        }
    }

    private final RouteMotionIndex path;
    private long lastFrame = -1;
    private long lastFix = -1;
    private long lastAcceptedFix = -1;
    private long lastZoomTargetChange = -1;
    private long delayZoomOutUntil = -1;
    private double previouslyUpcoming = Double.NaN;
    private double measuredProgress;
    private double lastAnimatedProgress;
    private double centerAlong;
    private double metresPerSecond;
    private double zoom = Double.NaN;
    private double zoomTarget = Double.NaN;
    private double bearing = Double.NaN;
    private double bearingTarget;
    private GeoMath.Point center;
    private GeoMath.Point vehicle;
    private GeoMath.Point vehicleFix;
    private boolean gpsConfident;
    private boolean roadSnap;
    private boolean northUp;
    private boolean initialized;

    public SmoothFollowCamera(RouteMotionIndex path) {
        if (path == null) throw new IllegalArgumentException("route is required");
        this.path = path;
    }

    /**
     * Called on each GPS fix (~1Hz), never from an animation frame.
     * routeProgress must be the validated route progress used by guidance.
     */
    public void onGps(GeoMath.Point filteredGps, double routeProgress,
                      double offRouteMetres, float accuracyMetres,
                      float speedMetresPerSecond, double junctionAheadMetres,
                      NavigationCamera.Frame desired, boolean northUp, long nowMs) {
        if (filteredGps == null || desired == null) return;
        this.northUp = northUp;
        lastFix = nowMs;
        double safeSpeed = Math.max(0, Math.min(45, speedMetresPerSecond));
        boolean trustworthy = accuracyMetres <= 40 && offRouteMetres <= 55;
        gpsConfident = trustworthy;
        // Only snap the displayed car to TomTom geometry on a high-confidence
        // fix. Never use snapped position for actual routing decisions.
        roadSnap = trustworthy && accuracyMetres <= 25 && offRouteMetres <= 23
                && safeSpeed >= 1.5;
        vehicleFix = trustworthy ? filteredGps
                : (vehicle == null ? filteredGps : vehicle);
        if (!initialized) {
            measuredProgress = Math.max(0, Math.min(path.lengthMetres, routeProgress));
            lastAnimatedProgress = measuredProgress;
            // Find the target centre once at GPS cadence. Animation frames
            // only use the immutable indexed route.
            centerAlong = nearbyProgress(desired.center, measuredProgress);
            zoom = zoomTarget = clamp(desired.zoom, 14.2, 19.0);
            bearing = bearingTarget = northUp ? 0 : normalize(desired.roadBearingDegrees);
            center = desired.center;
            vehicle = roadSnap ? path.pointAt(measuredProgress) : filteredGps;
            metresPerSecond = safeSpeed;
            previouslyUpcoming = junctionAheadMetres;
            lastZoomTargetChange = nowMs;
            lastAcceptedFix = trustworthy ? nowMs : -1;
            initialized = true;
            return;
        }

        if (!trustworthy) {
            // Do not move the camera on inaccurate or off-route fixes.
            // Freeze dead reckoning until an accurate fix arrives.
            metresPerSecond = 0;
            roadSnap = false;
            return;
        }
        double dt = lastAcceptedFix < 0 ? 1.0
                : Math.max(0.1, Math.min(8, (nowMs - lastAcceptedFix) / 1000.0));
        double maxGain = Math.max(18, safeSpeed * dt * 1.7 + 22);
        double measured = clamp(routeProgress, 0, path.lengthMetres);
        if (measured > measuredProgress + maxGain) measured = measuredProgress + maxGain;
        // Avoid GPS noise going backwards across a parallel road/roundabout.
        measured = Math.max(measuredProgress - 2, measured);
        // Only step backward by a couple of metres if the last predicted frame
        // didn't already progress further down the road.
        measuredProgress = Math.max(lastAnimatedProgress - 2, measured);
        lastAcceptedFix = nowMs;
        metresPerSecond = safeSpeed;
        centerAlong = nearbyProgress(desired.center, measuredProgress);
        bearingTarget = northUp ? 0 : normalize(desired.roadBearingDegrees);

        if (Double.isFinite(previouslyUpcoming)
                && junctionAheadMetres > previouslyUpcoming + 135) {
            // A completed turn has handed us a more distant next manoeuvre:
            // hold close detail briefly before gradually zooming back out.
            delayZoomOutUntil = nowMs + 2200;
        }
        previouslyUpcoming = junctionAheadMetres;
        double candidate = clamp(desired.zoom, 14.2, 19.0);
        boolean urgentApproach = junctionAheadMetres < 175 && candidate > zoomTarget;
        boolean holdExpired = lastZoomTargetChange < 0
                || nowMs - lastZoomTargetChange >= 1700;
        if (Math.abs(candidate - zoomTarget) >= 0.24
                && (holdExpired || Math.abs(candidate - zoomTarget) > 0.95
                        || urgentApproach)) {
            // Wait for the immediate post-junction hold before zooming out.
            if (candidate >= zoomTarget || nowMs >= delayZoomOutUntil) {
                zoomTarget = candidate;
                lastZoomTargetChange = nowMs;
            }
        }
    }

    /** Estimate a camera centre's route progress without scanning every vertex. */
    private double nearbyProgress(GeoMath.Point point, double progress) {
        // The camera always looks ahead. Sample up to 1.2 km ahead of the car
        // in 25m steps, then refine around the nearest sample to 2m.
        double from = Math.max(0, progress - 100);
        double to = Math.min(path.lengthMetres, progress + 1500);
        double best = progress;
        double off = Double.POSITIVE_INFINITY;
        for (double d = from; d <= to; d += 25) {
            double gap = GeoMath.distance(path.pointAt(d), point);
            if (gap < off) { off = gap; best = d; }
        }
        double step = 2;
        from = Math.max(0, best - 25);
        to = Math.min(path.lengthMetres, best + 25);
        for (double d = from; d <= to; d += step) {
            double gap = GeoMath.distance(path.pointAt(d), point);
            if (gap < off) { off = gap; best = d; }
        }
        return best;
    }

    /**
     * Called from the UI frame clock. All elapsed-time maths is independent of
     * the actual frame rate: delayed or dropped frames cannot accelerate zoom.
     */
    public Frame frame(long nowMs) {
        if (!initialized) return null;
        double dt = lastFrame < 0 ? 0 : clamp((nowMs - lastFrame) / 1000.0, 0, 0.2);
        lastFrame = nowMs;
        double age = lastFix < 0 ? 100 : Math.max(0, (nowMs - lastFix) / 1000.0);
        // A stale GPS fix cannot propel the car across an unconfirmed junction.
        boolean extrapolating = gpsConfident && metresPerSecond >= 1.5 && age <= 1.15;
        double anticipated = extrapolating
                ? Math.min(metresPerSecond * age, 35) : 0;
        double visualAlong = clamp(measuredProgress + anticipated, 0, path.lengthMetres);
        // Keep the displayed progress monotonic even when a GPS correction
        // arrives slightly behind the previous predicted position.
        visualAlong = Math.max(lastAnimatedProgress, visualAlong);
        lastAnimatedProgress = visualAlong;
        GeoMath.Point wantedVehicle = roadSnap
                ? path.pointAt(visualAlong) : vehicleFix;
        if (wantedVehicle == null) wantedVehicle = vehicle;
        // Faster tracking at road speed, gentle smoothing when nearly stopped.
        double vehicleTau = metresPerSecond < 1.5 ? 0.60 : 0.22;
        vehicle = blend(vehicle, wantedVehicle, alpha(dt, vehicleTau));
        double centrePredicted = extrapolating
                ? Math.max(0, visualAlong - measuredProgress) : 0;
        GeoMath.Point wantedCenter = path.pointAt(
                clamp(centerAlong + centrePredicted, 0, path.lengthMetres));
        if (GeoMath.distance(center, wantedCenter) > 500) {
            // After a discontinuity, prefer reinitialisation on the next route
            // rather than producing multiple seconds of misleading map movement.
            wantedCenter = center;
        }
        double positionFraction = alpha(dt, 0.45);
        center = blend(center, wantedCenter, positionFraction);

        if (Double.isFinite(zoomTarget)) {
            double zoomDelta = zoomTarget - zoom;
            // Maximum 0.75 zoom levels/second, even after a long GPS outage.
            double step = clamp(zoomDelta * alpha(dt, 1.55),
                    -0.75 * dt, 0.75 * dt);
            if (Math.abs(zoomDelta) > 0.03) zoom += step;
        }

        if (northUp) bearingTarget = 0;
        if (northUp || metresPerSecond > 1.3) {
            double yaw = shortestAngle(bearingTarget, bearing);
            double rotationSpeed = metresPerSecond < 4 ? 25 : 48;
            double step = clamp(yaw * alpha(dt, 0.85),
                    -rotationSpeed * dt, rotationSpeed * dt);
            if (Math.abs(yaw) > 0.35) bearing = normalize(bearing + step);
        }
        return new Frame(center, vehicle, zoom, bearing, extrapolating);
    }

    public boolean initialized() { return initialized; }

    private static double alpha(double dt, double seconds) {
        return dt <= 0 ? 0 : 1 - Math.exp(-dt / seconds);
    }
    private static GeoMath.Point blend(GeoMath.Point a, GeoMath.Point b, double t) {
        if (a == null) return b;
        if (b == null) return a;
        return new GeoMath.Point(a.lat + (b.lat - a.lat) * t,
                a.lon + (b.lon - a.lon) * t);
    }
    private static double shortestAngle(double desired, double current) {
        return (desired - current + 540) % 360 - 180;
    }
    private static double normalize(double degrees) {
        return (degrees % 360 + 360) % 360;
    }
    private static double clamp(double n, double low, double high) {
        return Math.max(low, Math.min(high, n));
    }
}
