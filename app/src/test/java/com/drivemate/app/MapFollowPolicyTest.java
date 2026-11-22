package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapFollowPolicyTest {
    @Test public void movingCameraFollowsWithoutTwoSecondDelay() {
        assertTrue(MapFollowPolicy.shouldMove(1000, 1850, 5, 7, 0, 0));
        assertFalse(MapFollowPolicy.shouldMove(1000, 1200, 5, 7, 0, 0));
    }
    @Test public void stoppedCameraIgnoresGpsJitter() {
        assertFalse(MapFollowPolicy.shouldMove(1000, 2900, 4, 0, 0, 0));
        assertTrue(MapFollowPolicy.shouldMove(1000, 2900, 16, 0, 0, 0));
        assertTrue(MapFollowPolicy.shouldMove(1000, 2900, 4, 0, 0.5, 0));
    }
    @Test public void headingNeverWrapsTheLongWay() {
        assertEquals(20, MapFollowPolicy.shortestHeadingDifference(10, 350), 0.001);
        assertEquals(-20, MapFollowPolicy.shortestHeadingDifference(350, 10), 0.001);
    }
    @Test public void reinitializesAfterClockReset() {
        assertTrue(MapFollowPolicy.shouldMove(0, 1000, 0, 0, 0, 0));
        assertTrue(MapFollowPolicy.shouldMove(2000, 1000, 0, 0, 0, 0));
    }
}
