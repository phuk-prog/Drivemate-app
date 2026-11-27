package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class UkTurnDistanceTest {
    @Test public void imperialSteps() {
        assertEquals("now", NavigationUnits.turnDistance(9, false));
        assertEquals("20 yd", NavigationUnits.turnDistance(18.3, false));
        assertEquals("50 yd", NavigationUnits.turnDistance(45.7, false));
        assertEquals("100 yd", NavigationUnits.turnDistance(95, false));
        assertEquals("200 yd", NavigationUnits.turnDistance(185, false));
        assertEquals("300 yd", NavigationUnits.turnDistance(270, false));
        assertEquals("½ mi", NavigationUnits.turnDistance(823, false));   // was "900 yd"
        assertEquals("¼ mi", NavigationUnits.turnDistance(400, false));
        assertEquals("¾ mi", NavigationUnits.turnDistance(1200, false));
        assertEquals("1.3 mi", NavigationUnits.turnDistance(2100, false));
    }

    @Test public void metricSteps() {
        assertEquals("now", NavigationUnits.turnDistance(5, true));
        assertEquals("40 m", NavigationUnits.turnDistance(38, true));
        assertEquals("250 m", NavigationUnits.turnDistance(240, true));
        assertEquals("1.2 km", NavigationUnits.turnDistance(1210, true));
        assertEquals("now", NavigationUnits.turnDistance(Double.NaN, true));
    }

    @Test public void plainHazardLabels() {
        assertEquals("Crossing", RoadTestDisplayPolicy.plainLabel("Mapped crossing"));
        assertEquals("Zebra crossing", RoadTestDisplayPolicy.plainLabel("Mapped zebra crossing"));
        assertEquals("Roadworks", RoadTestDisplayPolicy.plainLabel("Roadworks"));
        assertEquals("", RoadTestDisplayPolicy.plainLabel(null));
    }
}
