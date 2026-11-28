package com.drivemate.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GPU map display on MapLibre Native (BSD-2-Clause). Display only: routing, guidance and search stay DriveMate's own.
 * Tiles come from DriveMate's local tile server, which serves installed map areas first, then the OpenFreeMap CDN,
 * then the UK archive, with the same disk caches as the classic renderer. The route, turn arrow, markers, speed
 * signs, incidents and the car are GeoJSON layers drawn by the GPU on top of the base map.
 */
final class MapLibreMapController implements DriveMap {
    private static final String TAG = "DriveMateGL";
    private final Activity activity;
    private final Listener listener;
    private final FrameLayout root;
    private final MapView mapView;
    private final TextView debugText;
    private final NativeTileSource tileSource;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final float density;
    private DriveTileServer server;
    private MapLibreMap map;
    private Style style;
    private boolean disposed, night, traffic, buildings3d = true, debug;
    private int styleGeneration;
    private Runnable onUserGesture;
    private RoadDisplayHealth.State reported;
    private long styleLoadedAt;

    // State kept so it can be re-applied after a style reload (night mode) or before the map is ready.
    private List<GeoMath.Point> route = Collections.emptyList();
    private RouteRenderGeometry routeGeometry = new RouteRenderGeometry(Collections.emptyList());
    private double progress, turn = Double.NaN, secondTurn = Double.NaN;
    private double lastRemainingProgress = -1;
    private NativeMapScene routeScene, browseScene;
    private GeoMath.Point vehicle, destination, entrance, parked;
    private double vehicleBearing = Double.NaN;
    private String destinationLabel = "";
    private List<RouteIntelligence.SpeedSection> speeds = Collections.emptyList();
    private boolean metric;
    private List<LiveTrafficIncidents.Event> incidents = Collections.emptyList();
    private long incidentsAt;
    private CameraPosition pendingCamera;
    private int[] pendingPreview;
    private int viewportTop, viewportBottom;
    private List<GeoMath.Point> junctionContext = Collections.emptyList();

