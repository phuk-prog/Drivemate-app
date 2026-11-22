package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RouteTrackerTest {
    private RouteTracker tracker() {
        TomTomApi.Instruction one = new TomTomApi.Instruction(
                500, "Left", "TURN_LEFT", "TURN", "REGULAR", "", "", 0, -90, 80);
        TomTomApi.Instruction two = new TomTomApi.Instruction(
                1000, "Arrive", "ARRIVE", "LOCATION_ARRIVAL", "REGULAR", "", "", 0, 0, 300);
        TomTomApi.Route route = new TomTomApi.Route(Arrays.asList(
                new GeoMath.Point(53, -2), new GeoMath.Point(53.01, -2)),
                Arrays.asList(one, two), 1000, 300, 35);
        return new RouteTracker(route);
    }
    @Test public void usesTrafficAwareTimedInstructions() {
        RouteTracker tracker = tracker();
        tracker.updateProgress(250, 5);
        // 40 seconds elapsed (half of the first 80-sec segment)
        assertEquals(260, tracker.remainingSeconds(0));
        assertEquals(750, tracker.remainingMetres(), 0.1);
    }
    @Test public void doesNotBacktrackOnMinorGpsWander() {
        RouteTracker tracker = tracker();
        tracker.updateProgress(300, 10);
        tracker.updateProgress(290, 10);
        assertEquals(700, tracker.remainingMetres(), 0.1);
    }
    @Test public void rerouteNeedsGoodAccuracyMovementAndDwell() {
        RouteTracker tracker = tracker();
        assertFalse(tracker.shouldReroute(10000, 140, 20, 10));
        assertFalse(tracker.shouldReroute(16000, 140, 20, 10));
        assertTrue(tracker.shouldReroute(19000, 140, 20, 10));
        assertFalse(tracker.shouldReroute(20000, 140, 20, 10));
        assertFalse(tracker.shouldReroute(35000, 140, 80, 10));
    }
}
