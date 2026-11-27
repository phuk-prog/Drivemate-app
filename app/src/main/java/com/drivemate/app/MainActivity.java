package com.drivemate.app;

import android.Manifest;
import android.media.AudioAttributes;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Paint;
import java.text.SimpleDateFormat;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.Uri;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground driving navigation. Real GPS fixes, TomTom traffic-aware route
 * planning, spoken maneuvers, distance/ETA, and automatic off-route rerouting.
 * This first build intentionally does not claim offline/background navigation.
 */
public final class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_PERMISSION = 10;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 13;
    private static final String PREFS = "drivemate_settings";
    private static final int BLUE = Color.rgb(24, 110, 235);
    private static final int DARK = Color.rgb(10, 39, 59);
    private static final int CYAN = Color.rgb(7, 214, 235);
    private static final int VOICE_SEARCH_REQUEST = 12;
    private static final long ROUTE_REFRESH_MS = 300_000L;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    /** Live search suggestions run apart from routing so typing is never queued behind a route calculation. */
    private final ExecutorService suggestWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService packWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService incidentWorker=Executors.newSingleThreadExecutor();
    private long lastIncidentRequest;
    private GeoMath.Point lastIncidentCentre;
    private int incidentGeneration;
    private boolean incidentBusy;
    private boolean downloadingLocalPack;
    private final Handler gpsHandler = new Handler(Looper.getMainLooper());
    private boolean gpsLost;
    private final Runnable gpsWatchdog = new Runnable() {
        @Override public void run() {
            if(upcomingDialog!=null&&!canInspectGuidance())dismissUpcoming();
            if ((active||freeDriving) && currentLocation != null
                    && System.currentTimeMillis() - currentLocation.getTime() > LocationThresholds.GPS_LOST_AFTER_MS) {
                if (!gpsLost) {
                    gpsLost = true;
                    setStatus("GPS signal lost · waiting for a fresh fix");
                    clearLiveIncidents();
                    if(active)suspendPositionGuidance("GPS LOST · WAIT FOR SIGNAL","Waiting for a fresh position");
                    else if(freeDriving)clearFreeRoadContext();
                    if (speedText != null) speedText.setText("GPS —");
                    if (speedLimitText != null) speedLimitText.setVisibility(View.GONE);
                    if (upcomingSpeedText != null) upcomingSpeedText.setVisibility(View.GONE);
                    if(upcomingRoadsideText!=null)upcomingRoadsideText.setVisibility(View.GONE);
                    if (laneGuidanceText != null) laneGuidanceText.setVisibility(View.GONE);
                    if (trafficNoticeText != null) trafficNoticeText.setVisibility(View.GONE);
                    saySystemWarning("GPS signal lost. Waiting for location.");
                }
            }
            if(active&&!guidancePositionConfirmed&&!joiningRoute&&lastConfirmedMetricsAt>=0
                    && !RoadTestDisplayPolicy.retainMetrics(lastConfirmedMetricsAt,SystemClock.elapsedRealtime())){
                lastConfirmedMetricsAt=-1;
                setTripMetricLabels(false);
                if(tripEtaValue!=null){tripEtaValue.setText("—");tripEtaValue.setContentDescription("ETA unavailable");}
                if(tripMinutesValue!=null){tripMinutesValue.setText("—");tripMinutesValue.setContentDescription("Time unavailable");}
                if(tripDistanceValue!=null){tripDistanceValue.setText("—");tripDistanceValue.setContentDescription("Distance unavailable");}
            }
            if (active && !hasNetwork()) {
                if (!networkLost) {
                    networkLost = true;
                    lastNetworkWarning = SystemClock.elapsedRealtime();
                    setStatus("Internet unavailable · following cached on-screen route");
                    saySystemWarning("Internet connection lost. Continue carefully.");
                }
            } else if (active && networkLost) {
                networkLost = false;
                setStatus("Internet restored · route updates available");
            }
            gpsHandler.postDelayed(this, 5000);
        }
    };
    /**
     * The Android display vsync clock drives visual interpolation, independently
     * of the ~1Hz GPS fixes. GPU camera updates are capped near 30fps;
     * the legacy raster engine remains near 9fps to avoid tile redraw stalls.
     * Choreographer avoids the timer drift of repeated Handler.postDelayed.
     */
    private long lastVisualTickMs;
    private long motionStatsStartMs;
    private int motionFrames;
    private final android.view.Choreographer.FrameCallback smoothFrameClock =
            new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!active || !followLocation || smoothFollow == null || isFinishing()) {
                smoothLoopRunning = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            long interval = showingVector() ? 32 : 110;
            if (lastVisualTickMs == 0 || now - lastVisualTickMs >= interval) {
                lastVisualTickMs = now;
                SmoothFollowCamera.Frame frame = smoothFollow.frame(now);
                if (frame != null) {
                    lastCameraBearing = frame.bearing;
                    if(drivingCompass!=null)drivingCompass.setRotation((float)-frame.bearing);
                    lastTravelBearing = frame.travelBearing;
                    if(vectorMap!=null)vectorMap.followFrame(frame);
                    motionFrames++;
                }
                smoothTick++;
                if (motionStatsStartMs == 0) motionStatsStartMs = now;
                if (now - motionStatsStartMs >= 10000) {
                    int fps = (int) Math.round(motionFrames * 1000.0
                            / Math.max(1, now - motionStatsStartMs));
                    // Performance metrics never contain GPS coordinates or
                    // destination information.
                    android.util.Log.i("DriveMateCamera",
                            "Display camera updates/sec=" + fps
                                    + ", engine=" + (showingVector() ? "gpu" : "raster"));
                    motionFrames = 0;
                    motionStatsStartMs = now;
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startSmoothLoop() {
        if (smoothLoopRunning || !foreground || !active
                || !followLocation || smoothFollow == null) return;
        smoothLoopRunning = true;
        smoothTick = 0;
        lastVisualTickMs = 0;
        motionFrames = 0;
        motionStatsStartMs = 0;
        android.view.Choreographer.getInstance().postFrameCallback(smoothFrameClock);
    }

    private void stopSmoothLoop() {
        android.view.Choreographer.getInstance().removeFrameCallback(smoothFrameClock);
        smoothLoopRunning = false;
    }

    private void disengageFollowForGesture() {
        if (!active || !followLocation) return;
        followLocation = false;
        stopSmoothLoop();
        updateFollowButton();
        if (!mapGestureWasNotified) {
            mapGestureWasNotified = true;
            Toast.makeText(this, "Map follow paused · tap the highlighted recenter button",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateFollowButton() {
        if (followButton == null) return;
        boolean paused = active && !followLocation;
        followButton.setText(paused ? "↪" : "◎");
        followButton.setContentDescription(paused
                ? "Map following paused. Tap to recenter and resume navigation."
                : "Centre map on my GPS position");
        followButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                paused ? CYAN : DARK));
        followButton.setTextColor(paused ? DARK : Color.WHITE);
    }
    private NativeMapController vectorMap;
    private Bundle vectorSavedState;
    private boolean useLocalMap = true;
    private boolean vectorRoadsReady;
    private boolean autoFallback;
    private View speedBadgeView;
    private boolean keyboardOpen;
    private boolean homeMode = true;
    private boolean freeDriving;
    private boolean advanceSpeedVoice = true;
    private double freeDriveBearing=Double.NaN;
    private final FreeDriveRoadMatch freeRoadMatch=new FreeDriveRoadMatch();
    private NativeRegionalRouter.RoadContext freeRoadContext;
    private long freeContextRequestedAt;
    private int freeContextGeneration;
    private boolean freeContextBusy;
    private int freeLastLimit=-1;
    private long freeAdvanceNode=-1,freeLastLimitAt;
    private String freeContextEndpoint="";
    private LinearLayout homePanel;
    private LinearLayout navigationPanel;
    private LinearLayout topMapOverlay;
    private LinearLayout searchBar;
    private LinearLayout drivingTopPanel;
    private LinearLayout brandHeader;
    private LinearLayout tripMetricsRow;
    private LinearLayout drivingTurnCard;
    private TextView drivingCompass;
    private Button drivingMore;
    private Button overviewButton;
    private long lastConfirmedMetricsAt=-1;
    private String confirmedEta="",confirmedMinutes="",confirmedDistance="";
    private boolean drivingControlsExpanded;
    private LinearLayout previewTurnRow;
    private LinearLayout previewDetailsRow;
    private LinearLayout bottomActionRow;
    private LinearLayout.LayoutParams bottomActionsParams;
    private TextView tripEtaValue;
    private TextView tripMinutesValue;
    private TextView tripDistanceValue;
    private TextView topInstructionText;
    private TextView topRoadText;
    private TextView lanePromptText;
    private String currentLanePrompt = "";
    private int spokenLaneIndex = -1;
    private String spokenLanePrompt = "";
    private int traceInstructionIndex = -1;
    private float[] currentRoundaboutTrace;
    private List<GeoMath.Point> currentJunctionPath = java.util.Collections.emptyList();
    private JunctionManeuverView junctionPathView;
    private AlertDialog upcomingDialog;
    private int reassuranceIndex=-1;
    /** Large distance to the next manoeuvre, on its own line above the road. */
    private TextView topDistanceText;
    private TextView topTurnTitle;
    private TextView topNextTurnText;
    private TurnArrowView topTurnArrow;
    private TextView verifiedJunctionRoad;
    private LinearLayout recentList;
    private Button homeTile;
    private Button workTile;
    private FrameLayout.LayoutParams speedBadgePos;
    private FrameLayout.LayoutParams gpsButtonPos;
    private AutoCompleteTextView destinationInput;
    private final List<TomTomApi.Destination> suggestionMatches = new ArrayList<>();
    private Button trafficButton;
    private Button voiceButton;
    private Button followButton;
    private TextView banner;
    private TextView instructionText;
    private TurnArrowView maneuverIcon;
    private TextView nextTurnText;
    private TextView journeyTimeText;
    private TextView gpsAccuracyText;
    private TextView distanceText;
    private TextView etaText;
    private TextView speedText;
    private TextView speedLimitText;
    private LaneGuidanceView laneGuidanceText;
    private NavigationMatch navigationMatch;
    private final MapReview mapReview = new MapReview();
    private TomTomApi.Route comparisonRoute;
    private boolean comparisonBusy;
    private int reviewGeneration;
    private long comparisonRequestedAt;
    private long lastRoutePrefetchAt = -1;
    private TextView trafficNoticeText;
    private TextView arrivalHintText;
    private Button findParkingButton;
    private Button previewParkingButton;
    private final ArrivalPolicy arrivalPolicy = new ArrivalPolicy();
    private LinearLayout rerouteSuggestionRow;
    private TextView rerouteSuggestionText;
    private TomTomApi.Route pendingRoute;
    private long pendingRouteAtMs;
    private GeoMath.Point pendingRouteOrigin;
    private int pendingSavingSeconds;
    private int rerouteMode = 1; // 0 automatic, 1 ask first
    private int speedToleranceMph = 5;
    private long lastSpeedWarningAt;
    private TextView upcomingSpeedText, upcomingRoadsideText;
    private final RouteRoadsideAwareness roadsideAwareness=new RouteRoadsideAwareness();
    private final SpeedChangeAwareness speedChangeAwareness = new SpeedChangeAwareness();
    private long lastTrafficWarningAt;
    private double lastTrafficWarningStart = -1;
    private Button stopButton;
    private Button startButton;
    private Button saveButton;
    private Button repeatButton;
    private Button refreshButton;
    private Button unitsButton;
    private TextView routeSummary;
    private DestinationStore places;
    private boolean metricUnits;
    private String nextInstruction = "";
    private final VehicleFilter vehicleFilter = new VehicleFilter();
    private int voiceMode = DrivingVoicePolicy.NORMAL;
    private int cameraMode = DrivingDisplayPolicy.CAMERA_AUTO_3D;
    private int themePreference = DrivingDisplayPolicy.DEFAULT_THEME;
    private boolean nightDisplay;
    private boolean themeApplied;
    private boolean hazardWarningEnabled = true;
    private boolean speedWarningEnabled = true;
    /** Extruded building blocks on the map (Settings). */
    private boolean buildings3d = true;
    private final List<TextView> tripMetricCaptions = new ArrayList<>();
    private float guidanceVolume = 0.85f;
    private boolean closeZoom;
    private boolean highDetailOnline = true;
    private int previousInstructionIndex;
    private double stabilizedAlong = -1;
    private double observedSpeed;
    private long lastNetworkWarning;
    private boolean networkLost;
    private long lastRerouteAttempt;
    /** Consecutive failed reroutes while navigating; slows retries. Reset when a route installs. */
    private int rerouteFailures;
    /** "Recalculating" has been spoken for this off-route episode. */
    private boolean rerouteAnnounced;

    private LocationManager locationManager;
    private Location currentLocation;
    /** Last fix that passed FixQualityPolicy; the gate compares every new fix to it. */
    private FixQualityPolicy.Sample lastAcceptedFix;
    /** Whether the current location registration uses the navigating rate. */
    private boolean subscribedForNavigation;
    private boolean placesWarmed;
    /** Pre-fetch map tiles around Home and Work once per session, so the map is already there when needed. */
    private void warmSavedPlaces() {
        if (vectorMap == null || places == null) return;
        TomTomApi.Destination h = places.home(), w = places.work();
        if (h != null) vectorMap.warmArea(h.point);
        if (w != null) vectorMap.warmArea(w.point);
    }
    /** Start was tapped before a fresh GPS fix existed: start as soon as one arrives. */
    private boolean startWhenFix;
    private final Runnable startWaitTimeout = () -> {
        if (startWhenFix) {
            startWhenFix = false;
            showError("No fresh GPS position after 20 seconds. Check Precise Location is on and try again with a clear view of the sky.");
        }
    };
    /**
     * Full-rate location updates are needed while navigating, while a route is on screen, and while
     * Start is waiting for a fix. The lighter idle rate asks for a new fix only after 5 m of
     * movement, so a stationary driver on the route preview got no fixes and Start refused.
     */
    private boolean wantFastLocation() { return active || route != null || startWhenFix; }
    private boolean resubscribing;
    private boolean fusedActive;
    /** Latest camera angles, kept only for the shareable diagnostics report. */
    private double lastCameraBearing = Double.NaN;
    private double lastTravelBearing = Double.NaN;
    private double lastOffRouteMetres = Double.NaN;
    private String lastMatchReason="not matched";
    private boolean guidancePositionConfirmed;
    private boolean confirmedSinceRouteInstall;
    private boolean joiningRoute;
    private long lastJoinCameraAt=-1;
    /** Real Home/Work drive times from TomTom, refreshed at most every 5 minutes. */
    private String homeEtaLabel;
    /** Whether the highlighted junction is a roundabout (its diagram stays visible). */
    private String workEtaLabel;
    private long quickEtaFetchedAt;
    /** Display-only estimate of the vehicle position during short GPS gaps. */
    private final DeadReckoner deadReckoner = new DeadReckoner();
    /** Route-following estimate for longer gaps (tunnels); display only. */
    private final RouteDeadReckoner routeBridge = new RouteDeadReckoner();
    private final LocationPipeline locationPipeline = new LocationPipeline();
    private final HeadingFusion headingFusion = new HeadingFusion();
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private boolean rotationSensorOn;
    private long estimateStartedAt = -1;
    private boolean showingEstimate;
    private final SensorEventListener rotationListener = new SensorEventListener() {
        private final float[] matrix = new float[9], remapped = new float[9], angles = new float[3];
        @Override public void onSensorChanged(SensorEvent event) {
            // Phone assumed upright in a car mount (screen towards the driver, camera forward).
            // Not validated on a device yet; HeadingFusion expires a stale offset.
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
            SensorManager.getOrientation(remapped, angles);
            headingFusion.onSensorAzimuth(Math.toDegrees(angles[0]));
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
    private final Runnable deadReckonTick = new Runnable() {
        @Override public void run() {
            if (foreground && displayGps != null) {
                long now = System.currentTimeMillis();
                RouteDeadReckoner.Estimate bridged = active && route != null ? routeBridge.estimate(now) : null;
                if (bridged != null) {
                    if (estimateStartedAt < 0) estimateStartedAt = now;
                    showingEstimate = true;
                    if (vectorMap != null && useLocalMap) vectorMap.setVehicle(bridged.point);
                    if (gpsAccuracyText != null) {
                        gpsAccuracyText.setText((bridged.inTunnel ? "Tunnel · " : "")
                                + "Estimated position ±" + Math.round(bridged.uncertaintyMetres) + " m · no GPS");
                    }
                } else {
                    endEstimate(now);
                    GeoMath.Point estimate = deadReckoner.estimate(now);
                    // While guidance runs, SmoothFollowCamera owns the marker position.
                    if (estimate != null && (!active || smoothFollow == null)) {
                        if (vectorMap != null && useLocalMap) vectorMap.setVehicle(estimate);
                    }
                }
            }
            gpsHandler.postDelayed(this, 500);
        }
    };
    private void endEstimate(long now) {
        if (estimateStartedAt >= 0) locationPipeline.recordBridge(now - estimateStartedAt);
        estimateStartedAt = -1;
        showingEstimate = false;
    }
    private void startRotationSensor() {
        if (sensorManager == null || rotationSensor == null || rotationSensorOn) return;
        rotationSensorOn = sensorManager.registerListener(rotationListener, rotationSensor,
                SensorManager.SENSOR_DELAY_UI);
    }
    private void stopRotationSensor() {
        if (sensorManager != null && rotationSensorOn) sensorManager.unregisterListener(rotationListener);
        rotationSensorOn = false;
    }
    /** Hand the active route to the bridge: geometry, tunnel spans, manoeuvre hold points. */
    private void configureRouteBridge(TomTomApi.Route r) {
        if (r == null || r.points.size() < 2) { routeBridge.setRoute(null); return; }
        routeBridge.setRoute(r.points);
        routeBridge.setTunnelSpans(r.nativeScene == null ? null
                : TunnelSpans.fromShapes(r.points, r.nativeScene.shapes));
        double[] holds = new double[r.instructions.size()];
        for (int i = 0; i < holds.length; i++) holds[i] = r.instructions.get(i).routeOffsetMetres;
        routeBridge.setHoldPoints(holds);
    }
    /** Speed to ease toward during a gap: the mapped limit at this point, else 0 (hold fix speed). */
    private double expectedSpeedMps(TomTomApi.Route r, GeoMath.Point at) {
        if (r == null || r.speedSections == null || r.speedSections.isEmpty()) return 0;
        double along = GeoMath.project(r.points, at).alongMetres;
        for (RouteIntelligence.SpeedSection section : r.speedSections) {
            if (along >= section.start && along <= section.end) return section.kmh / 3.6;
        }
        return 0;
    }
    /** Satellites used in the current GPS fix, or -1 until the first GNSS report. */
    private int satellitesUsed = -1;
    private int l5SatellitesUsed = -1;
    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0, l5 = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (!status.usedInFix(i)) continue;
                used++;
                if (status.hasCarrierFrequencyHz(i) && GnssBands.isL5(status.getCarrierFrequencyHz(i))) l5++;
            }
            satellitesUsed = used;
            l5SatellitesUsed = l5;
        }
    };
    private long lastSpeechAt=-1;
    private List<GeoMath.Point> junctionFramePoints = java.util.Collections.emptyList();
    private int highlightedJunctionIndex = -1;
    private TextToSpeech speech;
    private boolean speechReady;
    private boolean trafficEnabled = false;
    private String incidentEnhancementKey="";
    private boolean followLocation = true;
    private boolean routing;
    private boolean active;
    private String apiKey = "";
    private TomTomApi.Destination destination;
    private TomTomApi.Route route;
    private long lastRouteRequest;
    private long offRouteSince;
    private long lastCameraUpdate;
    private GeoMath.Point lastCameraCenter;
    private GeoMath.Point displayGps;
    private MapStartupPolicy.Viewport startupMapView;
    private boolean locationSubscribed;
    private long lastMapAreaSavedAt;
    private SmoothFollowCamera smoothFollow;
    private boolean smoothLoopRunning;
    private boolean foreground;
    private int smoothTick;
    private boolean mapGestureWasNotified;
    private int requestGeneration;
    private int spokenDistanceIndex = -1;
    private int spokenCloseIndex = -1;
    private int spokenNowIndex = -1;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences settings = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = ""; // Native-only candidate: ignore saved external service credentials.
        metricUnits = settings.getBoolean("metric_units", false);
        voiceMode = settings.contains("voice_mode_v2")
                ? Math.max(0, Math.min(3, settings.getInt("voice_mode_v2", 1)))
                : DrivingVoicePolicy.fromLegacy(settings.getInt("speech_mode", 1),
                        settings.getBoolean("voice", true));
        guidanceVolume = Math.max(0.1f, Math.min(1f, settings.getFloat("speech_volume", 0.85f)));
        rerouteMode = Math.max(0, Math.min(1, settings.getInt("reroute_mode", 1)));
        speedToleranceMph = Math.max(0, Math.min(15, settings.getInt("speed_tolerance_mph", 5)));
        cameraMode = settings.contains("camera_mode")
                ? Math.max(0, Math.min(2, settings.getInt("camera_mode", 0)))
                : settings.getBoolean("north_up", false)
                        ? DrivingDisplayPolicy.CAMERA_NORTH_2D
                        : DrivingDisplayPolicy.CAMERA_AUTO_3D;
        // Light unless the driver has chosen Dark or Automatic.
        themePreference = DrivingDisplayPolicy.validTheme(
                settings.getInt("map_theme", DrivingDisplayPolicy.DEFAULT_THEME));
        hazardWarningEnabled = settings.getBoolean("hazard_voice_alerts", true);
        speedWarningEnabled = settings.getBoolean("speed_voice_alerts", true);
        advanceSpeedVoice = settings.getBoolean("advance_speed_voice", true);
        buildings3d = settings.getBoolean("buildings_3d", true);
        closeZoom = settings.getBoolean("close_zoom", true);
        highDetailOnline = settings.getBoolean("high_detail_online", true);
        places = new DestinationStore(this);
        incidentEnhancementKey=settings.getString("incident_enhancement_key","");
        trafficEnabled=NativeEnhancementPolicy.enabled(settings.getBoolean("incident_enhancement_consent",false),incidentEnhancementKey);
        useLocalMap = true;
        vectorSavedState = savedInstanceState;
        startupMapView = MapStartupPolicy.initial(
                settings.getFloat("last_map_lat", Float.NaN),
                settings.getFloat("last_map_lon", Float.NaN),
                settings.getLong("last_map_at", 0L),
                System.currentTimeMillis());
        buildScreen();
        applyTheme();
        updateVoiceButton();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (sensorManager != null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        speech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && speech != null) {
                speech.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                int language = speech.setLanguage(Locale.UK);
                speechReady = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
                speech.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) { runOnUiThread(() -> speechFinished()); }
                    @Override public void onError(String utteranceId) { runOnUiThread(() -> speechFinished()); }
                    @Override public void onStop(String utteranceId, boolean interrupted) { runOnUiThread(() -> speechFinished()); }
                });
            }
            if (!speechReady && !voiceUnavailableShown && voiceMode != DrivingVoicePolicy.SILENT) {
                voiceUnavailableShown = true;
                runOnUiThread(() -> setStatus("Voice guidance unavailable · install a UK English text-to-speech voice"));
            }
        });
        // TomTom credentials optional; configure independent services in Settings.
        if (!apiKey.isEmpty()) configureTiles();
        ensureLocationPermission();
    }


    /**
     * Map-first DriveMate home screen, following the supplied reference:
     * navy header, cyan road mark, white search, live map, speed/GPS badges,
     * Home/Work/Favourites and recent destinations in a dark bottom panel.
     */
    private void buildScreen() {
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(235, 241, 246));
        FrameLayout mapHost=new FrameLayout(this);
        frame.addView(mapHost,new FrameLayout.LayoutParams(-1,-1));
        vectorMap=new NativeMapController(this,(status,roads)->{
            vectorRoadsReady=status==RoadDisplayHealth.State.READY;
            if(!active&&route==null&&status==RoadDisplayHealth.State.FAILED)
                setStatus("DriveMate detail unavailable · regional coverage required");
            // The map recovered: clear the banner that said otherwise.
            else if(status==RoadDisplayHealth.State.READY&&banner!=null
                    &&banner.getText().toString().startsWith("DriveMate detail unavailable"))
                setStatus("Map ready");
        });
        mapHost.addView(vectorMap.view(),0,new FrameLayout.LayoutParams(-1,-1));
        useLocalMap=true;autoFallback=false;
        vectorMap.setOnUserGesture(this::disengageFollowForGesture);
        vectorMap.setCamera(startupMapView.center,startupMapView.zoom,0);
        applyHighDetailPolicy();updateMapVisibility();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setElevation(dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(15), dp(7), dp(14), dp(7));
        header.setBackgroundColor(DARK);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.drivemate_mark);
        logo.setContentDescription("DriveMate road logo");
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(51)));
        LinearLayout branding = new LinearLayout(this);
        branding.setOrientation(LinearLayout.VERTICAL);
        branding.setPadding(dp(4), 0, 0, 0);
        TextView brandName = text("DriveMate", 25, Color.WHITE, true);
        SpannableString brand = new SpannableString("DriveMate");
        brand.setSpan(new ForegroundColorSpan(Color.WHITE), 0, 5, 0);
        brand.setSpan(new ForegroundColorSpan(CYAN), 5, 9, 0);
        brandName.setText(brand);
        branding.addView(brandName);
        // The installed version, read from the package itself so it is always
        // the one actually running (never a hard-coded label).
        TextView subtitle = text("UK NAV  ·  v" + installedVersionName(), 10,
                Color.rgb(180, 222, 235), true);
        subtitle.setLetterSpacing(0.11f);
        branding.addView(subtitle);
        header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = chip("⚙");
        settings.setTextSize(23);
        settings.setContentDescription("DriveMate settings");
        settings.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(28, 68, 84)));
        settings.setTextColor(Color.WHITE);
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(49), dp(49)));
        top.addView(header, new LinearLayout.LayoutParams(-1, dp(68)));
        brandHeader = header;

        searchBar = new LinearLayout(this);
        LinearLayout search = searchBar;
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(10), dp(5), dp(8), dp(5));
        search.setBackground(rounded(Color.rgb(246, 251, 253), 16));
        search.setElevation(dp(5));
        TextView searchGlyph = text("⌕", 29, Color.rgb(95, 128, 142), false);
        searchGlyph.setGravity(Gravity.CENTER);
        search.addView(searchGlyph, new LinearLayout.LayoutParams(dp(36), -1));
        destinationInput = new AutoCompleteTextView(this);
        destinationInput.setThreshold(1);
        destinationInput.setDropDownBackgroundDrawable(rounded(Color.WHITE, 12));
        destinationInput.setSingleLine(true);
        destinationInput.setTextSize(17);
        destinationInput.setPadding(dp(4), 0, 0, 0);
        destinationInput.setBackgroundColor(Color.TRANSPARENT);
        destinationInput.setHint("Where to?");
        destinationInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        destinationInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        destinationInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchDestination();
                return true;
            }
            return false;
        });
        search.addView(destinationInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        destinationInput.setOnFocusChangeListener((v, focused) -> {
            if (focused) refreshSearchSuggestions();
        });
        destinationInput.setOnClickListener(v -> {
            refreshSearchSuggestions();
            if (destinationInput.length() > 0) destinationInput.showDropDown();
        });
        destinationInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                refreshSearchSuggestions();
                requestTypeahead(value.toString());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        Button clearSearch = chip("×");
        clearSearch.setContentDescription("Clear destination search");
        clearSearch.setTextSize(22);
        clearSearch.setOnClickListener(v -> {
            destinationInput.setText("");
            destinationInput.requestFocus();
        });
        search.addView(clearSearch, new LinearLayout.LayoutParams(dp(34), dp(42)));
        Button mic = chip("🎙");
        mic.setContentDescription("Search by voice");
        mic.setTextSize(19);
        mic.setOnClickListener(v -> voiceSearch());
        search.addView(mic, new LinearLayout.LayoutParams(dp(44), dp(43)));
        // No separate Find button: the keyboard's search key runs the same search.
        LinearLayout.LayoutParams searchPos = new LinearLayout.LayoutParams(-1, dp(60));
        searchPos.setMargins(dp(10), dp(11), dp(10), dp(7));
        top.addView(search, searchPos);

        drivingTopPanel = createDrivingTopPanel();
        LinearLayout.LayoutParams guidanceTopPos = new LinearLayout.LayoutParams(-1, -2);
        guidanceTopPos.setMargins(dp(10), dp(6), dp(10), dp(6));
        top.addView(drivingTopPanel, guidanceTopPos);
        drivingTopPanel.setVisibility(View.GONE);
        topMapOverlay = top;

        FrameLayout.LayoutParams topPos = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        frame.addView(top, topPos);

        // Driving controls become visible on the navigation panel, not on home.
        navigationPanel = new LinearLayout(this);
        navigationPanel.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.setPadding(dp(15), dp(12), dp(15), dp(12));
        navigationPanel.setBackground(rounded(DARK, 22));
        navigationPanel.setElevation(dp(10));
        banner = text("Ready to navigate", 12, CYAN, true);
        navigationPanel.addView(banner);
        previewTurnRow = new LinearLayout(this);
        LinearLayout turnRow = previewTurnRow;
        turnRow.setOrientation(LinearLayout.HORIZONTAL);
        turnRow.setGravity(Gravity.CENTER_VERTICAL);
        maneuverIcon = new TurnArrowView(this);
        maneuverIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams arrowSize =
                new LinearLayout.LayoutParams(dp(52), dp(52));
        arrowSize.rightMargin = dp(9);
        turnRow.addView(maneuverIcon, arrowSize);
        instructionText = text("Search for a destination, then preview the route.", 19,
                Color.WHITE, true);
        LinearLayout.LayoutParams instructionPos = new LinearLayout.LayoutParams(0, -2, 1);
        instructionPos.leftMargin = dp(2);
        turnRow.addView(instructionText, instructionPos);
        LinearLayout.LayoutParams turnRowPos = new LinearLayout.LayoutParams(-1, -2);
        turnRowPos.topMargin = dp(7);
        navigationPanel.addView(turnRow, turnRowPos);
        nextTurnText = text("", 13, Color.rgb(191, 225, 236), false);
        nextTurnText.setVisibility(View.GONE);
        navigationPanel.addView(nextTurnText);
        routeSummary = text("", 13, Color.rgb(194, 226, 238), false);
        routeSummary.setVisibility(View.GONE);
        navigationPanel.addView(routeSummary);
        previewDetailsRow = new LinearLayout(this);
        LinearLayout details = previewDetailsRow;
        details.setGravity(Gravity.CENTER_VERTICAL);
        distanceText = text("Distance: —", 13, Color.WHITE, false);
        details.addView(distanceText, new LinearLayout.LayoutParams(0, -2, 1));
        journeyTimeText = text("Time: —", 13, Color.WHITE, false);
        details.addView(journeyTimeText, new LinearLayout.LayoutParams(0, -2, 1));
        etaText = text("ETA: —", 13, Color.WHITE, false);
        details.addView(etaText, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams detailsPos = new LinearLayout.LayoutParams(-1, -2);
        detailsPos.topMargin = dp(10);
        navigationPanel.addView(details, detailsPos);
        // ETA, journey duration and distance belong at the bottom while driving,
        // leaving the road and the next manoeuvre unobstructed above.
        navigationPanel.addView(tripMetricsRow, new LinearLayout.LayoutParams(-1, dp(57)));
        tripMetricsRow.setVisibility(View.GONE);
        rerouteSuggestionRow = new LinearLayout(this);
        rerouteSuggestionRow.setGravity(Gravity.CENTER_VERTICAL);
        rerouteSuggestionRow.setBackground(rounded(Color.rgb(31, 75, 93), 12));
        rerouteSuggestionRow.setPadding(dp(5), dp(3), dp(5), dp(3));
        rerouteSuggestionText = text("Faster route", 12, Color.WHITE, true);
        rerouteSuggestionRow.addView(rerouteSuggestionText,
                new LinearLayout.LayoutParams(0, -2, 1));
        Button acceptRouteButton = chip("Switch");
        acceptRouteButton.setContentDescription("Accept suggested alternate route");
        acceptRouteButton.setOnClickListener(v -> acceptPendingRoute());
        rerouteSuggestionRow.addView(acceptRouteButton,
                new LinearLayout.LayoutParams(dp(71), dp(40)));
        Button keepRouteButton = chip("Keep");
        keepRouteButton.setContentDescription("Keep current route");
        keepRouteButton.setOnClickListener(v -> clearPendingRoute());
        LinearLayout.LayoutParams keepPos = new LinearLayout.LayoutParams(dp(59), dp(40));
        keepPos.leftMargin = dp(4);
        rerouteSuggestionRow.addView(keepRouteButton, keepPos);
        rerouteSuggestionRow.setVisibility(View.GONE);
        navigationPanel.addView(rerouteSuggestionRow,
                new LinearLayout.LayoutParams(-1, -2));
        gpsAccuracyText = text("", 11, Color.rgb(190, 219, 230), false);
        navigationPanel.addView(gpsAccuracyText);
        bottomActionRow = new LinearLayout(this);
        LinearLayout navActions = bottomActionRow;
        navActions.setGravity(Gravity.CENTER);
        startButton = chip("▶ Start");
        startButton.setVisibility(View.GONE);
        startButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CYAN));
        startButton.setOnClickListener(v -> startNavigation());
        navActions.addView(startButton, new LinearLayout.LayoutParams(0, dp(45), 2));
        saveButton = chip("★ Save");
        saveButton.setVisibility(View.GONE);
        saveButton.setOnClickListener(v -> saveCurrentDestination());
        navActions.addView(saveButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        previewParkingButton = chip("🅿 Nearby");
        previewParkingButton.setContentDescription("Find verified parking near destination");
        previewParkingButton.setVisibility(View.GONE);
        previewParkingButton.setOnClickListener(v -> findNearbyParking());
        navActions.addView(previewParkingButton,
                new LinearLayout.LayoutParams(0, dp(45), 1));
        // Driving controls: distinct icon plus a one-word label each, dark to
        // match the theme. Previously two buttons shared the same speaker icon.
        android.content.res.ColorStateList driveControl =
                android.content.res.ColorStateList.valueOf(Color.rgb(28, 68, 84));
        repeatButton = chip("🗣 Repeat");
        repeatButton.setBackgroundTintList(driveControl);
        repeatButton.setTextColor(Color.WHITE);
        repeatButton.setContentDescription("Repeat next spoken turn");
        repeatButton.setVisibility(View.GONE);
        repeatButton.setOnClickListener(v -> {
            if (!guidancePositionConfirmed || gpsLost || currentLocation==null || System.currentTimeMillis()-currentLocation.getTime()>10000) showError("Position not confirmed · check road signs.");
            else if (nextInstruction.isEmpty()) showError("No upcoming direction yet.");
            else if (voiceMode == DrivingVoicePolicy.SILENT)
                Toast.makeText(this, "Enable voice guidance to repeat a turn",
                        Toast.LENGTH_SHORT).show();
            else { voiceQueue.reset(); speak(nextInstruction, VoiceQueue.URGENT); } // Explicit repeat works in Alerts only mode and is never dropped as a duplicate.
        });
        navActions.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        refreshButton = chip("↻ Reroute");
        refreshButton.setBackgroundTintList(driveControl);
        refreshButton.setTextColor(Color.WHITE);
        refreshButton.setContentDescription("Refresh traffic-aware route");
        refreshButton.setVisibility(View.GONE);
        refreshButton.setOnClickListener(v -> planRoute(false));
        navActions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        voiceButton = chip("🔊 Voice");
        voiceButton.setBackgroundTintList(driveControl);
        voiceButton.setTextColor(Color.WHITE);
        voiceButton.setContentDescription("Change navigation voice mode");
        voiceButton.setVisibility(View.GONE);
        voiceButton.setOnClickListener(v -> cycleVoiceMode());
        navActions.addView(voiceButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        bottomActionsParams = new LinearLayout.LayoutParams(-1, -2);
        bottomActionsParams.topMargin = dp(10);
        navigationPanel.addView(navActions, bottomActionsParams);
        stopButton = chip("End navigation");
        // Red so it reads as "leave navigation", with a confirmation while
        // driving so a mis-tap cannot silently end guidance.
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(198, 40, 40)));
        stopButton.setTextColor(Color.WHITE);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> {
            if (!active) {
                stopNavigation(false);
                return;
            }
            new AlertDialog.Builder(this).setTitle("End navigation?")
                    .setPositiveButton("End", (d, w) -> stopNavigation(false))
                    .setNegativeButton("Keep navigating", null).show();
        });
        // On a route the three compact controls occupy one row at the bottom.
        // The map is no longer covered by a large instruction/control sheet.
        navActions.addView(stopButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        drivingMore=chip("Controls");drivingMore.setTextColor(Color.WHITE);drivingMore.setBackgroundTintList(driveControl);
        drivingMore.setContentDescription("Show navigation controls");drivingMore.setVisibility(View.GONE);
        drivingMore.setOnClickListener(v->{drivingControlsExpanded=!drivingControlsExpanded;applyDrivingControls();});
        navActions.addView(drivingMore,new LinearLayout.LayoutParams(0,dp(45),1));
        overviewButton=chip("Route");overviewButton.setTextColor(Color.WHITE);overviewButton.setBackgroundTintList(driveControl);
        overviewButton.setContentDescription("View remaining route overview");overviewButton.setVisibility(View.GONE);
        overviewButton.setOnClickListener(v->showRemainingRouteOverview());
        navActions.addView(overviewButton,new LinearLayout.LayoutParams(0,dp(45),1));
        FrameLayout.LayoutParams navPanelPos = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        navPanelPos.setMargins(dp(8), 0, dp(8), dp(9));
        frame.addView(navigationPanel, navPanelPos);
        navigationPanel.setVisibility(View.GONE);

        homePanel = new LinearLayout(this);
        homePanel.setOrientation(LinearLayout.VERTICAL);
        homePanel.setPadding(dp(13), dp(12), dp(13), dp(10));
        homePanel.setBackground(rounded(DARK, 23));
        homePanel.setElevation(dp(9));
        TextView heading = text("YOUR DESTINATIONS", 12, Color.rgb(189, 227, 239), true);
        heading.setLetterSpacing(.08f);
        homePanel.addView(heading);

        LinearLayout tiles = new LinearLayout(this);
        homeTile = quickTile(R.drawable.ic_quick_home, "Home", "Not set");
        workTile = quickTile(R.drawable.ic_quick_work, "Work", "Not set");
        Button savedPlaces = quickTile(R.drawable.ic_quick_star, "Saved", "Places");
        homeTile.setOnClickListener(v -> openQuickDestination(true));
        homeTile.setOnLongClickListener(v -> { configureQuickDestination(true); return true; });
        workTile.setOnClickListener(v -> openQuickDestination(false));
        workTile.setOnLongClickListener(v -> { configureQuickDestination(false); return true; });
        savedPlaces.setOnClickListener(v -> showSavedDestinations());
        tiles.addView(homeTile, new LinearLayout.LayoutParams(0, dp(72), 1));
        LinearLayout.LayoutParams workPos = new LinearLayout.LayoutParams(0, dp(72), 1);
        workPos.setMargins(dp(6), 0, dp(6), 0);
        tiles.addView(workTile, workPos);
        tiles.addView(savedPlaces, new LinearLayout.LayoutParams(0, dp(72), 1));
        LinearLayout.LayoutParams tilesPos = new LinearLayout.LayoutParams(-1, -2);
        tilesPos.topMargin = dp(8);
        homePanel.addView(tiles, tilesPos);

        TextView recentTitle = text("RECENT", 12, Color.rgb(189, 227, 239), true);
        recentTitle.setLetterSpacing(.07f);
        LinearLayout.LayoutParams recentHeaderPos = new LinearLayout.LayoutParams(-1, -2);
        recentHeaderPos.topMargin = dp(10);
        homePanel.addView(recentTitle, recentHeaderPos);

        ScrollView recentScroll = new ScrollView(this);
        recentScroll.setFillViewport(false);
        recentScroll.setVerticalScrollBarEnabled(false);
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentScroll.addView(recentList);
        // Three visible recents; the list scrolls for the rest (up to 8 stored).
        LinearLayout.LayoutParams recentPos = new LinearLayout.LayoutParams(-1, dp(138));
        recentPos.topMargin = dp(2);
        homePanel.addView(recentScroll, recentPos);
        // Parking actions live in Settings, not on the home screen.
        FrameLayout.LayoutParams homePos = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        homePos.setMargins(dp(4), 0, dp(4), 0);
        frame.addView(homePanel, homePos);
        // Keep the recentre button just above the sheet, whatever its height.
        homePanel.addOnLayoutChangeListener((view, l2, t2, r2, b2, ol2, ot2, or2, ob2) -> {
            if (!homeMode || gpsButtonPos == null || followButton == null) return;
            int wanted = (b2 - t2) + dp(12);
            if (gpsButtonPos.bottomMargin != wanted) {
                gpsButtonPos.bottomMargin = wanted;
                followButton.requestLayout();
            }
        });

        // Current speed in a dark circle, with the posted limit as a UK-style
        // red roundel on its top-right edge. The roundel only appears when the
        // mapped limit is known; there is no "Limit —" placeholder.
        FrameLayout speedCluster = new FrameLayout(this);
        LinearLayout speedBadge = new LinearLayout(this);
        speedBadge.setOrientation(LinearLayout.VERTICAL);
        speedBadge.setGravity(Gravity.CENTER);
        GradientDrawable speedBackground = rounded(DARK, 100);
        speedBackground.setStroke(dp(3), CYAN);
        speedBadge.setBackground(speedBackground);
        speedBadge.setElevation(dp(6));
        speedText = text(metricUnits ? "0 km/h" : "0 mph", 17, Color.WHITE, true);
        speedText.setGravity(Gravity.CENTER);
        speedBadge.addView(speedText);
        speedCluster.addView(speedBadge,
                new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM | Gravity.LEFT));
        speedLimitText = text("", 16, Color.BLACK, true);
        speedLimitText.setGravity(Gravity.CENTER);
        GradientDrawable roundel = new GradientDrawable();
        roundel.setShape(GradientDrawable.OVAL);
        roundel.setColor(Color.WHITE);
        roundel.setStroke(dp(4), Color.rgb(214, 40, 40));
        speedLimitText.setBackground(roundel);
        speedLimitText.setElevation(dp(7));
        speedLimitText.setVisibility(View.GONE);
        speedCluster.addView(speedLimitText,
                new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.RIGHT));
        speedBadgePos = new FrameLayout.LayoutParams(dp(112), dp(104), Gravity.BOTTOM | Gravity.LEFT);
        speedBadgePos.leftMargin = dp(12);
        frame.addView(speedCluster, speedBadgePos);
        speedBadgeView = speedCluster;

        followButton = chip("◎");
        followButton.setContentDescription("Centre map on my GPS position");
        followButton.setTextSize(27);
        followButton.setTextColor(Color.WHITE);
        // Round, to match the other floating map controls.
        GradientDrawable recentreShape = new GradientDrawable();
        recentreShape.setShape(GradientDrawable.OVAL);
        recentreShape.setColor(DARK);
        followButton.setBackground(recentreShape);
        followButton.setElevation(dp(6));
        followButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(DARK));
        followButton.setOnClickListener(v -> {
            followLocation = true;
            updateFollowButton();
            if (currentLocation != null && active && route != null) {
                smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(route.points));
                lastCameraUpdate = 0;
                updateGuidance(currentLocation);
                startSmoothLoop();
            } else if (currentLocation != null
                    && MapStartupPolicy.freshForRouting(
                            currentLocation.getLatitude(), currentLocation.getLongitude(),
                            currentLocation.getTime(), System.currentTimeMillis(),
                            currentLocation.hasAccuracy()
                                    ? currentLocation.getAccuracy() : 101f)) {
                centreOnLocation(true);
            } else {
                ensureLocationPermission();
                Toast.makeText(this, gpsUnavailableMessage(), Toast.LENGTH_LONG).show();
            }
        });
        gpsButtonPos = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.RIGHT);
        gpsButtonPos.rightMargin = dp(13);
        frame.addView(followButton, gpsButtonPos);
        drivingCompass=text("N ↑",13,Color.WHITE,true);
        drivingCompass.setGravity(Gravity.CENTER);drivingCompass.setBackground(drivingCard(22));
        drivingCompass.setElevation(dp(5));drivingCompass.setContentDescription("Map north direction");
        FrameLayout.LayoutParams compassPos=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.LEFT);
        compassPos.leftMargin=dp(12);frame.addView(drivingCompass,compassPos);drivingCompass.setVisibility(View.GONE);
        top.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->{compassPos.topMargin=b+dp(10);drivingCompass.requestLayout();});
        // With a route on screen, keep speed and recentre just above the bottom
        // panel whatever its height, so neither is cut off behind it.
        navigationPanel.addOnLayoutChangeListener((view, l3, t3, r3, b3, ol3, ot3, or3, ob3) -> {
            if (homeMode || speedBadgePos == null || gpsButtonPos == null) return;
            int above = (b3 - t3) + dp(19);
            if (speedBadgePos.bottomMargin != above || gpsButtonPos.bottomMargin != above) {
                speedBadgePos.bottomMargin = above;
                gpsButtonPos.bottomMargin = above;
                if (speedBadgeView != null) speedBadgeView.requestLayout();
                followButton.requestLayout();
            }
        });
        setContentView(frame);
        frame.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect visible = new Rect();
            frame.getWindowVisibleDisplayFrame(visible);
            int rootHeight = frame.getRootView().getHeight();
            boolean imeNow = rootHeight - visible.bottom > dp(160);
            if (imeNow != keyboardOpen) {
                keyboardOpen = imeNow;
                setHomeVisible(homeMode);
            }
        });
        setHomeVisible(true);
        refreshHomePanel();
    }

    private boolean matchingTomTomMap(){return false;}

    private boolean showingVector() {
        return vectorMap != null; // Native renderer owns camera, even during loading.
    }

    private void updateMapVisibility(){
        if(vectorMap!=null)vectorMap.setVisible(true);
    }

    /**
     * High-priority driving information stays at the top of the actual road map,
     * below the DriveMate brand bar. Search and the large bottom guidance pane
     * are reserved for Home / route preview, not live driving.
     */
    private LinearLayout createDrivingTopPanel() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setElevation(dp(7));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.setPadding(dp(7), dp(5), dp(7), dp(5));
        metrics.setBackground(rounded(DARK, 14));
        tripEtaValue = addDrivingMetric(metrics, "ETA");
        tripMinutesValue = addDrivingMetric(metrics, "MIN LEFT");
        tripDistanceValue = addDrivingMetric(metrics, "DISTANCE LEFT");
        tripMetricsRow = metrics;

        LinearLayout turnCard = new LinearLayout(this);
        turnCard.setOrientation(LinearLayout.VERTICAL);
        turnCard.setPadding(dp(12), dp(8), dp(12), dp(8));
        turnCard.setOnClickListener(v -> showUpcomingManoeuvres());
        turnCard.setBackground(drivingCard(18));
        drivingTurnCard = turnCard;
        LinearLayout.LayoutParams turnCardPos = new LinearLayout.LayoutParams(-1, -2);
        turnCardPos.topMargin = dp(6);
        container.addView(turnCard, turnCardPos);

        topTurnTitle = text("NEXT TURN", 12, CYAN, true);
        topTurnTitle.setLetterSpacing(0.08f);
        topTurnTitle.setSingleLine(true);
        topTurnTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        turnCard.addView(topTurnTitle);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        topTurnArrow = new TurnArrowView(this);
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(68), dp(68));
        arrow.rightMargin = dp(11);
        content.addView(topTurnArrow, arrow);
        // Distance first and largest, then the road or action on its own line
        // so the road name is not cut off behind a long sentence.
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        topDistanceText = text("", 38, Color.WHITE, true);
        topDistanceText.setSingleLine(true);
        topDistanceText.setVisibility(View.GONE);
        words.addView(topDistanceText);
        topInstructionText = text("Preparing next instruction…", 23, Color.WHITE, true);
        topInstructionText.setMaxLines(2);
        topInstructionText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(topInstructionText);
        topRoadText = text("", 18, Color.rgb(201,231,239), true);
        topRoadText.setMaxLines(2);
        topRoadText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topRoadText.setVisibility(View.GONE);
        words.addView(topRoadText);
        content.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams contentPos = new LinearLayout.LayoutParams(-1, -2);
        contentPos.topMargin = dp(2);
        turnCard.addView(content, contentPos);
        junctionPathView = new JunctionManeuverView(this);
        junctionPathView.setVisibility(View.GONE);
        junctionPathView.setNightMode(true);
        junctionPathView.setContentDescription("Calculated route path only; other roads are not shown");
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(-1,dp(92));
        pathParams.topMargin=dp(4);
        turnCard.addView(junctionPathView,pathParams);

        topNextTurnText = text("", 15, Color.rgb(201, 231, 239), true);
        topNextTurnText.setBackground(rounded(0xff17394c,8));topNextTurnText.setPadding(dp(8),dp(3),dp(8),dp(3));
        topNextTurnText.setMaxLines(2);
        topNextTurnText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams thenPos = new LinearLayout.LayoutParams(-1, -2);
        thenPos.topMargin = dp(6);
        turnCard.addView(topNextTurnText, thenPos);
        verifiedJunctionRoad = text("", 16, CYAN, true);
        verifiedJunctionRoad.setMaxLines(2);
        verifiedJunctionRoad.setEllipsize(android.text.TextUtils.TruncateAt.END);
        verifiedJunctionRoad.setVisibility(View.GONE);
        verifiedJunctionRoad.setContentDescription("TomTom mapped junction signpost or exit road");
        turnCard.addView(verifiedJunctionRoad);

        laneGuidanceText = new LaneGuidanceView(this);
        laneGuidanceText.setContentDescription("Verified route lane recommendations");
        laneGuidanceText.setVisibility(View.GONE);
        LinearLayout.LayoutParams lanePos = new LinearLayout.LayoutParams(-1, dp(48));
        lanePos.topMargin = dp(5);
        turnCard.addView(laneGuidanceText, lanePos);
        lanePromptText = text("", 15, Color.rgb(201,231,239), true);
        lanePromptText.setGravity(Gravity.CENTER);
        lanePromptText.setVisibility(View.GONE);
        turnCard.addView(lanePromptText);

        trafficNoticeText = text("", 12, Color.rgb(245, 200, 116), true);
        trafficNoticeText.setBackground(rounded(DARK,8));
        trafficNoticeText.setPadding(dp(8),0,dp(8),0);
        trafficNoticeText.setGravity(Gravity.CENTER_VERTICAL);
        trafficNoticeText.setSingleLine(true);
        trafficNoticeText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trafficNoticeText.setVisibility(View.GONE);
        trafficNoticeText.setOnClickListener(v -> showTrafficTimeline());
        LinearLayout.LayoutParams trafficPos = new LinearLayout.LayoutParams(-1, dp(25));
        trafficPos.topMargin = dp(3);
        container.addView(trafficNoticeText, trafficPos);
        upcomingSpeedText = text("", 14, Color.WHITE, true);
        upcomingSpeedText.setBackground(rounded(DARK, 8));
        upcomingSpeedText.setPadding(dp(8), dp(4), dp(8), dp(4));
        upcomingSpeedText.setMaxLines(2);
        upcomingSpeedText.setVisibility(View.GONE);
        container.addView(upcomingSpeedText, new LinearLayout.LayoutParams(-1, -2));
        upcomingRoadsideText=text("",13,Color.WHITE,true);
        upcomingRoadsideText.setBackground(rounded(DARK,8));
        upcomingRoadsideText.setPadding(dp(8),dp(4),dp(8),dp(4));
        upcomingRoadsideText.setVisibility(View.GONE);
        container.addView(upcomingRoadsideText,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout arrivalRow = new LinearLayout(this);
        arrivalRow.setGravity(Gravity.CENTER_VERTICAL);
        arrivalRow.setBackground(rounded(DARK, 12));
        arrivalRow.setPadding(dp(9), dp(4), dp(7), dp(4));
        arrivalHintText = text("", 12, Color.WHITE, true);
        arrivalHintText.setMaxLines(2);
        arrivalRow.addView(arrivalHintText, new LinearLayout.LayoutParams(0, -2, 1));
        findParkingButton = chip("🅿 Parking");
        findParkingButton.setContentDescription("Find verified parking near destination while stopped");
        findParkingButton.setOnClickListener(v -> findNearbyParking());
        arrivalRow.addView(findParkingButton, new LinearLayout.LayoutParams(dp(98), dp(38)));
        arrivalRow.setTag("arrival");
        arrivalRow.setVisibility(View.GONE);
        container.addView(arrivalRow, new LinearLayout.LayoutParams(-1, dp(48)));
        arrivalHintText.setTag(arrivalRow);
        return container;
    }

    private TextView addDrivingMetric(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        TextView caption = text(label, 10, Color.rgb(189, 220, 230), true);
        caption.setGravity(Gravity.CENTER);
        cell.addView(caption);
        tripMetricCaptions.add(caption);
        TextView number = text("—", 21, Color.WHITE, true);
        number.setGravity(Gravity.CENTER);
        number.setSingleLine(true);
        number.setAutoSizeTextTypeUniformWithConfiguration(
                13, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        cell.addView(number);
        row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
        return number;
    }

    private GradientDrawable drivingCard(int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff123d53,0xff071d31});
        d.setCornerRadius(dp(radius));d.setStroke(dp(1),0xff397b95);return d;
    }
    private void applyDrivingControls(){
        if(drivingMore==null)return;
        drivingMore.setVisibility(active?View.VISIBLE:View.GONE);
        if(overviewButton!=null)overviewButton.setVisibility(active?View.VISIBLE:View.GONE);
        drivingMore.setText(drivingControlsExpanded?"Less":"Trip controls · End");
        drivingMore.setContentDescription(drivingControlsExpanded?"Hide extra navigation controls":"Show extra navigation controls");
        if(active){stopButton.setVisibility(drivingControlsExpanded?View.VISIBLE:View.GONE);repeatButton.setVisibility(drivingControlsExpanded?View.VISIBLE:View.GONE);refreshButton.setVisibility(drivingControlsExpanded?View.VISIBLE:View.GONE);voiceButton.setVisibility(drivingControlsExpanded?View.VISIBLE:View.GONE);}
    }
    private void showRemainingRouteOverview(){
        if(!active||route==null||route.points.size()<2)return;
        followLocation=false;stopSmoothLoop();updateFollowButton();
        List<GeoMath.Point> remaining=GeoMath.slice(route.points,Math.max(0,stabilizedAlong),GeoMath.length(route.points));
        if(remaining.size()<2)remaining=route.points;
        if(vectorMap!=null){int top=(topMapOverlay!=null?topMapOverlay.getHeight():dp(140))+dp(18);int bottom=navigationPanel.getHeight()+dp(18);vectorMap.previewRoute(remaining,top,bottom,dp(36));}
    }
    private void applyNavigationChrome() {
        boolean navigating = active;
        if (drivingTopPanel != null)
            drivingTopPanel.setVisibility(navigating ? View.VISIBLE : View.GONE);
        if (brandHeader != null)
            brandHeader.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (tripMetricsRow != null)
            tripMetricsRow.setVisibility(navigating ? View.VISIBLE : View.GONE);
        if (voiceButton != null) voiceButton.setVisibility(navigating ? View.VISIBLE : View.GONE);
        if(!navigating)drivingControlsExpanded=false;
        applyDrivingControls();
        if(drivingCompass!=null)drivingCompass.setVisibility(navigating?View.VISIBLE:View.GONE);
        if(navigating){navigationPanel.setBackground(drivingCard(20));tripMetricsRow.setBackground(drivingCard(13));
            for(TextView caption:tripMetricCaptions)caption.setTextColor(0xffc4dce8);
            for(TextView value:new TextView[]{tripEtaValue,tripMinutesValue,tripDistanceValue})value.setTextColor(Color.WHITE);
        }else if(navigationPanel!=null){navigationPanel.setBackground(rounded(nightDisplay?DARK:Color.WHITE,22));}
        if (!navigating) {
            if (arrivalHintText != null && arrivalHintText.getTag() instanceof View)
                ((View) arrivalHintText.getTag()).setVisibility(View.GONE);
        }
        if (!navigating) {
            if (upcomingSpeedText != null) upcomingSpeedText.setVisibility(View.GONE);
                    if(upcomingRoadsideText!=null)upcomingRoadsideText.setVisibility(View.GONE);
            if (laneGuidanceText != null) laneGuidanceText.setVisibility(View.GONE);
            if (trafficNoticeText != null) trafficNoticeText.setVisibility(View.GONE);
            if (rerouteSuggestionRow != null) rerouteSuggestionRow.setVisibility(View.GONE);
            if (speedLimitText != null) speedLimitText.setVisibility(View.GONE);
        }
        if (searchBar != null)
            searchBar.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (banner != null) banner.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (previewTurnRow != null)
            previewTurnRow.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (previewDetailsRow != null)
            previewDetailsRow.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (gpsAccuracyText != null)
            gpsAccuracyText.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (bottomActionsParams != null)
            bottomActionsParams.topMargin = dp(navigating ? 0 : 10);
        if (navigationPanel != null) {
            navigationPanel.setPadding(dp(12), dp(navigating ? 6 : 12),
                    dp(12), dp(navigating ? 6 : 12));
            navigationPanel.requestLayout();
        }
    }

    private Button quickTile(int iconRes, String label, String detail) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setPadding(dp(4), dp(6), dp(4), dp(6));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setCompoundDrawablePadding(dp(3));
        button.setCompoundDrawablesWithIntrinsicBounds(null, getDrawable(iconRes), null, null);
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable background = rounded(Color.rgb(25, 69, 85), 12);
        background.setStroke(dp(1), CYAN);
        button.setBackground(background);
        setTileText(button, label, detail);
        return button;
    }

    /** Bold label on the first line, smaller destination name (or hint) below. */
    private void setTileText(Button tile, String label, String detail) {
        SpannableString content = new SpannableString(label + "\n" + detail);
        content.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                label.length() + 1, content.length(), 0);
        tile.setText(content);
    }

    private void setHomeVisible(boolean show) {
        homeMode = show;
        if(!show&&freeDriving){freeDriving=false;freeDriveBearing=Double.NaN;clearFreeRoadContext();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
        if (homePanel == null || navigationPanel == null) return;
        homePanel.setVisibility(show && !keyboardOpen && !freeDriving ? View.VISIBLE : View.GONE);
        navigationPanel.setVisibility(!show && !keyboardOpen ? View.VISIBLE : View.GONE);
        if (speedBadgeView != null) {
            // Speed only while actually navigating: not on home or route preview.
            speedBadgeView.setVisibility(keyboardOpen || (!active&&!freeDriving) || (show&&!freeDriving)
                    ? View.GONE : View.VISIBLE);
        }
        if (followButton != null) {
            followButton.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            updateFollowButton();
        }
        if (speedBadgePos != null && gpsButtonPos != null) {
            // During navigation the compact control row is only ~60dp tall.
            // Keep GPS speed and recenter reachable without hiding the road.
            // Off the home screen, sit just above the bottom panel (its own
            // 9 dp margin plus a 10 dp gap). Before its first layout, fall back
            // to the previous estimates.
            int navHeight = navigationPanel.getHeight();
            int above = navHeight > 0 ? navHeight + dp(19) : dp(active ? 100 : 225);
            speedBadgePos.bottomMargin = freeDriving ? dp(16) : show ? dp(320) : above;
            gpsButtonPos.bottomMargin = freeDriving ? dp(16) : show ? dp(327) : above;
            if(speedBadgeView!=null)speedBadgeView.requestLayout();
            if(followButton!=null)followButton.requestLayout();
        }
        applyNavigationChrome();
        if (show && !keyboardOpen) refreshHomePanel();
    }

    private void refreshHomePanel() {
        if (places == null || recentList == null) return;
        TomTomApi.Destination home = places.home();
        TomTomApi.Destination work = places.work();
        homeTile.setContentDescription(home == null ? "Set Home" : "Navigate Home to " + home);
        workTile.setContentDescription(work == null ? "Set Work" : "Navigate Work to " + work);
        // Real drive time from TomTom when available, otherwise the place name.
        setTileText(homeTile, "Home", home == null ? "Not set"
                : homeEtaLabel != null ? homeEtaLabel : home.title);
        setTileText(workTile, "Work", work == null ? "Not set"
                : workEtaLabel != null ? workEtaLabel : work.title);
        maybeFetchQuickEtas();
        recentList.removeAllViews();
        List<TomTomApi.Destination> recents = places.recent();
        if (recents.isEmpty()) {
            TextView empty = text("No recent journeys yet. Find a destination to begin.",
                    12, Color.rgb(202, 219, 224), false);
            empty.setPadding(dp(2), dp(18), dp(2), dp(8));
            recentList.addView(empty);
        } else {
            for (TomTomApi.Destination item : recents) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(7), 0, dp(6), 0);
                TextView name = text(item.title, 15, Color.WHITE, false);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                if (currentLocation != null && item.point != null) {
                    // Straight-line distance from the live GPS fix, shown on the right.
                    TextView distance = text(NavigationUnits.displayDistance(
                            GeoMath.distance(gpsPoint(currentLocation), item.point),
                            metricUnits), 13, Color.rgb(143, 176, 192), false);
                    distance.setPadding(dp(8), 0, 0, 0);
                    row.addView(distance);
                }
                // Remove just this entry; "Clear recent destinations" in
                // Settings still clears them all.
                TextView remove = text("×", 22, Color.rgb(143, 176, 192), false);
                remove.setGravity(Gravity.CENTER);
                remove.setContentDescription("Remove " + item.title + " from recent destinations");
                remove.setOnClickListener(v -> {
                    places.removeRecent(item);
                    refreshHomePanel();
                    refreshSearchSuggestions();
                });
                row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));
                row.setOnClickListener(v -> selectDestination(item));
                recentList.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
                View line = new View(this);
                line.setBackgroundColor(Color.rgb(40, 86, 104));
                recentList.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
    }

    /**
     * Fetch real Home/Work drive times (with traffic) from TomTom when the home
     * screen is showing. At most once every 5 minutes, and only with a fresh
     * GPS fix and a TomTom key, to keep API use low.
     */
    private void maybeFetchQuickEtas() {
        if (localRouting() || (!ownRouting() && apiKey.isEmpty()) || places == null || currentLocation == null || active) return;
        if (!MapStartupPolicy.freshForRouting(
                currentLocation.getLatitude(), currentLocation.getLongitude(),
                currentLocation.getTime(), System.currentTimeMillis(),
                currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 101f)) return;
        long now = SystemClock.elapsedRealtime();
        if (quickEtaFetchedAt != 0 && now - quickEtaFetchedAt < 5 * 60_000L) return;
        TomTomApi.Destination home = places.home();
        TomTomApi.Destination work = places.work();
        if (home == null && work == null) return;
        quickEtaFetchedAt = now;
        GeoMath.Point from = gpsPoint(currentLocation);
        String key = apiKey, own = ownEndpoint();
        network.execute(() -> {
            String homeLabel = home == null ? null : quickEta(key, own, from, home);
            String workLabel = work == null ? null : quickEta(key, own, from, work);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                homeEtaLabel = homeLabel;
                workEtaLabel = workLabel;
                if (homeMode) refreshHomePanel();
            });
        });
    }

    /** Drive time such as "14 min", or null if TomTom can't route right now. */
    private static String quickEta(String key,String own,GeoMath.Point from,TomTomApi.Destination to){return null;}

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private TextView text(String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, 1);
        return view;
    }

    private Button chip(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(221, 235, 255)));
        button.setTextColor(DARK);
        return button;
    }


    private void showSettings() {
        String[] options = {
                "DriveMate native routing · coverage",
                voiceMode == DrivingVoicePolicy.SILENT ? "Enable voice guidance" : "Mute voice guidance",
                "Voice guidance · " + voiceModeName(),
                "Voice volume · " + Math.round(guidanceVolume * 100) + "%",
                "Optional area traffic reports · "+(trafficEnabled?"On":"Off"),
                metricUnits ? "Use UK miles, yards and mph" : "Use metric kilometres and km/h",
                "Camera · " + cameraModeName(),
                closeZoom ? "Junction detail zoom · On" : "Junction detail zoom · Off",
                "Map · "+(vectorMap!=null?vectorMap.geometryDescription():"DriveMate local vector"),
                "Nearby map cache & Wi-Fi prefetch",
                "Set or change Home",
                "Set or change Work",
                "Clear recent destinations",
                "About DriveMate",
                "Map colours · " + themeModeName(),
                "Verified hazard alerts (when available) · " + (hazardWarningEnabled ? "On" : "Off"),
                "Verified speed alerts (when available) · " + (speedWarningEnabled ? "On" : "Off"),
                "Faster-route handling · " + (rerouteMode == 0 ? "Automatic" : "Ask first"),
                "Overspeed alert above mapped limit · " + speedToleranceMph + " mph",
                "DriveMate map renderer",
                "Save where I parked",
                places != null && places.parkedCar() != null
                        ? "Find my parked car" : "Find my parked car · none saved",
                "Share diagnostics (no location included)",
                "3D buildings · " + (buildings3d ? "On" : "Off"),
                "DriveMate routing and search",
                "Journey map review · " + mapReview.snapshot().size() + " findings",
                "Independent map survey drafts",
                "Offline routing · regional coverage",
                "Offline visual map · download full UK",
                "DriveMate original stack · Wells pilot",
                "Free driving · "+(freeDriving?"On":"Off"),
                "Advance speed-limit voice · "+(advanceSpeedVoice?"On":"Off"),
                "Map areas · fast offline detail"
        };
        new AlertDialog.Builder(this).setTitle("DriveMate settings")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: showKeyDialog(); break;
                        case 1:
                            setVoiceMode(voiceMode == DrivingVoicePolicy.SILENT
                                    ? DrivingVoicePolicy.NORMAL : DrivingVoicePolicy.SILENT);
                            break;
                        case 2: selectVoiceMode(); break;
                        case 3: selectSpeechVolume(); break;
                        case 4:
                            configureIncidentEnhancement();
                            break;
                        case 5:
                            metricUnits = !metricUnits;
                            if(vectorMap!=null && route!=null)vectorMap.setRouteEvents(route.speedSections,route.trafficSections,metricUnits,route.fetchedAtMs);
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("metric_units", metricUnits).apply();
                            if (currentLocation != null) {
                                speedText.setText(NavigationUnits.displaySpeed(
                                        currentLocation.getSpeed(), metricUnits));
                                if (active) updateGuidance(currentLocation);
                            }
                            if (route != null && !active) showPreview(route);
                            break;
                        case 6: selectCameraMode(); break;
                        case 7:
                            closeZoom = !closeZoom;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("close_zoom", closeZoom).apply();
                            lastCameraUpdate = 0;
                            if (active && currentLocation != null) updateGuidance(currentLocation);
                            break;
                        case 8:
                            if (vectorMap == null) {
                                Toast.makeText(this,
                                        "DriveMate local map is unavailable on this device",
                                        Toast.LENGTH_LONG).show();
                                break;
                            }
                            if (autoFallback && useLocalMap) {
                                // A single tap retries DriveMate Map rather than
                                // silently selecting the same TomTom fallback.
                                autoFallback = false;
                                vectorRoadsReady = false;
                                setStatus("Retrying DriveMate streets…");
                            } else {
                                useLocalMap = true;
                                autoFallback = false;
                                vectorRoadsReady = vectorMap.hasRenderedRoads();
                            }
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("drivemate_vector_map", useLocalMap).apply();
                            updateMapVisibility();
                            if (useLocalMap && currentLocation != null) {
                                vectorMap.setCamera(gpsPoint(currentLocation),
                                        vectorMap.currentZoom(), 0);
                                vectorMap.prefetch(gpsPoint(currentLocation));
                            }
                            break;
                        case 9:
                            String stats = vectorMap == null ? "Local vector map unavailable"
                                    : vectorMap.cacheStats();
                            new AlertDialog.Builder(this).setTitle("DriveMate nearby map cache")
                                    .setMessage(stats + "\n\nMaps are cached privately on your phone (up to 180 MB of street tiles). "
                                            + "An approximately 20–30 km area is prefetched on unmetered Wi-Fi "
                                            + "around a GPS fix. Online use fetches missing tiles individually. "
                                            + "Native routing uses the verified packaged regional graph. "
                                            + "Map data © OpenStreetMap contributors, ODbL 1.0.")
                                    .setPositiveButton("Prefetch nearby", (d, button) -> {
                                        if (vectorMap != null && currentLocation != null)
                                            vectorMap.prefetch(gpsPoint(currentLocation));
                                    })
                                    .setNegativeButton("Close", null).show();
                            break;
                        case 10: configureQuickDestination(true); break;
                        case 11: configureQuickDestination(false); break;
                        case 12:
                            new AlertDialog.Builder(this).setTitle("Clear recent destinations?")
                                    .setMessage("Favourites, Home and Work will be kept.")
                                    .setPositiveButton("Clear", (d, w) -> {
                                        places.clearRecent();
                                        refreshHomePanel();
                                        refreshSearchSuggestions();
                                    }).setNegativeButton("Cancel", null).show();
                            break;
                        case 13:
                            new AlertDialog.Builder(this).setTitle("DriveMate v" + installedVersionName())
                                    .setMessage("DriveMate Map © OpenStreetMap contributors (ODbL 1.0). "
                                            + "Cached vector tiles are map display only. "
                                            + "DriveMate calculates routes and instructions on your phone from map data. No live traffic feed. " +
                                            "Road-matching and route camera are experimental. " +
                                            "Keep DriveMate open; never use it as your sole navigator " +
                                            "before testing directions on a real device.")
                                    .setPositiveButton("OK", null).show();
                            break;
                        case 14: selectThemeMode(); break;
                        case 15:
                            hazardWarningEnabled = !hazardWarningEnabled;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("hazard_voice_alerts", hazardWarningEnabled).apply();
                            break;
                        case 16:
                            speedWarningEnabled = !speedWarningEnabled;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("speed_voice_alerts", speedWarningEnabled).apply();
                            break;
                        case 17: selectRerouteMode(); break;
                        case 18: selectSpeedTolerance(); break;
                        case 19:
                            if(!active) startActivity(new Intent(this,NativeMapPreviewActivity.class));
                            else Toast.makeText(this,"Stop navigation before opening map preview",Toast.LENGTH_SHORT).show();
                            break;
                        case 20: promptSaveParking(); break;
                        case 21: showParkedCar(); break;
                        case 22: shareDiagnostics(); break;
                        case 23:
                            buildings3d = !buildings3d;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("buildings_3d", buildings3d).apply();
                            if (vectorMap != null) vectorMap.setBuildings3D(buildings3d);
                            Toast.makeText(this, buildings3d
                                    ? "3D buildings on (estimated height; map data has no real heights)"
                                    : "Flat building outlines", Toast.LENGTH_SHORT).show();
                            break;
                        case 24: configureIndependent(); break;
                        case 25: showMapReview(); break;
                        case 26: showSurveyNotes(); break;
                        case 27: showOfflineRouting(); break;
                        case 28: showOfflineDisplay(); break;
                        case 29: if(!active) DmPilotScreen.show(this); break;
                        case 30: toggleFreeDriving(); break;
                        case 31: advanceSpeedVoice=!advanceSpeedVoice;
                            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("advance_speed_voice",advanceSpeedVoice).apply(); break;
                        case 32: showMapAreas(); break;
                        default: break;
                    }
                }).setNegativeButton("Close", null).show();
    }

    private String ownEndpoint(){return "";}
    private String ownSearchEndpoint(){return "";}
    private boolean localRouting(){return true;}
    private boolean ownRouting(){return true;}
    private void showOfflineRouting(){
        if(active||route!=null||routing||downloadingLocalPack){showError("End navigation and wait for current downloads first.");return;}
        NativeRegionPacks packs=new NativeRegionPacks(this);
        try{
            List<NativeRegionPacks.Entry> entries=packs.catalog();
            if(entries.isEmpty()){
                new AlertDialog.Builder(this).setTitle("DriveMate native regional routing")
                    .setMessage("Cheadle/Stockport is packaged. No additional verified GitHub regional release is pinned yet. Native route previews and local search only; no external fallback. Driving start remains blocked pending acceptance.")
                    .setPositiveButton("Close",null).show();return;
            }
            String[] labels=new String[entries.size()];
            for(int i=0;i<labels.length;i++){NativeRegionPacks.Entry e=entries.get(i);labels[i]=e.id+" · "+(packs.installed(e)?"Installed":(e.bytes/1000000)+" MB");}
            new AlertDialog.Builder(this).setTitle("DriveMate regional downloads").setItems(labels,(dialog,index)->{
                NativeRegionPacks.Entry e=entries.get(index);
                new AlertDialog.Builder(this).setTitle("Download "+e.id).setMessage("Verified GitHub data only, on unmetered Wi-Fi. No cross-region routing. Preview only until driving acceptance.")
                    .setPositiveButton("Download",(d,w)->{
                        if(active||route!=null||routing||downloadingLocalPack)return;
                        downloadingLocalPack=true;packWorker.execute(()->{try{
                            packs.install(e,message->runOnUiThread(()->{if(!isFinishing())setStatus(message);}));
                            runOnUiThread(()->{downloadingLocalPack=false;if(!isFinishing())setStatus("Regional pack installed · ready for routing");});
                        }catch(Exception err){runOnUiThread(()->{downloadingLocalPack=false;if(!isFinishing())showError(err.getMessage());});}});
                    }).setNegativeButton("Cancel",null).show();
            }).setNegativeButton("Close",null).show();
        }catch(Exception e){showError("Regional catalog invalid: "+e.getMessage());}
    }
    /** Downloads and installs one map area on the pack worker, with throttled progress in the status line. */
    private void installPack(final MapPacks store, final MapPackCatalog.Pack p){
        if(downloadingLocalPack)return;
        downloadingLocalPack=true;
        packWorker.execute(()->{try{
            final long[] last={0};
            store.install(p,(done,total)->{long now=SystemClock.elapsedRealtime();
                if(now-last[0]>1000){last[0]=now;final int pct=(int)(done*100/Math.max(1,total));
                    runOnUiThread(()->{if(!isFinishing())setStatus("Downloading "+p.name+" · "+pct+"%");});}
                return !isFinishing();});
            runOnUiThread(()->{downloadingLocalPack=false;if(!isFinishing())setStatus(p.name+" installed · map detail is now offline and fast");});
        }catch(Exception err){runOnUiThread(()->{downloadingLocalPack=false;if(!isFinishing())showError(err.getMessage()==null?"Download failed":err.getMessage());});}});
    }

    private boolean packOfferChecked;
    /** Once per area: offer the map area around the first GPS fix, because local tiles are far faster than any network. */
    private void maybeOfferRegionPack(final GeoMath.Point here){
        if(packOfferChecked||here==null||active||route!=null||downloadingLocalPack||!hasNetwork())return;
        packOfferChecked=true;
        final MapPacks store=new MapPacks(this);
        if(!store.installedFiles().isEmpty())return;
        packWorker.execute(()->{
            final MapPackCatalog catalog;
            try{catalog=store.catalog();}catch(Exception e){return;}
            List<MapPackCatalog.Pack> near=catalog.forPoint(here.lat,here.lon);
            if(near.isEmpty())return;
            final MapPackCatalog.Pack p=near.get(0);
            final SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
            if(prefs.getBoolean("pack_offer_declined_"+p.id,false))return;
            final boolean metered=((ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE)).isActiveNetworkMetered();
            runOnUiThread(()->{
                if(isFinishing()||active||route!=null)return;
                new AlertDialog.Builder(this).setTitle("Faster maps: download "+p.name+"?")
                    .setMessage("Download the full-detail map for "+p.name+" ("+(p.bytes/1_000_000L)+" MB) so the map draws instantly and works offline."
                        +(metered?" You are on mobile data, so Wi-Fi is better.":" You are on Wi-Fi."))
                    .setPositiveButton("Download",(d,w)->installPack(store,p))
                    .setNegativeButton("Not now",(d,w)->prefs.edit().putBoolean("pack_offer_declined_"+p.id,true).apply())
                    .show();
            });
        });
    }

    /** Free map areas: pick a region to download full road detail onto the phone (resumable, verified). */
    private void showMapAreas(){
        if(downloadingLocalPack){showError("Wait for the current download to finish.");return;}
        setStatus("Loading map areas…");
        packWorker.execute(()->{
            final MapPacks store=new MapPacks(this);
            final MapPackCatalog catalog;
            try{catalog=store.catalog();}catch(Exception e){runOnUiThread(()->{if(!isFinishing())showError("Map areas are not available right now. "+e.getMessage());});return;}
            final List<MapPackCatalog.Pack> offered=new ArrayList<>();
            for(MapPackCatalog.Pack p:catalog.packs)if(!p.bundled)offered.add(p);
            final String[] labels=new String[offered.size()];
            for(int i=0;i<labels.length;i++){
                MapPackCatalog.Pack p=offered.get(i);
                String state=!store.installed(p.id)?"Download":MapPackCatalog.needsUpdate(p,store.installedVersion(p.id))?"Update":"Installed";
                labels[i]=p.name+" · "+(p.bytes/1_000_000L)+" MB · "+state;
            }
            runOnUiThread(()->{
                if(isFinishing())return;
                setStatus("Map areas ready");
                new AlertDialog.Builder(this).setTitle("Map areas")
                    .setItems(labels,(d,which)->{
                        final MapPackCatalog.Pack p=offered.get(which);
                        if(store.installed(p.id)&&!MapPackCatalog.needsUpdate(p,store.installedVersion(p.id))){
                            new AlertDialog.Builder(this).setTitle(p.name).setMessage("Installed. Delete it to free "+(p.bytes/1_000_000L)+" MB?")
                                .setPositiveButton("Delete",(x,y)->{store.remove(p.id);setStatus(p.name+" removed");})
                                .setNegativeButton("Keep",null).show();
                            return;
                        }
                        installPack(store,p);
                    }).setNegativeButton("Close",null).show();
            });
        });
    }
    private void showOfflineDisplay(){
        if(active||route!=null||routing||downloadingLocalPack){showError("End navigation and wait for current downloads first.");return;}
        LocalRoutingPack pack=new LocalRoutingPack(this);
        new AlertDialog.Builder(this).setTitle("Full UK offline visual map")
            .setMessage(pack.displayInstalled()?"A complete visual map is installed. Verify it again if needed.":"Download the pinned UK visual map (approximately 1.87 GB) over unmetered Wi-Fi. Offline rendering uses this archive; this does not install the routing graph.")
            .setPositiveButton(pack.displayInstalled()?"Verify":"Download",(d,w)->{
                if(downloadingLocalPack||active||route!=null||routing)return;
                downloadingLocalPack=true;packWorker.execute(()->{try{pack.downloadDisplay(message->runOnUiThread(()->{if(!isFinishing())setStatus(message);}));
                    runOnUiThread(()->{downloadingLocalPack=false;if(!isFinishing()){useLocalMap=true;autoFallback=false;updateMapVisibility();}});}
                    catch(Exception e){runOnUiThread(()->{downloadingLocalPack=false;if(!isFinishing())showError(e.getMessage());});}});
            }).setNegativeButton("Close",null).show();
    }
    private void enableLocalRouting(boolean enabled){showOfflineRouting();}
    private boolean comparisonPermitted(){return false;}
    private void configureIndependent(){showOfflineRouting();}
    private List<TomTomApi.Destination> providerSearch(String query,GeoMath.Point near)throws Exception{
        List<TomTomApi.Destination> online=OnlineFirst.search(onlineKey(),query,near,hasNetwork());
        if(online!=null)return online;
        return new NativeRegionalRouter(this).search(query,near);
    }
    /** The saved TomTom key, only when the owner has consented to online search and routing. */
    private String onlineKey(){
        // A saved key is the owner's opt-in: "Disable" in the key dialog removes it.
        return NativeEnhancementPolicy.enabled(true,incidentEnhancementKey)?incidentEnhancementKey:"";
    }
    private void requestComparison(){/* Native-only: no external comparison requests. */}
    private void showMapReview(){
        if(active || currentLocation==null || !currentLocation.hasSpeed() || currentLocation.getSpeed()>=0.5f || System.currentTimeMillis()-currentLocation.getTime()>10000){showError("Review map differences only after ending navigation and parking safely.");return;}
        List<MapReview.Finding> items=mapReview.snapshot();
        if(items.isEmpty()){new AlertDialog.Builder(this).setTitle("Journey map review").setMessage("No comparable differences recorded. Comparison requires an authorised TomTom connection and aligned road data.").setPositiveButton("Close",null).show();return;}
        String[] labels=new String[items.size()];for(int i=0;i<labels.length;i++)labels[i]=items.get(i).label();
        new AlertDialog.Builder(this).setTitle("Map review · "+items.size()+" possible differences").setItems(labels,(d,i)->surveyFinding(items.get(i))).setNeutralButton("Clear",(d,w)->mapReview.clear()).setNegativeButton("Close",null).show();
    }
    private boolean safelyParked(){
        return !active&&currentLocation!=null&&currentLocation.hasSpeed()&&currentLocation.getSpeed()<0.5f&&System.currentTimeMillis()-currentLocation.getTime()<10000;
    }
    private void showSurveyNotes(){
        if(!safelyParked()){showError("Review surveys only when safely parked.");return;}
        SurveyNotes notes=new SurveyNotes(this);List<String> saved=notes.list();
        new AlertDialog.Builder(this).setTitle("Independent map survey drafts")
            .setItems(saved.toArray(new String[0]),(d,i)->new AlertDialog.Builder(this).setTitle("Independent survey")
                .setMessage(saved.get(i)).setPositiveButton("Share",(dd,w)->shareSurvey(saved.get(i)))
                .setNeutralButton("Delete",(dd,w)->new AlertDialog.Builder(this).setMessage("Delete this private survey draft?").setPositiveButton("Delete",(ddd,www)->notes.delete(i)).setNegativeButton("Cancel",null).show()).setNegativeButton("Close",null).show())
            .setPositiveButton("New independent survey",(d,w)->editSurvey()).setNegativeButton("Close",null).show();
    }
    private void editSurvey(){
        if(!safelyParked())return;
        EditText entry=new EditText(this);entry.setHint("Independent road observation: location, direction, sign, date and evidence");
        new AlertDialog.Builder(this).setTitle("New survey").setMessage("Use only your own observations or compatible official data. No TomTom values or copied comparison locations.")
            .setView(entry).setPositiveButton("Save draft",(d,w)->{
                if(!safelyParked())return;
                if(!new SurveyNotes(this).add(entry.getText().toString()))showError("Enter evidence (up to 2000 characters; maximum 100 drafts).");
                else setStatus("Independent survey saved privately");
            }).setNegativeButton("Cancel",null).show();
    }
    private void shareSurvey(String note){
        if(!safelyParked())return;
        android.content.Intent share=new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,"Independent road survey: "+note);
        startActivity(android.content.Intent.createChooser(share,"Share independent survey"));
    }
    private void surveyFinding(MapReview.Finding item){
        EditText evidence=new EditText(this);evidence.setHint("Independently observed location, sign, direction and date");
        new AlertDialog.Builder(this).setTitle(item.label()).setMessage(String.format(Locale.UK,"DriveMate location: %.5f, %.5f · travel heading %.0f°\n\nNot proof of a map error. Verify road signs or permitted official records independently. Only independent observations can be shared; never copy TomTom results.",item.point.lat,item.point.lon,item.bearing))
            .setView(evidence).setPositiveButton("Save independent draft",(d,w)->{
                if(!safelyParked())return;
                if(!new SurveyNotes(this).add(evidence.getText().toString()))showError("Independent survey evidence required (up to 2000 characters).");
                else setStatus("Independent survey saved privately");
            }).setNegativeButton("Close",null).show();
    }

    /** Always display the version installed in Android's package metadata.
     * Avoid hard-coded labels that stay stale after a signed APK update. */
    private String installedVersionName() {
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return version != null && !version.isEmpty() ? version : "unknown";
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }


    private String voiceModeName() {
        return new String[]{"Full", "Normal", "Alerts only", "Silent"}[voiceMode];
    }

    private void setVoiceMode(int selected) {
        voiceMode = Math.max(DrivingVoicePolicy.FULL,
                Math.min(DrivingVoicePolicy.SILENT, selected));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("voice_mode_v2", voiceMode).apply();
        if ((voiceMode == DrivingVoicePolicy.SILENT
                || voiceMode == DrivingVoicePolicy.ALERTS_ONLY) && speech != null)
            speech.stop();
        updateVoiceButton();
        Toast.makeText(this, "Voice: " + voiceModeName(), Toast.LENGTH_SHORT).show();
    }

    private void updateVoiceButton() {
        if (voiceButton == null) return;
        String[] symbols = {"🔊 Full", "🔉 Voice", "⚠ Alerts", "🔇 Muted"};
        voiceButton.setText(symbols[voiceMode]);
        voiceButton.setContentDescription("Voice: " + voiceModeName()
                + ". Tap to change voice mode.");
    }

    private void cycleVoiceMode() {
        setVoiceMode((voiceMode + 1) % 4);
    }

    private void selectVoiceMode() {
        new AlertDialog.Builder(this).setTitle("Voice guidance")
                .setSingleChoiceItems(new String[]{
                        "Full · early, approach and turn",
                        "Normal · approach and turn",
                        "Alerts only · no routine turns",
                        "Silent · no navigation speech"
                }, voiceMode, (dialog, which) -> {
                    setVoiceMode(which);
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private String cameraModeName() {
        return new String[]{"Auto 3D", "2D direction up", "2D north up"}[cameraMode];
    }

    private void selectCameraMode() {
        new AlertDialog.Builder(this).setTitle("Navigation camera")
                .setSingleChoiceItems(new String[]{
                        "Automatic 3D · adaptive tilt and zoom",
                        "2D · direction of travel up",
                        "2D · north always up"
                }, cameraMode, (dialog, which) -> {
                    cameraMode = which;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("camera_mode", which).apply();
                    lastCameraUpdate = 0;
                    applyHighDetailPolicy();
                    if (active && route != null && currentLocation != null) {
                        smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(route.points));
                        updateGuidance(currentLocation);
                    } else if (which == DrivingDisplayPolicy.CAMERA_NORTH_2D) {
                        if(vectorMap!=null&&displayGps!=null)vectorMap.setCamera(displayGps,vectorMap.currentZoom(),0);
                    }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void selectRerouteMode() {
        new AlertDialog.Builder(this).setTitle("Faster-route suggestions")
                .setSingleChoiceItems(new String[]{
                        "Automatic · switch for substantial estimated savings",
                        "Ask first · stay on route until I approve"
                }, rerouteMode, (dialog, which) -> {
                    rerouteMode = which;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("reroute_mode", which).apply();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void selectSpeedTolerance() {
        final String[] labels = {"0 mph", "3 mph", "5 mph", "7 mph", "10 mph", "15 mph"};
        final int[] values = {0, 3, 5, 7, 10, 15};
        int selected = 1;
        for (int i = 0; i < values.length; i++)
            if (values[i] == speedToleranceMph) selected = i;
        new AlertDialog.Builder(this).setTitle("Above mapped speed limit")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    speedToleranceMph = values[which];
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("speed_tolerance_mph", speedToleranceMph).apply();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private String themeModeName() {
        return DrivingDisplayPolicy.themeName(themePreference);
    }

    private void selectThemeMode() {
        int[] order = DrivingDisplayPolicy.THEME_MENU;
        int checked = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == themePreference) checked = i;
        new AlertDialog.Builder(this).setTitle("Map colours")
                .setSingleChoiceItems(new String[]{
                        "Light", "Dark", "Automatic · follow phone light/dark setting"
                }, checked, (dialog, which) -> {
                    themePreference = order[which];
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("map_theme", themePreference).apply();
                    applyTheme();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void applyTheme() {
        boolean systemNight = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean night = DrivingDisplayPolicy.night(themePreference, systemNight);
        if (themeApplied && night == nightDisplay
                && (vectorMap == null || vectorMap.nightMode() == night)) return;
        nightDisplay = night;
        themeApplied = true;
        boolean vectorStyleChanging = vectorMap != null && vectorMap.nightMode() != night;
        if (vectorStyleChanging) {
            // Keep the validated raster map visible while the next MapLibre
            // style loads. Never present an unverified empty road surface.
            vectorRoadsReady = false;
            vectorMap.setNightMode(night);
        }
        // Only DriveMate-owned style changes with theme.
        int panel = night || active ? DARK : Color.WHITE;
        int foreground = night || active ? Color.WHITE : Color.rgb(25, 48, 62);
        int subdued = night || active ? Color.rgb(191, 225, 236) : Color.rgb(55, 80, 96);
        if (navigationPanel != null) navigationPanel.setBackground(active?drivingCard(20):rounded(panel, 22));
        if (tripMetricsRow != null)
            tripMetricsRow.setBackground(active?drivingCard(13):rounded(night ? DARK : Color.rgb(232, 242, 247), 14));
        // The turn card always uses the DriveMate navy with white text and cyan
        // accents, in Light and Dark alike, so it matches the header.
        if (drivingTurnCard != null)
            drivingTurnCard.setBackground(drivingCard(18));
        for (TextView caption : tripMetricCaptions) caption.setTextColor(subdued);
        for (TextView value : new TextView[]{tripEtaValue, tripMinutesValue,
                tripDistanceValue, instructionText,
                distanceText, journeyTimeText, etaText}) {
            if (value != null) value.setTextColor(foreground);
        }
        for (TextView value : new TextView[]{topInstructionText, topDistanceText}) {
            if (value != null) value.setTextColor(Color.WHITE);
        }
        if (topNextTurnText != null) topNextTurnText.setTextColor(Color.rgb(201, 231, 239));
        for (TextView value : new TextView[]{nextTurnText,
                routeSummary, gpsAccuracyText}) {
            if (value != null) value.setTextColor(subdued);
        }
        if (topTurnTitle != null) topTurnTitle.setTextColor(CYAN);
        if (banner != null) banner.setTextColor(
                night ? CYAN : Color.rgb(0, 96, 130));
        // LaneGuidanceView uses a fixed high-contrast navy-card palette.
        if (trafficNoticeText != null) trafficNoticeText.setTextColor(
                night ? Color.rgb(245, 200, 116) : Color.rgb(132, 65, 0));
        if (topTurnArrow != null) topTurnArrow.setNightMode(true);   // on the navy card
        if (maneuverIcon != null) maneuverIcon.setNightMode(night);
        if (verifiedJunctionRoad != null) verifiedJunctionRoad.setTextColor(CYAN);
        if (vectorStyleChanging && !apiKey.isEmpty()) updateMapVisibility();
    }


    private void selectSpeechVolume() {
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(Math.round(guidanceVolume * 100));
        slider.setPadding(dp(22), dp(12), dp(22), dp(12));
        new AlertDialog.Builder(this).setTitle("Spoken guidance volume")
                .setMessage("Uses Android text-to-speech navigation audio. Media volume also matters.")
                .setView(slider)
                .setPositiveButton("Save and test", (dialog, which) -> {
                    guidanceVolume = Math.max(0.1f, slider.getProgress() / 100f);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putFloat("speech_volume", guidanceVolume).apply();
                    say("DriveMate guidance volume");
                }).setNegativeButton("Cancel", null).show();
    }

    private final List<TomTomApi.Destination> onlineSuggestions = new ArrayList<>();
    private String onlineFor = "";
    private final Handler typeaheadHandler = new Handler(Looper.getMainLooper());
    private int typeaheadSeq;
    private boolean keyTipShown;

    /** Debounced live suggestions from TomTom while the destination is typed. */
    private void requestTypeahead(String raw) {
        typeaheadHandler.removeCallbacksAndMessages(null);
        final String key = onlineKey();
        final String typed = raw.trim().toLowerCase(Locale.UK);
        if (typed.length() < 3 || !hasNetwork()) return;
        if (key.isEmpty()) {
            if (!keyTipShown) { keyTipShown = true; setStatus("Tip: add your TomTom key in Settings for faster search and live-traffic routes."); }
            return;
        }
        final int seq = ++typeaheadSeq;
        final GeoMath.Point near = currentLocation == null ? null : gpsPoint(currentLocation);
        typeaheadHandler.postDelayed(() -> suggestWorker.execute(() -> {
            final List<TomTomApi.Destination> found = OnlineFirst.suggest(key, typed, near);
            if (found == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || seq != typeaheadSeq || destinationInput == null) return;
                if (!destinationInput.getText().toString().trim().toLowerCase(Locale.UK).equals(typed)) return;
                onlineSuggestions.clear();
                onlineSuggestions.addAll(found);
                onlineFor = typed;
                refreshSearchSuggestions();
                if (destinationInput.hasFocus()) destinationInput.showDropDown();
            });
        }), 280);
    }

    private void refreshSearchSuggestions() {
        if (destinationInput == null || places == null) return;
        String text = destinationInput.getText().toString().trim().toLowerCase(Locale.UK);
        suggestionMatches.clear();
        List<String> labels = new ArrayList<>();
        List<TomTomApi.Destination> candidates = new ArrayList<>();
        final int onlineCount = text.equals(onlineFor) ? onlineSuggestions.size() : 0;
        if (onlineCount > 0) candidates.addAll(onlineSuggestions);
        candidates.addAll(places.favorites());
        candidates.addAll(places.recent());
        int position = -1;
        for (TomTomApi.Destination candidate : candidates) {
            position++;
            if (!text.isEmpty() && position >= onlineCount
                    && !candidate.toString().toLowerCase(Locale.UK).contains(text)) continue;
            boolean duplicate = false;
            for (TomTomApi.Destination prior : suggestionMatches) {
                if (GeoMath.distance(prior.point, candidate.point) < 3) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            suggestionMatches.add(candidate);
            labels.add(candidate.toString());
            if (labels.size() >= 8) break;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, labels);
        destinationInput.setAdapter(adapter);
        destinationInput.setOnItemClickListener((parent, view, position, id) -> {
            Object selected = parent.getItemAtPosition(position);
            for (TomTomApi.Destination place : new ArrayList<>(suggestionMatches)) {
                if (place.toString().equals(selected)) {
                    destinationInput.dismissDropDown();
                    selectDestination(place);
                    return;
                }
            }
        });
    }

    private void openQuickDestination(boolean home) {
        TomTomApi.Destination place = home ? places.home() : places.work();
        if (place == null) {
            configureQuickDestination(home);
        } else {
            selectDestination(place);
        }
    }

    private void configureQuickDestination(boolean home) {
        String label = home ? "Home" : "Work";
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setHint("Enter " + label + " address or postcode");
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(18), dp(6), dp(18), dp(4));
        container.addView(entry, new FrameLayout.LayoutParams(-1, dp(55)));
        new AlertDialog.Builder(this).setTitle("Set " + label)
                .setMessage("Choose a destination to save as " + label + ".")
                .setView(container)
                .setPositiveButton("Search", (dialog, which) -> {
                    String query = entry.getText().toString().trim();
                    if (query.length() < 2) {
                        showError("Enter a complete address or postcode.");
                        return;
                    }
                    if (!ownRouting() && apiKey.isEmpty()) {
                        showKeyDialog();
                        return;
                    }
                    GeoMath.Point near = currentLocation == null ? null : gpsPoint(currentLocation);
                    setStatus("Finding " + label + "…");
                    network.execute(() -> {
                        try {
                            List<TomTomApi.Destination> options =
                                    providerSearch(query, near);
                            runOnUiThread(() -> {
                                if (isFinishing()) return;
                                if (options.isEmpty()) {
                                    showError("No matching addresses. Try a fuller address.");
                                    return;
                                }
                                String[] labels = new String[options.size()];
                                for (int i = 0; i < options.size(); i++) {
                                    labels[i] = options.get(i).toString();
                                }
                                new AlertDialog.Builder(this)
                                        .setTitle("Save " + label + " location")
                                        .setItems(labels, (d, index) -> {
                                            TomTomApi.Destination chosen = options.get(index);
                                            // A new Home/Work needs a fresh drive time.
                                            quickEtaFetchedAt = 0;
                                            homeEtaLabel = null;
                                            workEtaLabel = null;
                                            if (home) places.setHome(chosen);
                                            else places.setWork(chosen);
                                            refreshHomePanel();
                                            Toast.makeText(this, label + " saved",
                                                    Toast.LENGTH_SHORT).show();
                                            selectDestination(chosen);
                                        })
                                        .setNegativeButton("Cancel", null).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (!isFinishing()) showError(error.getMessage());
                            });
                        }
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    private void voiceSearch() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an address or postcode");
            startActivityForResult(intent, VOICE_SEARCH_REQUEST);
        } catch (Exception e) {
            showError("Speech search is not available on this phone.");
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE_SEARCH_REQUEST || resultCode != RESULT_OK || data == null)
            return;
        ArrayList<String> results = data.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) {
            destinationInput.setText(results.get(0));
            searchDestination();
        }
    }

    private void showKeyDialog(){showOfflineRouting();}

    private void configureTiles(){updateMapVisibility();}

    private void configureTrafficTiles(){
        if(vectorMap!=null)vectorMap.setTraffic(trafficEnabled);
        if(!trafficEnabled)clearLiveIncidents();
    }
    private void configureIncidentEnhancement(){
        if(active||routing){showError("Stop safely before changing optional traffic settings.");return;}
        EditText input=new EditText(this);input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("TomTom API key");input.setText(incidentEnhancementKey);
        new AlertDialog.Builder(this).setTitle("TomTom online search, routing and traffic")
            .setMessage("Off by default. With your TomTom key and consent, DriveMate searches places and calculates routes with live traffic through TomTom while online, and shows nearby traffic reports. What you search for, your route start and end, and a nearby area box are sent to TomTom over HTTPS. Offline, or if TomTom fails, DriveMate uses its own map data and router.")
            .setView(input).setPositiveButton("Enable",(d,w)->{
                String key=input.getText().toString().trim();
                if(!NativeEnhancementPolicy.enabled(true,key)){showError("Enter a valid optional traffic key.");return;}
                clearLiveIncidents();incidentEnhancementKey=key;trafficEnabled=true;
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("incident_enhancement_key",key).putBoolean("incident_enhancement_consent",true).apply();
                configureTrafficTiles();
            }).setNeutralButton("Disable",(d,w)->{
                trafficEnabled=false;incidentEnhancementKey="";
                getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove("incident_enhancement_key").putBoolean("incident_enhancement_consent",false).apply();
                configureTrafficTiles();
            }).setNegativeButton("Cancel",null).show();
    }

    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != LOCATION_PERMISSION) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) startLocation();
        else {
            setStatus("Allow Precise Location to show your position and navigate.");
            Toast.makeText(this, "DriveMate needs Precise Location permission for driving.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasPreciseLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasEnabledLocationProvider() {
        if (locationManager == null) return false;
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception error) { return false; }
    }

    /** Human-readable reason, without falsely claiming mobile data is GPS. */
    private String gpsUnavailableMessage() {
        if (!hasPreciseLocationPermission())
            return "Allow Precise Location for DriveMate in Android app permissions.";
        if (!hasEnabledLocationProvider())
            return "Turn on your phone's Location service, then tap the GPS button.";
        if (currentLocation != null
                && System.currentTimeMillis() - currentLocation.getTime() > 20000)
            return "GPS position is out of date. Keep Location on and try again.";
        return "Waiting for a fresh GPS position. Move near a window or outdoors and retry.";
    }

    /**
     * Restore the map camera from the phone's last GPS OR network provider
     * result without advertising an old fix as current navigation position.
     */
    private void restoreLastKnownMapArea() {
        if (locationManager == null || !hasPreciseLocationPermission()
                || currentLocation != null || !followLocation) return;
        Location chosen = null;
        long now = System.currentTimeMillis();
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && MapStartupPolicy.usableCachedMapArea(
                        candidate.getLatitude(), candidate.getLongitude(),
                        candidate.getTime(), now,
                        candidate.hasAccuracy() ? candidate.getAccuracy() : 2501f)
                        && (chosen == null || candidate.getTime() > chosen.getTime()))
                    chosen = candidate;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // The remembered app-private overview remains available.
            }
        }
        if (chosen == null) return;
        // A genuinely fresh, accurate fix can enter the normal GPS path.
        if (MapStartupPolicy.freshForRouting(chosen.getLatitude(), chosen.getLongitude(),
                chosen.getTime(), now, chosen.getAccuracy())) {
            onLocationChanged(chosen);
            return;
        }
        if (chosen.getTime() <= getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong("last_map_at", 0L)) return;
        GeoMath.Point preview = new GeoMath.Point(
                chosen.getLatitude(), chosen.getLongitude());
        double zoom = chosen.hasAccuracy() && chosen.getAccuracy() > 1000
                ? 11.0 : 13.5;
        if (vectorMap != null) vectorMap.setCamera(preview, zoom, 0);
        // No blue GPS marker is created: this is historical positioning only.
        setStatus("Showing last known area · waiting for live GPS");
    }

    private void startLocation() {
        if (locationManager == null || !hasPreciseLocationPermission()) {
            setStatus("Precise Location permission is required for navigation.");
            return;
        }
        // onCreate and onResume both ask to start. Do not repeatedly tear down
        // GPS registration before the phone has obtained its first fix.
        if (locationSubscribed) return;
        try {
            if (!hasEnabledLocationProvider()) {
                setStatus("Phone Location is off · enable it to navigate");
                return;
            }
            boolean requested = false;
            // Android 12+: the platform fused provider combines GNSS, Wi-Fi, cell and
            // motion sensors. FixQualityPolicy ranks it above raw GPS; raw GPS stays
            // registered as a fallback and to drive the satellite count.
            // Fastest rate while navigating, lighter on the home screen and route
            // preview (see LocationRatePolicy). resubscribeLocationForMode()
            // switches between the two when guidance starts or ends.
            boolean navigating = wantFastLocation();
            long interval = LocationRatePolicy.gpsIntervalMs(navigating);
            float minDistance = LocationRatePolicy.minDistanceMetres(navigating);
            boolean fused = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && locationManager.hasProvider(LocationManager.FUSED_PROVIDER)
                    && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                LocationRequest request = new LocationRequest.Builder(interval)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateIntervalMillis(interval)
                        .setMinUpdateDistanceMeters(minDistance)
                        .build();
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER,
                        request, getMainExecutor(), this);
                fused = true;
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // While navigating this is 0 ms / 0 m: the fastest the GNSS
                // hardware supports, typically 1 Hz. This is the main battery cost.
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        interval, minDistance, this);
                locationManager.registerGnssStatusCallback(gnssCallback,
                        new Handler(Looper.getMainLooper()));
                startRotationSensor();
                requested = true;
            }
            // Fused already uses Wi-Fi and cell, so the network provider is only
            // needed on older phones.
            if (!fused && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        LocationRatePolicy.networkIntervalMs(navigating), minDistance, this);
                requested = true;
            }
            locationSubscribed = requested;
            subscribedForNavigation = navigating;
            fusedActive = fused;
            gpsHandler.removeCallbacks(deadReckonTick);
            if (requested) gpsHandler.post(deadReckonTick);
            // A rate switch mid-session must not jump the map to a stale area.
            if (requested && !resubscribing) restoreLastKnownMapArea();
        } catch (SecurityException error) {
            locationSubscribed = false;
            setStatus("Location permission denied. Enable Precise Location.");
        }
    }

    /**
     * Re-register location updates when guidance starts or ends, so the rate
     * matches the mode: fastest while navigating, lighter otherwise.
     */
    private void resubscribeLocationForMode() {
        if (locationManager == null || !locationSubscribed
                || subscribedForNavigation == wantFastLocation()) return;
        try {
            locationManager.removeUpdates(this);
            locationManager.unregisterGnssStatusCallback(gnssCallback);
            stopRotationSensor();
        } catch (SecurityException ignored) {
            // Permission revoked; startLocation() reports it.
        }
        locationSubscribed = false;
        resubscribing = true;
        try {
            startLocation();
        } finally {
            resubscribing = false;
        }
    }

    @Override public void onLocationChanged(Location fix) {
        if (fix == null || !MapStartupPolicy.freshForRouting(
                fix.getLatitude(), fix.getLongitude(), fix.getTime(),
                System.currentTimeMillis(),
                fix.hasAccuracy() ? fix.getAccuracy() : 101f)) return;
        // Drop noisy, out-of-order, teleporting or network-only fixes before they
        // reach the map, guidance or the off-route logic.
        FixQualityPolicy.Sample sample = new FixQualityPolicy.Sample(
                fix.getLatitude(), fix.getLongitude(), fix.getTime(),
                fix.hasAccuracy() ? fix.getAccuracy() : 99f,
                fixSource(fix));
        boolean mock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? fix.isMock() : fix.isFromMockProvider();
        LocationPipeline.Decision decision = locationPipeline.offer(sample, mock);
        if (decision.firstMock) {
            setStatus("Mock location in use · positions are simulated");
        }
        if (!decision.accepted) return;
        lastAcceptedFix = sample;
        boolean bearingTrusted = HeadingFusion.gpsBearingTrusted(fix.hasBearing(),
                fix.hasSpeed() ? fix.getSpeed() : 0f,
                fix.hasBearingAccuracy() ? fix.getBearingAccuracyDegrees() : 0f,
                fix.hasBearingAccuracy());
        if (bearingTrusted) headingFusion.onTrustedGpsBearing(fix.getBearing(), fix.getTime());
        double fusedHeading = bearingTrusted ? Double.NaN : headingFusion.fusedHeading(fix.getTime());
        boolean haveHeading = fix.hasBearing() || Double.isFinite(fusedHeading);
        GeoMath.Point fixPoint = new GeoMath.Point(fix.getLatitude(), fix.getLongitude());
        deadReckoner.onFix(fixPoint, fix.getTime(), fix.hasSpeed() ? fix.getSpeed() : 0f,
                fix.hasBearing() ? fix.getBearing() : (Double.isFinite(fusedHeading) ? fusedHeading : 0d),
                haveHeading);
        if (active && route != null) {
            routeBridge.onFix(fixPoint, fix.hasAccuracy() ? fix.getAccuracy() : 30f, fix.getTime(),
                    fix.hasSpeed() ? fix.getSpeed() : 0f, expectedSpeedMps(route, fixPoint));
        }
        endEstimate(System.currentTimeMillis());
        if (gpsLost) {
            gpsLost = false;
            if (active) {
                setStatus("GPS signal restored · checking route position");
                saySystemWarning("GPS signal restored.");
            }
        }
        if (currentLocation != null && fix.getTime() < currentLocation.getTime()
                && currentLocation.getTime() - fix.getTime() > 3000) return;
        currentLocation = fix;
        if (!placesWarmed) { placesWarmed = true; warmSavedPlaces(); }
        maybeOfferRegionPack(gpsPoint(fix));
        if (startWhenFix && !active && route != null) {
            startWhenFix = false;
            gpsHandler.removeCallbacks(startWaitTimeout);
            startNavigation();
        }
        if(upcomingDialog!=null&&!canInspectGuidance())dismissUpcoming();
        // The faster-route chip must disappear on its own as soon as the
        // offered geometry starts too far behind the moving car or goes stale.
        if (pendingRoute != null && (pendingRouteOrigin == null
                || System.currentTimeMillis() - pendingRouteAtMs > 45000
                || GeoMath.distance(gpsPoint(fix), pendingRouteOrigin) > 110)) {
            clearPendingRoute();
        }
        if (SystemClock.elapsedRealtime() - lastMapAreaSavedAt > 60000) {
            // Save only a coarse ~1 km area in app-private storage. Never use
            // this historical map centre as the origin of a TomTom route.
            lastMapAreaSavedAt = SystemClock.elapsedRealtime();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putFloat("last_map_lat",
                            (float) (Math.round(fix.getLatitude() * 100.0) / 100.0))
                    .putFloat("last_map_lon",
                            (float) (Math.round(fix.getLongitude() * 100.0) / 100.0))
                    .putLong("last_map_at", System.currentTimeMillis())
                    .apply();
        }
        GeoMath.Point smoothed = vehicleFilter.apply(gpsPoint(fix), fix.getTime(),
                fix.hasAccuracy() ? fix.getAccuracy() : 99f,
                fix.hasSpeed() ? fix.getSpeed() : 0f);
        displayGps = smoothed;
        if (vectorMap != null && useLocalMap) {
            if (!active || smoothFollow == null || !followLocation) vectorMap.setUnsnappedVehicle(smoothed,
                    fix.hasBearing()&&fix.hasSpeed()&&fix.getSpeed()>1.5f?fix.getBearing():Double.NaN);
            if (!autoFallback && !active) vectorMap.prefetch(gpsPoint(fix));
        }
        float filteredSpeed = fix.hasSpeed() && fix.getSpeed() >= 0.9f
                ? fix.getSpeed() : 0f;
        speedText.setText(NavigationUnits.displaySpeed(filteredSpeed, metricUnits));
        if (!active && !routing && destination == null && fix.hasAccuracy()) {
            setStatus((freeDriving?"Free driving · road limits not verified · GPS ±":"GPS ready · accuracy ±") + Math.round(fix.getAccuracy()) + " m"
                    + satelliteSuffix());
        }
        if (!active && followLocation) {
            // Fast idle GPS recenter, but ignore small stationary jitter.
            long now = SystemClock.elapsedRealtime();
            GeoMath.Point gps = gpsPoint(fix);
            double moved = lastCameraCenter == null ? Double.POSITIVE_INFINITY
                    : GeoMath.distance(lastCameraCenter, gps);
            if (MapFollowPolicy.shouldMove(lastCameraUpdate, now, moved,
                    fix.hasSpeed() ? fix.getSpeed() : 0f, 0, 0)) {
                centreOnLocation(false);
                lastCameraUpdate = now;
                lastCameraCenter = gps;
            }
        }
        if(freeDriving&&!active&&!routing)updateFreeRoadIntelligence(fix);
        updateLiveIncidents(fix);
        if (gpsAccuracyText != null && active) {
            gpsAccuracyText.setText("GPS accuracy "
                    + (fix.hasAccuracy() ? "±" + Math.round(fix.getAccuracy()) + " m" : "unknown")
                    + satelliteSuffix()
                    + (routing ? "  ·  updating traffic route…" : ""));
        }
        if (active && route != null) updateGuidance(fix);
        else if(active&&joiningRoute&&followLocation&&lastJoinCameraAt>=0&&SystemClock.elapsedRealtime()-lastJoinCameraAt>=1000){centreOnLocation(false);lastJoinCameraAt=SystemClock.elapsedRealtime();}
    }

    /** Maps a fix's provider to its FixQualityPolicy rank. */
    private static int fixSource(Location fix) {
        String provider = fix.getProvider();
        // "fused" is LocationManager.FUSED_PROVIDER (API 31); the literal keeps
        // this method safe to call on older phones.
        if ("fused".equals(provider)) return FixQualityPolicy.SOURCE_FUSED;
        if (LocationManager.GPS_PROVIDER.equals(provider)) return FixQualityPolicy.SOURCE_GPS;
        return FixQualityPolicy.SOURCE_NETWORK;
    }

    /** " · N satellites" once GNSS has reported; empty before that. */
    private String satelliteSuffix() {
        return satellitesUsed >= 0 ? " · " + satellitesUsed + " satellites" : "";
    }

    private BitmapDrawable makeCarIcon() {
        Bitmap source=NativeMapController.makeVehicleArrow();
        Bitmap scaled=Bitmap.createScaledBitmap(source,dp(55),dp(55),true);
        return new BitmapDrawable(getResources(),scaled);
    }

    private void clearLiveIncidents(){
        incidentGeneration++;lastIncidentRequest=0;lastIncidentCentre=null;
        if(vectorMap!=null)vectorMap.setLiveIncidents(null,0);
    }
    private void updateLiveIncidents(Location fix){
        if(!foreground||!(freeDriving||active)||!NativeEnhancementPolicy.enabled(trafficEnabled,incidentEnhancementKey)
                ||gpsLost||!showingVector()||!vectorMap.supportsLiveIncidents())return;
        if(!fix.hasAccuracy()||fix.getAccuracy()>50||incidentBusy)return;
        long now=SystemClock.elapsedRealtime();
        GeoMath.Point p=gpsPoint(fix);
        if(lastIncidentRequest!=0 && (now-lastIncidentRequest<45000 ||
                now-lastIncidentRequest<120000 && lastIncidentCentre!=null && GeoMath.distance(p,lastIncidentCentre)<1000))return;
        lastIncidentRequest=now;lastIncidentCentre=p;incidentBusy=true;
        final int generation=incidentGeneration;
        final String key=incidentEnhancementKey;
        incidentWorker.execute(()->{
            List<LiveTrafficIncidents.Event> data;
            try{data=TomTomApi.liveIncidents(key,p);}catch(Exception ignored){data=null;}
            final List<LiveTrafficIncidents.Event> result=data;
            final long fetched=System.currentTimeMillis();
            runOnUiThread(()->{
                incidentBusy=false;
                if(generation!=incidentGeneration||!foreground||!(freeDriving||active)
                    ||!NativeEnhancementPolicy.enabled(trafficEnabled,incidentEnhancementKey)||!key.equals(incidentEnhancementKey)||vectorMap==null||!vectorMap.supportsLiveIncidents()||gpsLost)return;
                if(currentLocation==null||GeoMath.distance(p,gpsPoint(currentLocation))>2000)return;
                if(result!=null)vectorMap.setLiveIncidents(result,fetched);
                // On failure the last successful snapshot expires independently; never imply clear roads.
            });
        });
    }
    private void clearFreeRoadContext(){
        freeContextGeneration++;freeContextBusy=false;freeContextRequestedAt=0;freeRoadContext=null;
        freeRoadMatch.reset();freeLastLimit=-1;freeLastLimitAt=0;freeAdvanceNode=-1;freeContextEndpoint="";
        if(vectorMap!=null)vectorMap.setBrowseGeometry(null);
        if(speedLimitText!=null)speedLimitText.setVisibility(View.GONE);
    }
    private void updateFreeRoadIntelligence(Location fix){
        GeoMath.Point point=gpsPoint(fix);
        final String endpoint="native-regional";
        if(freeRoadContext!=null && !freeRoadContext.freshAt(point)){
            freeRoadContext=null;freeRoadMatch.reset();freeLastLimit=-1;
            if(vectorMap!=null)vectorMap.setBrowseGeometry(null);
        }
        long now=SystemClock.elapsedRealtime();
        if(freeRoadContext==null&&!freeContextBusy
                &&(freeContextRequestedAt==0||now-freeContextRequestedAt>=15000)){
            freeContextBusy=true;freeContextRequestedAt=now;
            final int generation=freeContextGeneration;
            network.execute(()->{
                NativeRegionalRouter.RoadContext result;
                try{result=new NativeRegionalRouter(this).roadContext(point);}catch(Exception e){result=null;}
                final NativeRegionalRouter.RoadContext context=result;
                runOnUiThread(()->{
                    if(generation!=freeContextGeneration||!freeDriving||active)return;
                    freeContextBusy=false;
                    if(currentLocation!=null&&context!=null&&context.freshAt(gpsPoint(currentLocation))){
                        freeRoadContext=context;freeContextEndpoint=endpoint;
                        if(vectorMap!=null&&followLocation)vectorMap.setBrowseGeometry(context.map);
                    }
                });
            });
        }
        FreeDriveRoadMatch.Match match=null;
        if(freeRoadContext!=null&&!gpsLost&&fix.hasBearing()&&fix.hasSpeed()&&fix.hasAccuracy()
                &&(!fix.hasBearingAccuracy()||fix.getBearingAccuracyDegrees()<=20)){
            try{match=freeRoadMatch.confirm(FreeDriveRoadMatch.match(freeRoadContext.ways,point,fix.getAccuracy(),fix.getBearing(),fix.getSpeed()),fix.getTime());}
            catch(Exception ignored){freeRoadMatch.reset();}
        }else freeRoadMatch.reset();
        if(match==null){
            speedLimitText.setVisibility(View.GONE);speedText.setTextColor(Color.WHITE);
            if(now-freeLastLimitAt>10000)freeLastLimit=-1;
            return;
        }
        int shown=metricUnits?match.kmh:(int)Math.round(match.kmh/1.609344);
        speedLimitText.setText(String.valueOf(shown));speedLimitText.setVisibility(View.VISIBLE);
        speedLimitText.setContentDescription("Mapped speed limit "+shown+(metricUnits?" km/h":" mph")+". Check road signs.");
        String status="Free driving · mapped limit "+shown+(metricUnits?" km/h":" mph")+(match.name.isEmpty()?"":" · "+match.name);
        FreeDriveRoadMatch.Upcoming next=null;
        try{next=FreeDriveRoadMatch.upcoming(freeRoadContext.ways,match,point,fix.getSpeed(),freeRoadContext.bounds);}catch(Exception ignored){}
        if(next!=null){
            int future=metricUnits?next.kmh:(int)Math.round(next.kmh/1.609344);
            status+=" · "+future+(metricUnits?" km/h":" mph")+" in "+NavigationUnits.displayDistance(next.distance,metricUnits)+" if continuing";
            if(advanceSpeedVoice&&voiceMode!=DrivingVoicePolicy.SILENT&&speechReady&&speech!=null&&!speech.isSpeaking()&&freeAdvanceNode!=next.node){
                freeAdvanceNode=next.node;
                speak("If continuing on this road, mapped limit "+future+(metricUnits?" kilometres per hour in ":" miles per hour in ")
                    +NavigationUnits.spokenDistance(next.distance,metricUnits)+". Check road signs.");
            }
        }
        setStatus(status);
        boolean speeding=fix.getSpeed()*2.2369362921>match.kmh/1.609344+speedToleranceMph;
        speedText.setTextColor(speeding?Color.rgb(255,197,85):Color.WHITE);
        if(speeding&&speedWarningEnabled&&speechReady&&speech!=null&&!speech.isSpeaking()
            &&(lastSpeedWarningAt==0||now-lastSpeedWarningAt>120000)){
            lastSpeedWarningAt=now;sayVerifiedAlert("Check your speed. You may be above the mapped speed limit.",true);
        }
        if(freeLastLimit>0&&freeLastLimit!=match.kmh&&advanceSpeedVoice&&voiceMode!=DrivingVoicePolicy.SILENT
                &&speechReady&&speech!=null&&!speech.isSpeaking())
            speak("Mapped speed limit now "+shown+(metricUnits?" kilometres per hour.":" miles per hour.")+" Check road signs.");
        freeLastLimit=match.kmh;freeLastLimitAt=now;
    }
    private void toggleFreeDriving(){
        if(active||route!=null||routing){Toast.makeText(this,"End the route before using free driving.",Toast.LENGTH_SHORT).show();return;}
        freeDriving=!freeDriving;
        clearFreeRoadContext();
        freeDriveBearing=Double.NaN;followLocation=true;updateFollowButton();
        if(freeDriving)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setHomeVisible(true);
        setStatus(freeDriving?"Free driving · GPS and mapped features · road limits not verified":"Map browsing");
        if(currentLocation!=null)centreOnLocation(true);
    }

    private void centreOnLocation(boolean forceZoom) {
        if (currentLocation == null) return;
        GeoMath.Point position = gpsPoint(currentLocation);
        boolean vector = showingVector();
        if(freeDriving && !active && route==null){
            float speed=currentLocation.hasSpeed()?Math.max(0,currentLocation.getSpeed()):0;
            if(currentLocation.hasBearing() && speed>1.5f && currentLocation.hasAccuracy() && currentLocation.getAccuracy()<=25){
                double next=currentLocation.getBearing();
                freeDriveBearing=Double.isFinite(freeDriveBearing)?(freeDriveBearing+Math.max(-45,Math.min(45,(next-freeDriveBearing+540)%360-180))+360)%360:next;
            }
            double heading=Double.isFinite(freeDriveBearing)?freeDriveBearing:0;
            double bearing=DrivingDisplayPolicy.northUp(cameraMode)?0:heading;
            double pitch=DrivingDisplayPolicy.tilt(45+Math.min(9,speed*.25),cameraMode);
            double cameraZoom=Math.max(14.0,16.8-speed*.055);
            GeoMath.Point centre=position;
            if(Double.isFinite(freeDriveBearing) && !DrivingDisplayPolicy.northUp(cameraMode)){
                double ahead=Math.min(110,35+speed*2),rad=Math.toRadians(heading);
                centre=new GeoMath.Point(position.lat+Math.cos(rad)*ahead/111320,
                        position.lon+Math.sin(rad)*ahead/(111320*Math.max(.15,Math.cos(Math.toRadians(position.lat)))));
            }
            if(vector)vectorMap.setBrowseCamera(centre,cameraZoom,bearing,pitch,heading);
            lastCameraCenter=position;return;
        }
        double zoom=vectorMap!=null?vectorMap.currentZoom():16.5;
        if(forceZoom||zoom<14)zoom=16.5;
        if(vectorMap!=null)vectorMap.setCamera(position,zoom,0);
        lastCameraCenter = position;
    }

    /**
     * During active guidance keep the vehicle in the lower part of the visible
     * map, with the next manoeuvre and exit road above it. The camera uses
     * road geometry rather than a noisy stopped-vehicle compass bearing.
     */
    /**
     * GPS-cadence planning only: heavy route geometry and junction look-ahead
     * never run on every rendered frame. SmoothFollowCamera owns interpolation,
     * zoom hysteresis and bearing changes on the independent UI clock.
     */
    private void updateDrivingCamera(Location fix, GeoMath.Progress progress,
                                     double nextJunctionOffsetMetres) {
        // The first fresh fix always starts the driving camera, so the map
        // switches from the route overview to a heading-up driving view straight
        // away. Afterwards poor or off-route fixes are skipped here, and
        // SmoothFollowCamera itself ignores untrustworthy ones.
        boolean cameraStarted = smoothFollow != null && smoothFollow.initialized();
        if (!active || !followLocation || route == null || gpsLost
                || vectorMap.view().getHeight() < dp(350)) return;
        if (cameraStarted && (progress.offRouteMetres > 110
                || (fix.hasAccuracy() && fix.getAccuracy() > 75))) return;
        long now = SystemClock.elapsedRealtime();
        if (lastCameraUpdate > 0 && now - lastCameraUpdate < 450) return;
        if (smoothFollow == null)
            smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(route.points));

        int headerHeight = topMapOverlay != null && topMapOverlay.getHeight() > 0
                ? topMapOverlay.getHeight() + dp(5) : dp(140);
        int bottomPanelHeight = navigationPanel.getHeight() > 0
                ? navigationPanel.getHeight() + dp(18) : dp(95);
        double along = stabilizedAlong < 0 ? progress.alongMetres : stabilizedAlong;
        float velocity = fix.hasSpeed() ? fix.getSpeed() : 0f;
        NavigationCamera.Frame target = NavigationCamera.calculate(
                route.points, gpsPoint(fix), along, nextJunctionOffsetMetres,
                velocity, vectorMap.view().getHeight(), headerHeight, bottomPanelHeight,
                closeZoom || highlightedJunctionIndex >= 0);
        float accuracy = fix.hasAccuracy() ? fix.getAccuracy() : 100f;
        // Retain the route-aware look-ahead in all camera modes. Flat modes
        // request zero pitch instead of abandoning the smooth-follow engine.
        double remaining=Math.max(0,GeoMath.length(route.points)-along);
        boolean shortTrip=RoadTestDisplayPolicy.wideApproach(remaining,nextJunctionOffsetMetres-along)
                && highlightedJunctionIndex<0 && (!closeZoom||nextJunctionOffsetMetres-along>420);
        List<GeoMath.Point> cameraContext=junctionFramePoints;
        if(shortTrip){
            cameraContext=new ArrayList<>();
            for(int i=0;i<=8;i++)cameraContext.add(GeoMath.pointAtDistance(route.points,along+remaining*i/8.0));
            double centerAlong=along+remaining*.40;
            GeoMath.Point center=GeoMath.pointAtDistance(route.points,centerAlong);
            target=new NavigationCamera.Frame(center,target.zoom,target.roadBearingDegrees,target.junctionMetres,centerAlong,Math.min(38,target.tiltDegrees));
        }
        if (vectorMap != null && showingVector()) {
            double distance = nextJunctionOffsetMetres-along;
            GeoMath.Point junction = distance >= 0 && distance <= Math.max(480,velocity*8+100)
                    ? GeoMath.pointAtDistance(route.points,nextJunctionOffsetMetres) : null;
            vectorMap.setFollowViewport(headerHeight,bottomPanelHeight,junction,
                    shortTrip?cameraContext:distance<=260&&distance>=0?junctionFramePoints:java.util.Collections.emptyList());
        }
        boolean frameJunction=!junctionFramePoints.isEmpty()&&target.junctionMetres<=260;
        double fittedZoom=(frameJunction||shortTrip)?JunctionFraming.fitZoom(target.zoom,target.center,gpsPoint(fix),cameraContext,vectorMap.view().getWidth(),vectorMap.view().getHeight(),headerHeight,bottomPanelHeight,dp(20)):target.zoom;
        target = new NavigationCamera.Frame(target.center, fittedZoom,
                target.roadBearingDegrees, target.junctionMetres,
                target.cameraCenterAlongMetres,
                DrivingDisplayPolicy.tilt(frameJunction?Math.min(20,target.tiltDegrees):target.tiltDegrees, cameraMode));
        smoothFollow.onGps(displayGps != null ? displayGps : gpsPoint(fix),
                along, progress.offRouteMetres, accuracy, velocity,
                Math.max(0, nextJunctionOffsetMetres - along),
                target, DrivingDisplayPolicy.northUp(cameraMode), now);
        lastCameraUpdate = now;
        startSmoothLoop();
    }

    private void searchDestination() {
        if (!ownRouting() && apiKey.isEmpty()) {
            showKeyDialog();
            return;
        }
        String query = UkSearch.normalize(destinationInput.getText().toString());
        if (query.length() < 2) {
            destinationInput.setError("Enter an address or postcode");
            return;
        }
        destinationInput.dismissDropDown();
        destinationInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(destinationInput.getWindowToken(), 0);
        }
        setStatus("Finding " + query + "…");
        GeoMath.Point near = currentLocation == null ? null : gpsPoint(currentLocation);
        final int searchId = ++requestGeneration;
        network.execute(() -> {
            try {
                List<TomTomApi.Destination> found = providerSearch(query, near);
                runOnUiThread(() -> {
                    if (searchId != requestGeneration || isFinishing()) return;
                    if (found.isEmpty()) {
                        showError("No matching destinations. Try a fuller address."+(OnlineFirst.lastProblem.isEmpty()?"":" (Online search: "+OnlineFirst.lastProblem+")"));
                        return;
                    }
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).toString();
                    new AlertDialog.Builder(this)
                            .setTitle("Choose destination")
                            .setItems(labels, (d, which) -> selectDestination(found.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                    setStatus("Select the matching destination.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (searchId == requestGeneration) showError(error.getMessage());
                });
            }
        });
    }


    /** Select saved/recent destinations without making another search API call. */
    private void showSavedDestinations() {
        List<TomTomApi.Destination> favorites = places.favorites();
        List<TomTomApi.Destination> recents = places.recent();
        if (favorites.isEmpty() && recents.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Places")
                    .setMessage("No places saved yet. Search for a destination to build your list.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<TomTomApi.Destination> results = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (TomTomApi.Destination item : favorites) {
            results.add(item);
            descriptions.add("★ " + item.toString());
        }
        for (TomTomApi.Destination item : recents) {
            boolean alreadyShown = false;
            for (TomTomApi.Destination saved : favorites) {
                if (GeoMath.distance(item.point, saved.point) < 2) {
                    alreadyShown = true;
                    break;
                }
            }
            if (!alreadyShown) {
                results.add(item);
                descriptions.add("Recent · " + item.toString());
            }
        }
        new AlertDialog.Builder(this).setTitle("Saved & recent places")
                .setItems(descriptions.toArray(new String[0]), (dialog, which) ->
                        selectDestination(results.get(which)))
                .setNeutralButton("Remove favourite", (dialog, which) -> showRemoveFavorite())
                .setNegativeButton("Close", null).show();
    }

    private void showRemoveFavorite() {
        List<TomTomApi.Destination> favorites = places.favorites();
        if (favorites.isEmpty()) {
            Toast.makeText(this, "No saved favourites to remove.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[favorites.size()];
        for (int i = 0; i < favorites.size(); i++) names[i] = favorites.get(i).toString();
        new AlertDialog.Builder(this).setTitle("Remove a favourite")
                .setItems(names, (dialog, which) -> {
                    places.removeFavorite(favorites.get(which));
                    if (destination != null && !active && route != null) showPreview(route);
                    Toast.makeText(this, "Favourite removed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveCurrentDestination() {
        if (destination == null) return;
        if (places.isFavorite(destination)) {
            Toast.makeText(this, "Already saved in favourites", Toast.LENGTH_SHORT).show();
            return;
        }
        places.addFavorite(destination);
        saveButton.setText("★ Saved");
        refreshHomePanel();
        Toast.makeText(this, "Saved to favourites", Toast.LENGTH_SHORT).show();
    }

    private String previewDetails(TomTomApi.Route calculated) {
        String detail = NavigationUnits.displayDistance(calculated.lengthMetres, metricUnits)
                + " · " + NavigationUnits.duration(calculated.timeSeconds);
        if (calculated.trafficDelaySeconds > 0) {
            detail += " · traffic +" + NavigationUnits.duration(calculated.trafficDelaySeconds);
        } else {
            detail += NavigationStartPolicy.trafficStatus(calculated.provider);
        }
        return detail + "\n" + RouteMapPolicy.description(calculated.provider, calculated.nativeScene != null);
    }

    private void showPreview(TomTomApi.Route calculated) {
        active = false;
        applyHighDetailPolicy();
        NavigationService.stop(this);
        resubscribeLocationForMode();
        setHomeVisible(false);
        routeSummary.setText(previewDetails(calculated));
        routeSummary.setVisibility(View.VISIBLE);
        instructionText.setText(NavigationStartPolicy.previewOnly(calculated.provider)?"Experimental route preview — not for driving.":"Review your route before driving.");
        maneuverIcon.setVisibility(View.GONE);
        nextTurnText.setVisibility(View.GONE);
        distanceText.setText("Distance: " + NavigationUnits.displayDistance(
                calculated.lengthMetres, metricUnits));
        journeyTimeText.setText("Drive: " + NavigationUnits.duration(calculated.timeSeconds));
        etaText.setText("ETA: " + new SimpleDateFormat("HH:mm", Locale.UK)
                .format(new Date(System.currentTimeMillis()
                        + calculated.timeSeconds * 1000L)));
        startButton.setVisibility(NavigationStartPolicy.previewOnly(calculated.provider)?View.GONE:View.VISIBLE);
        saveButton.setVisibility(View.VISIBLE);
        if (previewParkingButton != null) previewParkingButton.setVisibility(NavigationStartPolicy.previewOnly(calculated.provider)?View.GONE:View.VISIBLE);
        saveButton.setText(places.isFavorite(destination) ? "★ Saved" : "★ Save");
        repeatButton.setVisibility(View.GONE);
        refreshButton.setVisibility(View.GONE);
        stopButton.setText("Cancel");
        stopButton.setContentDescription("Cancel route preview");
        stopButton.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus(NavigationStartPolicy.previewOnly(calculated.provider)?"Offline route preview · NOT FOR DRIVING":"Route ready · tap Start when parked and ready"
                + ("TomTom".equals(calculated.provider) ? " · TomTom live traffic"
                : onlineKey().isEmpty() ? " · offline route (add TomTom key in Settings)"
                : OnlineFirst.lastProblem.isEmpty() ? " · offline route" : " · offline route (online: " + OnlineFirst.lastProblem + ")"));
    }

    private void startNavigation() {
        if (route == null || destination == null) return;
        if (NavigationStartPolicy.previewOnly(route.provider)){showError("Local routing is preview-only until driving guidance is validated.");return;}
        if (currentLocation == null || !NavigationStartPolicy.freshFix(currentLocation.getLatitude(), currentLocation.getLongitude(), currentLocation.getTime(), System.currentTimeMillis(), currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : Float.NaN)) {
            // Do not just refuse: ask for full-rate fixes and start as soon as a fresh one arrives.
            if (!startWhenFix) {
                startWhenFix = true;
                setStatus("Getting a GPS fix · navigation starts as soon as it arrives");
                resubscribeLocationForMode();
                gpsHandler.postDelayed(startWaitTimeout, 20000);
            }
            return;
        }
        startWhenFix = false;
        gpsHandler.removeCallbacks(startWaitTimeout);
        active = true;
        arrivalPolicy.reset();
        if (previewParkingButton != null) previewParkingButton.setVisibility(View.GONE);
        smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(route.points));
        keyboardOpen = false;
        destinationInput.clearFocus();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(destinationInput.getWindowToken(), 0);
        setHomeVisible(false);
        followLocation = true;
        updateFollowButton();
        topTurnTitle.setText("NEXT TURN");
        maneuverIcon.setVisibility(View.VISIBLE);
        nextTurnText.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        routeSummary.setVisibility(View.GONE);
        startButton.setVisibility(View.GONE);
        saveButton.setVisibility(View.GONE);
        repeatButton.setVisibility(View.VISIBLE);
        refreshButton.setVisibility(View.VISIBLE);
        applyDrivingControls();
        stopButton.setText("End");
        stopButton.setContentDescription("End navigation");
        setStatus("Navigating with "+route.provider+" · "+RouteMapPolicy.description(route.provider, route.nativeScene != null));
        mapReview.clear(); comparisonRoute=null; comparisonBusy=false; comparisonRequestedAt=0; reviewGeneration++; requestComparison();
        guidancePositionConfirmed=false;confirmedSinceRouteInstall=false;joiningRoute=false;lastJoinCameraAt=-1;
        nextInstruction="";
        say("Navigation started.");
        lastCameraUpdate = 0;
        previousInstructionIndex = 0;
        speedChangeAwareness.reset();roadsideAwareness.reset();
        stabilizedAlong = -1;
        navigationMatch = null;
        lastRoutePrefetchAt = -1;
        observedSpeed = 0;
        applyHighDetailPolicy();
        startBackgroundNavigation();
        resubscribeLocationForMode();
        updateGuidance(currentLocation);
        startSmoothLoop();
    }

    /**
     * Keep guidance alive with the screen off or another app in front. Android
     * 13+ also needs the notification permission to show the ongoing
     * "navigating" notification; navigation still works if it is refused.
     */
    private void startBackgroundNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
        try {
            NavigationService.start(this, destination == null ? "" : destination.title);
        } catch (RuntimeException error) {
            // Android can refuse a foreground service in rare states; on-screen
            // navigation still works, it just won't continue in the background.
            android.util.Log.w("DriveMateNav", "Background navigation unavailable", error);
        }
    }

    /**
     * TomTom's high-detail raster tiles have street names and POI pins drawn
     * into the image, so they turn upside down when the map rotates and can't
     * be filtered. While navigating with a rotating camera, use the vector
     * streets instead (labels stay upright, no POI clutter). North-up and the
     * home screen keep the high-detail tiles when the setting is on.
     */
    private void applyHighDetailPolicy() {
        if (vectorMap == null) return;
        boolean rotating = active && cameraMode != DrivingDisplayPolicy.CAMERA_NORTH_2D;
        // Never swap the underlying map at a zoom threshold. Separate explicit map choice remains available.
        vectorMap.setHighDetailEnabled(false);
        // Same moments decide map clutter: shops, cafés and bus stops hide
        // while navigating; fuel, EV charging, parking and stations stay.
        vectorMap.setBuildings3D(buildings3d);
        vectorMap.setNavigationDetail(active);
    }

    private void selectDestination(TomTomApi.Destination selected) {
        if (currentLocation == null || !MapStartupPolicy.freshForRouting(
                currentLocation.getLatitude(), currentLocation.getLongitude(),
                currentLocation.getTime(), System.currentTimeMillis(),
                currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 101f)) {
            // Selecting a destination must never silently use the remembered
            // startup map centre or an old system cached GPS measurement.
            ensureLocationPermission();
            showError(gpsUnavailableMessage());
            return;
        }
        if (route != null || active) stopNavigation(false);
        setHomeVisible(false);
        destination = selected;
        arrivalPolicy.reset();
        displayEntranceMarker(selected);
        places.addRecent(selected);
        followLocation = false;
        updateFollowButton();
        destinationInput.setText(selected.title, false);
        destinationInput.dismissDropDown();
        if(vectorMap!=null)vectorMap.setDestination(selected.point);
        planRoute(true);
    }

    /** Only a provider-listed POI main entry point is marked as an entrance. */
    private void displayEntranceMarker(TomTomApi.Destination selected) {
        GeoMath.Point entry=selected==null?null:selected.mainEntrance;
        if(vectorMap!=null)vectorMap.setEntrance(entry);
    }

    private void showArrivalApproach(ArrivalPolicy.State state) {
        if (arrivalHintText == null || !(arrivalHintText.getTag() instanceof View))
            return;
        View panel = (View) arrivalHintText.getTag();
        if (state == ArrivalPolicy.State.EN_ROUTE || destination == null) {
            panel.setVisibility(View.GONE);
            return;
        }
        // Road side comes only from TomTom's final manoeuvre (ARRIVE_LEFT/RIGHT).
        String side = route == null || route.instructions.isEmpty() ? null
                : ArrivalPolicy.sideLabel(
                        route.instructions.get(route.instructions.size() - 1).maneuver);
        boolean nearby = state == ArrivalPolicy.State.NEARBY;
        String label = side != null
                ? side + (nearby ? " · slow down" : " · follow final road")
                : nearby ? "Destination nearby · slow down and check access"
                        : "Destination ahead · follow final road";
        if (destination.mainEntrance != null)
            label += "\nMap-listed entrance marked · verify access";
        else
            label += side != null ? "\nEntrance not verified · check access"
                    : "\nEntrance and correct road side not verified";
        arrivalHintText.setText(label);
        panel.setVisibility(View.VISIBLE);
        // The parking lookup is offered only when the driver is stationary.
        boolean parked = currentLocation != null && currentLocation.hasSpeed()
                && currentLocation.getSpeed() <= 1.2f
                && System.currentTimeMillis() - currentLocation.getTime() <= 7000;
        findParkingButton.setEnabled(parked);
        // Parking search belongs to the very end of the journey.
        findParkingButton.setVisibility(nearby ? View.VISIBLE : View.GONE);
        findParkingButton.setAlpha(parked ? 1f : 0.45f);
    }

    /** Parking information is requested only on demand and never guessed. */
    private void findNearbyParking(){showError("Native parking search is not yet validated. No external search fallback.");}

    private void offerSaveParkedCar() {
        new AlertDialog.Builder(this).setTitle("Save where you parked?")
                .setMessage("Store this GPS position on this phone only. "
                        + "You can view or clear it from the DriveMate home screen.")
                .setPositiveButton("Save location", (dialog, which) -> saveParkedCarNow())
                .setNegativeButton("Not now", null).show();
    }

    private void saveParkedCarNow() {
        if (currentLocation == null || !ArrivalPolicy.canSaveParking(
                currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : Float.MAX_VALUE,
                currentLocation.hasSpeed() ? currentLocation.getSpeed() : Float.MAX_VALUE,
                currentLocation.getTime(), System.currentTimeMillis())) {
            showError("Parking location not saved · wait for fresh precise stationary GPS.");
            return;
        }
        places.saveParkedCar(gpsPoint(currentLocation), System.currentTimeMillis());
        refreshHomePanel();
        Toast.makeText(this, "Parked car saved locally", Toast.LENGTH_LONG).show();
    }

    /**
     * Settings → "Share diagnostics": a text snapshot of GPS quality and the
     * camera/arrow angles, for reporting display problems such as a sideways
     * car arrow. No coordinates, addresses or destination names are included,
     * and nothing is sent unless the driver picks an app to share it with.
     */
    private void shareDiagnostics() {
        DiagnosticsReport.Snapshot s = new DiagnosticsReport.Snapshot();
        s.appVersion = installedVersionName();
        s.androidSdk = Build.VERSION.SDK_INT;
        s.device = Build.MANUFACTURER + " " + Build.MODEL;
        s.navigating = active;
        s.gpsLost = gpsLost;
        s.fixSource = lastAcceptedFix == null ? ""
                : lastAcceptedFix.source == FixQualityPolicy.SOURCE_FUSED ? "fused"
                : lastAcceptedFix.source == FixQualityPolicy.SOURCE_GPS ? "gps" : "network";
        if (fusedActive && s.fixSource.isEmpty()) s.fixSource = "fused (no fix yet)";
        if (currentLocation != null) {
            if (currentLocation.hasAccuracy()) s.accuracyMetres = currentLocation.getAccuracy();
            if (currentLocation.hasSpeed()) s.speedMps = currentLocation.getSpeed();
            if (currentLocation.hasBearing()) s.gpsBearing = currentLocation.getBearing();
        }
        s.satellitesUsed = satellitesUsed;
        s.l5SatellitesUsed = l5SatellitesUsed;
        s.fixesAccepted = locationPipeline.accepted();
        s.fixRejections = locationPipeline.rejectionSummary();
        s.positionBridges = locationPipeline.bridgeSummary();
        s.mockFixes = locationPipeline.mockFixes();
        s.estimatedPosition = showingEstimate;
        s.routingStats = NativeRegionalRouter.STATS.summary();
        s.headingFusion = headingFusion.fusedHeading(System.currentTimeMillis());
        s.offRouteMetres = active ? lastOffRouteMetres : Double.NaN;
        s.matchReason=(joiningRoute?"JOINING_ROUTE / "+lastMatchReason:lastMatchReason)
                +(route==null?"":" / map: "+(vectorMap!=null&&showingVector()?vectorMap.geometryDescription():RouteMapPolicy.description(route.provider,false)))
                +(showingVector()&&vectorMap!=null?" / visual alignment: "+vectorMap.routeAlignmentDiagnostic():" / raster alignment not measured")
                +" / base map "+DriveTileServer.MAP_RELEASE;
        s.fixAgeMs=currentLocation==null?-1:Math.max(0,System.currentTimeMillis()-currentLocation.getTime());
        s.cameraMode = cameraModeName();
        s.followingCar = followLocation;
        s.highDetailTiles = false; // vector default; explicit raster fallback remains possible
        s.cameraBearing = lastCameraBearing;
        s.travelBearing = lastTravelBearing;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "DriveMate diagnostics")
                .putExtra(Intent.EXTRA_TEXT, DiagnosticsReport.build(s));
        startActivity(Intent.createChooser(share, "Share DriveMate diagnostics"));
    }

    /** Settings → "Save where I parked": only saves a precise, stationary GPS fix. */
    private void promptSaveParking() {
        if (currentLocation == null || !ArrivalPolicy.canSaveParking(
                currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : Float.MAX_VALUE,
                currentLocation.hasSpeed() ? currentLocation.getSpeed() : Float.MAX_VALUE,
                currentLocation.getTime(), System.currentTimeMillis())) {
            showError("Park safely and wait for precise stationary GPS before saving.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Save parked-car location?")
                .setMessage("Only the current GPS estimate is saved on this phone. "
                        + "It can be cleared from Settings → Find my parked car.")
                .setPositiveButton("Save", (d, w) -> saveParkedCarNow())
                .setNegativeButton("Cancel", null).show();
    }

    private void showParkedCar() {
        GeoMath.Point saved = places.parkedCar();
        if (saved == null) {
            Toast.makeText(this, "No saved parking position", Toast.LENGTH_SHORT).show();
            refreshHomePanel();
            return;
        }
        // Leave the saved position visible rather than having the next live
        // GPS fix immediately drag the map back to the driver's location.
        followLocation = false;
        updateFollowButton();
        if(vectorMap!=null){vectorMap.setParkedCar(saved);vectorMap.setCamera(saved,17,0);}
        new AlertDialog.Builder(this).setTitle("Parked car · saved on this phone")
                .setMessage("Showing the GPS position you explicitly saved. "
                        + "It may not be an exact parking bay. "
                        + "DriveMate does not provide a walking route to your car.")
                .setPositiveButton("Done", null)
                .setNeutralButton("Clear saved position", (dialog, which) -> {
                    places.clearParkedCar();
                    if (vectorMap != null) vectorMap.setParkedCar(null);
                    refreshHomePanel();
                }).show();
    }

    private GeoMath.Point gpsPoint(Location location) {
        return new GeoMath.Point(location.getLatitude(), location.getLongitude());
    }

    /** Connectivity is only advisory; an existing map/route is preserved. */
    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        try {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException ignored) {
            return true;
        }
    }

    private void planRoute(boolean firstRoute) {
        if (routing || destination == null || currentLocation == null || (!ownRouting() && apiKey.isEmpty())) return;
        if (!localRouting() && !hasNetwork()) {
            setStatus("No internet · current route retained; traffic updates paused");
            if (route == null) showError("Connect to the internet to calculate a route.");
            return;
        }
        if (System.currentTimeMillis() - currentLocation.getTime() > 20000) {
            setStatus("Waiting for a fresh GPS position; current route retained");
            return;
        }
        routing = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        final int plannedGeneration = ++requestGeneration;
        // Plan from the steadier smoothed position when it agrees with the raw fix, and
        // from the travel direction so the route starts at a junction ahead of the car.
        final GeoMath.Point start = RerouteStart.choose(gpsPoint(currentLocation), displayGps);
        final double startBearing = currentLocation.hasBearing() && currentLocation.hasSpeed()
                && currentLocation.getSpeed() >= 3f
                && (!currentLocation.hasBearingAccuracy() || currentLocation.getBearingAccuracyDegrees() <= 30)
                ? currentLocation.getBearing() : Double.NaN;
        final GeoMath.Point end = destination.point;
        setStatus("Calculating route…");
        network.execute(() -> {
            try {
                TomTomApi.Route online = OnlineFirst.route(onlineKey(),start,end,hasNetwork());
                final TomTomApi.Route calculated = online != null ? online
                        : new NativeRegionalRouter(this).preview(start,end,startBearing);
                runOnUiThread(() -> {
                    if (plannedGeneration != requestGeneration || isFinishing()) return;
                    routing = false;
                    // Route updates start from the *current* GPS. Compare only
                    // after confirming the old route is still being followed.
                    // The decisions live in RouteUpdatePolicy so they are unit tested.
                    GeoMath.Progress old = null;
                    boolean confirmedOffRoute = false;
                    if (!firstRoute && active && route != null) {
                        old = GeoMath.project(route.points, start);
                        // Same rule as the reroute trigger, so a reroute is not
                        // thrown away as "GPS alignment" noise.
                        confirmedOffRoute = currentLocation != null && currentLocation.hasAccuracy()
                                && ReroutePolicy.offRoute(old.offRouteMetres, currentLocation.getAccuracy());
                    }
                    RouteUpdatePolicy.Gate gate = RouteUpdatePolicy.gate(firstRoute, active, route != null,
                            currentLocation != null && currentLocation.hasAccuracy(),
                            currentLocation == null ? 0f : currentLocation.getAccuracy(),
                            currentLocation == null ? Long.MAX_VALUE
                                    : System.currentTimeMillis() - currentLocation.getTime(),
                            currentLocation == null ? Double.MAX_VALUE
                                    : GeoMath.distance(gpsPoint(currentLocation), start),
                            confirmedOffRoute, old == null ? 0 : old.offRouteMetres);
                    if (gate == RouteUpdatePolicy.Gate.RETAIN_UNCERTAIN_GPS) {
                        setStatus("Uncertain GPS · current route retained");
                        return;
                    } else if (gate == RouteUpdatePolicy.Gate.RETAIN_GPS_MOVED) {
                        // A route calculated from a GPS point that the driver
                        // has already left is unsafe to install automatically.
                        setStatus("GPS moved during route update · old route retained");
                        return;
                    } else if (gate == RouteUpdatePolicy.Gate.RETAIN_ALIGNMENT) {
                        setStatus("Checking GPS alignment · previous route retained");
                        return;
                    } else if (gate == RouteUpdatePolicy.Gate.COMPARE && old != null) {
                        boolean different = RouteIntelligence.genuinelyDifferent(
                                route.points, old.alongMetres, calculated.points);
                        double remaining = Math.max(0, route.lengthMetres - old.alongMetres);
                        int oldSeconds = (int) Math.round(
                                route.timeSeconds * remaining / Math.max(1, route.lengthMetres));
                        boolean oldFresh = System.currentTimeMillis() - route.fetchedAtMs <= 480000;
                        int saving = oldFresh ? RouteIntelligence.meaningfulSaving(
                                oldSeconds, calculated.timeSeconds, different) : 0;
                        double nextManoeuvre = -1;
                        for (TomTomApi.Instruction step : route.instructions) {
                            if (step.routeOffsetMetres > old.alongMetres) {
                                nextManoeuvre = step.routeOffsetMetres - old.alongMetres;
                                break;
                            }
                        }
                        RouteUpdatePolicy.Compare verdict = RouteUpdatePolicy.compare(
                                different, saving, oldFresh, rerouteMode, nextManoeuvre);
                        if (verdict == RouteUpdatePolicy.Compare.OFFER_FASTER) {
                            showPendingRoute(calculated, start, saving);
                            setStatus("Faster route available · choose Switch or Keep");
                            return;
                        } else if (verdict == RouteUpdatePolicy.Compare.OFFER_UNVERIFIED) {
                            // When the old ETA is stale, an alternative is a choice, not an
                            // automatically faster route. Never silently switch on that basis.
                            showPendingRoute(calculated, start, 0);
                            setStatus("Updated route · time saving unverified");
                            return;
                        } else if (verdict == RouteUpdatePolicy.Compare.RETAIN_NEAR_MANOEUVRE) {
                            setStatus("Faster route ignored · next turn is close");
                            return;
                        } else if (verdict == RouteUpdatePolicy.Compare.RETAIN_NO_SAVING) {
                            setStatus("Current route retained · no verified time saving");
                            return;
                        }
                        // INSTALL: automatic mode with a verified improvement, or not genuinely different.
                    }
                    installCalculatedRoute(calculated, firstRoute);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (plannedGeneration != requestGeneration) return;
                    routing = false;
                    boolean coverage = error instanceof DmTileEngine.CoverageException
                            || String.valueOf(error.getMessage()).contains("regional coverage");
                    if (route != null) {
                        if (active) rerouteFailures++;
                        long retrySeconds = ReroutePolicy.retryGapMs(active ? rerouteFailures : 0) / 1000;
                        setStatus("Update failed · following last known route"
                                + (active ? " · retrying in " + retrySeconds + " s" : "")
                                + (coverage ? " · outside installed regions: download a regional pack" : ""));
                        Toast.makeText(this, "Route update unavailable: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    } else {
                        showError(error.getMessage());
                        stopButton.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    /** Only accept a suggestion if still close to the GPS it was planned for. */
    private void acceptPendingRoute() {
        if (!active || pendingRoute == null || pendingRouteOrigin == null
                || currentLocation == null) {
            clearPendingRoute();
            return;
        }
        if (System.currentTimeMillis() - pendingRouteAtMs > 45000
                || System.currentTimeMillis() - currentLocation.getTime() > 12000
                || GeoMath.distance(gpsPoint(currentLocation), pendingRouteOrigin) > 110) {
            clearPendingRoute();
            setStatus("Suggested route expired · checking again");
            planRoute(false);
            return;
        }
        TomTomApi.Route chosen = pendingRoute;
        clearPendingRoute();
        installCalculatedRoute(chosen, false);
        setStatus("Chosen faster or updated route ready");
    }

    /** Direction of the route at this distance along it, or NaN when there is no route. */
    private double routeBearingAt(double along) {
        if (route == null || route.points.size() < 2) return Double.NaN;
        return GeoMath.bearingDegrees(GeoMath.pointAtDistance(route.points, Math.max(0, along - 5)),
                GeoMath.pointAtDistance(route.points, along + 5));
    }

    private void showPendingRoute(TomTomApi.Route candidate, GeoMath.Point origin,
                                  int savingSeconds) {
        pendingRoute = candidate;
        pendingRouteOrigin = origin;
        pendingRouteAtMs = System.currentTimeMillis();
        pendingSavingSeconds = savingSeconds;
        if (rerouteSuggestionText != null) {
            String label = savingSeconds > 0
                    ? "Faster route · save about " + Math.max(1,
                            Math.round(savingSeconds / 60f)) + " min"
                    : "Updated route available";
            rerouteSuggestionText.setText(label);
        }
        if (rerouteSuggestionRow != null)
            rerouteSuggestionRow.setVisibility(View.VISIBLE);
    }

    private void clearPendingRoute() {
        pendingRoute = null;
        pendingRouteOrigin = null;
        pendingRouteAtMs = 0;
        pendingSavingSeconds = 0;
        if (rerouteSuggestionRow != null) rerouteSuggestionRow.setVisibility(View.GONE);
    }

    private void installCalculatedRoute(TomTomApi.Route calculated, boolean firstRoute) {
        clearPendingRoute();
        rerouteFailures = 0;
        if (rerouteAnnounced && active) {
            rerouteAnnounced = false;
            say("New route found.");
        }
        route = calculated;
        configureRouteBridge(route);
        lastConfirmedMetricsAt=-1; confirmedEta=confirmedMinutes=confirmedDistance="";
        updateMapVisibility();
        confirmedSinceRouteInstall=false;joiningRoute=false;lastJoinCameraAt=-1;
        if (active) {
            // A new route needs an independent motion index, never a blend
            // through the unrelated polyline of the previous route.
            smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(calculated.points));
            lastCameraUpdate = 0;
            lastCameraCenter = null;
        }
        offRouteSince = 0;
        previousInstructionIndex = 0;
        speedChangeAwareness.reset();roadsideAwareness.reset();
        stabilizedAlong = -1;
        navigationMatch = null;
        lastRoutePrefetchAt = -1;
        observedSpeed = 0;
        arrivalPolicy.reset();
        dismissUpcoming();
        spokenDistanceIndex = -1;
        spokenCloseIndex = -1;
        spokenNowIndex = -1;
        spokenLaneIndex = -1; spokenLanePrompt = ""; currentLanePrompt = "";
        traceInstructionIndex = -1; currentRoundaboutTrace = null; currentJunctionPath=java.util.Collections.emptyList(); reassuranceIndex=-1;
        lastTrafficWarningStart = -1;
        lastTrafficWarningAt = 0;
        lastSpeedWarningAt = 0;
        showRoute(calculated, firstRoute);
        if (firstRoute) {
            showPreview(calculated);
        } else {
            setStatus("Route updated · "+calculated.provider);
            say("Updated route ready.");
            if (currentLocation != null) updateGuidance(currentLocation);
        }
    }

    private void showRoute(TomTomApi.Route calculated, boolean preview) {
        clearJunctionHighlight();
        if (vectorMap != null) {
            vectorMap.setBuildingContext("DriveMate".equals(calculated.provider)?ownEndpoint():"",calculated.geometrySourceSha);
            vectorMap.setSharedRoute(calculated.nativeScene, calculated.points, calculated.provider);
            vectorMap.setRouteEvents(calculated.speedSections,calculated.trafficSections,metricUnits,calculated.fetchedAtMs);
            if (preview) {
                // Fit once the preview panel has laid out, so the whole route
                // sits between the search bar and the bottom panel.
                final List<GeoMath.Point> previewPoints = calculated.points;
                navigationPanel.post(() -> {
                    if (vectorMap == null || active || route != calculated) return;
                    int top = (topMapOverlay != null && topMapOverlay.getHeight() > 0
                            ? topMapOverlay.getHeight() : dp(170)) + dp(28);
                    int bottom = (navigationPanel.getHeight() > 0
                            ? navigationPanel.getHeight() + dp(9) : dp(260)) + dp(28);
                    vectorMap.previewRoute(previewPoints, top, bottom, dp(36));
                });
            }
        }
    }

    private void suspendPositionGuidance(String title,String reason) {
        boolean wasConfirmed=guidancePositionConfirmed;
        joiningRoute=false;
        guidancePositionConfirmed=false;nextInstruction="";
        speedChangeAwareness.clearEntry();arrivalPolicy.reset();
        showArrivalApproach(ArrivalPolicy.State.EN_ROUTE);
        setTripMetricLabels(false);
        dismissUpcoming();
        if(wasConfirmed&&speech!=null)speech.stop();
        if(vectorMap!=null)vectorMap.clearCurrentRoad();
        if(repeatButton!=null)repeatButton.setEnabled(false);
        if(topTurnTitle!=null)topTurnTitle.setText(title);
        if(topInstructionText!=null)topInstructionText.setText(reason);
        if(topRoadText!=null)topRoadText.setVisibility(View.GONE);
        if(lanePromptText!=null)lanePromptText.setVisibility(View.GONE);
        currentLanePrompt=""; traceInstructionIndex=-1; currentRoundaboutTrace=null; currentJunctionPath=java.util.Collections.emptyList();
        if(verifiedJunctionRoad!=null)verifiedJunctionRoad.setVisibility(View.GONE);
        boolean recent=RoadTestDisplayPolicy.retainMetrics(lastConfirmedMetricsAt,SystemClock.elapsedRealtime());
        if(recent&&tripMetricCaptions.size()>=3){
            tripMetricCaptions.get(0).setText("LAST ETA");
            tripMetricCaptions.get(1).setText("LAST MIN");
            tripMetricCaptions.get(2).setText("LAST DIST");
        }
        if(tripEtaValue!=null){tripEtaValue.setText(recent?confirmedEta:"—");tripEtaValue.setContentDescription(recent?"Last confirmed ETA "+confirmedEta:"ETA unavailable");}
        if(tripMinutesValue!=null){tripMinutesValue.setText(recent?confirmedMinutes:"—");tripMinutesValue.setContentDescription(recent?"Last confirmed minutes "+confirmedMinutes:"Time unavailable");}
        if(tripDistanceValue!=null){tripDistanceValue.setText(recent?confirmedDistance:"—");tripDistanceValue.setContentDescription(recent?"Last confirmed distance "+confirmedDistance:"Distance unavailable");}
        if(topTurnArrow!=null)topTurnArrow.setVisibility(View.INVISIBLE);
        if(topDistanceText!=null)topDistanceText.setVisibility(View.GONE);
        if(topNextTurnText!=null)topNextTurnText.setVisibility(View.GONE);
        if(laneGuidanceText!=null)laneGuidanceText.setVisibility(View.GONE);
        if(speedLimitText!=null)speedLimitText.setVisibility(View.GONE);
        if(trafficNoticeText!=null)trafficNoticeText.setVisibility(View.GONE);
        if(upcomingSpeedText!=null)upcomingSpeedText.setVisibility(View.GONE);
        if(upcomingRoadsideText!=null)upcomingRoadsideText.setVisibility(View.GONE);
        clearJunctionHighlight();stopSmoothLoop();smoothFollow=null;lastCameraUpdate=0;
        // Place the unsnapped accepted fix immediately, never infer a road position.
        if(!gpsLost&&displayGps!=null){
            if(vectorMap!=null)vectorMap.setUnsnappedVehicle(displayGps,currentLocation!=null&&currentLocation.hasBearing()&&currentLocation.hasSpeed()&&currentLocation.getSpeed()>1.5f?currentLocation.getBearing():Double.NaN);
        }
    }

    private void setTripMetricLabels(boolean planned){
        if(tripMetricCaptions.size()>=3){
            tripMetricCaptions.get(0).setText(planned?"PLAN ETA":"ETA");
            tripMetricCaptions.get(1).setText(planned?"PLAN MIN":"MIN LEFT");
            tripMetricCaptions.get(2).setText(planned?"PLAN DIST":"DISTANCE LEFT");
        }
    }
    private void showJoiningRoute(Location fix,long now){
        suspendPositionGuidance("JOIN HIGHLIGHTED ROUTE","GPS ready · join the route using road signs");
        joiningRoute=true;setTripMetricLabels(true);
        String eta=new SimpleDateFormat("HH:mm",Locale.UK).format(new Date(System.currentTimeMillis()+route.timeSeconds*1000L));
        tripEtaValue.setText(eta);tripEtaValue.setContentDescription("Planned ETA after joining road "+eta);
        tripMinutesValue.setText(TripHeaderFormat.minutesLeft(route.timeSeconds));
        tripMinutesValue.setContentDescription("Planned driving time after joining route");
        tripDistanceValue.setText(TripHeaderFormat.distanceLeft(route.lengthMetres,metricUnits));
        tripDistanceValue.setContentDescription("Planned road distance, excludes access to route");
        if(followLocation&&(lastJoinCameraAt<0||now-lastJoinCameraAt>=1000)){
            centreOnLocation(lastJoinCameraAt<0);lastJoinCameraAt=now;
        }
    }

    private void updateGuidance(Location fix) {
        if (!active || route == null || destination == null) return;
        GeoMath.Point current = gpsPoint(fix);
        long now = SystemClock.elapsedRealtime();
        float accuracy = fix.hasAccuracy() ? fix.getAccuracy() : 100f;
        if (System.currentTimeMillis() - fix.getTime() > 10000) {
            suspendPositionGuidance("POSITION UNCERTAIN · CHECK ROAD SIGNS","Waiting for a fresh position");return;
        }
        if (navigationMatch == null) navigationMatch = new NavigationMatch(route.points);
        NavigationMatch.State matched = navigationMatch.match(current, accuracy,
                fix.hasSpeed() ? fix.getSpeed() : 0f,
                fix.hasBearing() && (!fix.hasBearingAccuracy() || fix.getBearingAccuracyDegrees() <= 30) ? fix.getBearing() : Double.NaN, fix.getTime());
        if(matched.reason==NavigationMatch.Reason.DUPLICATE)return;
        lastMatchReason=matched.reason.name();
        GeoMath.Progress projected = matched.progress;
        lastOffRouteMetres = projected.offRouteMetres;

        // Use precise fixes for detecting a genuine prolonged departure from
        // the route. Do not reroute over one noisy fix or during API failures.
        float speedNow = fix.hasSpeed() ? fix.getSpeed() : Float.NaN;
        boolean joining=RouteJoinPolicy.joining(confirmedSinceRouteInstall,accuracy,GeoMath.distance(current,route.points.get(0)),projected.offRouteMetres,matched.reason);
        if (RouteJoinPolicy.waitForAccess(joining,speedNow)) {
            offRouteSince=0;
        } else if (ReroutePolicy.offRoute(projected.offRouteMetres, accuracy)) {
            if (offRouteSince == 0) offRouteSince = now;
            boolean wrongWay = ReroutePolicy.wrongWay(routeBearingAt(projected.alongMetres),
                    fix.hasBearing() ? fix.getBearing() : Double.NaN, speedNow);
            if (ReroutePolicy.confirmed(offRouteSince, now, speedNow, wrongWay)
                    && ReroutePolicy.mayRequest(now, lastRerouteAttempt, lastRouteRequest, rerouteFailures)
                    && !routing) {
                offRouteSince = 0;
                lastRerouteAttempt = now;
                setStatus("Off route · recalculating safely…");
                suspendPositionGuidance("OFF ROUTE · CHECK ROAD SIGNS","Recalculating your route");
                if (!rerouteAnnounced) {
                    rerouteAnnounced = true;
                    say("Recalculating your route.");
                }
                planRoute(false);
                return;
            }
        } else {
            offRouteSince = 0;
            rerouteAnnounced = false;
        }

        if (!matched.confident || System.currentTimeMillis() - fix.getTime() > 10000) {
            if(joining){showJoiningRoute(fix,now);return;}
            String reason=matched.reason==NavigationMatch.Reason.POOR_ACCURACY ? "GPS accuracy too low · "+Math.round(accuracy)+" m" : matched.reason==NavigationMatch.Reason.AMBIGUOUS ? "Matching position to nearby roads" : matched.reason==NavigationMatch.Reason.RECOVERING ? "Confirming position on route" : "Position not confirmed on route";
            if(matched.reason==NavigationMatch.Reason.OUTSIDE_WINDOW && accuracy<=35 && fix.hasSpeed() && fix.getSpeed()<1.5f && GeoMath.distance(current,route.points.get(0))<=100 && projected.offRouteMetres<=100) {
                reason="Near route start · follow signs to the highlighted road";
            }
            suspendPositionGuidance("POSITION UNCERTAIN · CHECK ROAD SIGNS",reason);
            return;
        }
        boolean justConfirmed=!guidancePositionConfirmed;
        guidancePositionConfirmed=true;confirmedSinceRouteInstall=true;joiningRoute=false;setTripMetricLabels(false);
        if(justConfirmed)setStatus("Position confirmed · guidance active");
        if(repeatButton!=null)repeatButton.setEnabled(true);
        double along = projected.alongMetres;
        if(matched.reacquired){
            while(previousInstructionIndex<route.instructions.size()-1 && route.instructions.get(previousInstructionIndex).routeOffsetMetres+8<along)previousInstructionIndex++;
            spokenDistanceIndex=-1;spokenCloseIndex=-1;spokenNowIndex=-1;
            spokenLaneIndex=-1;spokenLanePrompt="";
        }
        stabilizedAlong = along;
        if(vectorMap!=null){vectorMap.setConfirmedRouteProgress(along);if(showingVector()&&accuracy<=25)vectorMap.updateCurrentRoad(current);else vectorMap.clearCurrentRoad();}
        if ("DriveMate".equals(route.provider)) { requestComparison(); if(comparisonPermitted()) mapReview.observe(route,comparisonRoute,along); }
        topTurnArrow.setVisibility(View.VISIBLE);
        if (vectorMap != null && showingVector() && !autoFallback
                && (lastRoutePrefetchAt < 0 || now-lastRoutePrefetchAt >= 90000)) {
            lastRoutePrefetchAt = now;
            List<GeoMath.Point> ahead = new ArrayList<>();
            for (int d=500; d<=4000 && along+d<projected.totalMetres; d+=500)
                ahead.add(GeoMath.pointAtDistance(route.points, along+d));
            vectorMap.prefetchRoute(ahead);
        }
        if (fix.hasSpeed() && fix.getSpeed() >= 3) {
            observedSpeed = observedSpeed == 0 ? fix.getSpeed()
                    : 0.88 * observedSpeed + 0.12 * fix.getSpeed();
        }
        double leftMetres = Math.max(0, route.lengthMetres - along);
        double distanceToDestination = GeoMath.distance(current, destination.point);
        ArrivalPolicy.State arrival = arrivalPolicy.observe(
                distanceToDestination, leftMetres, projected.offRouteMetres,
                accuracy, fix.hasSpeed() ? fix.getSpeed() : Float.POSITIVE_INFINITY,
                fix.getTime(), System.currentTimeMillis());
        if (arrival == ArrivalPolicy.State.ARRIVED) {
            stopNavigation(true);
            return;
        }
        showArrivalApproach(arrival);

        if (now - lastRouteRequest > ROUTE_REFRESH_MS && leftMetres > 1500 && !routing) {
            lastRerouteAttempt = now;
            planRoute(false);
            // Keep confirmed guidance and camera updates alive during background refresh.
        }

        distanceText.setText("Left: " + NavigationUnits.displayDistance(leftMetres, metricUnits));
        long seconds = GuidanceLogic.remainingSeconds(leftMetres, route.lengthMetres,
                route.timeSeconds, observedSpeed);
        String etaClock = new SimpleDateFormat("HH:mm", Locale.UK)
                .format(new Date(System.currentTimeMillis() + seconds * 1000L));
        journeyTimeText.setText("Time: " + NavigationUnits.duration(seconds));
        etaText.setText("ETA: " + etaClock);
        // The compact journey summary is kept at the bottom of the screen.
        tripEtaValue.setText(etaClock);
        tripEtaValue.setContentDescription("Estimated arrival " + etaClock);
        tripMinutesValue.setText(TripHeaderFormat.minutesLeft(seconds));
        tripMinutesValue.setContentDescription(
                TripHeaderFormat.minutesLeft(seconds) + " remaining");
        tripDistanceValue.setText(TripHeaderFormat.distanceLeft(leftMetres, metricUnits));
        lastConfirmedMetricsAt=now;
        confirmedEta=etaClock;confirmedMinutes=TripHeaderFormat.minutesLeft(seconds);
        confirmedDistance=TripHeaderFormat.distanceLeft(leftMetres,metricUnits);
        tripDistanceValue.setContentDescription(
                TripHeaderFormat.distanceLeft(leftMetres, metricUnits) + " remaining");

        double[] offsets = new double[route.instructions.size()];
        for (int i = 0; i < route.instructions.size(); i++)
            offsets[i] = route.instructions.get(i).routeOffsetMetres;
        int next = navigationMatch.instruction(offsets, along, previousInstructionIndex);
        if (next < 0) {
            updateRoadIntelligence(fix, along, projected.offRouteMetres,
                    accuracy, Double.POSITIVE_INFINITY);
            topDistanceText.setVisibility(View.GONE);
            topInstructionText.setText("Continue along the highlighted route");
            verifiedJunctionRoad.setVisibility(View.GONE);
            topRoadText.setVisibility(View.GONE);
            topTurnArrow.setVisibility(View.INVISIBLE);
            topNextTurnText.setVisibility(View.GONE);
            clearJunctionHighlight();
            updateDrivingCamera(fix,projected,Math.max(along,projected.totalMetres));
            announceEnteredSpeed(Double.POSITIVE_INFINITY,along);
            return;
        }
        previousInstructionIndex = next;
        // A confirmed start is not a turn. Present the next actionable instruction
        // without advancing the underlying confirmation state or inventing a distance.
        TomTomApi.Instruction departure = route.instructions.get(next);
        if (next == 0 && next + 1 < route.instructions.size()
                && ("DEPART".equalsIgnoreCase(departure.maneuver) || "DEPARTURE".equalsIgnoreCase(departure.maneuver))
                && along >= departure.routeOffsetMetres && along <= departure.routeOffsetMetres + 8
                && route.instructions.get(next+1).routeOffsetMetres > along) next++;
        TomTomApi.Instruction step = route.instructions.get(next);
        double untilTurn = Math.max(0, step.routeOffsetMetres - along);
        updateRoadIntelligence(fix, along, projected.offRouteMetres,
                accuracy, untilTurn);
        JunctionIntelligence.Kind junctionKind = JunctionIntelligence.classify(step.maneuver,step.junctionType);
        String glyph = JunctionIntelligence.glyph(step,junctionKind);
        boolean roundabout = "↻".equals(glyph);
        int shownExit = roundabout ? JunctionFraming.reportedExit(step.exitNumber) : 0;
        String direction = IntelligentGuidance.direction(step, glyph, shownExit);
        String action = IntelligentGuidance.action(step, glyph, shownExit);
        String road = IntelligentGuidance.roadLine(action, step);
        maneuverIcon.update(glyph, shownExit);
        maneuverIcon.setVisibility(View.VISIBLE);
        topTurnArrow.update(glyph, shownExit);
        boolean fork = junctionKind==JunctionIntelligence.Kind.FORK;
        int roundaboutDirection=roundabout?IntelligentGuidance.roundaboutDirection(step):0;
        topTurnArrow.setRoundaboutDirection(roundaboutDirection,step.drivingSide);
        if(traceInstructionIndex!=next) {
            double previous=next>0?route.instructions.get(next-1).routeOffsetMetres:Double.NEGATIVE_INFINITY;
            double following=next+1<route.instructions.size()?route.instructions.get(next+1).routeOffsetMetres:Double.POSITIVE_INFINITY;
            currentRoundaboutTrace=roundabout?IntelligentGuidance.roundaboutTrace(route.points,step.routeOffsetMetres,previous,following):null;
            if(!IntelligentGuidance.traceAgrees(currentRoundaboutTrace,roundaboutDirection))currentRoundaboutTrace=null;
            currentJunctionPath=JunctionIntelligence.path(route.points,step.routeOffsetMetres,previous,following,junctionKind);
            traceInstructionIndex=next;
        }
        topTurnArrow.setRouteTrace(currentRoundaboutTrace);
        boolean previewJunction=JunctionIntelligence.preview(junctionKind,untilTurn,speedNow);
        updateJunctionHighlight(next, previewJunction);
        double primary=step.routeOffsetMetres>=along&&RoadTestDisplayPolicy.showManeuver(glyph,untilTurn,speedNow)?step.routeOffsetMetres:Double.NaN;
        double secondary=Double.NaN;
        if(Double.isFinite(primary)&&next+1<route.instructions.size()){
            TomTomApi.Instruction after=route.instructions.get(next+1);
            double gap=after.routeOffsetMetres-primary;
            if(gap>=20&&IntelligentGuidance.closeFollowing(gap,speedNow)
                    &&RoadTestDisplayPolicy.showManeuver(JunctionIntelligence.glyph(after,JunctionIntelligence.classify(after.maneuver,after.junctionType)),after.routeOffsetMetres-along,speedNow))
                secondary=after.routeOffsetMetres;
        }
        if(vectorMap!=null)vectorMap.setManeuverOffsets(primary,secondary);
        if(previewJunction&&!currentJunctionPath.isEmpty())junctionFramePoints=JunctionIntelligence.context(currentJunctionPath,junctionKind);
        boolean showPath=previewJunction&&JunctionIntelligence.complex(junctionKind)
                && getResources().getConfiguration().screenHeightDp>=740
                && getResources().getConfiguration().fontScale<=1.2f
                && laneGuidanceText.getVisibility()!=View.VISIBLE
                && (roundabout?currentRoundaboutTrace!=null:JunctionIntelligence.pathAgrees(currentJunctionPath,glyph,junctionKind));
        if(junctionPathView!=null){
            if(showPath)junctionPathView.setRoute(currentJunctionPath,roundabout,shownExit);
            else junctionPathView.setVisibility(View.GONE);
        }
        String sign=step.signpost==null?"":step.signpost.trim();
        verifiedJunctionRoad.setText("SIGNS · "+sign);
        verifiedJunctionRoad.setVisibility(!sign.isEmpty() && untilTurn<=DrivingDisplayPolicy.signpostHorizon(speedNow)?View.VISIBLE:View.GONE);
        topTurnTitle.setText(junctionKind.title);
        int iconSide = dp(getResources().getConfiguration().screenWidthDp<360 ? 60 : 68);
        if (topTurnArrow.getLayoutParams().width != iconSide) {
            android.view.ViewGroup.LayoutParams iconParams = topTurnArrow.getLayoutParams();
            iconParams.width = iconSide; iconParams.height = iconSide;
            topTurnArrow.setLayoutParams(iconParams);
        }
        instructionText.setText(NavigationUnits.displayDistance(untilTurn, metricUnits)+" · "+direction);
        boolean compactCard=DisplayScreenPolicy.compact(getResources().getConfiguration().screenHeightDp,getResources().getConfiguration().fontScale);
        topDistanceText.setTextSize(compactCard?30:38);
        topInstructionText.setTextSize(compactCard?19:23);
        topRoadText.setTextSize(compactCard?16:18);
        topRoadText.setMaxLines(compactCard?1:2);
        topDistanceText.setText(NavigationUnits.displayDistance(untilTurn, metricUnits));
        topDistanceText.setVisibility(View.VISIBLE);
        topInstructionText.setText(DrivingRoadLabel.highlight(action));
        topRoadText.setText(DrivingRoadLabel.highlight(road));
        topRoadText.setVisibility(road.isEmpty()?View.GONE:View.VISIBLE);
        topInstructionText.setContentDescription(
                "Next turn in " + NavigationUnits.spokenDistance(untilTurn, metricUnits)
                + ": " + direction);

        String followingVoice="";
        if (next + 1 < route.instructions.size()) {
            TomTomApi.Instruction after = route.instructions.get(next+1);
            double gap=Math.max(0,after.routeOffsetMetres-step.routeOffsetMetres);
            String afterGlyph=JunctionIntelligence.glyph(after,JunctionIntelligence.classify(after.maneuver,after.junctionType));
            int afterExit="↻".equals(afterGlyph)?JunctionFraming.reportedExit(after.exitNumber):0;
            String followingTurn=IntelligentGuidance.thenLine(after,gap,metricUnits);
            boolean showThen=JunctionIntelligence.showFollowing(untilTurn,gap,speedNow,
                    getResources().getConfiguration().screenHeightDp,
                    laneGuidanceText.getVisibility()==View.VISIBLE||verifiedJunctionRoad.getVisibility()==View.VISIBLE);
            if(showThen&&junctionPathView!=null)junctionPathView.setVisibility(View.GONE);
            topNextTurnText.setText(showThen?DrivingRoadLabel.highlight(followingTurn):"");
            topNextTurnText.setVisibility(showThen?View.VISIBLE:View.GONE);
            if(IntelligentGuidance.closeFollowing(gap,speedNow))
                followingVoice=IntelligentGuidance.direction(after,afterGlyph,afterExit)+" in "+NavigationUnits.spokenDistance(gap,metricUnits);
        } else {
            topNextTurnText.setText("");
            topNextTurnText.setVisibility(View.GONE);
        }
        nextTurnText.setVisibility(View.GONE);
        String speedAtTurn="";
        RouteIntelligence.SpeedSection currentLimit=RouteIntelligence.speedAt(route.speedSections,along,projected.offRouteMetres,accuracy,
                "DriveMate".equals(route.provider)?0:System.currentTimeMillis()-route.fetchedAtMs);
        SpeedChangeAwareness.Change turnChange=SpeedChangeAwareness.next(route.speedSections,currentLimit,along,speedNow);
        if(advanceSpeedVoice&&turnChange!=null&&RoadTestDisplayPolicy.atTurn(turnChange.offset,along,untilTurn)){
            int shown=metricUnits?turnChange.kmh:(int)Math.round(turnChange.kmh/1.609344);
            speedAtTurn=" Mapped speed limit "+(turnChange.kmh<currentLimit.kmh?"reduces":"increases")+" to "+shown
                    +(metricUnits?" kilometres per hour":" miles per hour")+" at the turn. Check road signs.";
        }
        nextInstruction=IntelligentGuidance.voice(direction,untilTurn,metricUnits,untilTurn<=30,currentLanePrompt,"")+speedAtTurn;
        updateDrivingCamera(fix, projected, step.routeOffsetMetres);
        double immediate = NavigationMatch.immediateDistance(speedNow);
        double approach = NavigationMatch.approachDistance(speedNow);
        double early = NavigationMatch.earlyDistance(speedNow);
        if (untilTurn <= immediate && spokenNowIndex != next) {
            spokenNowIndex = next;
            if (DrivingVoicePolicy.turn(voiceMode, 2)) sayTurn(true, IntelligentGuidance.voice(direction,untilTurn,metricUnits,true,"",followingVoice)+linkedSpeedSpeech(speedAtTurn,turnChange));
        } else if (untilTurn > immediate && untilTurn <= approach && spokenCloseIndex != next
                && spokenNowIndex != next) {
            spokenCloseIndex = next;
            if (DrivingVoicePolicy.turn(voiceMode, 1)) {
                sayTurn(false, IntelligentGuidance.voice(direction,untilTurn,metricUnits,false,currentLanePrompt,followingVoice)+linkedSpeedSpeech(speedAtTurn,turnChange));
                if(!currentLanePrompt.isEmpty()){spokenLaneIndex=next;spokenLanePrompt=currentLanePrompt;}
            }
        } else if (untilTurn > approach && untilTurn <= early && spokenDistanceIndex != next
                && spokenCloseIndex != next && spokenNowIndex != next) {
            spokenDistanceIndex = next;
            if (DrivingVoicePolicy.turn(voiceMode, 0)) {
                sayTurn(false, IntelligentGuidance.voice(direction,untilTurn,metricUnits,untilTurn<=30,currentLanePrompt,"")+linkedSpeedSpeech(speedAtTurn,turnChange));
                if(!currentLanePrompt.isEmpty()){spokenLaneIndex=next;spokenLanePrompt=currentLanePrompt;}
            }
        } else if (untilTurn>immediate && !currentLanePrompt.isEmpty()
                && (spokenLaneIndex!=next || !currentLanePrompt.equals(spokenLanePrompt))
                && DrivingVoicePolicy.turn(voiceMode,1)) {
            spokenLaneIndex=next;spokenLanePrompt=currentLanePrompt;
            sayTurn(false, IntelligentGuidance.voice(direction,untilTurn,metricUnits,false,currentLanePrompt,""));
        } else if (voiceMode==DrivingVoicePolicy.FULL && untilTurn>Math.max(2400,early*2) && reassuranceIndex!=next && speedNow>3) {
            reassuranceIndex=next;
            say("Continue along the highlighted route for "+NavigationUnits.spokenDistance(untilTurn,metricUnits));
        }
        announceEnteredSpeed(untilTurn,along);
    }
    private String linkedSpeedSpeech(String phrase,SpeedChangeAwareness.Change change){
        return !phrase.isEmpty()&&speechReady&&speech!=null&&speedChangeAwareness.shouldAnnounce(change)?phrase:"";
    }
    private boolean speechAvailable(){
        return speechReady&&speech!=null&&!speech.isSpeaking()&&(lastSpeechAt<0||SystemClock.elapsedRealtime()-lastSpeechAt>=1500);
    }
    private void announceEnteredSpeed(double nextTurn,double along){
        SpeedChangeAwareness.Change change=speedChangeAwareness.pendingEntry();
        if(change==null||!advanceSpeedVoice||voiceMode==DrivingVoicePolicy.SILENT||!guidancePositionConfirmed
                ||gpsLost||nextTurn<=65||along-change.offset>100||!speechAvailable())return;
        int shown=metricUnits?change.kmh:(int)Math.round(change.kmh/1.609344);
        speedChangeAwareness.entryAnnounced();
        speak("Mapped speed limit is now "+shown+(metricUnits?" kilometres per hour.":" miles per hour.")+" Check road signs.");
    }

    /**
     * Show only route-indexed data supplied by TomTom for the current route.
     * No road-class, lane or hazard guesses are ever made.
     */
    private void updateRoadIntelligence(Location fix, double along, double offRoute,
                                        float accuracy, double distanceToTurn) {
        if (!active || route == null) return;
        long age = System.currentTimeMillis() - route.fetchedAtMs;
        boolean confident = accuracy <= 35 && offRoute <= 30 && age >= 0
                && ("DriveMate".equals(route.provider)||age <= 480000) && !gpsLost;
        RouteIntelligence.LaneSection lanes = confident
                ? RouteIntelligence.lanesForManeuver(route.laneSections, along,
                        laneLookAheadMetres(fix.hasSpeed() ? fix.getSpeed() : 0f),
                        along + distanceToTurn) : null;
        if (laneGuidanceText != null) {
            laneGuidanceText.setSection(lanes);
            laneGuidanceText.setVisibility(lanes == null ? View.GONE : View.VISIBLE);
        }
        currentLanePrompt=IntelligentGuidance.lanePhrase(lanes);
        if(lanePromptText!=null){lanePromptText.setText(currentLanePrompt);lanePromptText.setVisibility(currentLanePrompt.isEmpty()?View.GONE:View.VISIBLE);}
        RouteIntelligence.SpeedSection limit = RouteIntelligence.speedAt(
                route.speedSections, along, offRoute, accuracy, "DriveMate".equals(route.provider)?0:age);
        if(confident&&guidancePositionConfirmed&&accuracy<=25&&offRoute<=20)
            speedChangeAwareness.observeEntry(route.speedSections,limit,along,SystemClock.elapsedRealtime());
        else speedChangeAwareness.clearEntry();
        boolean speeding = limit != null && fix.hasSpeed()
                && RouteIntelligence.overspeed(fix.getSpeed(), limit, speedToleranceMph);
        if (speedLimitText != null) {
            if (limit == null) {
                // No placeholder: an empty "Limit —" looks like a fault.
                speedLimitText.setVisibility(View.GONE);
            } else {
                int shown = metricUnits ? limit.kmh
                        : (int) Math.round(limit.kmh / 1.609344);
                String label = shown + (metricUnits ? " km/h" : " mph");
                speedLimitText.setText(String.valueOf(shown));
                speedLimitText.setContentDescription("Mapped speed limit " + label
                        + ". Road signs take precedence.");
                speedLimitText.setVisibility(View.VISIBLE);
            }
        }
        SpeedChangeAwareness.Change change = confident && !gpsLost && guidancePositionConfirmed
                ? SpeedChangeAwareness.next(route.speedSections, limit, along, fix.hasSpeed()?fix.getSpeed():0)
                : null;
        if (upcomingSpeedText != null) {
            if (change == null) upcomingSpeedText.setVisibility(View.GONE);
            else {
                int shown = metricUnits ? change.kmh : (int)Math.round(change.kmh / 1.609344);
                boolean atTurn=RoadTestDisplayPolicy.atTurn(change.offset,along,distanceToTurn);
                String changeLabel=change.kmh<limit.kmh?"Limit reduces to ":"Limit increases to ";
                upcomingSpeedText.setText(changeLabel+shown+(metricUnits?" km/h":" mph")
                        +(atTurn?" at the turn":" ahead · "+NavigationUnits.displayDistance(change.offset-along,metricUnits)));
                upcomingSpeedText.setVisibility(View.VISIBLE);
                // Defer to a close turn; do not mark as spoken until eligible.
                if (advanceSpeedVoice && voiceMode!=DrivingVoicePolicy.SILENT && speechAvailable()
                        && (!atTurn||voiceMode==DrivingVoicePolicy.ALERTS_ONLY)
                        && (distanceToTurn > 65 || (atTurn&&voiceMode==DrivingVoicePolicy.ALERTS_ONLY)) && speedChangeAwareness.shouldAnnounce(change)) {
                    speak("Mapped speed limit changes to " + shown
                            + (metricUnits?" kilometres per hour in ":" miles per hour in ")
                            + NavigationUnits.spokenDistance(change.offset-along, metricUnits) + ". Check road signs.");
                }
            }
        }
        speedText.setTextColor(speeding ? Color.rgb(255, 197, 85) : Color.WHITE);
        long now = SystemClock.elapsedRealtime();
        if (speeding && speedWarningEnabled && (speech==null||!speech.isSpeaking()) && distanceToTurn > 300
                && (lastSpeedWarningAt == 0 || now - lastSpeedWarningAt > 120000)) {
            lastSpeedWarningAt = now;
            sayVerifiedAlert("Check your speed. You may be above the mapped speed limit.",
                    true);
        }

        RouteRoadsideAwareness.Event roadside = confident && guidancePositionConfirmed && accuracy<=25 && offRoute<=20
                && route.nativeScene!=null && age<=480000
                ? RouteRoadsideAwareness.next(route.nativeScene.routeRoadside,along,fix.hasSpeed()?fix.getSpeed():0)
                : null;
        MappedEnforcement.Zone activeZone=null;
        if(confident&&guidancePositionConfirmed&&accuracy<=25&&offRoute<=20&&route.nativeScene!=null&&age<=480000){
            for(MappedEnforcement.Zone z:route.nativeScene.averageZones)if(z.active(along)){
                if(activeZone==null||z.end<activeZone.end)activeZone=z;
            }
        }
        if(upcomingRoadsideText!=null){
            upcomingRoadsideText.setVisibility(roadside==null&&activeZone==null?View.GONE:View.VISIBLE);
            if(activeZone!=null)upcomingRoadsideText.setText("Mapped average-speed zone · "+NavigationUnits.displayDistance(activeZone.end-along,metricUnits)+" remaining");
            if(roadside!=null){
                if(activeZone==null)upcomingRoadsideText.setText(roadside.label+" ahead · "+NavigationUnits.displayDistance(roadside.offset-along,metricUnits));
                if(hazardWarningEnabled&&voiceMode!=DrivingVoicePolicy.SILENT&&speechReady&&speech!=null&&!speech.isSpeaking()
                        && distanceToTurn>100 && fix.hasSpeed()&&fix.getSpeed()>1.5f && roadsideAwareness.announce(roadside))
                    sayVerifiedAlert(roadside.label+" ahead in "+NavigationUnits.spokenDistance(roadside.offset-along,metricUnits)+". Check road conditions.",false);
            }
        }
        RouteIntelligence.TrafficSection traffic = confident
                ? RouteIntelligence.nextTraffic(route.trafficSections, along, 2500) : null;
        if (trafficNoticeText != null) {
            if (traffic == null) {
                trafficNoticeText.setVisibility(View.GONE);
            } else {
                int minutes = (int) Math.ceil(traffic.delaySeconds / 60.0);
                String suffix = traffic.delaySeconds > 0 ? " · +" + minutes + " min" : "";
                trafficNoticeText.setText("⚠ "+RoadTestDisplayPolicy.trafficLabel(traffic.label(),traffic.start,traffic.end,along,metricUnits)+suffix+" · Timeline");
                trafficNoticeText.setVisibility(View.VISIBLE);
            }
        }
        if (traffic != null && hazardWarningEnabled && (speech==null||!speech.isSpeaking()) && traffic.start > along
                && traffic.start - along <= 450 && distanceToTurn > 300
                && (lastTrafficWarningStart < 0
                        || Math.abs(lastTrafficWarningStart - traffic.start) > 50)
                && (lastTrafficWarningAt == 0 || now - lastTrafficWarningAt > 90000)) {
            lastTrafficWarningStart = traffic.start;
            lastTrafficWarningAt = now;
            sayVerifiedAlert(traffic.label() + " reported ahead. Check road conditions.",
                    false);
        }
    }

    /**
     * Lane guidance as arrows, quicker to read at a glance than text:
     * recommended lanes bright cyan and larger, other lanes dimmed. Only
     * TomTom-supplied lanes are shown (LaneSection.trusted()).
     */
    /** Show lanes about 200 m ahead in town, up to 600 m at motorway speed. */
    private static double laneLookAheadMetres(float speedMps) {
        double speed = Float.isFinite(speedMps) ? Math.max(0, speedMps) : 0;
        return Math.max(220, Math.min(850, 150 + speed * 22));
    }

    private boolean canInspectGuidance(){
        if(!active||route==null||!guidancePositionConfirmed||gpsLost||currentLocation==null||stabilizedAlong<0)return false;
        Location fix=currentLocation;
        long age=System.currentTimeMillis()-fix.getTime();
        return age>=0&&age<5000&&fix.hasAccuracy()&&fix.getAccuracy()<=25&&fix.hasSpeed()&&fix.getSpeed()<0.5f;
    }

    private void dismissUpcoming(){
        if(upcomingDialog!=null){upcomingDialog.dismiss();upcomingDialog=null;}
    }

    private void showUpcomingManoeuvres(){
        if(!canInspectGuidance()){
            Toast.makeText(this,"Stop safely with a fresh GPS fix to view turns",Toast.LENGTH_SHORT).show();return;
        }
        dismissUpcoming();
        StringBuilder list=new StringBuilder();
        for(int i=Math.max(0,previousInstructionIndex);i<route.instructions.size()&&i<previousInstructionIndex+8;i++){
            TomTomApi.Instruction step=route.instructions.get(i);
            String glyph=GuidanceLogic.symbol(step.maneuver,step.junctionType,step.turnAngleDegrees);
            int exit="↻".equals(glyph)?JunctionFraming.reportedExit(step.exitNumber):0;
            if(list.length()>0)list.append("\n\n");
            list.append(NavigationUnits.displayDistance(Math.max(0,step.routeOffsetMetres-stabilizedAlong),metricUnits)).append(" · ").append(IntelligentGuidance.direction(step,glyph,exit));
        }
        upcomingDialog=new AlertDialog.Builder(this).setTitle("Upcoming manoeuvres").setMessage(list.length()==0?"No further instructions":list.toString()).setPositiveButton("Close",null).create();
        upcomingDialog.show();
    }

    private void showTrafficTimeline() {
        if (!active || route == null || currentLocation == null) return;
        if (System.currentTimeMillis() - route.fetchedAtMs > 480000) {
            Toast.makeText(this, "Route traffic information is old; refresh the route",
                    Toast.LENGTH_LONG).show();
            return;
        }
        GeoMath.Progress progress = GeoMath.project(route.points, gpsPoint(currentLocation));
        StringBuilder timeline = new StringBuilder();
        int count = 0;
        for (RouteIntelligence.TrafficSection item : route.trafficSections) {
            if (item.end < progress.alongMetres) continue;
            timeline.append(item.label()).append(" · ")
                    .append(NavigationUnits.displayDistance(
                            Math.max(0, item.start - progress.alongMetres), metricUnits))
                    .append(" ahead");
            if (item.delaySeconds > 0)
                timeline.append(" · approx +")
                        .append((int) Math.ceil(item.delaySeconds / 60.0)).append(" min");
            timeline.append("\n\n");
            if (++count >= 5) break;
        }
        if (count == 0) timeline.append("No route-positioned incidents reported.");
        new AlertDialog.Builder(this).setTitle("Upcoming traffic · TomTom")
                .setMessage(timeline.toString()
                        + "\nTraffic conditions can change. Follow road signs.")
                .setPositiveButton("Close", null).show();
    }

    /** Frame the real route and nearby junction context; never draw a second path. */
    private void updateJunctionHighlight(int instructionIndex, boolean visible) {
        if(!visible||route==null||route.points.size()<2){clearJunctionHighlight();return;}
        if(highlightedJunctionIndex==instructionIndex)return;
        TomTomApi.Instruction instruction=route.instructions.get(instructionIndex);
        boolean roundabout="↻".equals(GuidanceLogic.symbol(instruction.maneuver,instruction.junctionType,instruction.turnAngleDegrees));
        junctionFramePoints=JunctionFraming.context(route.points,instruction.routeOffsetMetres,roundabout);
        highlightedJunctionIndex=instructionIndex;
    }
    private void clearJunctionHighlight(){
        if(junctionPathView!=null)junctionPathView.setVisibility(View.GONE);
        junctionFramePoints=java.util.Collections.emptyList();highlightedJunctionIndex=-1;
        if(vectorMap!=null){vectorMap.clearJunctionFraming();vectorMap.setManeuverOffset(Double.NaN);}
    }

    private void stopNavigation(boolean arrived) {
        clearLiveIncidents();
        dismissUpcoming();
        boolean reviewAvailable=active&&!mapReview.snapshot().isEmpty();
        reviewGeneration++;comparisonBusy=false;comparisonRoute=null;
        boolean offerParkedCar = arrived && currentLocation != null
                && ArrivalPolicy.canSaveParking(
                        currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : Float.MAX_VALUE,
                        currentLocation.hasSpeed() ? currentLocation.getSpeed() : Float.MAX_VALUE,
                        currentLocation.getTime(), System.currentTimeMillis());
        stopSmoothLoop();
        smoothFollow = null;
        ++requestGeneration;
        routing = false;
        active = false;
        applyHighDetailPolicy();
        NavigationService.stop(this);
        resubscribeLocationForMode();
        arrivalPolicy.reset();
        route = null;
        routeBridge.setRoute(null);
        lastConfirmedMetricsAt=-1; confirmedEta=confirmedMinutes=confirmedDistance="";
        updateMapVisibility();
        destination = null;
        offRouteSince = 0;
        gpsLost = false;
        networkLost = false;
        lastRerouteAttempt = 0;
        nextInstruction = "";
        guidancePositionConfirmed=false;
        clearPendingRoute();
        clearJunctionHighlight();
        if (vectorMap != null) vectorMap.setEntrance(null);
        if (arrivalHintText != null && arrivalHintText.getTag() instanceof View)
            ((View) arrivalHintText.getTag()).setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (vectorMap != null) {
            vectorMap.setBuildingContext("","");
            vectorMap.setSharedRoute(null, null);
            vectorMap.setDestination(null);
        }
        lastCameraUpdate = 0;
        stopButton.setVisibility(View.GONE);
        setHomeVisible(true);
        startButton.setVisibility(View.GONE);
        saveButton.setVisibility(View.GONE);
        if (previewParkingButton != null) previewParkingButton.setVisibility(View.GONE);
        repeatButton.setVisibility(View.GONE);
        refreshButton.setVisibility(View.GONE);
        routeSummary.setVisibility(View.GONE);
        maneuverIcon.setVisibility(View.GONE);
        nextTurnText.setVisibility(View.GONE);
        journeyTimeText.setText("Time: —");
        gpsAccuracyText.setText("");
        vehicleFilter.reset();
        previousInstructionIndex = 0;
        speedChangeAwareness.reset();roadsideAwareness.reset();
        stabilizedAlong = -1;
        navigationMatch = null;
        lastRoutePrefetchAt = -1;
        observedSpeed = 0;
        followLocation = true;
        updateFollowButton();
        distanceText.setText("Distance: —");
        etaText.setText("ETA: —");
        if (arrived) {
            instructionText.setText("You have arrived at your destination.");
            setStatus("Destination reached");
            say("You have arrived at your destination.");
        } else {
            instructionText.setText("Search for a destination to navigate.");
            setStatus("Ready to navigate");
            if (speech != null) speech.stop();
        }
        if (reviewAvailable && !isFinishing()) {
            // Never open the review UI when the car is still moving or speed is unknown.
            if (currentLocation != null && currentLocation.hasSpeed() && currentLocation.getSpeed() < 0.5f
                    && System.currentTimeMillis()-currentLocation.getTime()<10000) showMapReview();
            else setStatus("Journey ended · map review available in Settings when parked");
        } else if (offerParkedCar && !isFinishing()) offerSaveParkedCar();
    }

    private void setStatus(String message) {
        if (banner != null) banner.setText(message);
    }

    private void showError(String message) {
        String safe = message == null || message.trim().isEmpty()
                ? "Unable to complete this request." : message;
        setStatus(safe);
        Toast.makeText(this, safe, Toast.LENGTH_LONG).show();
    }

    private void say(String message) {
        if (voiceMode != DrivingVoicePolicy.SILENT
                && voiceMode != DrivingVoicePolicy.ALERTS_ONLY)
            speak(message);
    }

    private void saySystemWarning(String message) {
        if (DrivingVoicePolicy.systemWarning(voiceMode)) speak(message);
    }

    /** Future verified road feeds must use this gate, never a guessed warning. */
    private void sayVerifiedAlert(String message, boolean speedCategory) {
        boolean allowed = speedCategory ? speedWarningEnabled : hazardWarningEnabled;
        if (DrivingVoicePolicy.verifiedAlert(voiceMode, allowed)) speak(message);
    }

    private void speak(String message) { speak(message, VoiceQueue.INFO); }

    private final VoiceQueue voiceQueue = new VoiceQueue();
    private AudioManager audioManager;
    private AudioFocusRequest voiceFocus;
    private int utterancesInFlight;
    private boolean voiceUnavailableShown;

    /** Speak with a priority so a status line cannot cut off a turn instruction. */
    private void speak(String message, int priority) {
        if (voiceMode!=DrivingVoicePolicy.SILENT && speechReady && speech != null && message != null
                && !message.trim().isEmpty()) {
            VoiceQueue.Action action = voiceQueue.offer(priority, message, SystemClock.elapsedRealtime());
            if (action == VoiceQueue.Action.DROP) return;
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, guidanceVolume);
            lastSpeechAt=SystemClock.elapsedRealtime();
            requestVoiceFocus();
            if (action == VoiceQueue.Action.PLAY_NOW) utterancesInFlight = 0;
            utterancesInFlight++;
            speech.speak(message, action == VoiceQueue.Action.QUEUE
                    ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, params, "drivemate-guidance");
        }
    }

    /** Ask other audio (music, podcasts) to lower its volume while guidance speaks. */
    private void requestVoiceFocus() {
        if (audioManager == null || voiceFocus != null) return;
        voiceFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        audioManager.requestAudioFocus(voiceFocus);
    }

    private void speechFinished() {
        if (utterancesInFlight > 0) utterancesInFlight--;
        if (utterancesInFlight > 0) return;
        voiceQueue.finished();
        if (audioManager != null && voiceFocus != null) {
            audioManager.abandonAudioFocusRequest(voiceFocus);
            voiceFocus = null;
        }
    }

    /** A turn instruction: urgent when it is due now. */
    private void sayTurn(boolean urgent, String message) {
        if (voiceMode != DrivingVoicePolicy.SILENT && voiceMode != DrivingVoicePolicy.ALERTS_ONLY)
            speak(message, urgent ? VoiceQueue.URGENT : VoiceQueue.TURN);
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider))
            setStatus("GPS disabled. Enable location services for accurate guidance.");
    }

    @Override public void onProviderEnabled(String provider) { /* GPS fixes follow. */ }

    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onStart() {
        super.onStart();
        if (vectorMap != null) vectorMap.onStart();
    }

    @Override public void onStop() {
        if (vectorMap != null) vectorMap.onStop();
        super.onStop();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (vectorMap != null) vectorMap.onLowMemory();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (vectorMap != null) vectorMap.onSaveInstanceState(state);
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(
            android.content.res.Configuration newConfiguration) {
        super.onConfigurationChanged(newConfiguration);
        // Switching system day/night colours must not end an active journey.
        applyTheme();
    }

    @Override public void onResume() {
        super.onResume();
        foreground = true;
        if (vectorMap != null) vectorMap.onResume();
        applyTheme();
        startLocation();
        gpsHandler.removeCallbacks(gpsWatchdog);
        gpsHandler.postDelayed(gpsWatchdog, 5000);
        if (active) startSmoothLoop();
    }

    @Override public void onPause() {
        dismissUpcoming();
        foreground = false;
        clearLiveIncidents();
        stopSmoothLoop();
        gpsHandler.removeCallbacks(deadReckonTick);
        if (active) {
            // Navigating: NavigationService keeps the app in the foreground, so
            // keep GPS, guidance, voice and the GPS-lost watchdog running while
            // the screen is off or another app is in front. Only the on-screen
            // animation stops.
        } else {
            gpsHandler.removeCallbacks(gpsWatchdog);
            if (locationManager != null) {
                locationManager.removeUpdates(this);
                locationManager.unregisterGnssStatusCallback(gnssCallback);
            stopRotationSensor();
            }
            locationSubscribed = false;
        }
        if (vectorMap != null) vectorMap.onPause();
        super.onPause();
    }

    @Override public void onDestroy() {
        // The app is closing: the background-navigation notification must go too.
        NavigationService.stop(this);
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            locationManager.unregisterGnssStatusCallback(gnssCallback);
            stopRotationSensor();
        }
        ++requestGeneration;
        network.shutdownNow();incidentWorker.shutdownNow();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
        }
        if (vectorMap != null) vectorMap.onDestroy();
        super.onDestroy();
    }
}
