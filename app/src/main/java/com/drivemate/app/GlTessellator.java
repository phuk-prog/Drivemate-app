package com.drivemate.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one tile's shapes into GPU-ready triangles, once, off the UI thread. Pure Java (no Android), unit tested.
 *
 * Coordinates are stored as 16-bit integers relative to the group's origin (Web Mercator × {@link #scale}), so a whole
 * tile fits a short and the GPU never sees large numbers. The view matrix is built per group in double precision.
 *
 * Areas: ear clipping with holes bridged to the outer ring (exact for concave shapes, no stencil tricks).
 * Lines: every segment becomes a quad whose corners the vertex shader pushes out by the road's on-screen width, so a
 * zoom or tilt change never needs re-tessellation; round joins and caps come from a point at every vertex.
 */
final class GlTessellator {
    /** Area colour slots (uniform array index). */
    static final int AREA_LAND = 0, AREA_WATER = 1, AREA_BUILDING = 2;
    /** Line style slots: kind (PATH..MOTORWAY, WATER, RAIL) + 8 for tunnels. */
    static final int LINE_TUNNEL = 8;
    static final int MAX_POLYGON_VERTICES = 6000;

    static final class Mesh {
        double originX, originY, scale;
        /** Bounds in Web Mercator (x west/east, y south/north as stored by shapes), for culling. */
        double w, s, e, n;
        /** x, y, slot per vertex (3 shorts). */
        short[] areas = new short[0];
        /** Per vertex: x, y, otherX, otherY, side(±1), slot (6 shorts); 4 vertices per segment. */
        short[] lines = new short[0];
        /** Two triangles per segment, indices into lines/6. */
        int[] lineIndex = new int[0];
        /** x, y, slot per joint (3 shorts): round joins and caps. */
        short[] joints = new short[0];
        /** Raised buildings: x, y, height (decimetres), shade (0-100) per vertex; plain triangles. */
        short[] buildings3d = new short[0];
        boolean bridges;
        int areaCount() { return areas.length / 3; }
        int segmentCount() { return lineIndex.length / 6; }
        int bytes() { return areas.length * 2 + lines.length * 2 + lineIndex.length * 4 + joints.length * 2 + buildings3d.length * 2; }
    }

