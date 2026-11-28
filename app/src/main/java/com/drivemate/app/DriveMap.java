package com.drivemate.app;

import android.os.Bundle;
import android.view.View;
import java.util.List;

/**
 * The map display, whichever engine draws it. Routing, guidance, search and the screens never depend on the engine:
 * they talk to this interface only. Engines: {@link MapLibreMapController} (GPU, default),
 * {@link NativeMapController} (DriveMate's own Canvas renderer) and DriveMate's own GPU renderer while it is built.
 */
interface DriveMap {
    interface Listener { void status(RoadDisplayHealth.State state, int count); }

    View view();
    /** Short name shown in Settings and diagnostics. */
    String engineName();

    void setOnUserGesture(Runnable r);
    void warmArea(GeoMath.Point p);
    boolean nightMode();
    boolean hasRenderedRoads();
    /** Zoom in DriveMate units (256-pixel tiles in physical pixels), the same for every engine. */
    double currentZoom();
    void setVisible(boolean v);
    void setDebug(boolean on);
    String geometryDescription();
    String routeAlignmentDiagnostic();
    void setHighDetailEnabled(boolean enabled);
    void setBuildings3D(boolean enabled);
    void setNavigationDetail(boolean navigating);
    void setNightMode(boolean v);

    void setCamera(GeoMath.Point p, double z, double bearing);
    void setBrowseCamera(GeoMath.Point p, double z, double bearing, double pitch, double vehicleBearing);
    void followFrame(SmoothFollowCamera.Frame f);
    void setFollowViewport(int top, int bottom, GeoMath.Point junction, List<GeoMath.Point> context);
    void previewRoute(List<GeoMath.Point> p, int top, int bottom, int side);
    void clearJunctionFraming();

    void setVehicle(GeoMath.Point p);
    void setUnsnappedVehicle(GeoMath.Point p, double bearing);
    void setConfirmedRouteProgress(double p);
    void setManeuverOffsets(double a, double b);
    void setManeuverOffset(double a);
    void updateCurrentRoad(GeoMath.Point p);
    void clearCurrentRoad();
    void setBuildingContext(String endpoint, String source);
    void setDestination(GeoMath.Point p);
    void setDestinationLabel(String label);
    void setEntrance(GeoMath.Point p);
    void setParkedCar(GeoMath.Point p);
    void setRouteEvents(List<RouteIntelligence.SpeedSection> speeds, List<RouteIntelligence.TrafficSection> traffic, boolean metric, long fetchedAt);
    void setTraffic(boolean v);
    /** Lane data along the route: the map shows a lane board at the next turn where lanes are trusted. */
    void setLanes(List<RouteIntelligence.LaneSection> lanes);
    boolean supportsLiveIncidents();
    void setLiveIncidents(List<LiveTrafficIncidents.Event> rows, long at);
    void setSharedRoute(NativeMapScene scene, List<GeoMath.Point> path);
    void setSharedRoute(NativeMapScene scene, List<GeoMath.Point> path, String provider);
    void setBrowseGeometry(NativeMapScene scene);
    void prefetch(GeoMath.Point p);
    void prefetchRoute(List<GeoMath.Point> ahead);
    String cacheStats();
    /** Route offsets (metres) of the speed-limit signs currently on the map; for the drive lab's checks. */
    double[] limitMarkerOffsets();

    void onStart();
    void onResume();
    void onPause();
    void onStop();
    void onLowMemory();
    void onSaveInstanceState(Bundle state);
    void onDestroy();
}
