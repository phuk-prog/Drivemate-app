package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class CurrentRoadTest {
    private static GeoMath.Point p(double lat, double lon) { return new GeoMath.Point(lat, lon); }

    @Test public void namesTheWayUnderTheVehicle() {
        List<GeoMath.Point> pts = Arrays.asList(p(53.395, -2.194), p(53.3955, -2.1939), p(53.3957, -2.1946));
        Map<Long, String> names = new HashMap<>();
        names.put(4953969L, "Aber Road");
        names.put(187541143L, "Menai Grove");
        CurrentRoad road = CurrentRoad.of(pts, Arrays.asList(4953969L, 187541143L), names);
        assertEquals("Aber Road", road.at(10));
        assertEquals("Menai Grove", road.at(70));
        assertEquals("Menai Grove", road.at(1e6));
    }

    @Test public void fallsBackToLastStreetAndRejectsBadInput() {
        assertEquals("", CurrentRoad.EMPTY.at(5));
        assertEquals("Boundary Road", CurrentRoad.label("", "Boundary Road"));
        assertEquals("Aber Road", CurrentRoad.label("Aber Road", "Boundary Road"));
        assertEquals("", CurrentRoad.label(null, null));
        assertSame(CurrentRoad.EMPTY, CurrentRoad.of(Arrays.asList(p(53, -2)), Collections.emptyList(), new HashMap<>()));
    }
}
