package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class AlternativeRoutesTest {
    private static List<GeoMath.Point> line(double lonOffset) {
        List<GeoMath.Point> p = new ArrayList<>();
        for (int i = 0; i <= 40; i++) p.add(new GeoMath.Point(53 + i * 0.0009, -2 + lonOffset));
        return p;
    }

    @Test public void rejectsSameRoute() {
        assertFalse(AlternativeRoutes.accept(line(0), 600, line(0), 600));
    }

    @Test public void acceptsDifferentRouteWithinTimeLimit() {
        // 0.01 degrees of longitude is about 670 m away: clearly a different road.
        assertTrue(AlternativeRoutes.accept(line(0), 600, line(0.01), 700));
    }

    @Test public void rejectsMuchSlowerRoute() {
        assertFalse(AlternativeRoutes.accept(line(0), 600, line(0.01), 800));   // +33%
    }

    @Test public void boundaryIsInclusive() {
        assertTrue(AlternativeRoutes.accept(line(0), 600, line(0.01), 750));    // exactly +25%
    }

    @Test public void rejectsBadInput() {
        assertFalse(AlternativeRoutes.accept(null, 600, line(0.01), 700));
        assertFalse(AlternativeRoutes.accept(line(0), 0, line(0.01), 700));
        assertFalse(AlternativeRoutes.accept(line(0), 600, line(0.01), 0));
    }

    @Test public void labels() {
        assertEquals("+2 min", AlternativeRoutes.slowerLabel(600, 720));
        assertEquals("same time", AlternativeRoutes.slowerLabel(600, 610));
        assertEquals("3 min faster", AlternativeRoutes.slowerLabel(600, 420));
    }
}
