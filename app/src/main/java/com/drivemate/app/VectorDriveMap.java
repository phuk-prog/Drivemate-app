package com.drivemate.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.os.Bundle;
import android.view.View;

import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GPU vector display only. GPS routing and manoeuvre generation remain in
 * TomTomApi and the existing MainActivity, not in DriveMate Map data.
 *
 * MapLibre requests UK Shortbread vector tiles from a bounded app-private
 * localhost tile cache. The world overview is read from a local APK asset.
 * The legacy TomTom/osmdroid display is kept as a fallback if this renderer
 * cannot fetch UK detail on the user's phone.
 */
public final class VectorDriveMap {
    private final Activity activity;
    private final MapView view;
    private final DriveTileServer tiles;
    private MapLibreMap map;
    private GeoJsonSource routeSource;
    private GeoJsonSource junctionSource;
    private GeoJsonSource vehicleSource;
    private GeoJsonSource destinationSource;
    private GeoJsonSource entranceSource;
    private GeoJsonSource parkedSource;
    private boolean styleReady;
    private boolean trafficEnabled;
    private boolean nightMode;
    private boolean highDetailEnabled = true;
    private String tomtomKey;
    private boolean disposed;
    private GeoMath.Point pendingGps;
    private GeoMath.Point pendingDest;
    private GeoMath.Point pendingEntrance;
    private GeoMath.Point pendingParked;
    private List<GeoMath.Point> pendingRoute;
    private List<GeoMath.Point> pendingJunctionRoute;
    private GeoMath.Point pendingCamera;
    private GeoMath.Point lastRenderedCamera;
    private GeoMath.Point lastRenderedVehicle;
    private double lastRenderedZoom = Double.NaN;
    private double lastRenderedBearing = Double.NaN;
    private double lastRenderedTilt = Double.NaN;
    private double lastRenderedVehicleAngle = Double.NaN;
    private Runnable onUserGesture;
    private double pendingZoom = 14.5, pendingBearing;
    private static final String LOG_TAG = "DriveMateVector";
    private static final long MONITOR_INTERVAL_MS = 1800L;
    public interface RoadStatusListener {
        void onRoadStatus(RoadDisplayHealth.State status, int visibleRoadFeatures);
    }
    private final RoadDisplayHealth roadHealth = new RoadDisplayHealth();
    private final RoadStatusListener roadListener;
    private RoadDisplayHealth.State previouslyReported = RoadDisplayHealth.State.NOT_APPLICABLE;
    private final Runnable roadMonitor = new Runnable() {
        @Override public void run() {
            if (disposed) return;
            if (view.getVisibility() == View.VISIBLE) inspectRoadDisplay();
            view.postDelayed(this, MONITOR_INTERVAL_MS);
        }
    };

