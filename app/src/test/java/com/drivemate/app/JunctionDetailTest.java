package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class JunctionDetailTest {
    @Test public void countableRoads() {
        assertTrue(JunctionDetail.countableHighway("residential"));
        assertTrue(JunctionDetail.countableHighway("primary_link"));
        assertFalse(JunctionDetail.countableHighway("service"));
        assertFalse(JunctionDetail.countableHighway("footway"));
        assertFalse(JunctionDetail.countableHighway(null));
    }

    @Test public void ignorableRoadsAreNotCountableOrUnknown() {
        assertTrue(JunctionDetail.ignorableHighway("service"));
        assertTrue(JunctionDetail.ignorableHighway("track"));
        assertFalse(JunctionDetail.ignorableHighway("residential"));
        assertFalse("something unfamiliar is unknown", JunctionDetail.ignorableHighway("proposed"));
    }

    @Test public void relativeBearingWraps() {
        assertEquals(20, JunctionDetail.relative(350, 10), 1e-9);
        assertEquals(-20, JunctionDetail.relative(10, 350), 1e-9);
        assertEquals(180, Math.abs(JunctionDetail.relative(0, 180)), 1e-9);
    }

    @Test public void forkWindow() {
        assertFalse(JunctionDetail.isFork(10));
        assertTrue(JunctionDetail.isFork(15));
        assertTrue(JunctionDetail.isFork(-45));
        assertTrue(JunctionDetail.isFork(60));
        assertFalse("a side road at 90 degrees is not a fork", JunctionDetail.isFork(90));
    }
}
