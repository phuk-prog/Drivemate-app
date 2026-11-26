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
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.GeoJsonOptions;
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
    private DmUnifiedGeometry sharedRouteMap;
    private String routeProvider="";
    private boolean providerStyle,providerStylePending,providerStyleFailed;
    private GeoJsonSource signalSource;
    private MappedSignals signals;
    private GeoJsonSource roadsideSource, speedChangeSource, routeEventSource;
    private MappedRoadside roadside;
    private boolean roadsideAttempted;
    private double lastRoadsideLat=Double.NaN,lastRoadsideLon=Double.NaN,lastRoadsideZoom=Double.NaN;
    private long routeEventsFetchedAt;
    private boolean routeEventsMetric, routeEventsExpired;
    private List<RouteIntelligence.SpeedSection> pendingSpeedSections=java.util.Collections.emptyList();
    private List<RouteIntelligence.TrafficSection> pendingTrafficSections=java.util.Collections.emptyList();
    private boolean signalsAttempted;
    private double lastSignalLat=Double.NaN,lastSignalLon=Double.NaN,lastSignalZoom=Double.NaN;
    private String[] providerRoadLayers=new String[0];
    private int styleGeneration;
    private int[] pendingPreviewPadding;
    private GeoJsonSource remainingRouteSource;
    private double routeLength,confirmedProgress;
    private RouteRenderGeometry exactGeometry=new RouteRenderGeometry(null);
    private long lastAlignmentCheck;
    private String lastAlignment="unmeasured";
    private long lastRoadLabelAt;
    private String roadCandidate="",currentRoad="";
    private int roadCandidateCount;
    private String pendingRoadName="";
    private GeoJsonSource roadNameSource;
    private GeoJsonSource vehicleSource;
    private GeoJsonSource destinationSource;
    private GeoJsonSource entranceSource;
    private GeoJsonSource parkedSource;
    private boolean styleReady;
    private boolean trafficEnabled;
    private boolean nightMode;
    // True z16–19 online road/label detail above the local PMTiles z14 ceiling.
    // The local vector map remains beneath these tiles if they fail to load.
    private boolean highDetailEnabled = false;
    /** Extruded building blocks from z15 (Shortbread has outlines only, no heights). */
    private boolean buildings3d = true;
    /** While navigating, general POIs and bus stops are hidden to reduce clutter. */
    private boolean navigationDetail;
    private String tomtomKey;
    private boolean disposed;
    private GeoMath.Point pendingGps;
    private GeoMath.Point pendingDest;
    private GeoMath.Point pendingEntrance;
    private GeoMath.Point pendingParked;
    private List<GeoMath.Point> pendingRoute;
    private List<GeoMath.Point> junctionFramePoints=java.util.Collections.emptyList();
    private GeoMath.Point pendingCamera;
    private GeoMath.Point lastRenderedCamera;
    private GeoMath.Point lastRenderedVehicle;
    private double lastRenderedZoom = Double.NaN;
    private double lastRenderedBearing = Double.NaN;
    private double lastRenderedTilt = Double.NaN;
    private int followTop, followBottom;
    private GeoMath.Point followJunction;
    private double framingZoomOut;
    private long lastFramingCheck;
    private double lastRenderedVehicleAngle = Double.NaN;
    private Runnable onUserGesture;
    private double pendingZoom = 14.5, pendingBearing, pendingPitch, pendingTravelBearing;
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
            if (view.getVisibility() == View.VISIBLE) {inspectRoadDisplay();inspectRouteAlignment();updateSignals();updateRoadside();expireRouteEvents();}
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
            map.setMaxZoomPreference(21.0);
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                        && onUserGesture != null) onUserGesture.run();
            });
            map.addOnCameraIdleListener(()->{updateSignals();updateRoadside();});
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

    public void setHighDetailEnabled(boolean enabled) {
        highDetailEnabled = enabled;
        if (!ready()) return;
        org.maplibre.android.style.layers.Layer layer = map.getStyle() == null ? null
                : map.getStyle().getLayer("dm-tomtom-high-detail");
        if (layer != null) layer.setProperties(PropertyFactory.visibility(
                enabled && !navigationDetail && !tomtomKey.isEmpty() ? "visible" : "none"));
    }

    /**
     * 3D buildings: extruded blocks from zoom 15 with the flat outlines below
     * that. Off: flat outlines at every zoom. The tiles carry no building
     * heights, so all blocks share one estimated height (about two storeys).
     */
    public void setBuildings3D(boolean enabled) {
        buildings3d = enabled;
        if (!ready() || map.getStyle() == null) return;
        org.maplibre.android.style.layers.Layer blocks = map.getStyle().getLayer("uk-buildings-3d");
        org.maplibre.android.style.layers.Layer flat = map.getStyle().getLayer("uk-buildings");
        if (blocks != null) blocks.setProperties(PropertyFactory.visibility(
                enabled ? "visible" : "none"));
        // Preserve genuine footprints as a fallback at every zoom; extrusion
        // height starts at zero and can otherwise leave buildings invisible.
        if (flat != null) flat.setMaxZoom(24f);
    }

    /**
     * Navigating: keep driving-relevant places (fuel, EV charging, parking,
     * stations) and hide general shops, cafés and bus stops, which only add
     * clutter at driving speed. Otherwise show everything the tiles hold.
     */
    public void setNavigationDetail(boolean navigating) {
        navigationDetail = navigating;
        if(navigating)pendingPreviewPadding=null;
        if (!navigating) { framingZoomOut=0; followJunction=null; junctionFramePoints=java.util.Collections.emptyList(); }
        if (!ready() || map.getStyle() == null) return;
        setBuildings3D(buildings3d);
        setHighDetailEnabled(highDetailEnabled);
        if(!navigating)clearCurrentRoad();
        if(providerStyle)for(org.maplibre.android.style.layers.Layer layer:map.getStyle().getLayers()){
            if(layer instanceof SymbolLayer&&JunctionFraming.nonDrivingPoi(layer.getId()))layer.setProperties(PropertyFactory.visibility(navigating?"none":"visible"));
        }
        for (String id : new String[]{"uk-pois-dots", "uk-pois-labels", "uk-bus-stops", "uk-browse-poi-icons", "regional-browse-pois", "regional-addresses", "regional-entrances", "regional-building-labels", "regional-road-context"}) {
            org.maplibre.android.style.layers.Layer layer = map.getStyle().getLayer(id);
            if (layer != null) layer.setProperties(PropertyFactory.visibility(
                    navigating ? "none" : "visible"));
        }
    }

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
        if (disposed || providerStylePending) return;
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
        if(ready())ensureRegionalCoverage(target);
        int visible = 0;
        if (ready() && view.getWidth() > 80 && view.getHeight() > 80) {
            try {
                // Exclude vehicle, route, labels, buildings and background.
                // Only actual streets painted by the Shortbread source count.
                RectF middle = new RectF(view.getWidth() * 0.08f,
                        view.getHeight() * 0.08f, view.getWidth() * 0.92f,
                        view.getHeight() * 0.92f);
                visible = providerStyle ? (providerRoadLayers.length==0?0:map.queryRenderedFeatures(middle,providerRoadLayers).size()) : map.queryRenderedFeatures(middle,"uk-roads","uk-road-links","uk-bridge-road","regional-bridge-fill").size();
            } catch (RuntimeException error) {
                Log.w(LOG_TAG, "MapLibre rendered road query failed", error);
            }
        }
        RoadDisplayHealth.State outcome = roadHealth.check(
                SystemClock.elapsedRealtime(), target.lat, target.lon, zoom, ready(), visible);
        if(outcome==RoadDisplayHealth.State.FAILED&&providerStyle){providerStyleFailed=true;providerStyle=false;loadStyle();return;}
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

    /** Local, bounded comparison of route geometry with actually rendered vector streets.
     * Never changes the route or emits geographic coordinates. A close unrelated road
     * can mask a discrepancy, so this is a visual diagnostic, not road matching.
     */
    private void inspectRouteAlignment(){
        long now=SystemClock.elapsedRealtime();
        if(!ready()||!navigationDetail||routeLength<40||map.getCameraPosition().zoom<14
                ||now-lastAlignmentCheck<5000)return;
        lastAlignmentCheck=now;
        try{
            int w=view.getWidth(),h=view.getHeight();
            if(w<100||h<100)return;
            RectF box=new RectF(0,Math.max(0,followTop),w,Math.max(followTop,h-followBottom));
            if(box.height()<100)return;
            List<Feature> features=providerStyle?(providerRoadLayers.length==0?java.util.Collections.emptyList():map.queryRenderedFeatures(box,providerRoadLayers)):map.queryRenderedFeatures(box,"uk-roads","uk-road-links","uk-bridge-road","regional-bridge-fill");
            if(features.isEmpty()||features.size()>180)return;
            List<List<GeoMath.Point>> roads=new ArrayList<>();
            int count=0;
            for(Feature f:features){
                List<LineString> lines=new ArrayList<>();
                if(f.geometry() instanceof LineString)lines.add((LineString)f.geometry());
                else if(f.geometry() instanceof org.maplibre.geojson.MultiLineString){
                    for(List<Point> part:((org.maplibre.geojson.MultiLineString)f.geometry()).coordinates())
                        if(part.size()>1)lines.add(LineString.fromLngLats(part));
                }
                for(LineString line:lines){
                    List<GeoMath.Point> road=new ArrayList<>();
                    for(Point pt:line.coordinates()){
                        if(++count>12000)return;
                        road.add(new GeoMath.Point(pt.latitude(),pt.longitude()));
                    }
                    if(road.size()>1)roads.add(road);
                }
            }
            if(roads.isEmpty())return;
            List<Double> gaps=new ArrayList<>();
            for(int d=15;d<=180;d+=15){
                double along=confirmedProgress+d;if(along>=routeLength)break;
                GeoMath.Point q=exactGeometry.at(along);
                android.graphics.PointF pos=map.getProjection().toScreenLocation(new LatLng(q.lat,q.lon));
                if(pos!=null&&box.contains(pos.x,pos.y))gaps.add(RouteRenderGeometry.nearestRoadDistance(q,roads));
            }
            if(gaps.size()<4)return;
            java.util.Collections.sort(gaps);
            double median=gaps.get(gaps.size()/2),max=gaps.get(gaps.size()-1);
            lastAlignment=String.format(java.util.Locale.ROOT,"samples=%d medianGap=%.1fm maxGap=%.1fm",gaps.size(),median,max);
            Log.i(LOG_TAG,"Route/base-map visual alignment (nearest visible street, not road identity): "+lastAlignment);
        }catch(RuntimeException ignored){lastAlignment="unmeasured";}
    }
    public String routeAlignmentDiagnostic(){return lastAlignment;}
    String geometryDescription(){return sharedRouteMap!=null&&sharedRouteMap.checkedEdges==0?"DriveMate source-matched detailed browse map":providerStyle?"TomTom vector roads and routing (same provider; geometry not independently verified)":providerStyleFailed?"Independent vector fallback · road alignment not verified":RouteMapPolicy.description(routeProvider,hasSharedRouteMap());}
    private boolean requestProviderStyle(){return sharedRouteMap==null&&!providerStyleFailed&&RouteMapPolicy.useTomTomVector(routeProvider,!tomtomKey.isEmpty());}
    private String providerStyleUrl(){return "https://api.tomtom.com/style/1/style/20.3.4-6?key="+android.net.Uri.encode(tomtomKey)+"&map="+(nightMode?"basic_night":"basic_main")+"&poi=poi_main"+(trafficEnabled?"&traffic_flow="+(nightMode?"flow_relative0-dark":"flow_relative0"):"");}
    boolean hasSharedRouteMap(){return sharedRouteMap!=null&&sharedRouteMap.checkedEdges>0&&ready();}
    public void setBrowseGeometry(DmUnifiedGeometry context){
        if(pendingRoute!=null&&!pendingRoute.isEmpty())return;
        if(context!=null&&context.checkedEdges!=0)return;
        if(sharedRouteMap==context)return;
        if(sharedRouteMap!=null&&sharedRouteMap.checkedEdges>0)return;
        // Reuse the active detailed style on context refresh, instead of flashing the map.
        if(sharedRouteMap!=null&&context!=null&&ready()&&map.getStyle()!=null){
            Style style=map.getStyle();GeoJsonSource roads=style.getSourceAs("unified-roads"),areas=style.getSourceAs("unified-areas"),points=style.getSourceAs("unified-points");
            if(roads!=null&&areas!=null){sharedRouteMap=context;roads.setGeoJson(context.roads);areas.setGeoJson(context.areas);if(points!=null)points.setGeoJson(context.mappedPoints);return;}
        }
        sharedRouteMap=context;loadStyle();
    }
    /** Instrumentation checks the same road and route layers used by live guidance. */
    boolean sharedRouteRendered(double zoom){
        if(!hasSharedRouteMap()||view.getWidth()<100||view.getHeight()<100||Math.abs(currentZoom()-zoom)>.2)return false;
        RectF box=new RectF(0,0,view.getWidth(),view.getHeight());
        try{return !map.queryRenderedFeatures(box,"uk-roads").isEmpty()&&!map.queryRenderedFeatures(box,"dm-route-line").isEmpty();}catch(RuntimeException failure){return false;}
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
        tomtomKey = newKey;providerStyleFailed=false;
        if (map != null) loadStyle();
    }

    private void loadStyle() {
        if (disposed || map == null) return;
        // Re-validate rendered UK roads after every theme reload. A previous
        // READY report must not prove the new style has rendered anything.
        roadHealth.reset();
        previouslyReported = RoadDisplayHealth.State.NOT_APPLICABLE;
        final int generation=++styleGeneration;
        final DmUnifiedGeometry shared=sharedRouteMap;
        final boolean remote=requestProviderStyle();providerStylePending=remote;providerStyle=false;providerRoadLayers=new String[0];
        try {
            String json = readAsset(activity, nightMode
                    ? "drivemate_vector_style_night.json"
                    : "drivemate_vector_style.json")
                    .replace("__TILE_URL__", tiles.tilesUrl())
                    .replace("__TOMTOM_STYLE__", nightMode ? "night" : "main")
                    .replace("__TOMTOM_KEY__", android.net.Uri.encode(tomtomKey));
            // Use full-detail regional streets only after route identity proof. The main route overlay is still used.
            if(shared!=null){
                JSONObject local=new JSONObject(readAsset(activity,nightMode?"dm_unified_night.json":"dm_unified_day.json"));
                org.json.JSONArray layers=local.getJSONArray("layers"), filtered=new org.json.JSONArray();
                for(int i=0;i<layers.length();i++){
                    JSONObject layer=layers.getJSONObject(i);
                    if("unified-route".equals(layer.optString("source")))continue;
                    String id=layer.getString("id");
                    if("road-fill".equals(id))layer.put("id","uk-roads");
                    if("buildings-flat".equals(id))layer.put("id","uk-buildings");
                    if("buildings-3d".equals(id))layer.put("id","uk-buildings-3d");
                    filtered.put(layer);
                }
                local.put("layers",filtered);json=local.toString();
            }
            // Parse first so invalid style substitutions don't leave a half-loaded map.
            new JSONObject(json);
            styleReady = false;
            lastRenderedVehicle = null;
            lastRenderedCamera = null;
            lastRenderedZoom = Double.NaN;
            lastRenderedBearing = Double.NaN;
            lastRenderedTilt = Double.NaN;
            lastRenderedVehicleAngle = Double.NaN;
            Style.Builder builder=remote?new Style.Builder().fromUri(providerStyleUrl()):new Style.Builder().fromJson(json);
            if(remote)view.postDelayed(()->{if(!disposed&&generation==styleGeneration&&providerStylePending){providerStyleFailed=true;providerStylePending=false;loadStyle();}},18000);
            map.setStyle(builder, style -> {
                if (disposed || generation!=styleGeneration) return;
                providerStylePending=false;providerStyle=remote;
                if(remote){List<String> ids=new ArrayList<>();for(org.maplibre.android.style.layers.Layer layer:style.getLayers()){String id=layer.getId().toLowerCase(java.util.Locale.ROOT);if(layer instanceof LineLayer&&(id.contains("road")||id.contains("street")||id.contains("motorway")||id.contains("highway"))&&!id.contains("traffic")&&!id.contains("label")&&!id.contains("rail"))ids.add(layer.getId());}providerRoadLayers=ids.toArray(new String[0]);}
                if(shared!=null){
                    GeoJsonSource roads=style.getSourceAs("unified-roads"),areas=style.getSourceAs("unified-areas");
                    if(roads==null||areas==null){sharedRouteMap=null;loadStyle();return;}
                    roads.setGeoJson(shared.roads);areas.setGeoJson(shared.areas);
                    GeoJsonSource mapped=style.getSourceAs("unified-points");if(mapped!=null)mapped.setGeoJson(shared.mappedPoints);
                }
                installOverlays(style);
                styleReady = true;
                updateSignals();updateRoadside();publishRouteEvents();
                setHighDetailEnabled(highDetailEnabled);
                setBuildings3D(buildings3d);
                setNavigationDetail(navigationDetail);
                setTraffic(trafficEnabled);
                if (pendingGps != null) setVehicle(pendingGps);
                if (pendingDest != null) setDestination(pendingDest);
                setEntrance(pendingEntrance);
                setParkedCar(pendingParked);
                if (pendingRoute != null) {renderRoute();renderRemaining();}
                publishRoadName();
                if (pendingCamera != null) setBrowseCamera(pendingCamera,
                         pendingZoom, pendingBearing,pendingPitch,pendingTravelBearing);
                if(!navigationDetail&&pendingPreviewPadding!=null){int[] pad=pendingPreviewPadding;pendingPreviewPadding=null;previewRoute(pendingRoute,pad[0],pad[1],pad[2]);}
            });
        } catch (Exception error) {
            Log.e(LOG_TAG, "Unable to load DriveMate MapLibre style");
            if(remote){providerStyleFailed=true;providerStylePending=false;loadStyle();}
            // The health check will trigger a visible, timed fallback when
            // no UK roads are rendered. Never silently treat a style as ready.
        }
    }

    private static GeoJsonSource exactLineSource(String id){
        // Disable GeoJSON tile simplification as well as app-side simplification.
        return new GeoJsonSource(id,FeatureCollection.fromFeatures(new Feature[0]),new GeoJsonOptions().withTolerance(0f).withMaxZoom(21));
    }

    private static org.maplibre.android.style.expressions.Expression routeWidth(float low,float middle,float high){
        return org.maplibre.android.style.expressions.Expression.interpolate(
                org.maplibre.android.style.expressions.Expression.linear(),org.maplibre.android.style.expressions.Expression.zoom(),
                org.maplibre.android.style.expressions.Expression.stop(10,low),
                org.maplibre.android.style.expressions.Expression.stop(16,middle),
                org.maplibre.android.style.expressions.Expression.stop(20,high));
    }
    private void installOverlays(Style style) {
        routeSource = exactLineSource("dm-route");
        vehicleSource = new GeoJsonSource("dm-vehicle",
                FeatureCollection.fromFeatures(new Feature[0]));
        destinationSource = new GeoJsonSource("dm-destination",
                FeatureCollection.fromFeatures(new Feature[0]));
        entranceSource = new GeoJsonSource("dm-entrance",
                FeatureCollection.fromFeatures(new Feature[0]));
        parkedSource = new GeoJsonSource("dm-parked",
                FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(routeSource);
        remainingRouteSource=exactLineSource("dm-remaining-route");
        roadNameSource=new GeoJsonSource("dm-current-road",FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(remainingRouteSource);style.addSource(roadNameSource);
        installDetailIcons(style);
        style.addSource(destinationSource);
        style.addSource(entranceSource);
        style.addSource(parkedSource);
        style.addSource(vehicleSource);
        // Muted full geometry under the remaining, independently coloured ribbon.
        LineLayer travelled=new LineLayer("dm-route-travelled","dm-route");
        travelled.setProperties(PropertyFactory.lineColor(nightMode?"#52667a":"#8da3ad"),
                PropertyFactory.lineWidth(7f),PropertyFactory.lineOpacity(.68f),
                PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        style.addLayer(travelled);
        LineLayer routeHalo = new LineLayer("dm-route-halo", "dm-remaining-route");
        routeHalo.setProperties(PropertyFactory.lineColor(nightMode?"#12374e":"#154b78"),
                PropertyFactory.lineWidth(routeWidth(5f,9f,15f)),PropertyFactory.lineOpacity(.96f),
                PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        style.addLayer(routeHalo);
        LineLayer routeLayer = new LineLayer("dm-route-line", "dm-remaining-route");
        routeLayer.setProperties(PropertyFactory.lineColor(nightMode?"#24d9f5":"#007dbb"),
                PropertyFactory.lineWidth(routeWidth(3f,6f,11f)),PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        style.addLayer(routeLayer);
        LineLayer gleam=new LineLayer("dm-route-centre","dm-remaining-route");
        gleam.setProperties(PropertyFactory.lineColor(nightMode?"#b2f6ff":"#57e5ff"),
                PropertyFactory.lineWidth(routeWidth(0.7f,1f,2f)),PropertyFactory.lineOpacity(.72f),
                PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        style.addLayer(gleam);
        installSignalOverlay(style);
        installRoadsideOverlay(style);
        installRouteEvents(style);
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
                PropertyFactory.iconRotate(0f),PropertyFactory.iconSize(1.28f));
        style.addLayer(vehicle);
        SymbolLayer currentRoadLayer=new SymbolLayer("dm-current-road-label","dm-current-road");
        currentRoadLayer.setProperties(PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("name")),
                PropertyFactory.textFont(new String[]{"Noto Sans Regular"}),PropertyFactory.textSize(14f),
                PropertyFactory.textColor("#ffffff"),PropertyFactory.textHaloColor("#0a273b"),PropertyFactory.textHaloWidth(3f),
                PropertyFactory.textAnchor("top"),PropertyFactory.textOffset(new Float[]{0f,1.9f}),
                PropertyFactory.textAllowOverlap(false),PropertyFactory.textIgnorePlacement(false));
        style.addLayer(currentRoadLayer);
    }

    private void installSignalOverlay(Style style){
        signalSource=new GeoJsonSource("dm-static-signals",FeatureCollection.fromFeatures(new Feature[0]));style.addSource(signalSource);
        Bitmap b=Bitmap.createBitmap(28,42,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);c.drawRoundRect(new RectF(1,1,27,41),6,6,p);p.setColor(0xff213142);c.drawRoundRect(new RectF(3,3,25,39),5,5,p);
        // Three coloured lenses denote a mapped traffic light, never the current phase.
        int[] colors={0xffd95358,0xffe5b73b,0xff48a77a};for(int i=0;i<3;i++){p.setColor(colors[i]);c.drawCircle(14,10+i*11,4.1f,p);}
        style.addImage("dm-mapped-traffic-light",b);
        SymbolLayer l=new SymbolLayer("dm-traffic-light-locations","dm-static-signals");
        l.setMinZoom(15f);l.setProperties(PropertyFactory.iconImage("dm-mapped-traffic-light"),PropertyFactory.iconSize(.75f),PropertyFactory.iconAllowOverlap(false),PropertyFactory.iconIgnorePlacement(false));style.addLayer(l);
        lastSignalLat=lastSignalLon=lastSignalZoom=Double.NaN;
    }
    private void updateSignals(){
        if(!ready()||signalSource==null||map.getCameraPosition().target==null)return;
        double z=map.getCameraPosition().zoom;LatLng at=map.getCameraPosition().target;
        double lat=at.getLatitude(),lon=at.getLongitude();
        if(z<15){if(!Double.isNaN(lastSignalZoom)){signalSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));lastSignalZoom=Double.NaN;}return;}
        if(!signalsAttempted){signalsAttempted=true;try{signals=MappedSignals.load(activity);}catch(Exception ignored){return;}}
        if(signals==null)return;
        double span=Math.min(.055, .035*Math.pow(2,15-z));span=Math.max(.0015,span);
        if(Double.isFinite(lastSignalZoom)&&Math.abs(z-lastSignalZoom)<.4&&Math.abs(lat-lastSignalLat)<span*.18&&Math.abs(lon-lastSignalLon)<span*.18)return;
        lastSignalLat=lat;lastSignalLon=lon;lastSignalZoom=z;
        signalSource.setGeoJson(signals.features(lat-span,lon-span,lat+span,lon+span));
    }
    private void installRoadsideOverlay(Style style) {
        roadsideSource=new GeoJsonSource("dm-static-roadside",FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(roadsideSource);
        String[] names={"camera","crossing","calming"};
        String[] glyphs={"CAM","X","!"};
        for(int i=0;i<names.length;i++){
            Bitmap b=Bitmap.createBitmap(48,40,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);c.drawRoundRect(new RectF(1,1,47,39),8,8,p);
            p.setColor(i==0?0xffa76d0f:0xffa43a31);c.drawRoundRect(new RectF(3,3,45,37),6,6,p);
            p.setColor(Color.WHITE);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);p.setTextSize(i==0?15:24);
            c.drawText(glyphs[i],24,20-(p.ascent()+p.descent())/2,p);
            style.addImage("dm-mapped-"+names[i],b);
        }
        SymbolLayer l=new SymbolLayer("dm-roadside-locations","dm-static-roadside");
        l.setMinZoom(13f);
        l.setProperties(PropertyFactory.iconImage(org.maplibre.android.style.expressions.Expression.get("icon")),
                PropertyFactory.iconSize(.8f),PropertyFactory.iconAllowOverlap(false),PropertyFactory.iconIgnorePlacement(false));
        style.addLayer(l);
        lastRoadsideLat=lastRoadsideLon=lastRoadsideZoom=Double.NaN;
    }

    private void updateRoadside(){
        if(!ready()||roadsideSource==null||map.getCameraPosition().target==null)return;
        double z=map.getCameraPosition().zoom;
        if(z<13){if(Double.isFinite(lastRoadsideZoom)){roadsideSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));lastRoadsideZoom=Double.NaN;}return;}
        if(!roadsideAttempted){roadsideAttempted=true;try{roadside=MappedRoadside.load(activity);}catch(Exception e){Log.w(LOG_TAG,"Mapped roadside data unavailable");}}
        if(roadside==null)return;
        LatLng target=map.getCameraPosition().target;
        double lat=target.getLatitude(),lon=target.getLongitude();
        double span=Math.max(.002,Math.min(.075,.045*Math.pow(2,15-z)));
        if(Double.isFinite(lastRoadsideZoom)&&Math.abs(z-lastRoadsideZoom)<.3&&Math.abs(lat-lastRoadsideLat)<span*.15&&Math.abs(lon-lastRoadsideLon)<span*.15)return;
        lastRoadsideLat=lat;lastRoadsideLon=lon;lastRoadsideZoom=z;
        roadsideSource.setGeoJson(roadside.features(lat-span,lon-span,lat+span,lon+span,z>=16));
    }

    public void setRouteEvents(List<RouteIntelligence.SpeedSection> speed,List<RouteIntelligence.TrafficSection> traffic,boolean metric,long fetchedAt){
        routeEventsMetric=metric;routeEventsFetchedAt=fetchedAt;routeEventsExpired=false;
        pendingSpeedSections=speed==null?java.util.Collections.emptyList():new ArrayList<>(speed);
        pendingTrafficSections=traffic==null?java.util.Collections.emptyList():new ArrayList<>(traffic);
        publishRouteEvents();
    }

    private void installRouteEvents(Style style){
        speedChangeSource=new GeoJsonSource("dm-speed-changes",FeatureCollection.fromFeatures(new Feature[0]));
        routeEventSource=new GeoJsonSource("dm-route-events",FeatureCollection.fromFeatures(new Feature[0]));
        style.addSource(speedChangeSource);style.addSource(routeEventSource);
        CircleLayer c=new CircleLayer("dm-speed-change-roundels","dm-speed-changes");
        c.setMinZoom(13f);c.setProperties(PropertyFactory.circleColor("#ffffff"),
                PropertyFactory.circleRadius(13f),PropertyFactory.circleStrokeWidth(3f),PropertyFactory.circleStrokeColor("#d9272e"));
        style.addLayer(c);
        SymbolLayer text=new SymbolLayer("dm-speed-change-values","dm-speed-changes");
        text.setMinZoom(13f);text.setProperties(PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("value")),
                PropertyFactory.textSize(12f),PropertyFactory.textColor("#111111"),PropertyFactory.textAllowOverlap(true));
        style.addLayer(text);
        CircleLayer hazard=new CircleLayer("dm-route-event-points","dm-route-events");
        hazard.setMinZoom(12f);hazard.setProperties(PropertyFactory.circleColor("#c45616"),
                PropertyFactory.circleRadius(6f),PropertyFactory.circleStrokeWidth(2f),PropertyFactory.circleStrokeColor("#ffffff"));
        style.addLayer(hazard);
        SymbolLayer notice=new SymbolLayer("dm-route-event-labels","dm-route-events");
        notice.setMinZoom(13f);notice.setProperties(PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("label")),
                PropertyFactory.textSize(11f),PropertyFactory.textColor("#ffffff"),PropertyFactory.textHaloColor("#78350f"),
                PropertyFactory.textHaloWidth(2f),PropertyFactory.textOffset(new Float[]{0f,-1.4f}));
        style.addLayer(notice);
    }

    private void expireRouteEvents(){
        long age=System.currentTimeMillis()-routeEventsFetchedAt;
        if(!routeEventsExpired && (age<0||age>480000)){routeEventsExpired=true;publishRouteEvents();}
    }
    private void publishRouteEvents(){
        if(!ready()||speedChangeSource==null||routeEventSource==null)return;
        ArrayList<Feature> speed=new ArrayList<>(),traffic=new ArrayList<>();
        long age=System.currentTimeMillis()-routeEventsFetchedAt;
        if(!routeEventsExpired && age>=0 && age<=480000 && pendingRoute!=null&&pendingRoute.size()>1){
            for(RouteIntelligence.SpeedSection section:pendingSpeedSections){
                if(speed.size()>=500)break;
                if(section==null||section.end<=0||section.end>routeLength||section.end-section.start<8)continue;
                SpeedChangeAwareness.Change change=SpeedChangeAwareness.next(pendingSpeedSections,section,section.end-4,0);
                if(change==null)continue;
                GeoMath.Point p=exactGeometry.at(change.offset);
                if(p!=null){Feature f=Feature.fromGeometry(Point.fromLngLat(p.lon,p.lat));
                    f.addStringProperty("value",String.valueOf(routeEventsMetric?change.kmh:Math.round(change.kmh/1.609344)));speed.add(f);}
            }
            // Traffic labels are only supplied route events, never synthetic hazards.
            for(RouteIntelligence.TrafficSection section:pendingTrafficSections){
                if(traffic.size()>=500)break;
                if(section==null||!Double.isFinite(section.start)||section.start<0||section.start>routeLength||section.end<=section.start)continue;
                GeoMath.Point p=exactGeometry.at(section.start);
                if(p!=null){Feature f=Feature.fromGeometry(Point.fromLngLat(p.lon,p.lat));f.addStringProperty("label",section.label());traffic.add(f);}
            }
        }
        speedChangeSource.setGeoJson(FeatureCollection.fromFeatures(speed.toArray(new Feature[0])));
        routeEventSource.setGeoJson(FeatureCollection.fromFeatures(traffic.toArray(new Feature[0])));
    }

    static void installDetailIcons(Style style){
        style.addImage("dm-shield-blue",shieldBitmap(0xff164d94));style.addImage("dm-shield-green",shieldBitmap(0xff285a48));style.addImage("dm-shield-white",shieldBitmap(0xfff4f8fa));
        String[][] icons={{"fuel","F"},{"ev","EV"},{"parking","P"},{"hospital","+"},{"food","R"},{"shop","S"},{"services","MS"},{"hotel","H"},{"garage","G"},{"airport","A"},{"ferry","FE"},{"station","T"},{"place","•"}};
        int[] colors={0xffa76319,0xff167b6e,0xff245c9a,0xffa63d45,0xff8d5739,0xff596c9b,0xff386985,0xff795b91,0xff65747a,0xff4b7596,0xff347d9c,0xff4b5972,0xff6a8390};
        for(int i=0;i<icons.length;i++){Bitmap b=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);c.drawRoundRect(new RectF(1,1,39,39),9,9,p);p.setColor(colors[i]);c.drawRoundRect(new RectF(3,3,37,37),7,7,p);p.setColor(Color.WHITE);p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(icons[i][1].length()>1?17:23);c.drawText(icons[i][1],20,20-(p.ascent()+p.descent())/2,p);style.addImage("dm-poi-"+icons[i][0],b);}
    }
    private static Bitmap shieldBitmap(int color){
        Bitmap bitmap=Bitmap.createBitmap(36,25,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(0,0,36,25),5,5,paint);paint.setColor(color);
        canvas.drawRoundRect(new RectF(1.5f,1.5f,34.5f,23.5f),4,4,paint);return bitmap;
    }
    static Bitmap makeVehicleArrow() {
        Bitmap bitmap = Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        // A translucent position halo, an offset shadow and a white-edged arrow.
        paint.setColor(0x3032cce9);canvas.drawCircle(40,41,36,paint);
        Path arrow=new Path();arrow.moveTo(40,9);arrow.lineTo(68,60);arrow.quadTo(69,65,63,64);
        arrow.lineTo(40,55);arrow.lineTo(17,64);arrow.quadTo(11,65,12,60);arrow.close();
        canvas.save();canvas.translate(0,4);paint.setColor(0x8830485d);canvas.drawPath(arrow,paint);canvas.restore();
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);canvas.drawPath(arrow,paint);
        canvas.save();canvas.scale(.80f,.80f,40,40);
        paint.setShader(new android.graphics.LinearGradient(20,10,60,65,new int[]{0xff39d9f9,0xff0579d1,0xff1754b8},null,android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(arrow,paint);paint.setShader(null);canvas.restore();return bitmap;
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

    public void setSharedRoute(DmUnifiedGeometry shared,List<GeoMath.Point> path){setSharedRoute(shared,path,"");}
    public void setSharedRoute(DmUnifiedGeometry shared,List<GeoMath.Point> path,String provider){
        pendingSpeedSections=java.util.Collections.emptyList();pendingTrafficSections=java.util.Collections.emptyList();routeEventsExpired=true;
        String next=provider==null||path==null?"":provider;
        boolean changedProvider=!next.equals(routeProvider);
        boolean change=sharedRouteMap!=shared||changedProvider;
        if(changedProvider)providerStyleFailed=false;
        routeProvider=next;sharedRouteMap=shared;pendingPreviewPadding=null;
        setRoute(path);
        if(change)loadStyle();
    }
    private void ensureRegionalCoverage(GeoMath.Point center){
        if(sharedRouteMap!=null && center!=null && !sharedRouteMap.covers(center)){
            sharedRouteMap=null;loadStyle();
        }
    }
    public void setRoute(List<GeoMath.Point> path) {
        exactGeometry=new RouteRenderGeometry(path);pendingRoute=exactGeometry.full();routeLength=exactGeometry.length;
        lastAlignment="unmeasured";lastAlignmentCheck=0;
        confirmedProgress=0;clearCurrentRoad();renderRoute();renderRemaining();publishRouteEvents();
    }
    private void geometry(GeoJsonSource source,List<GeoMath.Point> path){
        if(!ready()||source==null)return;
        if(path==null||path.size()<2){source.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));return;}
        List<Point> points=new ArrayList<>();
        for(GeoMath.Point p:path)points.add(Point.fromLngLat(p.lon,p.lat));
        if(points.size()<2){source.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));return;}
        source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)));
    }
    private void renderRoute(){geometry(routeSource,pendingRoute);}
    private void renderRemaining(){
        if(pendingRoute==null||confirmedProgress<=0){geometry(remainingRouteSource,pendingRoute);return;}
        geometry(remainingRouteSource,exactGeometry.remaining(confirmedProgress));
    }
    public void setConfirmedRouteProgress(double along){
        if(!navigationDetail||!Double.isFinite(along)||routeLength<=0)return;
        double next=Math.max(0,Math.min(routeLength,along));
        if(Math.abs(next-confirmedProgress)<8&&next<routeLength)return;
        if(next==confirmedProgress)return;
        confirmedProgress=next;renderRemaining();
    }
    public void clearCurrentRoad(){
        roadCandidate="";currentRoad="";pendingRoadName="";roadCandidateCount=0;lastRoadLabelAt=0;
        publishRoadName();
    }
    private void publishRoadName(){
        if(!ready()||roadNameSource==null)return;
        if(pendingRoadName.isEmpty()||pendingGps==null){roadNameSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[0]));return;}
        Feature f=Feature.fromGeometry(Point.fromLngLat(pendingGps.lon,pendingGps.lat));f.addStringProperty("name",pendingRoadName);roadNameSource.setGeoJson(f);
    }
    /** Only unambiguous named road geometry near a confirmed GPS fix, never next-turn text. */
    public void updateCurrentRoad(GeoMath.Point gps){
        if(!ready()||!navigationDetail||gps==null)return;
        long now=SystemClock.elapsedRealtime();if(now-lastRoadLabelAt<800)return;lastRoadLabelAt=now;
        java.util.Set<String> names=new java.util.HashSet<>();
        try{
            android.graphics.PointF pos=map.getProjection().toScreenLocation(new LatLng(gps.lat,gps.lon));
            if(pos==null||!Float.isFinite(pos.x)||!Float.isFinite(pos.y))return;
            float pad=18*activity.getResources().getDisplayMetrics().density;
            for(Feature feature:map.queryRenderedFeatures(new RectF(pos.x-pad,pos.y-pad,pos.x+pad,pos.y+pad),"uk-roads")){
                if(!feature.hasProperty("name")||!(feature.geometry() instanceof LineString))continue;
                String name=feature.getStringProperty("name");if(name==null||name.trim().isEmpty()||name.length()>80)continue;
                List<GeoMath.Point> coords=new ArrayList<>();for(Point pt:((LineString)feature.geometry()).coordinates())coords.add(new GeoMath.Point(pt.latitude(),pt.longitude()));
                if(coords.size()>1&&GeoMath.project(coords,gps).offRouteMetres<=12)names.add(name.trim());
            }
        }catch(RuntimeException ignored){names.clear();}
        String candidate=names.size()==1?names.iterator().next():"";
        if(candidate.isEmpty()){clearCurrentRoad();return;}
        if(!candidate.equals(roadCandidate)){roadCandidate=candidate;roadCandidateCount=1;pendingRoadName="";publishRoadName();return;}
        if(++roadCandidateCount>=2){currentRoad=candidate;pendingRoadName=currentRoad;publishRoadName();}
    }

    public void clearJunctionFraming(){junctionFramePoints=java.util.Collections.emptyList();followJunction=null;}

    public void setVehicle(GeoMath.Point point) {
        pendingGps = point;
        if (point == null || !ready() || vehicleSource == null) return;
        if (lastRenderedVehicle != null && GeoMath.distance(lastRenderedVehicle, point) < 0.45)
            return; // Avoid rebuilding the GeoJSON source for subpixel movement.
        vehicleSource.setGeoJson(Feature.fromGeometry(
                Point.fromLngLat(point.lon, point.lat)));
        lastRenderedVehicle = point;
        if(!pendingRoadName.isEmpty())publishRoadName();
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
        setBrowseCamera(center,zoom,bearing,0,bearing);
    }

    public void setBrowseCamera(GeoMath.Point center,double zoom,double bearing,double pitch,double vehicleBearing) {
        pendingCamera = center;
        pendingZoom = zoom;
        pendingBearing = bearing;pendingPitch=pitch;pendingTravelBearing=vehicleBearing;
        ensureRegionalCoverage(center);
        if (!ready() || center == null) return;
        // The GPU map follows directly; GPS camera updates do not restart
        // a long animation on every position fix.
        org.maplibre.android.camera.CameraUpdate update =
                CameraUpdateFactory.newCameraPosition(
                        new CameraPosition.Builder()
                                .target(new LatLng(center.lat, center.lon))
                                .zoom(zoom)
                                .bearing((bearing % 360 + 360) % 360)
                                .tilt(Math.max(0,Math.min(55,pitch)))
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
        lastRenderedTilt = pitch;
        setVehicleHeading(CameraHeadingPolicy.screenDegrees(vehicleBearing,bearing));
    }

    /**
     * Frame-driven navigation camera: a direct GPU camera update, never a
     * restarted 650ms animation. Android's frame clock runs independently
     * from the GPS (normally 1Hz) and samples a time-based smooth controller.
     */
    public void setFollowViewport(int top, int bottom, GeoMath.Point junction,List<GeoMath.Point> context) {
        followTop=Math.max(0,top);followBottom=Math.max(0,bottom);followJunction=junction;
        junctionFramePoints=context==null?java.util.Collections.emptyList():context;
    }

    // Check the actual projection at a low cadence, rather than assuming the
    // route-arc-length estimate always fits a pitched or narrow display.
    private double viewportZoomOut(SmoothFollowCamera.Frame frame) {
        if (!ready() || !navigationDetail || lastRenderedCamera==null || frame.vehicle==null)
            return 0;
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastFramingCheck<500)return framingZoomOut;
        lastFramingCheck=now;
        int h=view.getHeight(), w=view.getWidth(), bottom=h-followBottom;
        if(w<100 || bottom-followTop<120)return framingZoomOut;
        android.graphics.PointF car=map.getProjection().toScreenLocation(
                new LatLng(frame.vehicle.lat,frame.vehicle.lon));
        android.graphics.PointF turn=followJunction==null?null:map.getProjection().toScreenLocation(
                new LatLng(followJunction.lat,followJunction.lon));
        boolean clipped=outside(car,w,followTop,bottom,16)
                || (turn!=null && outside(turn,w,followTop,bottom,16));
        boolean safelyInside=!outside(car,w,followTop,bottom,48)
                && (turn==null || !outside(turn,w,followTop,bottom,48));
        // Check every bounded junction envelope vertex as well as the car and turn.
        for(GeoMath.Point p:junctionFramePoints){android.graphics.PointF q=map.getProjection().toScreenLocation(new LatLng(p.lat,p.lon));
            clipped|=outside(q,w,followTop,bottom,20);safelyInside&=!outside(q,w,followTop,bottom,56);
        }
        if(clipped)framingZoomOut=Math.min(junctionFramePoints.isEmpty()?1.3:2.5,framingZoomOut+.20);
        else if(safelyInside)framingZoomOut=Math.max(0,framingZoomOut-.08);
        return framingZoomOut;
    }
    private static boolean outside(android.graphics.PointF point,int width,int top,int bottom,int margin){
        return point==null || !Float.isFinite(point.x) || !Float.isFinite(point.y)
                || point.x<margin || point.x>width-margin || point.y<top+margin || point.y>bottom-margin;
    }

    public void followFrame(SmoothFollowCamera.Frame frame) {
        if (frame == null) return;
        // NavigationCamera zoom is in osmdroid units; convert for MapLibre.
        double mapZoom = NavigationCamera.toMapLibreZoom(frame.zoom,
                activity.getResources().getDisplayMetrics().density) - viewportZoomOut(frame);
        pendingCamera = frame.center;
        pendingZoom = mapZoom;
        pendingBearing = frame.bearing;pendingPitch=frame.tilt;pendingTravelBearing=frame.travelBearing;
        pendingGps = frame.vehicle;
        ensureRegionalCoverage(frame.center);
        if (!ready()) return;
        setVehicle(frame.vehicle);
        setVehicleHeading(CameraHeadingPolicy.screenDegrees(
                frame.travelBearing, frame.bearing));
        boolean moved = lastRenderedCamera == null
                || GeoMath.distance(lastRenderedCamera, frame.center) > 0.32;
        boolean zoomed = !Double.isFinite(lastRenderedZoom)
                || Math.abs(mapZoom - lastRenderedZoom) > 0.006;
        double yaw = !Double.isFinite(lastRenderedBearing) ? 360
                : (frame.bearing - lastRenderedBearing + 540) % 360 - 180;
        boolean tilted = !Double.isFinite(lastRenderedTilt)
                || Math.abs(frame.tilt - lastRenderedTilt) > 0.16;
        if (!moved && !zoomed && Math.abs(yaw) < 0.28 && !tilted) return;
        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder()
                        .target(new LatLng(frame.center.lat, frame.center.lon))
                        .zoom(mapZoom)
                        .bearing((frame.bearing % 360 + 360) % 360)
                        .tilt(frame.tilt)
                        .build()));
        lastRenderedCamera = frame.center;
        lastRenderedZoom = mapZoom;
        lastRenderedBearing = frame.bearing;
        lastRenderedTilt = frame.tilt;
    }

    /**
     * Fit the whole route into the part of the screen the driver can see:
     * below the search bar (padTop) and above the preview panel (padBottom),
     * flat and north-up. Padding is in screen pixels.
     */
    public void previewRoute(List<GeoMath.Point> path, int padTop, int padBottom, int padSide) {
        setRoute(path);
        if(path==null||path.isEmpty())return;
        if(!ready()){pendingPreviewPadding=new int[]{padTop,padBottom,padSide};return;}
        pendingPreviewPadding=null;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (GeoMath.Point p : path) {
            minLat = Math.min(minLat, p.lat);
            maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon);
            maxLon = Math.max(maxLon, p.lon);
        }
        GeoMath.Point midpoint = new GeoMath.Point(
                (minLat + maxLat) / 2, (minLon + maxLon) / 2);
        if (maxLat - minLat < 1e-6 && maxLon - minLon < 1e-6) {
            setCamera(midpoint, 16, 0);
            return;
        }
        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(new LatLng(maxLat, maxLon))
                .include(new LatLng(minLat, minLon))
                .build();
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0, 0,
                    padSide, padTop, padSide, padBottom));
        } catch (RuntimeException error) {
            // For example if the map view has not been laid out yet.
            Log.w(LOG_TAG, "Route preview fit failed; using a wide view", error);
            setCamera(midpoint, 13, 0);
        }
        // The next driving frame must re-apply its own camera in full.
        lastRenderedCamera = null;
        lastRenderedZoom = Double.NaN;
        lastRenderedBearing = Double.NaN;
        lastRenderedTilt = Double.NaN;
    }

    public void setTraffic(boolean visible) {
        boolean changed=trafficEnabled!=visible;trafficEnabled = visible;
        if(changed&&requestProviderStyle()){loadStyle();return;}
        if (!ready()) return;
        org.maplibre.android.style.layers.Layer layer =
                map.getStyle() == null ? null :
                        map.getStyle().getLayer("tomtom-traffic-flow");
        if (layer != null) layer.setProperties(
                PropertyFactory.visibility("none"));
    }

    public void prefetch(GeoMath.Point point) {
        if (point != null) tiles.prefetchAround(point.lat, point.lon);
    }
    public void prefetchRoute(List<GeoMath.Point> ahead) { tiles.prefetchRoute(ahead); }
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
