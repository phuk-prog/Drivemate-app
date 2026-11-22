package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NavigationUnitsTest {
    @Test public void ukDistancesAreMilesAndYards() {
        assertEquals("1.0 mi", NavigationUnits.displayDistance(1609.344, false));
        assertEquals("300 yd", NavigationUnits.displayDistance(274.32, false));
        assertEquals("300 yards", NavigationUnits.spokenDistance(274.32, false));
    }
    @Test public void metricDistancesAndSpeedAreAvailable() {
        assertEquals("1.5 km", NavigationUnits.displayDistance(1500, true));
        assertEquals("250 m", NavigationUnits.displayDistance(250, true));
        assertEquals("100 km/h", NavigationUnits.displaySpeed(27.7777778, true));
        assertEquals("62 mph", NavigationUnits.displaySpeed(27.7777778, false));
    }
    @Test public void negativeNumbersDoNotGenerateNegativeGuidance() {
        assertEquals("0 yd", NavigationUnits.displayDistance(-10, false));
        assertEquals("10 yards", NavigationUnits.spokenDistance(-10, false));
        assertTrue(NavigationUnits.duration(0).contains("min"));
        assertEquals("1 hr 5 min", NavigationUnits.duration(3900));
    }
}
