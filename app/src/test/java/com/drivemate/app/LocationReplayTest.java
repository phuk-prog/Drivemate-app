package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/**
 * Replays a synthetic drive (not a recording) through the whole location path:
 * fix filtering, the pipeline counters and the route bridge. It exercises
 * open road, an urban-canyon section of poor fixes, a tunnel with no fixes at
 * all, and re-acquisition after the tunnel.
 */
public class LocationReplayTest {
    private static List<GeoMath.Point> route() {
        List<GeoMath.Point> p = new ArrayList<>();
        for (int i = 0; i <= 100; i++) p.add(new GeoMath.Point(53 + i * 0.0009, -2));   // ~10 km north
        return p;
    }

    private static FixQualityPolicy.Sample fix(double along, long t, float acc, int source) {
        GeoMath.Point p = GeoMath.pointAtDistance(route(), along);
        return new FixQualityPolicy.Sample(p.lat, p.lon, t, acc, source);
    }

    @Test public void tunnelDriveIsBridgedAndRecovers() {
        List<GeoMath.Point> route = route();
        LocationPipeline pipe = new LocationPipeline();
        RouteDeadReckoner bridge = new RouteDeadReckoner();
        bridge.setRoute(route);
        bridge.setTunnelSpans(Collections.singletonList(new double[] {3000, 4000}));

        double speed = 20;                       // m/s
        long t = 0;
        double along = 0;
        GeoMath.Point lastFix = null;
        long lastFixTime = 0;
        // 1. Open road, good fixes every second up to 2950 m (just inside the tunnel mouth).
        for (; along < 2950; along += speed, t += 1000) {
            FixQualityPolicy.Sample s = fix(along, t, 6, FixQualityPolicy.SOURCE_FUSED);
            assertTrue(pipe.offer(s, false).accepted);
            lastFix = new GeoMath.Point(s.lat, s.lon);
            lastFixTime = t;
            bridge.onFix(lastFix, 6, t, (float) speed, 0);
        }
        assertEquals(0, pipe.rejected());

        // 2. Tunnel: no fixes for 50 s. The bridge must keep producing positions, in order, along the route.
        double previousAlong = -1;
        for (long gap = 2000; gap <= 50_000; gap += 2000) {
            RouteDeadReckoner.Estimate e = bridge.estimate(lastFixTime + gap);
            if (e == null) break;
            assertTrue("estimate moves forward", e.alongMetres >= previousAlong);
            assertTrue("never leaves the tunnel", e.alongMetres <= 4000.5);
            previousAlong = e.alongMetres;
        }
        assertTrue("bridge survived beyond the open-road limit", previousAlong > 3000);

        // 3. Exit at 4000 m: first fix back, 52 s after the last (re-acquisition after a long gap).
        long exitTime = lastFixTime + 52_000;
        FixQualityPolicy.Sample back = fix(4000, exitTime, 12, FixQualityPolicy.SOURCE_GPS);
        assertTrue("re-acquisition accepted despite the jump", pipe.offer(back, false).accepted);
        pipe.recordBridge(52_000);
        assertEquals(1, pipe.bridgeEpisodes());
    }

    @Test public void urbanCanyonPoorFixesAreCountedNotUsed() {
        LocationPipeline pipe = new LocationPipeline();
        long t = 0;
        assertTrue(pipe.offer(fix(0, t, 5, FixQualityPolicy.SOURCE_GPS), false).accepted);
        // Eight poor 150 m fixes, one per second: all rejected.
        for (int i = 1; i <= 8; i++) {
            t += 1000;
            assertFalse(pipe.offer(fix(i * 15, t, 150, FixQualityPolicy.SOURCE_GPS), false).accepted);
        }
        assertEquals(8, pipe.count(FixQualityPolicy.Reason.TOO_POOR));
        // A 70 m fix 6 s after the last accepted one is now accepted (new rule), earlier would not be.
        t = 6000;
        assertTrue(pipe.offer(fix(120, t, 70, FixQualityPolicy.SOURCE_GPS), false).accepted);
    }

    @Test public void teleportingFixInsideTheDriveIsRejected() {
        LocationPipeline pipe = new LocationPipeline();
        assertTrue(pipe.offer(fix(0, 0, 5, FixQualityPolicy.SOURCE_GPS), false).accepted);
        assertTrue(pipe.offer(fix(20, 1000, 5, FixQualityPolicy.SOURCE_GPS), false).accepted);
        assertEquals(FixQualityPolicy.Reason.TELEPORT,
                pipe.offer(fix(4000, 2000, 5, FixQualityPolicy.SOURCE_GPS), false).reason);
        assertTrue(pipe.offer(fix(40, 3000, 5, FixQualityPolicy.SOURCE_GPS), false).accepted);
    }
}
