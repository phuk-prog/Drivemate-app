package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class RouteUpdatePolicyTest {
    private static RouteUpdatePolicy.Gate gate(boolean first, boolean active, boolean hasRoute, float acc,
                                               long age, double moved, boolean offRoute, double oldOff) {
        return RouteUpdatePolicy.gate(first, active, hasRoute, true, acc, age, moved, offRoute, oldOff);
    }

    @Test public void firstRouteAlwaysInstalls() {
        assertEquals(RouteUpdatePolicy.Gate.INSTALL, gate(true, true, true, 999, 99999, 999, false, 999));
    }

    @Test public void routeBeforeNavigationInstalls() {
        assertEquals(RouteUpdatePolicy.Gate.INSTALL, gate(false, false, true, 5, 0, 0, false, 0));
        assertEquals(RouteUpdatePolicy.Gate.INSTALL, gate(false, true, false, 5, 0, 0, false, 0));
    }

    @Test public void poorOrMissingGpsRetains() {
        assertEquals(RouteUpdatePolicy.Gate.RETAIN_UNCERTAIN_GPS, gate(false, true, true, 60, 0, 0, false, 0));
        assertEquals(RouteUpdatePolicy.Gate.RETAIN_UNCERTAIN_GPS,
                gate(false, true, true, 5, LocationThresholds.GPS_LOST_AFTER_MS + 1, 0, false, 0));
        assertEquals(RouteUpdatePolicy.Gate.RETAIN_UNCERTAIN_GPS,
                RouteUpdatePolicy.gate(false, true, true, false, 5, 0, 0, false, 0));
    }

    @Test public void movedTooFarRetains() {
        assertEquals(RouteUpdatePolicy.Gate.RETAIN_GPS_MOVED, gate(false, true, true, 5, 0, 111, false, 0));
    }

    @Test public void confirmedOffRouteInstalls() {
        assertEquals(RouteUpdatePolicy.Gate.INSTALL, gate(false, true, true, 5, 0, 10, true, 200));
    }

    @Test public void slightlyOffRouteIsAlignmentNoise() {
        assertEquals(RouteUpdatePolicy.Gate.RETAIN_ALIGNMENT, gate(false, true, true, 5, 0, 10, false, 45));
    }

    @Test public void onRouteComparesRoutes() {
        assertEquals(RouteUpdatePolicy.Gate.COMPARE, gate(false, true, true, 5, 0, 10, false, 10));
    }

    @Test public void sameRouteJustInstalls() {
        assertEquals(RouteUpdatePolicy.Compare.INSTALL, RouteUpdatePolicy.compare(false, 0, true, 1, 1000));
    }

    @Test public void fasterRouteOfferedWhenAskingFirst() {
        assertEquals(RouteUpdatePolicy.Compare.OFFER_FASTER, RouteUpdatePolicy.compare(true, 240, true, 1, 1000));
    }

    @Test public void fasterRouteInstalledInAutomaticMode() {
        assertEquals(RouteUpdatePolicy.Compare.INSTALL, RouteUpdatePolicy.compare(true, 240, true, 0, 1000));
    }

    @Test public void staleEtaOffersUnverifiedChoice() {
        assertEquals(RouteUpdatePolicy.Compare.OFFER_UNVERIFIED, RouteUpdatePolicy.compare(true, 0, false, 1, 1000));
    }

    @Test public void noSavingKeepsCurrentRoute() {
        assertEquals(RouteUpdatePolicy.Compare.RETAIN_NO_SAVING, RouteUpdatePolicy.compare(true, 0, true, 1, 1000));
        assertEquals(RouteUpdatePolicy.Compare.RETAIN_NO_SAVING, RouteUpdatePolicy.compare(true, 0, false, 0, 1000));
    }

    @Test public void nothingOfferedNearNextManoeuvre() {
        assertEquals(RouteUpdatePolicy.Compare.RETAIN_NEAR_MANOEUVRE, RouteUpdatePolicy.compare(true, 400, true, 1, 250));
        assertEquals(RouteUpdatePolicy.Compare.OFFER_FASTER, RouteUpdatePolicy.compare(true, 400, true, 1, 300));
    }

    @Test public void unknownManoeuvreDistanceDoesNotBlock() {
        assertEquals(RouteUpdatePolicy.Compare.OFFER_FASTER, RouteUpdatePolicy.compare(true, 400, true, 1, -1));
    }
}
