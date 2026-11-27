package com.drivemate.app;

/**
 * Decides what to do with a freshly calculated route while navigating: install
 * it, offer it, or keep the current route. Moved out of
 * {@code MainActivity.planRoute} so the rules are unit tested. Behaviour is the
 * same as before, plus one rule: no faster-route offer within
 * {@link #NEAR_MANOEUVRE_METRES} of the next manoeuvre.
 */
public final class RouteUpdatePolicy {
    private RouteUpdatePolicy() { }

    public static final float MAX_ACCURACY_METRES = 55f;
    public static final double MAX_START_MOVED_METRES = 110;
    public static final double ALIGNMENT_METRES = 30;
    public static final double NEAR_MANOEUVRE_METRES = 300;

    public enum Gate { INSTALL, RETAIN_UNCERTAIN_GPS, RETAIN_GPS_MOVED, RETAIN_ALIGNMENT, COMPARE }

    public enum Compare { INSTALL, OFFER_FASTER, OFFER_UNVERIFIED, RETAIN_NO_SAVING, RETAIN_NEAR_MANOEUVRE }

    /**
     * @param movedMetres distance between current GPS and the point the route was planned from
     * @param confirmedOffRoute the old route is genuinely left (a reroute, not a refresh)
     */
    public static Gate gate(boolean firstRoute, boolean active, boolean hasRoute,
                            boolean hasAccuracy, float accuracyMetres, long gpsAgeMs,
                            double movedMetres, boolean confirmedOffRoute, double oldOffRouteMetres) {
        if (firstRoute || !active || !hasRoute) return Gate.INSTALL;
        if (!hasAccuracy || accuracyMetres > MAX_ACCURACY_METRES
                || gpsAgeMs > LocationThresholds.GPS_LOST_AFTER_MS) return Gate.RETAIN_UNCERTAIN_GPS;
        if (movedMetres > MAX_START_MOVED_METRES) return Gate.RETAIN_GPS_MOVED;
        if (confirmedOffRoute) return Gate.INSTALL;
        if (oldOffRouteMetres > ALIGNMENT_METRES) return Gate.RETAIN_ALIGNMENT;
        return Gate.COMPARE;
    }

    /**
     * @param rerouteMode 0 automatic, 1 ask first
     * @param metresToNextManoeuvre distance to the next instruction on the current route, or negative if unknown
     */
    public static Compare compare(boolean different, int savingSeconds, boolean oldEtaFresh,
                                  int rerouteMode, double metresToNextManoeuvre) {
        if (!different) return Compare.INSTALL;
        if (metresToNextManoeuvre >= 0 && metresToNextManoeuvre < NEAR_MANOEUVRE_METRES) {
            return Compare.RETAIN_NEAR_MANOEUVRE;
        }
        if (savingSeconds > 0) return rerouteMode == 1 ? Compare.OFFER_FASTER : Compare.INSTALL;
        if (!oldEtaFresh && rerouteMode == 1) return Compare.OFFER_UNVERIFIED;
        return Compare.RETAIN_NO_SAVING;
    }
}
