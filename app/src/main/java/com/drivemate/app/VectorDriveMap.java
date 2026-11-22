package com.drivemate.app;

import android.app.Activity;
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
    private GeoJsonSource vehicleSource;
    private GeoJsonSource destinationSource;
    private boolean styleReady;
    private boolean trafficEnabled;
    private String tomtomKey;
    private boolean disposed;
    private GeoMath.Point pendingGps;
    private GeoMath.Point pendingDest;
    private List<GeoMath.Point> pendingRoute;
    private GeoMath.Point pendingCamera;
    private GeoMath.Point lastRenderedCamera;
    private double pendingZoom = 14.5, pendingBearing;
    private Runnable firstTileCallback;

    public VectorDriveMap(Activity activity, Bundle state, DriveTileServer tiles,
                          String key, boolean showTraffic, Runnable firstTileCallback) {
        this.activity = activity;
        this.tiles = tiles;
        this.tomtomKey = key == null ? "" : key;
        this.trafficEnabled = showTraffic;
        this.firstTileCallback = firstTileCallback;
        MapLibre.getInstance(activity.getApplicationContext());
        view = new MapView(activity);
        view.onCreate(state);
        view.getMapAsync(mapLibreMap -> {
            if (disposed) return;
            map = mapLibreMap;
            map.setMinZoomPreference(2.0);
            map.setMaxZoomPreference(19.0);
            loadStyle();
        });
        tiles.setOnFirstTile(() -> activity.runOnUiThread(() -> {
            if (!disposed && this.firstTileCallback != null) {
                this.firstTileCallback.run();
                this.firstTileCallback = null;
            }
        }));
    }

    public View view() { return view; }
    public boolean ready() { return styleReady && map != null; }
    public void setVisible(boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
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
        try {
            String json = readAsset(activity, "drivemate_vector_style.json")
                    .replace("__TILE_URL__", tiles.tilesUrl())
                    .replace("__TOMTOM_KEY__", android.net.Uri.encode(tomtomKey));
            // Parse first so invalid style substitutions don't leave a half-loaded map.
            new JSONObject(json);
            styleReady = false;
            map.setStyle(new Style.Builder().fromJson(json), style -> {
                if (disposed) return;
                installOverlays(style);
                styleReady = true;
                setTraffic(trafficEnabled);
                if (pendingGps != null) setVehicle(pendingGps);
                if (pendingDest != null) setDestination(pendingDest);
                if (pendingRoute != null) setRoute(pendingRoute);
                if (pendingCamera != null) setCamera(pendingCamera,
                        pendingZoom, pendingBearing);
            });
        } catch (Exception ignored) {
            // Caller still has the unchanged TomTom/osmdroid map as fallback.
        }
    }

    private void installOverlays(Style style) {
        routeSource = new GeoJsonSource("dm-route",
                FeatureCollection.fromFeatures(new Feature[0]));
        vehicleSource = new GeoJsonSource("dm-vehicle",
                FeatureCollection.fromFeatures(new Feature[0]));
        destinationSource = new GeoJsonSource("dm-destination",
                FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(routeSource);
        style.addSource(destinationSource);
        style.addSource(vehicleSource);
        LineLayer routeHalo = new LineLayer("dm-route-halo", "dm-route");
        routeHalo.setProperties(PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(10f),
                PropertyFactory.lineOpacity(0.9f));
        style.addLayer(routeHalo);
        LineLayer routeLayer = new LineLayer("dm-route-line", "dm-route");
        routeLayer.setProperties(PropertyFactory.lineColor("#157EED"),
                PropertyFactory.lineWidth(6.5f));
        style.addLayer(routeLayer);
        CircleLayer dest = new CircleLayer("dm-dest-dot", "dm-destination");
        dest.setProperties(PropertyFactory.circleColor("#E14343"),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f));
        style.addLayer(dest);
        CircleLayer vehicle = new CircleLayer("dm-vehicle-dot", "dm-vehicle");
        vehicle.setProperties(PropertyFactory.circleColor("#10A3E1"),
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(4f));
        style.addLayer(vehicle);
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

    public void setVehicle(GeoMath.Point point) {
        pendingGps = point;
        if (ready() && vehicleSource != null) {
            vehicleSource.setGeoJson(Feature.fromGeometry(
                    Point.fromLngLat(point.lon, point.lat)));
        }
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
        view.onDestroy();
    }
}
