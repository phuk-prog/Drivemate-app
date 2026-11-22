package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveTileGridTest {
    @Test public void tilesNearGreenwichAndUKStayWithinRange() {
        int zoom = 14;
        int x = DriveTileServer.tileX(-0.1278, zoom);
        int y = DriveTileServer.tileY(51.5074, zoom);
        assertTrue(x > 0 && x < (1 << zoom));
        assertTrue(y > 0 && y < (1 << zoom));
        assertTrue(x < (1 << zoom) / 2);
    }
    @Test public void zoomZeroHasExactlyOneTile() {
        assertEquals(0, DriveTileServer.tileX(-175, 0));
        assertEquals(0, DriveTileServer.tileY(53, 0));
    }
    @Test public void extremeLatitudesAreSafelyClamped() {
        assertEquals(0, DriveTileServer.tileY(90, 14));
        assertEquals((1 << 14) - 1, DriveTileServer.tileY(-90, 14));
    }
}
