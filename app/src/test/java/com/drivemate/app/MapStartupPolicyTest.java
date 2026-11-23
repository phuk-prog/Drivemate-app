package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapStartupPolicyTest {
    private final long now = 1_800_000_000_000L;

    @Test public void startupWithoutGpsShowsUkNotTheWorld() {
        MapStartupPolicy.Viewport v = MapStartupPolicy.initial(
                Double.NaN, Double.NaN, 0, now);
        assertFalse(v.remembered);
        assertEquals(54.4, v.center.lat, 0.001);
        assertEquals(-2.5, v.center.lon, 0.001);
        assertTrue(v.zoom > 5 && v.zoom < 8);
    }

    @Test public void previouslyVisitedAreaCanRestoreMapButIsNotGps() {
        MapStartupPolicy.Viewport v = MapStartupPolicy.initial(
                53.4, -2.16, now - 12 * 60 * 60 * 1000, now);
        assertTrue(v.remembered);
        assertEquals(53.4, v.center.lat, 0.001);
        assertTrue(v.zoom >= 12);
        assertFalse(MapStartupPolicy.freshForRouting(v.center.lat, v.center.lon,
                now - 12 * 60 * 60 * 1000, now, 5));
    }

    @Test public void rejectOutdatedOrInvalidLastArea() {
        assertFalse(MapStartupPolicy.initial(999, -2, now - 100, now).remembered);
        assertFalse(MapStartupPolicy.initial(53.4, -2,
                now - 40L * 24 * 60 * 60 * 1000, now).remembered);
        assertFalse(MapStartupPolicy.initial(53.4, -2, now + 1000, now).remembered);
    }

    @Test public void acceptsReasonableNetworkCacheForVisualDisplayOnly() {
        assertTrue(MapStartupPolicy.usableCachedMapArea(53.4, -2.2,
                now - 24 * 60 * 60 * 1000, now, 900));
        assertFalse(MapStartupPolicy.freshForRouting(53.4, -2.2,
                now - 24 * 60 * 60 * 1000, now, 900));
        assertFalse(MapStartupPolicy.usableCachedMapArea(53.4, -2.2,
                now - 24 * 60 * 60 * 1000, now, 3000));
    }

    @Test public void navigationNeedsFreshAndAccurateLocation() {
        assertTrue(MapStartupPolicy.freshForRouting(
                53.4, -2.2, now - 5000, now, 25));
        assertFalse(MapStartupPolicy.freshForRouting(
                53.4, -2.2, now - 25000, now, 25));
        assertFalse(MapStartupPolicy.freshForRouting(
                53.4, -2.2, now - 5000, now, 150));
        assertFalse(MapStartupPolicy.freshForRouting(
                53.4, -2.2, now + 5000, now, 20));
    }
}
