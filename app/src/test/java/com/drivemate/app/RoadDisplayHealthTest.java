package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoadDisplayHealthTest {
    @Test public void downloadedTilesDoNotCountAsVisibleRoads() {
        RoadDisplayHealth h = new RoadDisplayHealth();
        assertEquals(RoadDisplayHealth.State.LOADING,
                h.check(1000, 53.4, -2.2, 16, true, 0));
        assertEquals(RoadDisplayHealth.State.LOADING,
                h.check(11999, 53.4, -2.2, 16, true, 0));
        assertEquals(RoadDisplayHealth.State.FAILED,
                h.check(13000, 53.4, -2.2, 16, true, 0));
    }

    @Test public void actuallyRenderedStreetsPermitMapDisplay() {
        RoadDisplayHealth h = new RoadDisplayHealth();
        h.check(1000, 53.4, -2.2, 16, false, 0);
        assertEquals(RoadDisplayHealth.State.READY,
                h.check(4500, 53.4, -2.2, 16, true, 12));
        assertEquals(RoadDisplayHealth.State.LOADING,
                h.check(6000, 53.4, -2.2, 16, true, 0));
        assertEquals(RoadDisplayHealth.State.FAILED,
                h.check(18001, 53.4, -2.2, 16, true, 0));
    }

    @Test public void waitingForStyleStillTimesOut() {
        RoadDisplayHealth h = new RoadDisplayHealth();
        h.check(1, 51.5, -0.12, 16, false, 0);
        assertEquals(RoadDisplayHealth.State.FAILED,
                h.check(12001, 51.5, -0.12, 16, false, 0));
    }

    @Test public void doesNotFallbackAtWorldOverviewOrOutsideUk() {
        RoadDisplayHealth h = new RoadDisplayHealth();
        assertEquals(RoadDisplayHealth.State.NOT_APPLICABLE,
                h.check(100, 51.5, -0.12, 5, true, 0));
        assertEquals(RoadDisplayHealth.State.NOT_APPLICABLE,
                h.check(30000, 40.0, -74.0, 16, true, 0));
    }

    @Test public void resetsAfterCameraLeavesUkArea() {
        RoadDisplayHealth h = new RoadDisplayHealth();
        h.check(1000, 53.4, -2.2, 16, true, 0);
        h.check(13001, 53.4, -2.2, 16, true, 0);
        h.check(15000, 40, -74, 16, true, 0);
        assertEquals(RoadDisplayHealth.State.LOADING,
                h.check(15001, 53.4, -2.2, 16, true, 0));
    }
}
