package com.drivemate.app;

import java.util.Collections;
import java.util.List;

/**
 * Display-only estimate of the vehicle position along the active route while
 * GPS is missing. Unlike {@link DeadReckoner} (a straight line, route-free) this
 * follows the route geometry, so it stays on the road through bends.
 *
 * Rules:
 * - On open road it bridges for at most {@link LocationThresholds#OPEN_ROAD_BRIDGE_MS}.
 * - Inside a mapped tunnel span it keeps going until the signal returns, up to
 *   {@link LocationThresholds#TUNNEL_BRIDGE_MAX_MS}, and holds at the tunnel exit.
 * - Speed eases from the last fix speed toward an expected speed (the road's
 *   limit if known), so a car slowing in a tunnel does not run ahead.
 * - It stops {@link LocationThresholds#MANOEUVRE_HOLD_METRES} short of the next
 *   hold point (a manoeuvre) and never passes it. Guidance does not use this class.
 * - Uncertainty grows with time and distance; past
 *   {@link LocationThresholds#BRIDGE_GIVE_UP_UNCERTAINTY_METRES} it returns null.
 * - Every estimate is flagged as estimated. Nothing here feeds off-route
 *   detection, rerouting or spoken instructions.
 */
public final class RouteDeadReckoner {
    /** Time constant for easing toward the expected speed, seconds. */
    static final double SPEED_EASE_SECONDS = 10.0;
    /** Only show an estimate once a gap is clearly longer than normal fix spacing. */
    static final long MIN_GAP_MS = 1500L;

    /** One estimated position. */
    public static final class Estimate {
        public final GeoMath.Point point;
        public final double bearingDegrees;
        public final double alongMetres;
        public final double uncertaintyMetres;
        public final boolean inTunnel;
        /** Always true; present so callers cannot forget the position is not a fix. */
        public final boolean estimated = true;
        Estimate(GeoMath.Point point, double bearing, double along, double uncertainty, boolean tunnel) {
            this.point = point;
            this.bearingDegrees = bearing;
            this.alongMetres = along;
            this.uncertaintyMetres = uncertainty;
            this.inTunnel = tunnel;
        }
    }

    private List<GeoMath.Point> path = Collections.emptyList();
    private double totalMetres;
    private double[][] tunnels = new double[0][];
    private double[] holdPoints = new double[0];

    private boolean anchored;
    private double anchorAlong;
    private long anchorMs;
    private double anchorSpeed;
    private double expectedSpeed;
    private float anchorAccuracy;
    private boolean anchorInTunnel;

    /** Replace the route. Resets the anchor. */
    public void setRoute(List<GeoMath.Point> route) {
        path = route == null ? Collections.<GeoMath.Point>emptyList() : route;
        totalMetres = path.size() < 2 ? 0 : GeoMath.length(path);
        anchored = false;
    }

    /** Along-route ranges {start, end} in metres that are tunnels. */
    public void setTunnelSpans(List<double[]> spans) {
        tunnels = spans == null ? new double[0][] : spans.toArray(new double[0][]);
    }

    /** Along-route offsets of manoeuvres. The marker is never taken to within the hold distance of one. */
    public void setHoldPoints(double[] offsets) {
        holdPoints = offsets == null ? new double[0] : offsets.clone();
    }

    /**
     * Record a real, accepted fix as the new anchor.
     *
     * @param expectedMps speed to ease toward, or a value <= 0 to hold the fix speed
     */
    public void onFix(GeoMath.Point fix, float accuracyMetres, long timeMs, float speedMps,
                      double expectedMps) {
        if (path.size() < 2 || fix == null) { anchored = false; return; }
        GeoMath.Progress progress = GeoMath.project(path, fix);
        // A fix far from the route is not evidence of position along it.
        if (progress.offRouteMetres > 60) { anchored = false; return; }
        anchorAlong = progress.alongMetres;
        anchorMs = timeMs;
        anchorSpeed = Math.max(0, speedMps);
        expectedSpeed = expectedMps > 0 ? expectedMps : anchorSpeed;
        anchorAccuracy = Float.isFinite(accuracyMetres) ? accuracyMetres : 30f;
        anchorInTunnel = inTunnel(anchorAlong);
        anchored = anchorSpeed >= DeadReckoner.MIN_SPEED_MPS;
    }

    public void reset() { anchored = false; }

    /** Estimate at {@code nowMs}, or null when there is nothing sensible to show. */
    public Estimate estimate(long nowMs) {
        if (!anchored || path.size() < 2) return null;
        long gap = nowMs - anchorMs;
        if (gap < MIN_GAP_MS) return null;
        double seconds = gap / 1000.0;

        // Distance with speed easing from anchorSpeed toward expectedSpeed.
        double tau = SPEED_EASE_SECONDS;
        double travelled = expectedSpeed * seconds
                + (anchorSpeed - expectedSpeed) * tau * (1 - Math.exp(-seconds / tau));
        double along = Math.min(totalMetres, anchorAlong + Math.max(0, travelled));

        // Tunnel handling: a bridge that is inside a tunnel when it starts runs
        // until the tunnel's end; anywhere else it is limited to open-road time.
        double[] span = anchorInTunnel ? spanAt(anchorAlong) : null;
        boolean tunnelNow = span != null && along <= span[1];
        long limit = span != null
                ? LocationThresholds.TUNNEL_BRIDGE_MAX_MS : LocationThresholds.OPEN_ROAD_BRIDGE_MS;
        if (gap > limit) return null;
        if (span != null && along > span[1]) along = span[1];

        // Never reach a manoeuvre: stop short of the next hold point ahead of the anchor.
        for (double hold : holdPoints) {
            double stop = hold - LocationThresholds.MANOEUVRE_HOLD_METRES;
            if (hold > anchorAlong && along > stop) {
                along = Math.max(anchorAlong, stop);
                break;
            }
        }
        double moved = Math.max(0, along - anchorAlong);
        double uncertainty = anchorAccuracy + 0.05 * moved + 1.0 * seconds;
        if (uncertainty > LocationThresholds.BRIDGE_GIVE_UP_UNCERTAINTY_METRES) return null;

        GeoMath.Point point = GeoMath.pointAtDistance(path, along);
        double ahead = Math.min(totalMetres, along + 5);
        double behind = Math.max(0, along - 5);
        double bearing = ahead > behind
                ? GeoMath.bearingDegrees(GeoMath.pointAtDistance(path, behind),
                        GeoMath.pointAtDistance(path, ahead))
                : 0;
        return new Estimate(point, bearing, along, uncertainty, tunnelNow || inTunnel(along));
    }

    private boolean inTunnel(double along) { return spanAt(along) != null; }

    private double[] spanAt(double along) {
        for (double[] t : tunnels) if (along >= t[0] && along <= t[1]) return t;
        return null;
    }
}
