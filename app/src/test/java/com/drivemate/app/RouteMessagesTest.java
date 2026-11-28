package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class RouteMessagesTest {
    @Test public void engineErrorsBecomePlainEnglish() {
        assertEquals(RouteMessages.OUTSIDE_COVERAGE, RouteMessages.friendly("No connected route inside pilot limits", false, false));
        assertEquals(RouteMessages.OUTSIDE_COVERAGE, RouteMessages.friendly("Route crosses unverified regional boundary", false, false));
        assertEquals(RouteMessages.OUTSIDE_COVERAGE, RouteMessages.friendly(null, true, false));
        assertEquals(RouteMessages.NO_ROAD_NEAR, RouteMessages.friendly("Route endpoints too far from verified road nodes", false, true));
        assertEquals(RouteMessages.ALREADY_THERE, RouteMessages.friendly("Same snapped junction", false, true));
        assertEquals(RouteMessages.GENERIC, RouteMessages.friendly("java.lang.IllegalStateException: x", false, true));
        assertFalse(RouteMessages.friendly("No connected route inside pilot limits", false, false).contains("pilot"));
    }

    @Test public void trafficNote() {
        assertEquals(" · live traffic", RouteMessages.traffic(true));
        assertEquals(" · no live traffic", RouteMessages.traffic(false));
    }
}
