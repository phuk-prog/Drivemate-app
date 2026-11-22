package com.drivemate.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DisplayPolylineTest {
    @Test public void straightRoadDropsRedundantVertices() {
        List<GeoMath.Point> route = new ArrayList<>();
        for (int i = 0; i <= 100; i++)
            route.add(new GeoMath.Point(53.0 + i * 0.00001, -2.0));
        List<GeoMath.Point> drawn = DisplayPolyline.simplify(route, 1.5);
        assertEquals(2, drawn.size());
        assertSame(route.get(0), drawn.get(0));
        assertSame(route.get(100), drawn.get(1));
    }
    @Test public void preservesSharpJunctionBend() {
        GeoMath.Point a = new GeoMath.Point(53.0, -2.0);
        GeoMath.Point turn = new GeoMath.Point(53.001, -2.0);
        GeoMath.Point b = new GeoMath.Point(53.001, -1.998);
        assertEquals(3, DisplayPolyline.simplify(Arrays.asList(a, turn, b), 1.5).size());
    }
    @Test public void keepsSmallRouteAndNeverRewritesInput() {
        List<GeoMath.Point> p = Arrays.asList(
                new GeoMath.Point(53, -2), new GeoMath.Point(53.01, -2));
        assertSame(p, DisplayPolyline.simplify(p, 1.5));
    }
}
