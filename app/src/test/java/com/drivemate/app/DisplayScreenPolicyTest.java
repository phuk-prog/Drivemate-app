package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayScreenPolicyTest {
    @Test public void unknownAndStaleSpeedAreNotZero() {
        assertTrue(DisplayScreenPolicy.speedKnown(0, 100));
        assertFalse(DisplayScreenPolicy.speedKnown(Double.NaN, 100));
        assertFalse(DisplayScreenPolicy.speedKnown(-1, 100));
        assertFalse(DisplayScreenPolicy.speedKnown(15, 15001));
    }
    @Test public void progressIsBoundedAndRejectsInvalidValues() {
        assertEquals(500,DisplayScreenPolicy.progress(50,100));
        assertEquals(1000,DisplayScreenPolicy.progress(120,100));
        assertEquals(0,DisplayScreenPolicy.progress(Double.NaN,100));
        assertEquals(0,DisplayScreenPolicy.progress(50,0));
    }
    @Test public void routeRedrawIsThrottled() {
        assertTrue(DisplayScreenPolicy.redrawRoute(-1,0,0));
        assertFalse(DisplayScreenPolicy.redrawRoute(5,30,1000));
        assertFalse(DisplayScreenPolicy.redrawRoute(5,10,5000));
        assertTrue(DisplayScreenPolicy.redrawRoute(5,30,5000));
        assertFalse(DisplayScreenPolicy.redrawRoute(5,Double.NaN,5000));
    }
    @Test public void compactAndTrafficStates() {
        assertTrue(DisplayScreenPolicy.compact(620,1));
        assertTrue(DisplayScreenPolicy.compact(900,1.3f));
        assertFalse(DisplayScreenPolicy.compact(800,1));
        assertTrue(DisplayScreenPolicy.trafficFresh(480000));
        assertFalse(DisplayScreenPolicy.trafficFresh(480001));
        assertFalse(DisplayScreenPolicy.trafficFresh(-1));
    }
}
