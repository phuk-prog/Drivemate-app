package com.drivemate.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class GlTessellatorTest {
    private static final double O = 0.5, U = 1e-5;   // somewhere in the world, 1e-5 Mercator units ≈ 400 m

    private static NativeMapScene.Shape area(int kind, int[] parts, double... local) {
        double[] xy = new double[local.length];
        for (int i = 0; i < local.length; i++) xy[i] = O + local[i] * U;
        return new NativeMapScene.Shape(1, kind, xy, "", false, false, true, 0, parts);
    }

    private static NativeMapScene.Shape line(int kind, boolean bridge, double... local) {
        double[] xy = new double[local.length];
        for (int i = 0; i < local.length; i++) xy[i] = O + local[i] * U;
        return new NativeMapScene.Shape(2, kind, xy, "Road", bridge, false, false, 0, new int[]{0});
    }

    /** Sum of triangle areas, in squared original units. */
    private static double area(GlTessellator.Mesh m) {
        double total = 0, k = 1 / (m.scale * U);
        short[] a = m.areas;
        for (int i = 0; i < a.length; i += 9) {
            double x1 = a[i] * k, y1 = a[i + 1] * k, x2 = a[i + 3] * k, y2 = a[i + 4] * k, x3 = a[i + 6] * k, y3 = a[i + 7] * k;
            total += Math.abs((x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1)) / 2;
        }
        return total;
    }

    @Test public void squareIsTwoTrianglesWithItsFullArea() {
        GlTessellator.Mesh m = GlTessellator.build(Collections.singletonList(
                area(NativeMapScene.LAND, new int[]{0}, 0, 0, 10, 0, 10, 10, 0, 10, 0, 0)), false);
        assertEquals(6, m.areaCount());
        assertEquals(100, area(m), 0.5);
    }

    @Test public void concaveShapeIsFilledExactly() {
        // L shape: 10x10 square minus the 5x5 top-right corner = 75
        GlTessellator.Mesh m = GlTessellator.build(Collections.singletonList(
                area(NativeMapScene.BUILDING, new int[]{0}, 0, 0, 10, 0, 10, 5, 5, 5, 5, 10, 0, 10)), false);
        assertEquals(75, area(m), 0.5);
        for (int i = 2; i < m.areas.length; i += 3) assertEquals(GlTessellator.AREA_BUILDING, m.areas[i]);
    }

    @Test public void holesStayEmptyWhateverTheRingWinding() {
        // 20x20 outer, 6x6 hole: 400 - 36 = 364; the hole ring is wound the other way (MVT convention)
        double[] ring = {0, 0, 20, 0, 20, 20, 0, 20, 0, 0, 7, 7, 7, 13, 13, 13, 13, 7, 7, 7};
        GlTessellator.Mesh m = GlTessellator.build(Collections.singletonList(
                area(NativeMapScene.WATER, new int[]{0, 10}, ring)), false);
        assertEquals(364, area(m), 1.0);
    }

    @Test public void twoSeparatePolygonsInOneShapeAreBothFilled() {
        double[] two = {0, 0, 4, 0, 4, 4, 0, 4, 10, 10, 13, 10, 13, 13, 10, 13};
        GlTessellator.Mesh m = GlTessellator.build(Collections.singletonList(
                area(NativeMapScene.LAND, new int[]{0, 8}, two)), false);
        assertEquals(16 + 9, area(m), 0.5);
    }

    @Test public void eachRoadSegmentIsOneQuadAndEveryVertexAJoint() {
        GlTessellator.Mesh m = GlTessellator.build(Collections.singletonList(
                line(NativeMapScene.MAIN, false, 0, 0, 10, 0, 10, 10)), false);
        assertEquals(2, m.segmentCount());
        assertEquals(8 * 6, m.lines.length);
        assertEquals(3 * 3, m.joints.length);
        assertEquals(NativeMapScene.MAIN, m.lines[5]);
        assertTrue(m.bytes() > 0);
    }

    @Test public void bridgesGoInTheirOwnMeshAndAreasAreNotRepeated() {
        java.util.List<NativeMapScene.Shape> shapes = Arrays.asList(
                line(NativeMapScene.MOTORWAY, true, 0, 0, 10, 0),
                line(NativeMapScene.LOCAL, false, 0, 5, 10, 5),
                area(NativeMapScene.LAND, new int[]{0}, 0, 0, 10, 0, 10, 10, 0, 10));
        GlTessellator.Mesh ground = GlTessellator.build(shapes, false), bridges = GlTessellator.build(shapes, true);
        assertEquals(1, ground.segmentCount());
        assertEquals(1, bridges.segmentCount());
        assertEquals(0, bridges.areaCount());
        assertEquals(6, ground.areaCount());
    }

    @Test public void tunnelsUseTheirOwnSlot() {
        NativeMapScene.Shape t = new NativeMapScene.Shape(3, NativeMapScene.LOCAL, new double[]{O, O, O + U, O}, "", false, true, false, 0, new int[]{0});
        assertEquals(NativeMapScene.LOCAL + GlTessellator.LINE_TUNNEL, GlTessellator.lineSlot(t));
    }

    @Test public void coordinatesFitInShorts() {
        GlTessellator.Mesh m = GlTessellator.build(Arrays.asList(
                line(NativeMapScene.MAIN, false, 0, 0, 100000, 100000),
                area(NativeMapScene.LAND, new int[]{0}, 0, 0, 1, 0, 1, 1)), false);
        for (short v : m.lines) assertTrue(Math.abs(v) <= 30001);
    }
}