    private static final class ShortList {
        short[] a = new short[1024]; int n;
        void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = (short) v; }
        short[] done() { return java.util.Arrays.copyOf(a, n); }
    }

    private static final class IntList {
        int[] a = new int[1024]; int n;
        void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] done() { return java.util.Arrays.copyOf(a, n); }
    }

    private GlTessellator() { }

    static int lineSlot(NativeMapScene.Shape s) {
        int slot = s.kind == NativeMapScene.WATER ? 1 : s.kind == NativeMapScene.RAIL ? 2 : s.kind; // PATH=3 .. MOTORWAY=6
        return s.tunnel && s.kind >= NativeMapScene.PATH && s.kind <= NativeMapScene.MOTORWAY ? slot + LINE_TUNNEL : slot;
    }

    static int areaSlot(NativeMapScene.Shape s) {
        return s.kind == NativeMapScene.WATER ? AREA_WATER : s.kind == NativeMapScene.BUILDING ? AREA_BUILDING : AREA_LAND;
    }

    /** bridges: true to build only bridge lines (drawn above everything), false for everything else. */
    static Mesh build(List<NativeMapScene.Shape> shapes, boolean bridges) {
        Mesh m = new Mesh();
        m.bridges = bridges;
        double w = Double.POSITIVE_INFINITY, s = w, e = Double.NEGATIVE_INFINITY, n = e;
        for (NativeMapScene.Shape f : shapes) { w = Math.min(w, f.w); s = Math.min(s, f.s); e = Math.max(e, f.e); n = Math.max(n, f.n); }
        if (!(w <= e)) return m;
        m.originX = (w + e) / 2; m.originY = (s + n) / 2;
        m.w = w; m.s = s; m.e = e; m.n = n;
        double span = Math.max(1e-12, Math.max(e - w, n - s));
        m.scale = 60000.0 / span;   // ±30000: inside a short with margin
        ShortList areas = new ShortList(), lines = new ShortList(), joints = new ShortList();
        IntList index = new IntList();
        if (!bridges)
            for (int slot = AREA_LAND; slot <= AREA_BUILDING; slot++)
                for (NativeMapScene.Shape f : shapes)
                    if (f.area && f.kind != NativeMapScene.LABEL && areaSlot(f) == slot) areas(f, m, areas);
        java.util.List<NativeMapScene.Shape> ordered = new java.util.ArrayList<>(shapes);
        ordered.sort((x, y) -> Integer.compare(lineOrder(x.kind), lineOrder(y.kind)));
        for (NativeMapScene.Shape f : ordered) {
            if (f.kind == NativeMapScene.LABEL) continue;
            if (f.area) continue;
            else {
                if (f.bridge != bridges) continue;
                if (f.kind < NativeMapScene.WATER) continue;
                if (f.kind != NativeMapScene.WATER && f.kind != NativeMapScene.RAIL
                        && (f.kind < NativeMapScene.PATH || f.kind > NativeMapScene.MOTORWAY)) continue;
                lines(f, m, lines, index, joints);
            }
        }
        if (!bridges) {
            ShortList b3 = new ShortList();
            for (NativeMapScene.Shape f : shapes)
                if (f.area && f.kind == NativeMapScene.BUILDING && f.height > 0) building3d(f, m, b3);
            m.buildings3d = b3.done();
        }
        m.areas = areas.done(); m.lines = lines.done(); m.lineIndex = index.done(); m.joints = joints.done();
        return m;
    }

    /** Water and rail under roads; then paths, local, main, motorway. */
    static int lineOrder(int kind) {
        return kind == NativeMapScene.WATER ? 0 : kind == NativeMapScene.RAIL ? 1 : kind + 2;
    }

    private static int qx(Mesh m, double x) { return (int) Math.round((x - m.originX) * m.scale); }
    private static int qy(Mesh m, double y) { return (int) Math.round((y - m.originY) * m.scale); }

    /** Walls (shaded by which way they face, light from the north-west) and a roof at the building's height. */
    private static void building3d(NativeMapScene.Shape f, Mesh m, ShortList out) {
        int h = (int) Math.max(1, Math.min(3000, Math.round(f.height * 10)));
        int start = f.parts[0], end = f.parts.length > 1 ? f.parts[1] : f.xy.length;
        int n = (end - start) / 2;
        if (n < 3) return;
        for (int k = 0; k < n; k++) {
            int i = start + 2 * k, j = start + 2 * ((k + 1) % n);
            int ax = qx(m, f.xy[i]), ay = qy(m, f.xy[i + 1]), bx = qx(m, f.xy[j]), by = qy(m, f.xy[j + 1]);
            if (ax == bx && ay == by) continue;
            double nx = by - ay, ny = -(bx - ax), len = Math.hypot(nx, ny);
            int shade = (int) Math.round(72 + 22 * ((-nx - ny) / (len * Math.sqrt(2))));
            int[][] q = {{ax, ay, 0}, {bx, by, 0}, {bx, by, h}, {ax, ay, 0}, {bx, by, h}, {ax, ay, h}};
            for (int[] v : q) { out.add(v[0]); out.add(v[1]); out.add(v[2]); out.add(shade); }
        }
        ShortList roof = new ShortList();
        List<int[]> ring = new ArrayList<>();
        ring.add(new int[]{start, end});
        triangulate(f.xy, ring, m, 0, roof);
        for (int i = 0; i + 2 < roof.n; i += 3) { out.add(roof.a[i]); out.add(roof.a[i + 1]); out.add(h); out.add(100); }
    }

    /** Douglas-Peucker tolerance in mesh units (60000 per group span): ~0.05 px at street zoom, removes
     *  the many near-collinear points of curved roads without any visible change. */
    static final double SIMPLIFY_UNITS = 6;

    private static void lines(NativeMapScene.Shape f, Mesh m, ShortList out, IntList index, ShortList joints) {
        int slot = lineSlot(f);
        for (int j = 0; j < f.parts.length; j++) {
            int start = f.parts[j], end = j + 1 < f.parts.length ? f.parts[j + 1] : f.xy.length;
            int n = (end - start) / 2;
            if (n < 2) continue;
            int[] xs = new int[n], ys = new int[n];
            for (int k = 0; k < n; k++) { xs[k] = qx(m, f.xy[start + 2 * k]); ys[k] = qy(m, f.xy[start + 2 * k + 1]); }
            boolean[] keep = simplify(xs, ys, SIMPLIFY_UNITS);
            int px = 0, py = 0; boolean have = false;
            for (int k = 0; k < n; k++) {
                if (!keep[k]) continue;
                int x = xs[k], y = ys[k];
                joints.add(x); joints.add(y); joints.add(slot);
                if (have && (x != px || y != py)) {
                    int base = out.n / 6;
                    vertex(out, px, py, x, y, 1, slot); vertex(out, px, py, x, y, -1, slot);
                    vertex(out, x, y, px, py, 1, slot); vertex(out, x, y, px, py, -1, slot);
                    // a+P, a-P, b-P(=b side +1 seen from b), b+P: two triangles covering the quad
                    index.add(base); index.add(base + 1); index.add(base + 2);
                    index.add(base); index.add(base + 2); index.add(base + 3);
                }
                px = x; py = y; have = true;
            }
        }
    }

    /** Douglas-Peucker: which points to keep (first and last always kept). Iterative, no recursion limits. */
    static boolean[] simplify(int[] xs, int[] ys, double tolerance) {
        int n = xs.length;
        boolean[] keep = new boolean[n];
        if (n == 0) return keep;
        keep[0] = true; keep[n - 1] = true;
        if (n < 3) return keep;
        int[] stack = new int[2 * n]; int top = 0;
        stack[top++] = 0; stack[top++] = n - 1;
        double tol2 = tolerance * tolerance;
        while (top > 0) {
            int b = stack[--top], a = stack[--top];
            double dx = xs[b] - xs[a], dy = ys[b] - ys[a], len2 = dx * dx + dy * dy;
            int best = -1; double bestD = tol2;
            for (int i = a + 1; i < b; i++) {
                double ex = xs[i] - xs[a], ey = ys[i] - ys[a], d;
                if (len2 == 0) d = ex * ex + ey * ey;
                else { double c = ex * dy - ey * dx; d = c * c / len2; }
                if (d > bestD) { bestD = d; best = i; }
            }
            if (best >= 0) { keep[best] = true; stack[top++] = a; stack[top++] = best; stack[top++] = best; stack[top++] = b; }
        }
        return keep;
    }

    private static void vertex(ShortList out, int x, int y, int ox, int oy, int side, int slot) {
        out.add(x); out.add(y); out.add(ox); out.add(oy); out.add(side); out.add(slot);
    }

    // ---------------------------------------------------------------- polygons

    private static double signedArea(double[] xy, int start, int end) {
        double a = 0;
        for (int i = start, j = end - 2; i < end; j = i, i += 2) a += (xy[j] - xy[i]) * (xy[i + 1] + xy[j + 1]);
        return a;
    }

    private static void areas(NativeMapScene.Shape f, Mesh m, ShortList out) {
        int slot = areaSlot(f);
        // Group rings into polygons: a ring with the first ring's winding starts a polygon, the others are its holes.
        List<int[]> rings = new ArrayList<>();
        for (int j = 0; j < f.parts.length; j++) {
            int start = f.parts[j], end = j + 1 < f.parts.length ? f.parts[j + 1] : f.xy.length;
            if (end - start >= 6) rings.add(new int[]{start, end});
        }
        if (rings.isEmpty()) return;
        double firstSign = Math.signum(signedArea(f.xy, rings.get(0)[0], rings.get(0)[1]));
        List<int[]> polygon = new ArrayList<>();
        for (int[] r : rings) {
            double sign = Math.signum(signedArea(f.xy, r[0], r[1]));
            if (sign == firstSign && !polygon.isEmpty()) { triangulate(f.xy, polygon, m, slot, out); polygon = new ArrayList<>(); }
            polygon.add(r);
        }
        if (!polygon.isEmpty()) triangulate(f.xy, polygon, m, slot, out);
    }

    /** Ring as a list of distinct quantized points (closing duplicate removed). */
    private static List<long[]> ring(double[] xy, int[] r, Mesh m) {
        List<long[]> pts = new ArrayList<>();
        for (int i = r[0]; i + 1 < r[1]; i += 2) {
            long x = qx(m, xy[i]), y = qy(m, xy[i + 1]);
            if (!pts.isEmpty()) { long[] last = pts.get(pts.size() - 1); if (last[0] == x && last[1] == y) continue; }
            pts.add(new long[]{x, y});
        }
        if (pts.size() > 1) { long[] a = pts.get(0), b = pts.get(pts.size() - 1); if (a[0] == b[0] && a[1] == b[1]) pts.remove(pts.size() - 1); }
        return pts;
    }

    private static long area2(List<long[]> p) {
        long a = 0;
        for (int i = 0, j = p.size() - 1; i < p.size(); j = i++) a += (p.get(j)[0] - p.get(i)[0]) * (p.get(i)[1] + p.get(j)[1]);
        return a;
    }

    private static List<long[]> decimate(List<long[]> p, int max) {
        if (p.size() <= max) return p;
        List<long[]> out = new ArrayList<>(max);
        double step = p.size() / (double) max;
        for (int i = 0; i < max; i++) out.add(p.get((int) (i * step)));
        return out;
    }

    static void triangulate(double[] xy, List<int[]> rings, Mesh m, int slot, ShortList out) {
        List<long[]> outer = ring(xy, rings.get(0), m);
        if (outer.size() < 3) return;
        // Work counter-clockwise (positive area in this sign convention) for the outer ring, clockwise for holes.
        if (area2(outer) < 0) java.util.Collections.reverse(outer);
        int budget = MAX_POLYGON_VERTICES;
        List<List<long[]>> holes = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) {
            List<long[]> h = ring(xy, rings.get(i), m);
            if (h.size() < 3) continue;
            if (area2(h) > 0) java.util.Collections.reverse(h);
            holes.add(h);
        }
        int total = outer.size();
        for (List<long[]> h : holes) total += h.size() + 2;
        if (total > budget) {
            // Very large area (coastline, big lake): thin it out rather than skip it.
            double keep = budget / (double) total;
            outer = decimate(outer, Math.max(3, (int) (outer.size() * keep)));
            for (int i = 0; i < holes.size(); i++) holes.set(i, decimate(holes.get(i), Math.max(3, (int) (holes.get(i).size() * keep))));
        }
        List<long[]> poly = bridgeHoles(outer, holes);
        earClip(poly, slot, out);
    }

    /** Joins each hole to the outer ring with a zero-width bridge (rightmost hole vertex to a visible outer vertex). */
    static List<long[]> bridgeHoles(List<long[]> outer, List<List<long[]>> holes) {
        List<long[]> poly = new ArrayList<>(outer);
        holes.sort((a, b) -> Long.compare(maxX(b), maxX(a)));
        for (List<long[]> hole : holes) {
            int hi = 0;
            for (int i = 1; i < hole.size(); i++) if (hole.get(i)[0] > hole.get(hi)[0]) hi = i;
            long[] h = hole.get(hi);
            int best = -1; double bestDist = Double.POSITIVE_INFINITY;
            for (int i = 0; i < poly.size(); i++) {
                long[] p = poly.get(i);
                if (p[0] < h[0]) continue;
                double d = (double) (p[0] - h[0]) * (p[0] - h[0]) + (double) (p[1] - h[1]) * (p[1] - h[1]);
                if (d < bestDist && visible(poly, h, p, i)) { bestDist = d; best = i; }
            }
            if (best < 0) {   // no vertex to the right is visible: take the nearest visible one anywhere
                for (int i = 0; i < poly.size(); i++) {
                    long[] p = poly.get(i);
                    double d = (double) (p[0] - h[0]) * (p[0] - h[0]) + (double) (p[1] - h[1]) * (p[1] - h[1]);
                    if (d < bestDist && visible(poly, h, p, i)) { bestDist = d; best = i; }
                }
            }
            if (best < 0) continue;   // degenerate hole: leave it filled
            List<long[]> merged = new ArrayList<>(poly.size() + hole.size() + 2);
            for (int i = 0; i <= best; i++) merged.add(poly.get(i));
            for (int k = 0; k <= hole.size(); k++) merged.add(hole.get((hi + k) % hole.size()));
            merged.add(poly.get(best));
            for (int i = best + 1; i < poly.size(); i++) merged.add(poly.get(i));
            poly = merged;
        }
        return poly;
    }

    private static long maxX(List<long[]> ring) { long x = Long.MIN_VALUE; for (long[] p : ring) x = Math.max(x, p[0]); return x; }

    /** True when segment h→p crosses no edge of the polygon (edges touching p excluded). */
    private static boolean visible(List<long[]> poly, long[] h, long[] p, int pi) {
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (i == pi || j == pi) continue;
            long[] a = poly.get(i), b = poly.get(j);
            if (same(a, p) || same(b, p)) continue;
            if (properCross(h, p, a, b)) return false;
        }
        return true;
    }

    private static boolean same(long[] a, long[] b) { return a[0] == b[0] && a[1] == b[1]; }

    private static long cross(long[] o, long[] a, long[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }

    private static boolean properCross(long[] p1, long[] p2, long[] q1, long[] q2) {
        long d1 = cross(q1, q2, p1), d2 = cross(q1, q2, p2), d3 = cross(p1, p2, q1), d4 = cross(p1, p2, q2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static boolean inTriangle(long[] a, long[] b, long[] c, long[] p) {
        return cross(a, b, p) >= 0 && cross(b, c, p) >= 0 && cross(c, a, p) >= 0;
    }

    /** Ear clipping of a counter-clockwise simple polygon (bridges allowed). Emits triangles into out. */
    static void earClip(List<long[]> poly, int slot, ShortList out) {
        int n = poly.size();
        if (n < 3) return;
        int[] prev = new int[n], next = new int[n];
        for (int i = 0; i < n; i++) { prev[i] = (i + n - 1) % n; next[i] = (i + 1) % n; }
        int remaining = n, i = 0, sinceEar = 0;
        while (remaining > 3 && sinceEar < remaining * 2) {
            int a = prev[i], c = next[i];
            long[] pa = poly.get(a), pb = poly.get(i), pc = poly.get(c);
            boolean ear = cross(pa, pb, pc) > 0;
            if (ear) {
                for (int k = next[c]; k != a; k = next[k]) {
                    long[] pk = poly.get(k);
                    if (same(pk, pa) || same(pk, pb) || same(pk, pc)) continue;
                    if (inTriangle(pa, pb, pc, pk)) { ear = false; break; }
                }
            } else if (cross(pa, pb, pc) == 0) {
                // collinear or zero-width (bridge) vertex: drop it without a triangle
                next[a] = c; prev[c] = a; remaining--; i = c; sinceEar = 0; continue;
            }
            if (ear) {
                tri(out, pa, pb, pc, slot);
                next[a] = c; prev[c] = a; remaining--; i = c; sinceEar = 0;
            } else { i = next[i]; sinceEar++; }
        }
        if (remaining == 3) { int a = prev[i], c = next[i]; if (cross(poly.get(a), poly.get(i), poly.get(c)) != 0) tri(out, poly.get(a), poly.get(i), poly.get(c), slot); }
        else if (remaining > 3) {
            // Self-touching leftovers (rare, bad data): fan them so the area is still drawn.
            int a = i;
            for (int k = next[a]; next[k] != a; k = next[k]) tri(out, poly.get(a), poly.get(k), poly.get(next[k]), slot);
        }
    }

    private static void tri(ShortList out, long[] a, long[] b, long[] c, int slot) {
        out.add((int) a[0]); out.add((int) a[1]); out.add(slot);
        out.add((int) b[0]); out.add((int) b[1]); out.add(slot);
        out.add((int) c[0]); out.add((int) c[1]); out.add(slot);
    }
}
