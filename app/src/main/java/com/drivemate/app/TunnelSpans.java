package com.drivemate.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds where on the active route the map data says there is a tunnel, as
 * along-route ranges in metres, for {@link RouteDeadReckoner}.
 *
 * Native scene shapes store coordinates as the unit-square Web Mercator world
 * (x, y both 0..1). Each tunnel vertex is converted to latitude/longitude and
 * kept only if it lies within {@link #MAX_OFF_ROUTE_METRES} of the route, so a
 * parallel tunnel that the route does not use is ignored. If the coordinate
 * assumption were wrong no vertex would land near the route and the result
 * would be empty: the safe failure is "no tunnel known", never a wrong span.
 */
final class TunnelSpans {
    static final double MAX_OFF_ROUTE_METRES = 25.0;
    static final double MIN_SPAN_METRES = 20.0;
    private TunnelSpans() { }

    static List<double[]> fromShapes(List<GeoMath.Point> route, List<NativeMapScene.Shape> shapes) {
        List<double[]> spans = new ArrayList<>();
        if (route == null || route.size() < 2 || shapes == null) return spans;
        for (NativeMapScene.Shape shape : shapes) {
            if (!shape.tunnel || shape.area || shape.kind == NativeMapScene.WATER
                    || shape.kind == NativeMapScene.BUILDING || shape.kind == NativeMapScene.LAND) continue;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i + 1 < shape.xy.length; i += 2) {
                GeoMath.Point p = worldToPoint(shape.xy[i], shape.xy[i + 1]);
                GeoMath.Progress progress = GeoMath.project(route, p);
                if (progress.offRouteMetres > MAX_OFF_ROUTE_METRES) continue;
                min = Math.min(min, progress.alongMetres);
                max = Math.max(max, progress.alongMetres);
            }
            if (max - min >= MIN_SPAN_METRES) spans.add(new double[] {min, max});
        }
        return merge(spans);
    }

    static GeoMath.Point worldToPoint(double x, double y) {
        double lon = x * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))));
        return new GeoMath.Point(lat, lon);
    }

    static List<double[]> merge(List<double[]> spans) {
        spans.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] s : spans) {
            if (!out.isEmpty() && s[0] <= out.get(out.size() - 1)[1] + 5) {
                double[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], s[1]);
            } else {
                out.add(new double[] {s[0], s[1]});
            }
        }
        return out;
    }
}