    MapLibreMapController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.tileSource = new NativeTileSource(activity);
        MapLibre.getInstance(activity.getApplicationContext());
        // Every tile comes from DriveMate's own server on this phone (127.0.0.1), which handles offline itself.
        // Without this MapLibre pauses all requests whenever Android reports no internet, leaving a blank map.
        MapLibre.setConnected(true);
        root = new FrameLayout(activity);
        mapView = new MapView(activity);
        mapView.setContentDescription("DriveMate map");
        root.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        debugText = new TextView(activity);
        debugText.setTextSize(10);
        debugText.setTextColor(Color.WHITE);
        debugText.setBackgroundColor(0xcc000000);
        debugText.setPadding(12, 4, 12, 4);
        debugText.setVisibility(View.GONE);
        FrameLayout.LayoutParams dbg = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        root.addView(debugText, dbg);
        try {
            server = new DriveTileServer(activity.getApplicationContext());
            server.setTileProvider(tileSource::anyTile);
        } catch (IOException e) {
            Log.e(TAG, "Local tile server unavailable", e);
        }
        mapView.onCreate(null);
        mapView.getMapAsync(m -> {
            if (disposed) return;
            map = m;
            map.setMinZoomPreference(2);
            map.setMaxZoomPreference(20.5);
            map.getUiSettings().setLogoEnabled(false);
            map.getUiSettings().setCompassEnabled(false);
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && onUserGesture != null)
                    onUserGesture.run();
            });
            map.addOnCameraIdleListener(this::checkRoads);
            loadStyle();
        });
        root.postDelayed(healthCheck, 2000);
    }

    // ---------------------------------------------------------------- style and layers

    private static String readAsset(Activity a, String name) throws IOException {
        try (InputStream in = a.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void loadStyle() {
        if (disposed || map == null) return;
        final int generation = ++styleGeneration;
        style = null;
        try {
            String json = readAsset(activity, night ? "dm_gl_night.json" : "dm_gl_day.json")
                    .replace("__TILE_URL__", server != null ? server.tilesUrl() : "http://127.0.0.1:1/{z}/{x}/{y}.pbf");
            map.setStyle(new Style.Builder().fromJson(json), loaded -> {
                if (disposed || generation != styleGeneration) return;
                style = loaded;
                styleLoadedAt = SystemClock.elapsedRealtime();
                roadLayerIds = null;
                if (reported == RoadDisplayHealth.State.READY) { reported = null; root.removeCallbacks(healthCheck); root.postDelayed(healthCheck, 1000); }
                installOverlays(loaded);
                setBuildings3D(buildings3d);
                publishAll();
                if (pendingCamera != null) { map.moveCamera(CameraUpdateFactory.newCameraPosition(pendingCamera)); pendingCamera = null; }
                if (pendingPreview != null) { int[] p = pendingPreview; pendingPreview = null; previewRoute(route, p[0], p[1], p[2]); }
            });
        } catch (Exception e) {
            Log.e(TAG, "GPU map style failed", e);
        }
    }

    private static GeoJsonSource source(String id) {
        return new GeoJsonSource(id, FeatureCollection.fromFeatures(new Feature[0]),
                new GeoJsonOptions().withTolerance(0.05f).withMaxZoom(20));
    }

    private static Expression byZoom(float z10, float z16, float z20) {
        return Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10, z10), Expression.stop(16, z16), Expression.stop(20, z20));
    }

    private void installOverlays(Style s) {
        for (String id : new String[]{"dm-scene-areas", "dm-scene-lines", "dm-route", "dm-remaining", "dm-turn", "dm-turn-head", "dm-limits", "dm-incidents",
                "dm-destination", "dm-entrance", "dm-parked", "dm-vehicle"}) s.addSource(source(id));
        s.addImage("dm-vehicle", NativeMapController.makeVehicleArrow());
        s.addImage("dm-pin", pinBitmap());
        s.addImage("dm-head", arrowHeadBitmap());
        s.addImage("dm-limit", limitBitmap());
        String round = Property.LINE_CAP_ROUND, join = Property.LINE_JOIN_ROUND;
        // Lines and areas go under the map's own names (like Google Maps/Waze); signs, pin and car stay on top.
        String names = null;
        for (Layer l : s.getLayers()) if (l instanceof SymbolLayer && !l.getId().startsWith("dm-")) { names = l.getId(); break; }
        final String under = names;
        // DriveMate's own road data along the route (and nearby when browsing): drawn even with no map tiles.
        Expression kind = Expression.get("kind");
        below(s, under, new FillLayer("dm-scene-area", "dm-scene-areas").withProperties(PropertyFactory.fillColor(
                Expression.match(kind, Expression.color(night ? 0xff243c37 : 0xffdce9ce),
                        Expression.stop(NativeMapScene.WATER, Expression.color(night ? 0xff254d6b : 0xffa6d5e9)),
                        Expression.stop(NativeMapScene.BUILDING, Expression.color(night ? 0xff334654 : 0xffd7cbb9))))));
        below(s, under, new LineLayer("dm-scene-casing", "dm-scene-lines").withProperties(
                PropertyFactory.lineColor(night ? "#10202d" : "#b1b8b9"),
                PropertyFactory.lineWidth(Expression.match(kind, byZoom(3, 6, 12),
                        Expression.stop(NativeMapScene.MOTORWAY, byZoom(6, 11, 20)), Expression.stop(NativeMapScene.MAIN, byZoom(5, 9, 17)),
                        Expression.stop(NativeMapScene.PATH, byZoom(1, 2.5f, 5)))),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        below(s, under, new LineLayer("dm-scene-road", "dm-scene-lines").withProperties(
                PropertyFactory.lineColor(Expression.match(kind, Expression.color(night ? 0xff50616d : 0xffffffff),
                        Expression.stop(NativeMapScene.MOTORWAY, Expression.color(night ? 0xffa18b68 : 0xfff1cd9d)),
                        Expression.stop(NativeMapScene.MAIN, Expression.color(night ? 0xff667178 : 0xffffe7a7)))),
                PropertyFactory.lineWidth(Expression.match(kind, byZoom(2, 4.5f, 10),
                        Expression.stop(NativeMapScene.MOTORWAY, byZoom(4.5f, 9, 18)), Expression.stop(NativeMapScene.MAIN, byZoom(3.5f, 7, 15)),
                        Expression.stop(NativeMapScene.PATH, byZoom(.6f, 1.5f, 3)))),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        below(s, under, new LineLayer("dm-route-full", "dm-route").withProperties(
                PropertyFactory.lineColor(night ? "#516270" : "#aeb9bb"), PropertyFactory.lineWidth(byZoom(4, 7, 13)),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        below(s, under, new LineLayer("dm-route-casing", "dm-remaining").withProperties(
                PropertyFactory.lineColor("#143d64"), PropertyFactory.lineWidth(byZoom(6, 10, 17)),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        below(s, under, new LineLayer("dm-route-line", "dm-remaining").withProperties(
                PropertyFactory.lineColor("#16a6f4"), PropertyFactory.lineWidth(byZoom(4, 7, 12)),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        below(s, under, new LineLayer("dm-turn-casing", "dm-turn").withProperties(
                PropertyFactory.lineColor("#10202d"), PropertyFactory.lineWidth(byZoom(5, 10, 16)),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        below(s, under, new LineLayer("dm-turn-line", "dm-turn").withProperties(
                PropertyFactory.lineColor("#ffffff"), PropertyFactory.lineWidth(byZoom(3, 6.5f, 11)),
                PropertyFactory.lineCap(round), PropertyFactory.lineJoin(join)));
        s.addLayer(new SymbolLayer("dm-turn-head-layer", "dm-turn-head").withProperties(
                PropertyFactory.iconImage("dm-head"), PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconSize(byZoom(.35f, .7f, 1.2f)),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(false)));
        s.addLayer(new SymbolLayer("dm-limits-layer", "dm-limits").withProperties(
                PropertyFactory.iconImage("dm-limit"), PropertyFactory.iconSize(.5f),
                PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(new String[]{"Noto Sans Regular"}),
                PropertyFactory.textSize(11f), PropertyFactory.textColor("#192635"),
                // A sign never sits on the turn arrow, the pin or another sign: it gives way instead.
                PropertyFactory.iconAllowOverlap(false), PropertyFactory.textAllowOverlap(false),
                PropertyFactory.iconPadding(4f)));
        s.addLayer(new CircleLayer("dm-incidents-layer", "dm-incidents").withProperties(
                PropertyFactory.circleColor("#bd5426"), PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)));
        s.addLayer(new CircleLayer("dm-entrance-layer", "dm-entrance").withProperties(
                PropertyFactory.circleColor("#267a58"), PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)));
        s.addLayer(new CircleLayer("dm-parked-layer", "dm-parked").withProperties(
                PropertyFactory.circleColor("#426987"), PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)));
        s.addLayer(new SymbolLayer("dm-destination-layer", "dm-destination").withProperties(
                PropertyFactory.iconImage("dm-pin"), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(.6f), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(new String[]{"Noto Sans Regular"}),
                PropertyFactory.textSize(13f), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                PropertyFactory.textOffset(new Float[]{0f, -3.6f}), PropertyFactory.textColor(night ? "#ffffff" : "#10202d"),
                PropertyFactory.textHaloColor(night ? "#0f2236" : "#ffffff"), PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textOptional(true)));
        s.addLayer(new SymbolLayer("dm-vehicle-layer", "dm-vehicle").withProperties(
                PropertyFactory.iconImage("dm-vehicle"), PropertyFactory.iconSize(.48f),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)));
    }

    private static void below(Style s, String names, Layer layer) {
        if (names != null && s.getLayer(names) != null) s.addLayerBelow(layer, names); else s.addLayer(layer);
    }

    private static Bitmap pinBitmap() {
        Bitmap b = Bitmap.createBitmap(80, 110, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffd7262e);
        Path tail = new Path();
        tail.moveTo(40, 108); tail.lineTo(16, 62); tail.lineTo(64, 62); tail.close();
        c.drawPath(tail, p);
        c.drawCircle(40, 40, 36, p);
        p.setColor(Color.WHITE);
        c.drawCircle(40, 40, 22, p);
        p.setColor(0xffd7262e);
        c.drawCircle(40, 40, 9, p);
        return b;
    }

    private static Bitmap arrowHeadBitmap() {
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path head = new Path();   // points "up" (north) at rotation 0
        head.moveTo(32, 4); head.lineTo(60, 56); head.lineTo(4, 56); head.close();
        p.setColor(Color.WHITE); c.drawPath(head, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(0xff10202d);
        c.drawPath(head, p);
        return b;
    }

    private static Bitmap limitBitmap() {
        Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffb7232c); c.drawCircle(32, 32, 31, p);
        p.setColor(Color.WHITE); c.drawCircle(32, 32, 23, p);
        return b;
    }

    // ---------------------------------------------------------------- publishing overlay data

    private void set(String id, FeatureCollection data) {
        if (style == null) return;
        GeoJsonSource s = style.getSourceAs(id);
        if (s != null) s.setGeoJson(data);
    }

    private static FeatureCollection empty() { return FeatureCollection.fromFeatures(new Feature[0]); }

    private static Point pt(GeoMath.Point p) { return Point.fromLngLat(p.lon, p.lat); }

    private static FeatureCollection line(List<GeoMath.Point> points) {
        if (points == null || points.size() < 2) return empty();
        List<Point> out = new ArrayList<>(points.size());
        for (GeoMath.Point p : points) out.add(pt(p));
        return FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(out)));
    }

    private static FeatureCollection marker(GeoMath.Point p) {
        return p == null ? empty() : FeatureCollection.fromFeature(Feature.fromGeometry(pt(p)));
    }

    private void publishScene() {
        if (style == null) return;
        NativeMapScene sc = routeScene != null ? routeScene : browseScene;
        List<Feature> lines = new ArrayList<>(), areas = new ArrayList<>();
        if (sc != null) for (NativeMapScene.Shape f : sc.shapes) {
            if (f.kind == NativeMapScene.LABEL) continue;
            for (int j = 0; j < f.parts.length; j++) {
                int start = f.parts[j], end = j + 1 < f.parts.length ? f.parts[j + 1] : f.xy.length;
                List<Point> pts = new ArrayList<>();
                for (int i = start; i + 1 < end; i += 2) pts.add(Point.fromLngLat(NativeMapProjection.lon(f.xy[i]), NativeMapProjection.lat(f.xy[i + 1])));
                if (f.area) {
                    if (pts.size() < 3) continue;
                    if (!pts.get(0).equals(pts.get(pts.size() - 1))) pts.add(pts.get(0));
                    Feature feature = Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(pts)));
                    feature.addNumberProperty("kind", f.kind);
                    areas.add(feature);
                } else if (f.kind >= NativeMapScene.PATH && f.kind <= NativeMapScene.MOTORWAY && pts.size() >= 2) {
                    Feature feature = Feature.fromGeometry(LineString.fromLngLats(pts));
                    feature.addNumberProperty("kind", f.kind);
                    lines.add(feature);
                }
            }
        }
        // Main roads last so they draw over minor ones.
        lines.sort((x, y) -> Integer.compare(x.getNumberProperty("kind").intValue(), y.getNumberProperty("kind").intValue()));
        set("dm-scene-areas", FeatureCollection.fromFeatures(areas));
        set("dm-scene-lines", FeatureCollection.fromFeatures(lines));
    }

    private void publishAll() {
        publishScene();
        set("dm-route", line(route));
        lastRemainingProgress = -1;
        publishProgress();
        publishMarkers();
        publishVehicle();
        publishLimits();
        publishIncidents();
    }

    private void publishProgress() {
        if (style == null) return;
        // The remaining ribbon is rebuilt every 15 m, not on every GPS frame.
        if (lastRemainingProgress >= 0 && Math.abs(progress - lastRemainingProgress) < 15) { publishTurn(); return; }
        lastRemainingProgress = progress;
        set("dm-remaining", route.size() < 2 ? empty() : line(routeGeometry.remaining(progress)));
        publishTurn();
        publishLimits();   // signs the car has passed disappear
    }

    private void publishTurn() {
        if (style == null) return;
        double length = routeGeometry.length;
        if (route.size() < 2 || !Double.isFinite(turn) || turn <= progress || turn >= length) {
            set("dm-turn", empty()); set("dm-turn-head", empty()); return;
        }
        boolean merged = Double.isFinite(secondTurn) && secondTurn > turn
                && secondTurn - turn <= NativeMapView.TURN_ARROW_MERGE_METRES;
        double after = merged ? secondTurn - turn + NativeMapView.TURN_ARROW_AFTER_METRES : NativeMapView.TURN_ARROW_AFTER_METRES;
        double start = Math.max(progress, turn - NativeMapView.TURN_ARROW_BEFORE_METRES), end = Math.min(length, turn + after);
        if (end - start < 10) { set("dm-turn", empty()); set("dm-turn-head", empty()); return; }
        List<GeoMath.Point> band = new ArrayList<>();
        double step = Math.max(2, (end - start) / 40);
        for (double d = start; d < end; d += step) band.add(routeGeometry.at(d));
        band.add(routeGeometry.at(end));
        set("dm-turn", line(band));
        GeoMath.Point tip = band.get(band.size() - 1), before = routeGeometry.at(Math.max(start, end - 6));
        Feature head = Feature.fromGeometry(pt(tip));
        head.addNumberProperty("bearing", GeoMath.bearingDegrees(before, tip));
        set("dm-turn-head", FeatureCollection.fromFeature(head));
    }

    private void publishMarkers() {
        if (style == null) return;
        if (destination == null) set("dm-destination", empty());
        else {
            Feature f = Feature.fromGeometry(pt(destination));
            String label = destinationLabel.length() > 28 ? destinationLabel.substring(0, 27) + "…" : destinationLabel;
            f.addStringProperty("label", label);
            set("dm-destination", FeatureCollection.fromFeature(f));
        }
        boolean entranceOnPin = entrance != null && destination != null
                && GeoMath.distance(entrance, destination) < MapLayers.MARKER_OVERLAP_METRES;
        set("dm-entrance", entranceOnPin ? empty() : marker(entrance));
        set("dm-parked", marker(parked));
    }

    private void publishVehicle() {
        if (style == null) return;
        if (vehicle == null) { set("dm-vehicle", empty()); return; }
        Feature f = Feature.fromGeometry(pt(vehicle));
        f.addNumberProperty("bearing", Double.isFinite(vehicleBearing) ? vehicleBearing : 0);
        set("dm-vehicle", FeatureCollection.fromFeature(f));
    }

    private void publishLimits() {
        if (style == null) return;
        List<Feature> out = new ArrayList<>();
        int previous = -1; double lastShown = Double.NaN;
        double[] offsets = new double[speeds.size()]; int shownCount = 0;
        for (RouteIntelligence.SpeedSection s : speeds) {
            int prev = previous; previous = s.kmh;
            if (s.start >= routeGeometry.length) continue;
            if (!MapLayers.showLimitMarker(s.kmh, prev, s.start, lastShown, progress)) continue;
            if (MapLayers.nearManeuver(s.start, turn) || MapLayers.nearManeuver(s.start, secondTurn)) continue;
            lastShown = s.start;
            offsets[shownCount++] = s.start;
            Feature f = Feature.fromGeometry(pt(routeGeometry.at(s.start)));
            f.addStringProperty("label", String.valueOf(metric ? s.kmh : (int) Math.round(s.kmh / 1.609344)));
            out.add(f);
        }
        set("dm-limits", FeatureCollection.fromFeatures(out));
        shownLimits = java.util.Arrays.copyOf(offsets, shownCount);
    }

    private void publishIncidents() {
        if (style == null) return;
        List<Feature> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (now - incidentsAt >= 0 && now - incidentsAt <= LiveTrafficIncidents.MAX_AGE_MS)
            for (LiveTrafficIncidents.Event e : incidents)
                if (e.present(now) && !e.points.isEmpty()) out.add(Feature.fromGeometry(pt(e.points.get(0))));
        set("dm-incidents", FeatureCollection.fromFeatures(out));
    }

    // ---------------------------------------------------------------- camera

    private double toGl(double zoom) { return NavigationCamera.toMapLibreZoom(zoom, density); }

    private double fromGl(double zoom) { return zoom + 1 + Math.log(Math.max(.5, density)) / Math.log(2); }

    private void camera(GeoMath.Point center, double zoom, double bearing, double tilt) {
        if (center == null || !Double.isFinite(zoom)) return;
        double t = Math.max(0, Math.min(55, tilt));
        int h = mapView.getHeight();
        // Tilted driving view: the car sits low on the screen, like the classic renderer's anchor.
        double top = t > 0 && h > 0 ? h * (2 * NativeMapProjection.TILTED_ANCHOR - 1) : 0;
        CameraPosition position = new CameraPosition.Builder()
                .target(new LatLng(center.lat, center.lon))
                .zoom(toGl(zoom))
                .bearing(((bearing % 360) + 360) % 360)
                .tilt(t)
                .padding(0, top, 0, 0)
                .build();
        if (map == null || style == null) { pendingCamera = position; return; }
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position));
    }

    @Override public void setCamera(GeoMath.Point p, double z, double bearing) { camera(p, z, bearing, 0); }

    @Override public void setBrowseCamera(GeoMath.Point p, double z, double bearing, double pitch, double vehicleBearing) {
        camera(p, z, bearing, pitch);
    }

    @Override public void followFrame(SmoothFollowCamera.Frame f) {
        if (f == null) return;
        double zoom = junctionContext.isEmpty() ? f.zoom : JunctionFraming.fitZoom(f.zoom, f.center, f.vehicle,
                junctionContext, mapView.getWidth(), mapView.getHeight(), viewportTop, viewportBottom, 20);
        camera(f.center, zoom, f.bearing, f.tilt);
        vehicle = f.vehicle; vehicleBearing = f.travelBearing;
        publishVehicle();
    }

    @Override public void setFollowViewport(int top, int bottom, GeoMath.Point junction, List<GeoMath.Point> context) {
        viewportTop = top; viewportBottom = bottom;
        List<GeoMath.Point> c = context == null ? new ArrayList<>() : new ArrayList<>(context);
        if (junction != null) c.add(junction);
        junctionContext = c;
    }

    @Override public void clearJunctionFraming() { junctionContext = Collections.emptyList(); }

    @Override public void previewRoute(List<GeoMath.Point> path, int top, int bottom, int side) {
        if (path == null || path.size() < 2) return;
        if (map == null || style == null || mapView.getWidth() < 100) { pendingPreview = new int[]{top, bottom, side}; return; }
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (GeoMath.Point p : path) b.include(new LatLng(p.lat, p.lon));
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 0, 0, side, top, side, bottom));
        } catch (RuntimeException e) {
            GeoMath.Point mid = path.get(path.size() / 2);
            camera(mid, 14, 0, 0);
        }
    }

    @Override public double currentZoom() {
        if (map == null) return pendingCamera != null ? fromGl(pendingCamera.zoom) : 16;
        return fromGl(map.getCameraPosition().zoom);
    }

    // ---------------------------------------------------------------- route, markers, events

    @Override public void setSharedRoute(NativeMapScene scene, List<GeoMath.Point> path) { setSharedRoute(scene, path, ""); }

    @Override public void setSharedRoute(NativeMapScene scene, List<GeoMath.Point> path, String provider) {
        routeScene = scene;
        route = path == null ? Collections.emptyList() : new ArrayList<>(path);
        routeGeometry = new RouteRenderGeometry(route);
        progress = 0; turn = secondTurn = Double.NaN;
        publishAll();
    }

    @Override public void setBrowseGeometry(NativeMapScene scene) { browseScene = scene; if (routeScene == null) publishScene(); }
    @Override public void setConfirmedRouteProgress(double p) { progress = Math.max(0, p); publishProgress(); }
    @Override public void setManeuverOffsets(double a, double b) {
        boolean changed = !(a == turn || Double.isNaN(a) && Double.isNaN(turn)) || !(b == secondTurn || Double.isNaN(b) && Double.isNaN(secondTurn));
        turn = a; secondTurn = b; publishTurn();
        if (changed) publishLimits();
    }
    @Override public void setManeuverOffset(double a) { setManeuverOffsets(a, Double.NaN); }
    @Override public void setVehicle(GeoMath.Point p) { vehicle = p; vehicleBearing = Double.NaN; publishVehicle(); }
    @Override public void setUnsnappedVehicle(GeoMath.Point p, double bearing) { vehicle = p; vehicleBearing = bearing; publishVehicle(); }
    @Override public void setDestination(GeoMath.Point p) { destination = p; publishMarkers(); }
    @Override public void setDestinationLabel(String label) { destinationLabel = label == null ? "" : label.trim(); publishMarkers(); }
    @Override public void setEntrance(GeoMath.Point p) { entrance = p; publishMarkers(); }
    @Override public void setParkedCar(GeoMath.Point p) { parked = p; publishMarkers(); }

    @Override public void setRouteEvents(List<RouteIntelligence.SpeedSection> s, List<RouteIntelligence.TrafficSection> t,
                                         boolean metric, long fetchedAt) {
        speeds = s == null ? Collections.emptyList() : s; this.metric = metric; publishLimits();
    }

    @Override public void setLanes(List<RouteIntelligence.LaneSection> lanes) { }
    @Override public void setTraffic(boolean v) { traffic = v; if (!v) setLiveIncidents(null, 0); }
    @Override public boolean supportsLiveIncidents() { return traffic; }
    @Override public void setLiveIncidents(List<LiveTrafficIncidents.Event> rows, long at) {
        incidents = rows == null ? Collections.emptyList() : rows; incidentsAt = at; publishIncidents();
    }

    // ---------------------------------------------------------------- display settings

    @Override public View view() { return root; }
    @Override public String engineName() { return "MapLibre (GPU)"; }
    private double[] shownLimits = new double[0];
    @Override public double[] limitMarkerOffsets() { return shownLimits.clone(); }
    @Override public void setOnUserGesture(Runnable r) { onUserGesture = r; }
    @Override public void warmArea(GeoMath.Point p) { if (!disposed && p != null) worker.execute(() -> tileSource.warm(p)); }
    @Override public boolean nightMode() { return night; }
    @Override public void setVisible(boolean v) { root.setVisibility(v ? View.VISIBLE : View.INVISIBLE); }
    @Override public void setHighDetailEnabled(boolean enabled) { }
    @Override public void setNavigationDetail(boolean navigating) { }
    @Override public void updateCurrentRoad(GeoMath.Point p) { }
    @Override public void clearCurrentRoad() { }
    @Override public void setBuildingContext(String endpoint, String source) { }
    @Override public void prefetch(GeoMath.Point p) { }
    @Override public void prefetchRoute(List<GeoMath.Point> ahead) { }

    @Override public void setNightMode(boolean v) {
        if (v == night) return;
        night = v;
        loadStyle();
    }

    @Override public void setBuildings3D(boolean enabled) {
        buildings3d = enabled;
        if (style == null) return;
        for (String id : new String[]{"uk-buildings-3d", "omt-building-3d"}) {
            Layer l = style.getLayer(id);
            if (l != null) l.setProperties(PropertyFactory.visibility(enabled ? Property.VISIBLE : Property.NONE));
        }
    }

    @Override public void setDebug(boolean on) {
        debug = on;
        debugText.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) updateDebug();
    }

    private void updateDebug() {
        if (!debug) return;
        String z = map == null ? "–" : String.format(java.util.Locale.UK, "%.1f", map.getCameraPosition().zoom);
        debugText.setText("MapLibre GPU · zoom " + z + " · tiles fetched " + (server == null ? 0 : server.downloadedTiles())
                + " · errors " + (server == null ? 0 : server.errors()));
    }

    @Override public String geometryDescription() { return "MapLibre GPU map · DriveMate tiles"; }
    @Override public String routeAlignmentDiagnostic() { return "route drawn from DriveMate route geometry"; }
    @Override public String cacheStats() {
        String cam = map == null ? "no map" : String.format(java.util.Locale.UK, "z%.1f %.4f,%.4f",
                map.getCameraPosition().zoom, map.getCameraPosition().target.getLatitude(), map.getCameraPosition().target.getLongitude());
        return "MapLibre GPU · style " + (style != null ? "loaded" : "not loaded") + " · server " + (server == null ? "none" : server.tilesUrl())
                + " · fetched " + (server == null ? 0 : server.downloadedTiles()) + " · cached " + (server == null ? 0 : server.localHits())
                + " · errors " + (server == null ? 0 : server.errors()) + " · " + cam + " · roads at centre " + lastRoadCount;
    }

    // ---------------------------------------------------------------- health: report READY once roads are on screen

    private String[] roadLayerIds;
    private int lastRoadCount;

    /** Cheap, bounded check: a small patch in the middle of the map, never the whole screen. Querying every road on
     *  a zoomed-out screen copied tens of thousands of features on the UI thread and froze the app (2.3.0 report). */
    private int roadsOnScreen() {
        if (map == null || style == null || mapView.getWidth() == 0) return 0;
        if (roadLayerIds == null) {
            List<String> ids = new ArrayList<>();
            for (Layer l : style.getLayers()) {
                String id = l.getId();
                if (l instanceof LineLayer && (id.equals("dm-scene-road") || id.startsWith("omt-road") || id.startsWith("uk-road")
                        || id.equals("uk-trunk") || id.equals("uk-roads") || id.equals("world-roads"))) ids.add(id);
            }
            roadLayerIds = ids.toArray(new String[0]);
        }
        float w = mapView.getWidth(), h = mapView.getHeight(), half = Math.min(w, h) * MapLayers.ROAD_PROBE_FRACTION / 2;
        try {
            lastRoadCount = map.queryRenderedFeatures(new RectF(w / 2 - half, h / 2 - half, w / 2 + half, h / 2 + half), roadLayerIds).size();
        } catch (RuntimeException e) {
            lastRoadCount = 0;
        }
        return lastRoadCount;
    }

    @Override public boolean hasRenderedRoads() { return reported == RoadDisplayHealth.State.READY || roadsOnScreen() > 0; }

    /** Runs until roads first appear (or a style reload), then stops: no work on every camera stop. */
    private void checkRoads() {
        if (disposed || style == null || reported == RoadDisplayHealth.State.READY) return;
        int roads = roadsOnScreen();
        RoadDisplayHealth.State state = roads > 0 ? RoadDisplayHealth.State.READY
                : SystemClock.elapsedRealtime() - styleLoadedAt > 15000 ? RoadDisplayHealth.State.FAILED : null;
        if (state != null && state != reported) { reported = state; listener.status(state, roads); }
        updateDebug();
    }

    private final Runnable healthCheck = new Runnable() {
        @Override public void run() {
            if (disposed || reported == RoadDisplayHealth.State.READY) return;
            checkRoads();
            if (reported != RoadDisplayHealth.State.READY) root.postDelayed(this, 2000);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override public void onStart() { mapView.onStart(); }
    @Override public void onResume() { mapView.onResume(); }
    @Override public void onPause() { mapView.onPause(); }
    @Override public void onStop() { mapView.onStop(); }
    @Override public void onLowMemory() { mapView.onLowMemory(); }
    @Override public void onSaveInstanceState(Bundle state) { if (state != null) mapView.onSaveInstanceState(state); }

    @Override public void onDestroy() {
        disposed = true;
        root.removeCallbacks(healthCheck);
        mapView.onDestroy();
        worker.execute(() -> {
            try { if (server != null) server.close(); } catch (Exception ignored) { }
            tileSource.close();
        });
        worker.shutdown();
    }
}
