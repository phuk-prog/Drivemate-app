package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class RoutePreferencesTest {
    @Test public void nothingRequestedByDefault() {
        assertEquals(RoutePreferences.Availability.NOT_REQUESTED, RoutePreferences.NONE.availability());
        assertFalse(RoutePreferences.NONE.any());
    }

    @Test public void requestedPreferencesAreReportedUnavailableUntilTilesCarryRoadClass() {
        assertFalse(RoutePreferences.EDGE_CLASS_IN_TILES);
        RoutePreferences p = new RoutePreferences(true, false, false);
        assertTrue(p.any());
        assertEquals(RoutePreferences.Availability.UNAVAILABLE_NEEDS_MAP_UPDATE, p.availability());
    }
}
