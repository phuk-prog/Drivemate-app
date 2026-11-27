package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class TunnelSpansTest {
    /** Inverse of {@link TunnelSpans#worldToPoint}: lat/lon to unit-square Web Mercator. */
    private static double[] world(double lat, double lon) {
        double x = (lon + 180) / 360;
        double s = Math.sin(Math.toRadians(lat));
        double y = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
        return new double[] {x, y};
    }

    private static List<GeoMath.Point> route() {
        List<GeoMath.Point> p = new ArrayList<>();
        for (int i = 0; i <= 40; i++) p.add(new GeoMath.Point(53 + i * 0.0009, -2));
        return p;
    }

    private static NativeMapScene.Shape shape(int kind, boolean tunnel, double lonOffset, int from, int to) {
        double[] xy = new double[(to - from + 1) * 2];
        for (int i = from, k = 0; i <= to; i++, k += 2) {
            double[] w = world(53 + i * 0.0009, -2 + lonOffset);
            xy[k] = w[0];
            xy[k + 1] = w[1];
        }
        return new NativeMapScene.Shape(1, kind, xy, "", false, tunnel, false);
    }

    @Test public void roundTripConversion() {
        double[] w = world(53.5, -2.25);
        GeoMath.Point p = TunnelSpans.worldToPoint(w[0], w[1]);
        assertEquals(53.5, p.lat, 1e-6);
        assertEquals(-2.25, p.lon, 1e-6);
    }

    @Test public void findsTunnelOnTheRoute() {
        List<double[]> spans = TunnelSpans.fromShapes(route(),
                Collections.singletonList(shape(NativeMapScene.MAIN, true, 0, 10, 20)));
        assertEquals(1, spans.size());
        assertEquals(1000, spans.get(0)[0], 15);
        assertEquals(2000, spans.get(0)[1], 15);
    }

    @Test public void ignoresParallelTunnelFarFromRoute() {
        // 0.001 degrees of longitude is about 67 m at this latitude.
        List<double[]> spans = TunnelSpans.fromShapes(route(),
                Collections.singletonList(shape(NativeMapScene.MAIN, true, 0.001, 10, 20)));
        assertTrue(spans.isEmpty());
    }

    @Test public void ignoresNonTunnelsAndAreas() {
        assertTrue(TunnelSpans.fromShapes(route(),
                Collections.singletonList(shape(NativeMapScene.MAIN, false, 0, 10, 20))).isEmpty());
        assertTrue(TunnelSpans.fromShapes(route(),
                Collections.singletonList(shape(NativeMapScene.BUILDING, true, 0, 10, 20))).isEmpty());
    }

    @Test public void mergesTouchingSpans() {
        List<NativeMapScene.Shape> shapes = Arrays.asList(
                shape(NativeMapScene.MAIN, true, 0, 5, 12), shape(NativeMapScene.MAIN, true, 0, 12, 20));
        List<double[]> spans = TunnelSpans.fromShapes(route(), shapes);
        assertEquals(1, spans.size());
    }

    @Test public void emptyInputsGiveNoSpans() {
        assertTrue(TunnelSpans.fromShapes(null, null).isEmpty());
        assertTrue(TunnelSpans.fromShapes(route(), Collections.<NativeMapScene.Shape>emptyList()).isEmpty());
    }
}
