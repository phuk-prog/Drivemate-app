package com.drivemate.app;

import java.util.List;

/** Road progress, remaining ETA and rerouting hysteresis. No Android dependencies. */
public final class RouteTracker {
    private final double distanceMeters;
    private final int initialTimeSeconds;
    private final List<TomTomApi.Instruction> instructions;
    private double greatestProgress;
    private long offRouteSince;
    private long lastRerouteAttempt = Long.MIN_VALUE / 2;

    public RouteTracker(TomTomApi.Route route) {
        distanceMeters = route.lengthMetres;
        initialTimeSeconds = route.timeSeconds;
        instructions = route.instructions;
    }

    public double updateProgress(double measuredProgress, double accuracyMeters) {
        double candidate = Math.max(0, Math.min(distanceMeters, measuredProgress));
        // Avoid backward jumps from common low-speed GPS wander while allowing
        // substantial reverse travel to be corrected by off-route rerouting.
        double backwardAllowance = accuracyMeters >= 35 ? 0 : 24;
        if (candidate + backwardAllowance >= greatestProgress) {
            greatestProgress = Math.max(greatestProgress, candidate);
        }
        return greatestProgress;
    }

    public double remainingMetres() {
        return Math.max(0, distanceMeters - greatestProgress);
    }

    /** Interpolates TomTom time-at-manoeuvre rather than simply scaling total distance. */
    public long remainingSeconds(float speedMps) {
        double currentTime = 0;
        double previousOffset = 0;
        double previousTime = 0;
        boolean usedInstructionTimes = false;
        for (TomTomApi.Instruction step : instructions) {
            if (step.routeOffsetMetres <= previousOffset
                    || step.travelTimeSeconds <= previousTime) continue;
            if (greatestProgress <= step.routeOffsetMetres) {
                double fraction = (greatestProgress - previousOffset)
                        / (step.routeOffsetMetres - previousOffset);
                currentTime = previousTime
                        + Math.max(0, Math.min(1, fraction))
                        * (step.travelTimeSeconds - previousTime);
                usedInstructionTimes = true;
                break;
            }
            previousOffset = step.routeOffsetMetres;
            previousTime = step.travelTimeSeconds;
        }
        if (!usedInstructionTimes && previousOffset < distanceMeters
                && greatestProgress > previousOffset && initialTimeSeconds >= previousTime) {
            currentTime = previousTime + (greatestProgress - previousOffset)
                    / Math.max(1, distanceMeters - previousOffset)
                    * (initialTimeSeconds - previousTime);
            usedInstructionTimes = true;
        }
        if (!usedInstructionTimes) {
            currentTime = initialTimeSeconds * greatestProgress
                    / Math.max(1, distanceMeters);
        }
        double baseline = Math.max(0, initialTimeSeconds - currentTime);
        // When moving, modestly incorporate recent GPS speed to avoid stale ETA
        // without treating a momentary stop as hours of delay.
        if (speedMps >= 3.5 && remainingMetres() > 100) {
            double speedEstimate = remainingMetres() / speedMps;
            double capped = Math.max(baseline * 0.65, Math.min(baseline * 1.5, speedEstimate));
            baseline = baseline * 0.85 + capped * 0.15;
        }
        return (long) Math.ceil(baseline);
    }

    public boolean shouldReroute(long nowElapsedMs, double offRouteMetres,
                                 float gpsAccuracy, float speedMps) {
        if (gpsAccuracy > 40 || speedMps < 1.0 || offRouteMetres <= Math.max(65, gpsAccuracy * 2.3)) {
            offRouteSince = 0;
            return false;
        }
        if (offRouteSince == 0) offRouteSince = nowElapsedMs;
        if (nowElapsedMs - offRouteSince >= 8500 && nowElapsedMs - lastRerouteAttempt >= 45000) {
            lastRerouteAttempt = nowElapsedMs;
            offRouteSince = 0;
            return true;
        }
        return false;
    }

    public void markRerouteAttempt(long elapsedMs) {
        lastRerouteAttempt = elapsedMs;
        offRouteSince = 0;
    }
}