    public VectorDriveMap(Activity activity, Bundle state, DriveTileServer tiles,
                          String key, boolean showTraffic, RoadStatusListener roadListener) {
        this.activity = activity;
        this.tiles = tiles;
        this.tomtomKey = key == null ? "" : key;
        this.trafficEnabled = showTraffic;
        this.roadListener = roadListener;
        MapLibre.getInstance(activity.getApplicationContext());
        view = new MapView(activity);
        view.onCreate(state);
        view.getMapAsync(mapLibreMap -> {
            if (disposed) return;
            map = mapLibreMap;
            map.setMinZoomPreference(2.0);
            map.setMaxZoomPreference(19.0);
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                        && onUserGesture != null) onUserGesture.run();
            });
            loadStyle();
        });
        // A successfully downloaded tile can be empty or fail to render.
        // Evaluate *rendered road features*, never network callbacks.
        view.postDelayed(roadMonitor, MONITOR_INTERVAL_MS);
    }

    public View view() { return view; }
    public void setOnUserGesture(Runnable callback) { onUserGesture = callback; }
    public boolean ready() { return styleReady && map != null; }
    public boolean nightMode() { return nightMode; }

    public void setNightMode(boolean enabled) {
        if (nightMode == enabled) return;
        nightMode = enabled;
        if (map != null) loadStyle();
    }
    /** Current GPU map-camera state for seamless vector/legacy switching. */
    public double currentZoom() {
        return ready() ? map.getCameraPosition().zoom : pendingZoom;
    }
    public double currentBearing() {
        return ready() ? map.getCameraPosition().bearing : pendingBearing;
    }

    public void setVisible(boolean visible) {
        boolean wasVisible = view.getVisibility() == View.VISIBLE;
        view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (visible && !wasVisible) {
            roadHealth.reset();
            previouslyReported = RoadDisplayHealth.State.NOT_APPLICABLE;
        }
    }

    public boolean hasRenderedRoads() {
        return roadHealth.lastState() == RoadDisplayHealth.State.READY;
    }

    /** Checks the actual MapLibre street layers in the on-screen viewport. */
    private void inspectRoadDisplay() {
        if (disposed) return;
        // When the user pans or pinches manually, examine the *actual*
        // onscreen camera, not the last GPS-follow position.
        GeoMath.Point target = pendingCamera;
        double zoom = pendingZoom;
        if (ready() && map.getCameraPosition().target != null) {
            LatLng at = map.getCameraPosition().target;
            target = new GeoMath.Point(at.getLatitude(), at.getLongitude());
            zoom = map.getCameraPosition().zoom;
        }
        if (target == null) return;
        int visible = 0;
        if (ready() && view.getWidth() > 80 && view.getHeight() > 80) {
            try {
                // Exclude vehicle, route, labels, buildings and background.
                // Only actual streets painted by the Shortbread source count.
                RectF middle = new RectF(view.getWidth() * 0.08f,
                        view.getHeight() * 0.08f, view.getWidth() * 0.92f,
                        view.getHeight() * 0.92f);
                visible = map.queryRenderedFeatures(middle, "uk-roads").size();
            } catch (RuntimeException error) {
                Log.w(LOG_TAG, "MapLibre rendered road query failed", error);
            }
        }
        RoadDisplayHealth.State outcome = roadHealth.check(
                SystemClock.elapsedRealtime(), target.lat, target.lon, zoom, ready(), visible);
        if (outcome != previouslyReported) {
            previouslyReported = outcome;
            if (outcome == RoadDisplayHealth.State.READY
                    || outcome == RoadDisplayHealth.State.FAILED) {
                Log.i(LOG_TAG, "UK roads display state=" + outcome
                        + ", rendered streets=" + visible + ", zoom=" + zoom);
                if (roadListener != null) roadListener.onRoadStatus(outcome, visible);
            }
        }
    }

    private static String readAsset(Activity activity, String name) throws Exception {
        try (InputStream in = activity.getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    public void setTomTomKey(String key) {
        String newKey = key == null ? "" : key;
        if (newKey.equals(tomtomKey)) return;
        tomtomKey = newKey;
        if (map != null) loadStyle();
    }

    private void loadStyle() {
        if (disposed || map == null) return;
        // Re-validate rendered UK roads after every theme reload. A previous
        // READY report must not prove the new style has rendered anything.
        roadHealth.reset();
        previouslyReported = RoadDisplayHealth.State.NOT_APPLICABLE;
        try {
            String json = readAsset(activity, nightMode
                    ? "drivemate_vector_style_night.json"
                    : "drivemate_vector_style.json")
                    .replace("__TILE_URL__", tiles.tilesUrl())
                    .replace("__TOMTOM_KEY__", android.net.Uri.encode(tomtomKey));
            // Parse first so invalid style substitutions don't leave a half-loaded map.
            new JSONObject(json);
            styleReady = false;
            lastRenderedVehicle = null;
            lastRenderedCamera = null;
            lastRenderedZoom = Double.NaN;
            lastRenderedBearing = Double.NaN;
            lastRenderedTilt = Double.NaN;
            lastRenderedVehicleAngle = Double.NaN;
            map.setStyle(new Style.Builder().fromJson(json), style -> {
                if (disposed) return;
                installOverlays(style);
                styleReady = true;
                setTraffic(trafficEnabled);
                updateHighDetailVisibility();
                if (pendingGps != null) setVehicle(pendingGps);
                if (pendingDest != null) setDestination(pendingDest);
                setEntrance(pendingEntrance);
                setParkedCar(pendingParked);
                if (pendingRoute != null) setRoute(pendingRoute);
                if (pendingJunctionRoute != null) setJunctionPreview(pendingJunctionRoute);
                if (pendingCamera != null) setCamera(pendingCamera,
                        pendingZoom, pendingBearing);
            });
        } catch (Exception error) {
            Log.e(LOG_TAG, "Unable to load DriveMate MapLibre style", error);
            // The health check will trigger a visible, timed fallback when
            // no UK roads are rendered. Never silently treat a style as ready.
        }
    }

    private void installOverlays(Style style) {
        routeSource = new GeoJsonSource("dm-route",
                FeatureCollection.fromFeatures(new Feature[0]));
        vehicleSource = new GeoJsonSource("dm-vehicle",
                FeatureCollection.fromFeatures(new Feature[0]));
        destinationSource = new GeoJsonSource("dm-destination",
                FeatureCollection.fromFeatures(new Feature[0]));
        entranceSource = new GeoJsonSource("dm-entrance",
                FeatureCollection.fromFeatures(new Feature[0]));
        parkedSource = new GeoJsonSource("dm-parked",
                FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(routeSource);
        junctionSource = new GeoJsonSource("dm-junction",
                FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(junctionSource);
        style.addSource(destinationSource);
        style.addSource(entranceSource);
        style.addSource(parkedSource);
        style.addSource(vehicleSource);
        LineLayer routeHalo = new LineLayer("dm-route-halo", "dm-route");
        routeHalo.setProperties(PropertyFactory.lineColor(
                        nightMode ? "#112B3B" : "#FFFFFF"),
                PropertyFactory.lineWidth(10f),
                PropertyFactory.lineOpacity(0.9f));
        style.addLayer(routeHalo);
        LineLayer routeLayer = new LineLayer("dm-route-line", "dm-route");
        routeLayer.setProperties(PropertyFactory.lineColor("#157EED"),
                PropertyFactory.lineWidth(6.5f));
        style.addLayer(routeLayer);
        // This stroke traces the real calculated route through an approaching
        // roundabout. It never attempts to deduce geometry from an exit count.
        LineLayer junctionHalo = new LineLayer("dm-junction-halo", "dm-junction");
        junctionHalo.setProperties(PropertyFactory.lineColor(
                        nightMode ? "#071923" : "#FFFFFF"),
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineOpacity(0.96f));
        style.addLayer(junctionHalo);
        LineLayer junctionLayer = new LineLayer("dm-junction-highlight", "dm-junction");
        junctionLayer.setProperties(PropertyFactory.lineColor("#12DFC7"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineOpacity(0.95f));
        style.addLayer(junctionLayer);
        CircleLayer dest = new CircleLayer("dm-dest-dot", "dm-destination");
        dest.setProperties(PropertyFactory.circleColor("#E14343"),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f));
        style.addLayer(dest);
        CircleLayer entranceDot = new CircleLayer("dm-entrance-pin", "dm-entrance");
        entranceDot.setProperties(PropertyFactory.circleColor("#17B68F"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f));
        style.addLayer(entranceDot);
        CircleLayer parkedDot = new CircleLayer("dm-parked-pin", "dm-parked");
        parkedDot.setProperties(PropertyFactory.circleColor("#FFBB38"),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#172E45"),
                PropertyFactory.circleStrokeWidth(3f));
        style.addLayer(parkedDot);
        // Directional arrow is screen-aligned. A circle cannot indicate
        // whether the vehicle is following the map or driving across it.
        style.addImage("dm-vehicle-arrow-image", makeVehicleArrow());
        SymbolLayer vehicle = new SymbolLayer("dm-vehicle-arrow-layer", "dm-vehicle");
        vehicle.setProperties(
                PropertyFactory.iconImage("dm-vehicle-arrow-image"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("viewport"),
                PropertyFactory.iconRotate(0f));
        style.addLayer(vehicle);
    }

    private static Bitmap makeVehicleArrow() {
        Bitmap bitmap = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(28, 28, 22, paint);
        paint.setColor(Color.rgb(21, 126, 237));
        Path arrow = new Path();
        arrow.moveTo(28, 7);
        arrow.lineTo(42, 43);
        arrow.lineTo(28, 34);
        arrow.lineTo(14, 43);
        arrow.close();
        canvas.drawPath(arrow, paint);
        return bitmap;
    }

    private void setVehicleHeading(double clockwiseScreenDegrees) {
        if (!ready() || map.getStyle() == null) return;
        double delta = Double.isFinite(lastRenderedVehicleAngle)
                ? (clockwiseScreenDegrees - lastRenderedVehicleAngle + 540) % 360 - 180
                : 360;
        if (Math.abs(delta) < 0.5) return;
        org.maplibre.android.style.layers.Layer layer =
                map.getStyle().getLayer("dm-vehicle-arrow-layer");
        if (layer != null) {
            layer.setProperties(PropertyFactory.iconRotate((float) clockwiseScreenDegrees));
            lastRenderedVehicleAngle = clockwiseScreenDegrees;
        }
    }

    public void setRoute(List<GeoMath.Point> path) {
        pendingRoute = path;
        if (!ready() || routeSource == null) return;
        if (path == null || path.size() < 2) {
            routeSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
            return;
        }
        List<Point> geometry = new ArrayList<>();
        // Only the drawing path is simplified, never the TomTom guidance route.
        for (GeoMath.Point p : DisplayPolyline.simplify(path, 1.5))
            geometry.add(Point.fromLngLat(p.lon, p.lat));
        routeSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(geometry)));
    }

    public void setJunctionPreview(List<GeoMath.Point> segment) {
        pendingJunctionRoute = segment;
        if (!ready() || junctionSource == null) return;
        if (segment == null || segment.size() < 2) {
            junctionSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
            return;
        }
        List<Point> geometry = new ArrayList<>();
        for (GeoMath.Point point : segment)
            geometry.add(Point.fromLngLat(point.lon, point.lat));
        junctionSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(geometry)));
    }

    public void setVehicle(GeoMath.Point point) {
        pendingGps = point;
        if (point == null || !ready() || vehicleSource == null) return;
        if (lastRenderedVehicle != null && GeoMath.distance(lastRenderedVehicle, point) < 0.45)
            return; // Avoid rebuilding the GeoJSON source for subpixel movement.
        vehicleSource.setGeoJson(Feature.fromGeometry(
                Point.fromLngLat(point.lon, point.lat)));
        lastRenderedVehicle = point;
    }

    public void setDestination(GeoMath.Point point) {
        pendingDest = point;
        if (!ready() || destinationSource == null) return;
        if (point == null) {
            destinationSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        } else {
            destinationSource.setGeoJson(Feature.fromGeometry(
                    Point.fromLngLat(point.lon, point.lat)));
        }
    }

    public void setEntrance(GeoMath.Point point) {
        pendingEntrance = point;
        if (!ready() || entranceSource == null) return;
        if (point == null)
            entranceSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        else
            entranceSource.setGeoJson(Feature.fromGeometry(
                    Point.fromLngLat(point.lon, point.lat)));
    }

    public void setParkedCar(GeoMath.Point point) {
        pendingParked = point;
        if (!ready() || parkedSource == null) return;
        if (point == null)
            parkedSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));
        else
            parkedSource.setGeoJson(Feature.fromGeometry(
                    Point.fromLngLat(point.lon, point.lat)));
    }

    public void setCamera(GeoMath.Point center, double zoom, double bearing) {
        pendingCamera = center;
        pendingZoom = zoom;
        pendingBearing = bearing;
        if (!ready() || center == null) return;
        // The GPU map follows directly; GPS camera updates do not restart
        // a long animation on every position fix.
        org.maplibre.android.camera.CameraUpdate update =
                CameraUpdateFactory.newCameraPosition(
                        new CameraPosition.Builder()
                                .target(new LatLng(center.lat, center.lon))
                                .zoom(zoom)
                                .bearing((bearing % 360 + 360) % 360)
                                .tilt(0)
                                .build());
        // Native GPU linear interpolation is short enough to finish before
        // the next typical one-second GPS fix. Sudden position jumps, startup
        // and manual route preview are handled immediately.
        boolean follow = lastRenderedCamera != null
                && GeoMath.distance(lastRenderedCamera, center) < 130
                && Math.abs(map.getCameraPosition().zoom - zoom) < 0.8;
        if (follow) map.easeCamera(update, 650, false);
        else map.moveCamera(update);
        lastRenderedCamera = center;
        lastRenderedZoom = zoom;
        lastRenderedBearing = bearing;
        lastRenderedTilt = 0;
    }

    /**
     * Frame-driven navigation camera: a direct GPU camera update, never a
     * restarted 650ms animation. Android's frame clock runs independently
     * from the GPS (normally 1Hz) and samples a time-based smooth controller.
     */
    public void followFrame(SmoothFollowCamera.Frame frame) {
        if (frame == null) return;
        pendingCamera = frame.center;
        pendingZoom = frame.zoom;
        pendingBearing = frame.bearing;
        pendingGps = frame.vehicle;
        if (!ready()) return;
        setVehicle(frame.vehicle);
        setVehicleHeading(CameraHeadingPolicy.screenDegrees(
                frame.travelBearing, frame.bearing));
        boolean moved = lastRenderedCamera == null
                || GeoMath.distance(lastRenderedCamera, frame.center) > 0.32;
        boolean zoomed = !Double.isFinite(lastRenderedZoom)
                || Math.abs(frame.zoom - lastRenderedZoom) > 0.006;
        double yaw = !Double.isFinite(lastRenderedBearing) ? 360
                : (frame.bearing - lastRenderedBearing + 540) % 360 - 180;
        boolean tilted = !Double.isFinite(lastRenderedTilt)
                || Math.abs(frame.tilt - lastRenderedTilt) > 0.16;
        if (!moved && !zoomed && Math.abs(yaw) < 0.28 && !tilted) return;
        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder()
                        .target(new LatLng(frame.center.lat, frame.center.lon))
                        .zoom(frame.zoom)
                        .bearing((frame.bearing % 360 + 360) % 360)
                        .tilt(frame.tilt)
                        .build()));
        lastRenderedCamera = frame.center;
        lastRenderedZoom = frame.zoom;
        lastRenderedBearing = frame.bearing;
        lastRenderedTilt = frame.tilt;
    }

    public void previewRoute(List<GeoMath.Point> path) {
        setRoute(path);
        if (!ready() || path == null || path.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (GeoMath.Point p : path) {
            minLat = Math.min(minLat, p.lat);
            maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon);
            maxLon = Math.max(maxLon, p.lon);
        }
        GeoMath.Point midpoint = new GeoMath.Point(
                (minLat + maxLat) / 2, (minLon + maxLon) / 2);
        // The legacy map continues to compute bounds exactly; this preview
        // uses a conservative initial zoom to avoid jumping off the route.
        double span = Math.max(maxLat - minLat, (maxLon - minLon) * 0.65);
        double zoom = Math.max(4, Math.min(16.5, 8 - Math.log(
                Math.max(0.00005, span)) / Math.log(2)));
        setCamera(midpoint, zoom, 0);
    }

    /**
     * Optional true high-zoom TomTom raster tiles are drawn ABOVE z14 OSM
     * streets but BELOW the real route and junction geometry. If the key is
     * unavailable or a tile fails, the OSM vector map remains visible.
     */
    public void setHighDetailEnabled(boolean enabled) {
        highDetailEnabled = enabled;
        updateHighDetailVisibility();
    }

    private void updateHighDetailVisibility() {
        if (!ready() || map.getStyle() == null) return;
        org.maplibre.android.style.layers.Layer layer =
                map.getStyle().getLayer("tomtom-street-detail");
        if (layer != null) layer.setProperties(
                PropertyFactory.visibility(highDetailEnabled && !tomtomKey.isEmpty()
                        ? "visible" : "none"));
    }

    public void setTraffic(boolean visible) {
        trafficEnabled = visible;
        if (!ready()) return;
        org.maplibre.android.style.layers.Layer layer =
                map.getStyle() == null ? null :
                        map.getStyle().getLayer("tomtom-traffic-flow");
        if (layer != null) layer.setProperties(
                PropertyFactory.visibility(visible && !tomtomKey.isEmpty()
                        ? "visible" : "none"));
    }

    public void prefetch(GeoMath.Point point) {
        if (point != null) tiles.prefetchAround(point.lat, point.lon);
    }
    public String cacheStats() {
        return tiles.localHits() + " local reads, " + tiles.downloadedTiles()
                + " new tiles, " + tiles.errors() + " errors";
    }

    public void onStart() { view.onStart(); }
    public void onResume() { view.onResume(); }
    public void onPause() { view.onPause(); }
    public void onStop() { view.onStop(); }
    public void onLowMemory() { view.onLowMemory(); }
    public void onSaveInstanceState(Bundle state) { view.onSaveInstanceState(state); }
    public void onDestroy() {
        disposed = true;
        view.removeCallbacks(roadMonitor);
        view.onDestroy();
    }
}
