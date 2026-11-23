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
import android.os.Build;
import android.os.Bundle;
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

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.TilesOverlay;

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
    private static final String PREFS = "drivemate_settings";
    private static final int BLUE = Color.rgb(24, 110, 235);
    private static final int DARK = Color.rgb(10, 39, 59);
    private static final int CYAN = Color.rgb(7, 214, 235);
    private static final int VOICE_SEARCH_REQUEST = 12;
    private static final long ROUTE_REFRESH_MS = 300_000L;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler gpsHandler = new Handler(Looper.getMainLooper());
    private boolean gpsLost;
    private final Runnable gpsWatchdog = new Runnable() {
        @Override public void run() {
            if (active && currentLocation != null
                    && System.currentTimeMillis() - currentLocation.getTime() > 15000) {
                if (!gpsLost) {
                    gpsLost = true;
                    setStatus("GPS signal lost · waiting for a fresh fix");
                    if (topTurnTitle != null) topTurnTitle.setText("GPS LOST · WAIT FOR SIGNAL");
                    if (speedText != null) speedText.setText("GPS —");
                    if (speedLimitText != null) speedLimitText.setText("Limit —");
                    if (laneGuidanceText != null) laneGuidanceText.setVisibility(View.GONE);
                    if (trafficNoticeText != null) trafficNoticeText.setVisibility(View.GONE);
                    saySystemWarning("GPS signal lost. Waiting for location.");
                }
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
                    if (showingVector() && vectorMap != null) {
                        vectorMap.followFrame(frame);
                    } else {
                        if (map != null) {
                            if (userPin != null) {
                                userPin.setPosition(new GeoPoint(
                                        frame.vehicle.lat, frame.vehicle.lon));
                                // A flat screen-facing arrow must show travel
                                // direction *relative to* the moving map camera.
                                userPin.setRotation(CameraHeadingPolicy.rasterMarkerRotation(
                                        frame.travelBearing, frame.bearing));
                            }
                            map.getController().setCenter(new GeoPoint(
                                    frame.center.lat, frame.center.lon));
                            if (smoothTick % 3 == 0
                                    && Math.abs(map.getZoomLevelDouble() - frame.zoom) >= 0.12)
                                map.getController().setZoom(frame.zoom);
                            float delta = (float) MapFollowPolicy.shortestHeadingDifference(
                                    -frame.bearing, map.getMapOrientation());
                            if (smoothTick % 2 == 0 && Math.abs(delta) >= 1.5)
                                map.setMapOrientation(map.getMapOrientation() + delta);
                            map.postInvalidateOnAnimation();
                        }
                        if (vectorMap != null && useLocalMap && !autoFallback
                                && smoothTick % 5 == 0) vectorMap.followFrame(frame);
                    }
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

    private MapView map;
    private DriveTileServer driveTiles;
    private VectorDriveMap vectorMap;
    private Bundle vectorSavedState;
    private boolean useLocalMap = true;
    private boolean vectorRoadsReady;
    private boolean autoFallback;
    private View speedBadgeView;
    private boolean keyboardOpen;
    private boolean homeMode = true;
    private LinearLayout homePanel;
    private LinearLayout navigationPanel;
    private LinearLayout topMapOverlay;
    private LinearLayout searchBar;
    private LinearLayout drivingTopPanel;
    private LinearLayout brandHeader;
    private LinearLayout tripMetricsRow;
    private LinearLayout drivingTurnCard;
    private LinearLayout previewTurnRow;
    private LinearLayout previewDetailsRow;
    private LinearLayout bottomActionRow;
    private LinearLayout.LayoutParams bottomActionsParams;
    private TextView tripEtaValue;
    private TextView tripMinutesValue;
    private TextView tripDistanceValue;
    private TextView topInstructionText;
    private TextView topTurnTitle;
    private TextView topNextTurnText;
    private TurnArrowView topTurnArrow;
    private JunctionManeuverView junctionDiagram;
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
    private TextView laneGuidanceText;
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
    private int themePreference = DrivingDisplayPolicy.THEME_AUTO;
    private boolean nightDisplay;
    private boolean themeApplied;
    private boolean hazardWarningEnabled = true;
    private boolean speedWarningEnabled = true;
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

    private LocationManager locationManager;
    private Location currentLocation;
    /** Last fix that passed FixQualityPolicy; the gate compares every new fix to it. */
    private FixQualityPolicy.Sample lastAcceptedFix;
    /** Display-only estimate of the vehicle position during short GPS gaps. */
    private final DeadReckoner deadReckoner = new DeadReckoner();
    private final Runnable deadReckonTick = new Runnable() {
        @Override public void run() {
            if (foreground && userPin != null) {
                GeoMath.Point estimate = deadReckoner.estimate(System.currentTimeMillis());
                // While guidance runs, SmoothFollowCamera owns the marker position.
                if (estimate != null && (!active || smoothFollow == null)) {
                    userPin.setPosition(new GeoPoint(estimate.lat, estimate.lon));
                    if (vectorMap != null && useLocalMap) vectorMap.setVehicle(estimate);
                    map.postInvalidateOnAnimation();
                }
            }
            gpsHandler.postDelayed(this, 500);
        }
    };
    /** Satellites used in the current GPS fix, or -1 until the first GNSS report. */
    private int satellitesUsed = -1;
    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) used++;
            }
            satellitesUsed = used;
        }
    };
    private Marker userPin;
    private Marker destinationPin;
    private Marker entrancePin;
    private Marker parkedCarPin;
    private Polyline routeLine;
    private Polyline junctionLine;
    private Polyline junctionHaloLine;
    private int highlightedJunctionIndex = -1;
    private TilesOverlay trafficTiles;
    private TextToSpeech speech;
    private boolean speechReady;
    private boolean trafficEnabled = true;
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
        // Configure osmdroid before constructing MapView. Cache tiles in the
        // app-private cache, and allow additional parallel tile downloads.
        // TomTom traffic remains a separate, optional overlay.
        Configuration.getInstance().setUserAgentValue(getPackageName());
        File mapCache = new File(getCacheDir(), "drivemate-map-tiles");
        if (!mapCache.exists()) mapCache.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(mapCache);
        Configuration.getInstance().setOsmdroidTileCache(new File(mapCache, "tiles"));
        Configuration.getInstance().setCacheMapTileCount((short) 36);
        Configuration.getInstance().setTileDownloadThreads((short) 4);
        Configuration.getInstance().setTileDownloadMaxQueueSize((short) 60);
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(180L * 1024L * 1024L);
        Configuration.getInstance().setTileFileSystemCacheTrimBytes(140L * 1024L * 1024L);
        SharedPreferences settings = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = settings.getString("tomtom_key", "");
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
        themePreference = Math.max(0, Math.min(2, settings.getInt("map_theme", 0)));
        hazardWarningEnabled = settings.getBoolean("hazard_voice_alerts", true);
        speedWarningEnabled = settings.getBoolean("speed_voice_alerts", true);
        closeZoom = settings.getBoolean("close_zoom", true);
        highDetailOnline = settings.getBoolean("high_detail_online", true);
        places = new DestinationStore(this);
        trafficEnabled = settings.getBoolean("traffic", false);
        useLocalMap = settings.getBoolean("drivemate_vector_map", true);
        vectorSavedState = savedInstanceState;
        startupMapView = MapStartupPolicy.initial(
                settings.getFloat("last_map_lat", Float.NaN),
                settings.getFloat("last_map_lon", Float.NaN),
                settings.getLong("last_map_at", 0L),
                System.currentTimeMillis());
        try {
            driveTiles = new DriveTileServer(this);
        } catch (java.io.IOException e) {
            driveTiles = null;
            useLocalMap = false;
        }
        buildScreen();
        applyTheme();
        updateVoiceButton();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        speech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && speech != null) {
                speech.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                int language = speech.setLanguage(Locale.UK);
                speechReady = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        if (apiKey.isEmpty()) map.post(this::showKeyDialog);
        else configureTiles();
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
        map = new MapView(this);
        map.setMultiTouchControls(true);
        final float[] touchAnchor = new float[2];
        map.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                touchAnchor[0] = event.getX();
                touchAnchor[1] = event.getY();
            } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
                    && Math.hypot(event.getX() - touchAnchor[0],
                            event.getY() - touchAnchor[1]) > dp(16)) {
                disengageFollowForGesture();
            }
            return false;
        });
        map.setMinZoomLevel(2.0);
        // Never initialise a UK navigation app at MapLibre's world-default
        // camera. An approximate saved map area is DISPLAY ONLY, never GPS.
        map.getController().setZoom(startupMapView.zoom);
        map.getController().setCenter(new GeoPoint(
                startupMapView.center.lat, startupMapView.center.lon));
        map.setVisibility(apiKey.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        // Keep both map engines inside the SAME background-only host:
        // the visible TomTom raster map covers MapLibre until actual UK roads
        // (not merely an HTTP tile) are drawn. Control overlays remain on top.
        FrameLayout mapHost = new FrameLayout(this);
        frame.addView(mapHost, new FrameLayout.LayoutParams(-1, -1));
        mapHost.addView(map, new FrameLayout.LayoutParams(-1, -1));
        if (driveTiles != null) {
            try {
                vectorMap = new VectorDriveMap(this, vectorSavedState, driveTiles,
                        apiKey, trafficEnabled, (status, roads) -> {
                            if (status == RoadDisplayHealth.State.READY) {
                                vectorRoadsReady = true;
                                if (useLocalMap && !autoFallback) {
                                    updateMapVisibility();
                                    if (!active) setStatus("DriveMate map ready · UK streets visible");
                                }
                            } else if (status == RoadDisplayHealth.State.FAILED
                                    && useLocalMap && !autoFallback) {
                                vectorRoadsReady = false;
                                if (!apiKey.isEmpty()) {
                                    autoFallback = true;
                                    updateMapVisibility();
                                    if (route != null && !active) showRoute(route, true);
                                    else if (currentLocation != null) centreOnLocation(true);
                                    setStatus("DriveMate streets not rendering · TomTom map active");
                                } else {
                                    setStatus("Map roads unavailable · check connection or TomTom key");
                                }
                            }
                        });
                vectorMap.setHighDetailEnabled(highDetailOnline);
                vectorMap.setOnUserGesture(this::disengageFollowForGesture);
                vectorMap.setCamera(startupMapView.center, startupMapView.zoom, 0);
                // Place MapLibre below the existing TomTom map while waiting
                // for first verifiably painted UK street features.
                mapHost.addView(vectorMap.view(), 0,
                        new FrameLayout.LayoutParams(-1, -1));
                updateMapVisibility();
            } catch (Throwable error) {
                android.util.Log.e("DriveMateMap", "Vector map initialization failed", error);
                vectorMap = null;
                useLocalMap = false;
                if (driveTiles != null) {
                    driveTiles.close();
                    driveTiles = null;
                }
            }
        }

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
        // Version lives in Settings → About; the home header stays clean.
        TextView subtitle = text("UK NAV", 10, Color.rgb(180, 222, 235), true);
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
        repeatButton = chip("🔊");
        repeatButton.setContentDescription("Repeat next spoken turn");
        repeatButton.setVisibility(View.GONE);
        repeatButton.setOnClickListener(v -> {
            if (nextInstruction.isEmpty()) showError("No upcoming direction yet.");
            else if (voiceMode == DrivingVoicePolicy.SILENT)
                Toast.makeText(this, "Enable voice guidance to repeat a turn",
                        Toast.LENGTH_SHORT).show();
            else speak(nextInstruction); // Explicit repeat works in Alerts only mode.
        });
        navActions.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        refreshButton = chip("↻");
        refreshButton.setContentDescription("Refresh traffic-aware route");
        refreshButton.setVisibility(View.GONE);
        refreshButton.setOnClickListener(v -> planRoute(false));
        navActions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        voiceButton = chip("🔊");
        voiceButton.setContentDescription("Change navigation voice mode");
        voiceButton.setVisibility(View.GONE);
        voiceButton.setOnClickListener(v -> cycleVoiceMode());
        navActions.addView(voiceButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        bottomActionsParams = new LinearLayout.LayoutParams(-1, -2);
        bottomActionsParams.topMargin = dp(10);
        navigationPanel.addView(navActions, bottomActionsParams);
        stopButton = chip("End navigation");
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(42, 80, 100)));
        stopButton.setTextColor(Color.WHITE);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopNavigation(false));
        // On a route the three compact controls occupy one row at the bottom.
        // The map is no longer covered by a large instruction/control sheet.
        navActions.addView(stopButton, new LinearLayout.LayoutParams(0, dp(45), 1));
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

        LinearLayout speedBadge = new LinearLayout(this);
        speedBadge.setOrientation(LinearLayout.VERTICAL);
        speedBadge.setGravity(Gravity.CENTER);
        GradientDrawable speedBackground = rounded(DARK, 100);
        speedBackground.setStroke(dp(3), CYAN);
        speedBadge.setBackground(speedBackground);
        speedBadge.setElevation(dp(6));
        TextView gpsLabel = text("GPS", 10, Color.WHITE, true);
        speedBadge.addView(gpsLabel);
        speedText = text(metricUnits ? "0 km/h" : "0 mph", 16, Color.WHITE, true);
        speedText.setGravity(Gravity.CENTER);
        speedBadge.addView(speedText);
        speedLimitText = text("Limit —", 12, Color.WHITE, true);
        speedLimitText.setGravity(Gravity.CENTER);
        speedLimitText.setContentDescription("Posted speed limit unknown");
        speedBadge.addView(speedLimitText);
        speedBadgePos = new FrameLayout.LayoutParams(dp(92), dp(102), Gravity.BOTTOM | Gravity.LEFT);
        speedBadgePos.leftMargin = dp(12);
        frame.addView(speedBadge, speedBadgePos);
        speedBadgeView = speedBadge;

        followButton = chip("◎");
        followButton.setContentDescription("Centre map on my GPS position");
        followButton.setTextSize(27);
        followButton.setTextColor(Color.WHITE);
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
        gpsButtonPos = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM | Gravity.RIGHT);
        gpsButtonPos.rightMargin = dp(13);
        frame.addView(followButton, gpsButtonPos);
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

    private boolean showingVector() {
        return useLocalMap && !autoFallback && vectorMap != null
                && vectorRoadsReady;
    }

    private void updateMapVisibility() {
        boolean vector = showingVector();
        // Keep the vector engine running *behind* the raster fallback until
        // its own rendered-road query proves the UK streets are visible.
        boolean vectorLoading = useLocalMap && !autoFallback && vectorMap != null;
        if (vectorMap != null)
            vectorMap.setVisible(vectorLoading || apiKey.isEmpty());
        if (map != null)
            map.setVisibility(!vector && !apiKey.isEmpty()
                    ? View.VISIBLE : View.INVISIBLE);
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
        turnCard.setPadding(dp(12), dp(9), dp(12), dp(10));
        turnCard.setBackground(rounded(Color.rgb(14, 47, 66), 14));
        drivingTurnCard = turnCard;
        LinearLayout.LayoutParams turnCardPos = new LinearLayout.LayoutParams(-1, -2);
        turnCardPos.topMargin = dp(6);
        container.addView(turnCard, turnCardPos);

        topTurnTitle = text("NEXT TURN", 10, CYAN, true);
        topTurnTitle.setLetterSpacing(0.08f);
        topTurnTitle.setSingleLine(true);
        topTurnTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        turnCard.addView(topTurnTitle);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        topTurnArrow = new TurnArrowView(this);
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(54), dp(55));
        arrow.rightMargin = dp(11);
        content.addView(topTurnArrow, arrow);
        topInstructionText = text("Preparing next instruction…", 18, Color.WHITE, true);
        topInstructionText.setMaxLines(2);
        topInstructionText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(topInstructionText, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams contentPos = new LinearLayout.LayoutParams(-1, -2);
        contentPos.topMargin = dp(2);
        turnCard.addView(content, contentPos);

        topNextTurnText = text("", 12, Color.rgb(201, 231, 239), false);
        topNextTurnText.setSingleLine(true);
        topNextTurnText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams thenPos = new LinearLayout.LayoutParams(-1, -2);
        thenPos.topMargin = dp(1);
        turnCard.addView(topNextTurnText, thenPos);
        verifiedJunctionRoad = text("", 12, CYAN, true);
        verifiedJunctionRoad.setSingleLine(true);
        verifiedJunctionRoad.setEllipsize(android.text.TextUtils.TruncateAt.END);
        verifiedJunctionRoad.setVisibility(View.GONE);
        verifiedJunctionRoad.setContentDescription("TomTom mapped junction signpost or exit road");
        turnCard.addView(verifiedJunctionRoad);

        // Only shown on the final approach to a verified complex junction.
        // The shape is traced from TomTom route geometry, never inferred
        // from roundabout exit numbers or guessed lane information.
        junctionDiagram = new JunctionManeuverView(this);
        junctionDiagram.setContentDescription("Calculated route path at the upcoming junction");
        junctionDiagram.setVisibility(View.GONE);
        LinearLayout.LayoutParams junctionPos = new LinearLayout.LayoutParams(-1, dp(84));
        junctionPos.topMargin = dp(5);
        turnCard.addView(junctionDiagram, junctionPos);

        laneGuidanceText = text("", 15, CYAN, true);
        laneGuidanceText.setGravity(Gravity.CENTER);
        laneGuidanceText.setContentDescription("Verified route lane recommendations");
        laneGuidanceText.setVisibility(View.GONE);
        LinearLayout.LayoutParams lanePos = new LinearLayout.LayoutParams(-1, -2);
        lanePos.topMargin = dp(5);
        turnCard.addView(laneGuidanceText, lanePos);

        trafficNoticeText = text("", 12, Color.rgb(245, 200, 116), true);
        trafficNoticeText.setSingleLine(true);
        trafficNoticeText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trafficNoticeText.setVisibility(View.GONE);
        trafficNoticeText.setOnClickListener(v -> showTrafficTimeline());
        LinearLayout.LayoutParams trafficPos = new LinearLayout.LayoutParams(-1, dp(25));
        trafficPos.topMargin = dp(3);
        container.addView(trafficNoticeText, trafficPos);

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

    private void applyNavigationChrome() {
        boolean navigating = active;
        if (drivingTopPanel != null)
            drivingTopPanel.setVisibility(navigating ? View.VISIBLE : View.GONE);
        if (brandHeader != null)
            brandHeader.setVisibility(navigating ? View.GONE : View.VISIBLE);
        if (tripMetricsRow != null)
            tripMetricsRow.setVisibility(navigating ? View.VISIBLE : View.GONE);
        if (voiceButton != null) voiceButton.setVisibility(navigating ? View.VISIBLE : View.GONE);
        if (!navigating) {
            if (arrivalHintText != null && arrivalHintText.getTag() instanceof View)
                ((View) arrivalHintText.getTag()).setVisibility(View.GONE);
        }
        if (!navigating) {
            if (laneGuidanceText != null) laneGuidanceText.setVisibility(View.GONE);
            if (trafficNoticeText != null) trafficNoticeText.setVisibility(View.GONE);
            if (rerouteSuggestionRow != null) rerouteSuggestionRow.setVisibility(View.GONE);
            if (speedLimitText != null) speedLimitText.setText("Limit —");
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
        if (homePanel == null || navigationPanel == null) return;
        homePanel.setVisibility(show && !keyboardOpen ? View.VISIBLE : View.GONE);
        navigationPanel.setVisibility(!show && !keyboardOpen ? View.VISIBLE : View.GONE);
        if (speedBadgeView != null) {
            // Home shows no speed readout; it returns when navigating.
            speedBadgeView.setVisibility(keyboardOpen || show ? View.GONE : View.VISIBLE);
        }
        if (followButton != null) {
            followButton.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            updateFollowButton();
        }
        if (speedBadgePos != null && gpsButtonPos != null) {
            // During navigation the compact control row is only ~60dp tall.
            // Keep GPS speed and recenter reachable without hiding the road.
            speedBadgePos.bottomMargin = dp(show ? 320 : active ? 100 : 225);
            gpsButtonPos.bottomMargin = dp(show ? 327 : active ? 105 : 230);
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
        setTileText(homeTile, "Home", home == null ? "Not set" : home.title);
        setTileText(workTile, "Work", work == null ? "Not set" : work.title);
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
                row.setOnClickListener(v -> selectDestination(item));
                recentList.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
                View line = new View(this);
                line.setBackgroundColor(Color.rgb(40, 86, 104));
                recentList.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
    }

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
                "TomTom API key",
                voiceMode == DrivingVoicePolicy.SILENT ? "Enable voice guidance" : "Mute voice guidance",
                "Voice guidance · " + voiceModeName(),
                "Voice volume · " + Math.round(guidanceVolume * 100) + "%",
                trafficEnabled ? "Hide live traffic overlay" : "Show live traffic overlay",
                metricUnits ? "Use UK miles, yards and mph" : "Use metric kilometres and km/h",
                "Camera · " + cameraModeName(),
                closeZoom ? "Junction detail zoom · On" : "Junction detail zoom · Off",
                autoFallback && useLocalMap ? "Retry DriveMate map (TomTom fallback active)"
                        : useLocalMap ? "Map · DriveMate local vector"
                        : "Map · TomTom online raster",
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
                "Online close-up map detail · " + (highDetailOnline ? "On" : "Off"),
                "Save where I parked",
                places != null && places.parkedCar() != null
                        ? "Find my parked car" : "Find my parked car · none saved"
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
                            trafficEnabled = !trafficEnabled;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("traffic", trafficEnabled).apply();
                            configureTrafficTiles();
                            break;
                        case 5:
                            metricUnits = !metricUnits;
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
                                useLocalMap = !useLocalMap;
                                autoFallback = false;
                                vectorRoadsReady = vectorMap.hasRenderedRoads();
                            }
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("drivemate_vector_map", useLocalMap).apply();
                            updateMapVisibility();
                            if (useLocalMap && currentLocation != null) {
                                vectorMap.setCamera(gpsPoint(currentLocation),
                                        map.getZoomLevelDouble(), 0);
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
                                            + "TomTom routing still needs internet. "
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
                                            + "Online TomTom routing, traffic and spoken directions. " +
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
                            highDetailOnline = !highDetailOnline;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("high_detail_online", highDetailOnline).apply();
                            if (vectorMap != null)
                                vectorMap.setHighDetailEnabled(highDetailOnline);
                            Toast.makeText(this, highDetailOnline
                                    ? "High-detail TomTom tiles enabled at close zoom (internet required)"
                                    : "Close-up map uses cached local vector streets",
                                    Toast.LENGTH_LONG).show();
                            break;
                        case 20: promptSaveParking(); break;
                        case 21: showParkedCar(); break;
                        default: break;
                    }
                }).setNegativeButton("Close", null).show();
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
        String[] symbols = {"🔊", "🔉", "⚠", "🔇"};
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
                    if (active && route != null && currentLocation != null) {
                        smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(route.points));
                        updateGuidance(currentLocation);
                    } else if (which == DrivingDisplayPolicy.CAMERA_NORTH_2D) {
                        map.setMapOrientation(0);
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
        final String[] labels = {"3 mph", "5 mph", "7 mph", "10 mph", "15 mph"};
        final int[] values = {3, 5, 7, 10, 15};
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
        return new String[]{"Automatic", "Day", "Night"}[themePreference];
    }

    private void selectThemeMode() {
        new AlertDialog.Builder(this).setTitle("Navigation map colours")
                .setSingleChoiceItems(new String[]{
                        "Automatic · follow phone appearance", "Day", "Night"
                }, themePreference, (dialog, which) -> {
                    themePreference = which;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt("map_theme", which).apply();
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
        if (map != null && !apiKey.isEmpty())
            map.setTileSource(tileSource(night ? "DriveMate-TomTom-Night" : "DriveMate-TomTom-Map",
                    night ? "map/1/tile/basic/night/" : "map/1/tile/basic/main/"));
        int panel = night ? DARK : Color.WHITE;
        int foreground = night ? Color.WHITE : Color.rgb(25, 48, 62);
        int subdued = night ? Color.rgb(191, 225, 236) : Color.rgb(55, 80, 96);
        if (navigationPanel != null) navigationPanel.setBackground(rounded(panel, 22));
        if (tripMetricsRow != null)
            tripMetricsRow.setBackground(rounded(night ? DARK : Color.rgb(232, 242, 247), 14));
        if (drivingTurnCard != null)
            drivingTurnCard.setBackground(rounded(night ? Color.rgb(14, 47, 66)
                    : Color.rgb(247, 251, 252), 14));
        for (TextView caption : tripMetricCaptions) caption.setTextColor(subdued);
        for (TextView value : new TextView[]{tripEtaValue, tripMinutesValue,
                tripDistanceValue, topInstructionText, instructionText,
                distanceText, journeyTimeText, etaText}) {
            if (value != null) value.setTextColor(foreground);
        }
        for (TextView value : new TextView[]{topNextTurnText, nextTurnText,
                routeSummary, gpsAccuracyText}) {
            if (value != null) value.setTextColor(subdued);
        }
        if (topTurnTitle != null) topTurnTitle.setTextColor(
                night ? CYAN : Color.rgb(0, 105, 130));
        if (banner != null) banner.setTextColor(
                night ? CYAN : Color.rgb(0, 96, 130));
        if (laneGuidanceText != null) laneGuidanceText.setTextColor(
                night ? CYAN : Color.rgb(0, 101, 130));
        if (trafficNoticeText != null) trafficNoticeText.setTextColor(
                night ? Color.rgb(245, 200, 116) : Color.rgb(132, 65, 0));
        if (topTurnArrow != null) topTurnArrow.setNightMode(night);
        if (maneuverIcon != null) maneuverIcon.setNightMode(night);
        if (junctionDiagram != null) junctionDiagram.setNightMode(night);
        if (verifiedJunctionRoad != null) verifiedJunctionRoad.setTextColor(
                night ? CYAN : Color.rgb(0, 100, 131));
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

    private void refreshSearchSuggestions() {
        if (destinationInput == null || places == null) return;
        String text = destinationInput.getText().toString().trim().toLowerCase(Locale.UK);
        suggestionMatches.clear();
        List<String> labels = new ArrayList<>();
        List<TomTomApi.Destination> candidates = new ArrayList<>(places.favorites());
        candidates.addAll(places.recent());
        for (TomTomApi.Destination candidate : candidates) {
            if (!text.isEmpty()
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
                    if (apiKey.isEmpty()) {
                        showKeyDialog();
                        return;
                    }
                    GeoMath.Point near = currentLocation == null ? null : gpsPoint(currentLocation);
                    setStatus("Finding " + label + "…");
                    network.execute(() -> {
                        try {
                            List<TomTomApi.Destination> options =
                                    TomTomApi.search(apiKey, query, near);
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

    private void showKeyDialog() {
        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("Paste your TomTom API key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setText(apiKey);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(20), dp(8), dp(20), 0);
        holder.addView(keyInput, new FrameLayout.LayoutParams(-1, dp(55)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Connect TomTom")
                .setMessage("A TomTom API key with Maps, Search, Routing and Traffic access is required. The key stays on this device.")
                .setView(holder)
                .setPositiveButton("Save and open map", null)
                .setNegativeButton("Cancel", (d, w) -> {
                    if (apiKey.isEmpty()) setStatus("Enter an API key to show the map.");
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String supplied = keyInput.getText().toString().trim();
            if (supplied.isEmpty()) {
                keyInput.setError("A key is required");
                return;
            }
            if (!supplied.equals(apiKey)) stopNavigation(false);
            apiKey = supplied;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("tomtom_key", supplied).apply();
            configureTiles();
            setStatus("Connected. Search for a destination.");
            instructionText.setText("Type an address or postcode and tap GO.");
            dialog.dismiss();
        }));
        dialog.show();
    }

    private XYTileSource tileSource(String name, String prefix) {
        final String key = Uri.encode(apiKey);
        return new XYTileSource(name, 0, 20, 256, ".png",
                new String[]{"https://api.tomtom.com/"}) {
            @Override public String getTileURLString(long tileIndex) {
                return "https://api.tomtom.com/" + prefix
                        + MapTileIndex.getZoom(tileIndex) + "/"
                        + MapTileIndex.getX(tileIndex) + "/"
                        + MapTileIndex.getY(tileIndex) + ".png?key=" + key;
            }
        };
    }

    private void configureTiles() {
        if (apiKey.isEmpty()) return;
        map.setTileSource(tileSource(
                nightDisplay ? "DriveMate-TomTom-Night" : "DriveMate-TomTom-Map",
                nightDisplay ? "map/1/tile/basic/night/" : "map/1/tile/basic/main/"));
        if (vectorMap != null) vectorMap.setTomTomKey(apiKey);
        updateMapVisibility();
        configureTrafficTiles();
        map.invalidate();
    }

    private void configureTrafficTiles() {
        if (trafficTiles != null) {
            map.getOverlays().remove(trafficTiles);
            trafficTiles = null;
        }
        if (trafficEnabled && !apiKey.isEmpty()) {
            trafficTiles = new TilesOverlay(
                    new MapTileProviderBasic(this, tileSource("DriveMate-TomTom-Traffic",
                            "traffic/map/4/tile/flow/relative0/")), this);
            trafficTiles.setLoadingBackgroundColor(Color.TRANSPARENT);
            trafficTiles.setLoadingLineColor(Color.TRANSPARENT);
            map.getOverlays().add(0, trafficTiles);
        }
        if (vectorMap != null) vectorMap.setTraffic(trafficEnabled);
        map.invalidate();
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
        map.getController().setZoom(zoom);
        map.getController().setCenter(new GeoPoint(preview.lat, preview.lon));
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
            boolean fused = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && locationManager.hasProvider(LocationManager.FUSED_PROVIDER)
                    && locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                LocationRequest request = new LocationRequest.Builder(0L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateIntervalMillis(0L)
                        .build();
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER,
                        request, getMainExecutor(), this);
                fused = true;
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // Ask for the fastest rate the GNSS hardware supports (0 ms, 0 m).
                // Android delivers at the receiver's own maximum, typically 1 Hz
                // on most phones and higher on some. This is the main battery cost.
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        0L, 0f, this);
                locationManager.registerGnssStatusCallback(gnssCallback,
                        new Handler(Looper.getMainLooper()));
                requested = true;
            }
            // Fused already uses Wi-Fi and cell, so the network provider is only
            // needed on older phones.
            if (!fused && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Maximum rate here too; the fix gate decides which fixes are used.
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        0L, 0f, this);
                requested = true;
            }
            locationSubscribed = requested;
            gpsHandler.removeCallbacks(deadReckonTick);
            if (requested) gpsHandler.post(deadReckonTick);
            if (requested) restoreLastKnownMapArea();
        } catch (SecurityException error) {
            locationSubscribed = false;
            setStatus("Location permission denied. Enable Precise Location.");
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
        if (!FixQualityPolicy.accept(lastAcceptedFix, sample)) return;
        lastAcceptedFix = sample;
        deadReckoner.onFix(new GeoMath.Point(fix.getLatitude(), fix.getLongitude()),
                fix.getTime(), fix.hasSpeed() ? fix.getSpeed() : 0f,
                fix.hasBearing() ? fix.getBearing() : 0d, fix.hasBearing());
        if (gpsLost) {
            gpsLost = false;
            if (active) {
                setStatus("GPS signal restored · guidance resumed");
                saySystemWarning("GPS signal restored.");
            }
        }
        if (currentLocation != null && fix.getTime() < currentLocation.getTime()
                && currentLocation.getTime() - fix.getTime() > 3000) return;
        currentLocation = fix;
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
        GeoPoint point = new GeoPoint(smoothed.lat, smoothed.lon);
        if (userPin == null) {
            userPin = new Marker(map);
            userPin.setTitle("Your live GPS position");
            userPin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userPin.setFlat(true);
            userPin.setIcon(makeCarIcon());
            map.getOverlays().add(userPin);
        }
        // The independent frame clock owns the moving marker while driving.
        // Never teleport it back to each noisy 1Hz GPS fix.
        if (!active || smoothFollow == null) userPin.setPosition(point);
        if (vectorMap != null && useLocalMap) {
            if (!active || smoothFollow == null) vectorMap.setVehicle(smoothed);
            if (!autoFallback) vectorMap.prefetch(gpsPoint(fix));
        }
        if ((!active || smoothFollow == null) && fix.hasBearing()
                && fix.hasSpeed() && fix.getSpeed() > 1.5f)
            userPin.setRotation(-fix.getBearing());
        float filteredSpeed = fix.hasSpeed() && fix.getSpeed() >= 0.9f
                ? fix.getSpeed() : 0f;
        speedText.setText(NavigationUnits.displaySpeed(filteredSpeed, metricUnits));
        if (!active && !routing && destination == null && fix.hasAccuracy()) {
            setStatus("GPS ready · accuracy ±" + Math.round(fix.getAccuracy()) + " m"
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
        if (gpsAccuracyText != null && active) {
            gpsAccuracyText.setText("GPS accuracy "
                    + (fix.hasAccuracy() ? "±" + Math.round(fix.getAccuracy()) + " m" : "unknown")
                    + satelliteSuffix()
                    + (routing ? "  ·  updating traffic route…" : ""));
        }
        if (active && route != null && !routing) updateGuidance(fix);
        if (!active && !showingVector()) map.postInvalidateOnAnimation();
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
        Bitmap bitmap = Bitmap.createBitmap(dp(48), dp(48), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = bitmap.getWidth() / 2f;
        float cy = bitmap.getHeight() / 2f;
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, dp(15), paint);
        paint.setColor(BLUE);
        Path arrow = new Path();
        arrow.moveTo(cx, cy - dp(15));
        arrow.lineTo(cx + dp(11), cy + dp(11));
        arrow.lineTo(cx, cy + dp(6));
        arrow.lineTo(cx - dp(11), cy + dp(11));
        arrow.close();
        canvas.drawPath(arrow, paint);
        return new BitmapDrawable(getResources(), bitmap);
    }

    private void centreOnLocation(boolean forceZoom) {
        if (currentLocation == null) return;
        GeoMath.Point position = gpsPoint(currentLocation);
        boolean vector = showingVector();
        double zoom = vector ? vectorMap.currentZoom() : map.getZoomLevelDouble();
        if (forceZoom || zoom < 14.0) zoom = 16.5;
        if (vector) {
            vectorMap.setCamera(position, zoom, 0);
        } else {
            if (Math.abs(map.getZoomLevelDouble() - zoom) > 0.1)
                map.getController().setZoom(zoom);
            map.getController().setCenter(new GeoPoint(position.lat, position.lon));
            if (useLocalMap && !autoFallback && vectorMap != null) {
                // The candidate map needs the same real UK camera position
                // in order to request and *render* street tiles for verification.
                vectorMap.setCamera(position, zoom, 0);
            }
        }
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
        if (!active || !followLocation || route == null || gpsLost
                || progress.offRouteMetres > 110
                || (fix.hasAccuracy() && fix.getAccuracy() > 75)
                || map.getHeight() < dp(350)) return;
        long now = SystemClock.elapsedRealtime();
        if (lastCameraUpdate > 0 && now - lastCameraUpdate < 450) return;
        if (smoothFollow == null)
            smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(route.points));

        int headerHeight = topMapOverlay != null && topMapOverlay.getHeight() > 0
                ? topMapOverlay.getHeight() + dp(5) : dp(260);
        int bottomPanelHeight = navigationPanel.getHeight() > 0
                ? navigationPanel.getHeight() + dp(18) : dp(95);
        double along = stabilizedAlong < 0 ? progress.alongMetres : stabilizedAlong;
        float velocity = fix.hasSpeed() ? fix.getSpeed() : 0f;
        NavigationCamera.Frame target = NavigationCamera.calculate(
                route.points, gpsPoint(fix), along, nextJunctionOffsetMetres,
                velocity, map.getHeight(), headerHeight, bottomPanelHeight,
                closeZoom);
        float accuracy = fix.hasAccuracy() ? fix.getAccuracy() : 100f;
        // Retain the route-aware look-ahead in all camera modes. Flat modes
        // request zero pitch instead of abandoning the smooth-follow engine.
        target = new NavigationCamera.Frame(target.center, target.zoom,
                target.roadBearingDegrees, target.junctionMetres,
                target.cameraCenterAlongMetres,
                DrivingDisplayPolicy.tilt(target.tiltDegrees, cameraMode));
        smoothFollow.onGps(displayGps != null ? displayGps : gpsPoint(fix),
                along, progress.offRouteMetres, accuracy, velocity,
                Math.max(0, nextJunctionOffsetMetres - along),
                target, DrivingDisplayPolicy.northUp(cameraMode), now);
        lastCameraUpdate = now;
        startSmoothLoop();
    }

    private void searchDestination() {
        if (apiKey.isEmpty()) {
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
                List<TomTomApi.Destination> found = TomTomApi.search(apiKey, query, near);
                runOnUiThread(() -> {
                    if (searchId != requestGeneration || isFinishing()) return;
                    if (found.isEmpty()) {
                        showError("No matching destinations. Try a fuller address.");
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
            detail += " · traffic checked";
        }
        return detail;
    }

    private void showPreview(TomTomApi.Route calculated) {
        active = false;
        setHomeVisible(false);
        routeSummary.setText(previewDetails(calculated));
        routeSummary.setVisibility(View.VISIBLE);
        instructionText.setText("Review your route before driving.");
        maneuverIcon.setVisibility(View.GONE);
        nextTurnText.setVisibility(View.GONE);
        distanceText.setText("Distance: " + NavigationUnits.displayDistance(
                calculated.lengthMetres, metricUnits));
        journeyTimeText.setText("Drive: " + NavigationUnits.duration(calculated.timeSeconds));
        etaText.setText("ETA: " + new SimpleDateFormat("HH:mm", Locale.UK)
                .format(new Date(System.currentTimeMillis()
                        + calculated.timeSeconds * 1000L)));
        startButton.setVisibility(View.VISIBLE);
        saveButton.setVisibility(View.VISIBLE);
        if (previewParkingButton != null) previewParkingButton.setVisibility(View.VISIBLE);
        saveButton.setText(places.isFavorite(destination) ? "★ Saved" : "★ Save");
        repeatButton.setVisibility(View.GONE);
        refreshButton.setVisibility(View.GONE);
        stopButton.setText("Cancel");
        stopButton.setContentDescription("Cancel route preview");
        stopButton.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus("Route ready · tap Start when parked and ready");
    }

    private void startNavigation() {
        if (route == null || destination == null) return;
        if (currentLocation == null || System.currentTimeMillis() - currentLocation.getTime() > 15000) {
            showError("Waiting for a fresh GPS position before starting.");
            return;
        }
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
        stopButton.setText("End");
        stopButton.setContentDescription("End navigation");
        setStatus("Navigating to " + destination.title);
        say("Navigation started. Follow the directions.");
        lastCameraUpdate = 0;
        previousInstructionIndex = 0;
        stabilizedAlong = -1;
        observedSpeed = 0;
        updateGuidance(currentLocation);
        startSmoothLoop();
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
        if (destinationPin == null) {
            destinationPin = new Marker(map);
            destinationPin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            map.getOverlays().add(destinationPin);
        }
        destinationPin.setPosition(new GeoPoint(selected.point.lat, selected.point.lon));
        if (vectorMap != null) vectorMap.setDestination(selected.point);
        destinationPin.setTitle(selected.toString());
        planRoute(true);
    }

    /** Only a provider-listed POI main entry point is marked as an entrance. */
    private void displayEntranceMarker(TomTomApi.Destination selected) {
        if (entrancePin != null) {
            map.getOverlays().remove(entrancePin);
            entrancePin = null;
        }
        GeoMath.Point entry = selected == null ? null : selected.mainEntrance;
        if (vectorMap != null) vectorMap.setEntrance(entry);
        if (entry != null) {
            entrancePin = new Marker(map);
            entrancePin.setPosition(new GeoPoint(entry.lat, entry.lon));
            entrancePin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            entrancePin.setTitle("Map-listed main entrance · verify road access");
            map.getOverlays().add(entrancePin);
        }
        map.invalidate();
    }

    private void showArrivalApproach(ArrivalPolicy.State state) {
        if (arrivalHintText == null || !(arrivalHintText.getTag() instanceof View))
            return;
        View panel = (View) arrivalHintText.getTag();
        if (state == ArrivalPolicy.State.EN_ROUTE || destination == null) {
            panel.setVisibility(View.GONE);
            return;
        }
        String label = state == ArrivalPolicy.State.NEARBY
                ? "Destination nearby · slow down and check access"
                : "Destination ahead · follow final road";
        if (destination.mainEntrance != null)
            label += "\nMap-listed entrance marked · verify access";
        else
            label += "\nEntrance and correct road side not verified";
        arrivalHintText.setText(label);
        panel.setVisibility(View.VISIBLE);
        // The parking lookup is offered only when the driver is stationary.
        boolean parked = currentLocation != null && currentLocation.hasSpeed()
                && currentLocation.getSpeed() <= 1.2f
                && System.currentTimeMillis() - currentLocation.getTime() <= 7000;
        findParkingButton.setEnabled(parked);
        findParkingButton.setAlpha(parked ? 1f : 0.45f);
    }

    /** Parking information is requested only on demand and never guessed. */
    private void findNearbyParking() {
        if (destination == null || apiKey.isEmpty()) {
            showError("Choose a destination and configure TomTom before finding parking.");
            return;
        }
        if (currentLocation == null || !currentLocation.hasSpeed()
                || currentLocation.getSpeed() > 1.2f
                || System.currentTimeMillis() - currentLocation.getTime() > 7000) {
            showError("Stop safely before finding or selecting parking.");
            return;
        }
        final TomTomApi.Destination searchedFor = destination;
        final String key = apiKey;
        setStatus("Looking for map-listed parking near destination…");
        network.execute(() -> {
            try {
                List<TomTomApi.Destination> results =
                        TomTomApi.parkingNearby(key, searchedFor.point);
                results.removeIf(item -> GeoMath.distance(
                        item.point, searchedFor.point) > 1400);
                results.sort((a, b) -> Double.compare(
                        GeoMath.distance(a.point, searchedFor.point),
                        GeoMath.distance(b.point, searchedFor.point)));
                runOnUiThread(() -> {
                    if (isFinishing() || destination == null
                            || GeoMath.distance(destination.point, searchedFor.point) > 2)
                        return;
                    if (results.isEmpty()) {
                        new AlertDialog.Builder(this).setTitle("Nearby parking")
                                .setMessage("TomTom returned no verified nearby parking POIs. "
                                        + "Parking availability and access are not guaranteed.")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    String[] names = new String[results.size()];
                    for (int i = 0; i < results.size(); i++) {
                        TomTomApi.Destination item = results.get(i);
                        names[i] = item.title + " · " + NavigationUnits.displayDistance(
                                GeoMath.distance(item.point, searchedFor.point), metricUnits);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Map-listed parking · availability unknown")
                            .setItems(names, (dialog, which) -> {
                                TomTomApi.Destination chosen = results.get(which);
                                new AlertDialog.Builder(this)
                                        .setTitle("Preview route to " + chosen.title + "?")
                                        .setMessage("This will replace your current destination. "
                                                + "Parking capacity, pricing and vehicle access "
                                                + "have not been verified. Follow signs.")
                                        .setPositiveButton("Preview route", (d, w) ->
                                                selectDestination(chosen))
                                        .setNegativeButton("Keep destination", null).show();
                            })
                            .setNegativeButton("Cancel", null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && destination != null
                            && GeoMath.distance(destination.point, searchedFor.point) <= 2)
                        showError("Nearby parking unavailable: " + error.getMessage());
                });
            }
        });
    }

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
        if (parkedCarPin != null) map.getOverlays().remove(parkedCarPin);
        parkedCarPin = new Marker(map);
        parkedCarPin.setPosition(new GeoPoint(saved.lat, saved.lon));
        parkedCarPin.setTitle("Saved parked-car position · GPS estimate");
        map.getOverlays().add(parkedCarPin);
        if (vectorMap != null) {
            vectorMap.setParkedCar(saved);
            vectorMap.setCamera(saved, 17.0, 0);
        }
        map.getController().setZoom(17.0);
        map.getController().setCenter(new GeoPoint(saved.lat, saved.lon));
        map.invalidate();
        new AlertDialog.Builder(this).setTitle("Parked car · saved on this phone")
                .setMessage("Showing the GPS position you explicitly saved. "
                        + "It may not be an exact parking bay. "
                        + "DriveMate does not provide a walking route to your car.")
                .setPositiveButton("Done", null)
                .setNeutralButton("Clear saved position", (dialog, which) -> {
                    places.clearParkedCar();
                    if (parkedCarPin != null) {
                        map.getOverlays().remove(parkedCarPin);
                        parkedCarPin = null;
                    }
                    if (vectorMap != null) vectorMap.setParkedCar(null);
                    refreshHomePanel();
                    map.invalidate();
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
        if (routing || destination == null || currentLocation == null || apiKey.isEmpty()) return;
        if (!hasNetwork()) {
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
        final GeoMath.Point start = gpsPoint(currentLocation);
        final GeoMath.Point end = destination.point;
        final String keyForRequest = apiKey;
        setStatus(firstRoute ? "Planning traffic-aware route…" : "Updating route for traffic / GPS…");
        network.execute(() -> {
            try {
                TomTomApi.Route calculated = TomTomApi.route(keyForRequest, start, end);
                runOnUiThread(() -> {
                    if (plannedGeneration != requestGeneration || isFinishing()) return;
                    routing = false;
                    // Route updates start from the *current* GPS. Compare only
                    // after confirming the old route is still being followed.
                    if (!firstRoute && active && route != null) {
                        if (currentLocation == null || !currentLocation.hasAccuracy()
                                || currentLocation.getAccuracy() > 55
                                || System.currentTimeMillis() - currentLocation.getTime() > 15000) {
                            setStatus("Uncertain GPS · current route retained");
                            return;
                        }
                        // A route calculated from a GPS point that the driver
                        // has already left is unsafe to install automatically.
                        if (GeoMath.distance(gpsPoint(currentLocation), start) > 110) {
                            setStatus("GPS moved during route update · old route retained");
                            return;
                        }
                        GeoMath.Progress old = GeoMath.project(route.points, start);
                        boolean confirmedOffRoute = old.offRouteMetres > 90
                                && currentLocation.getAccuracy() <= 35;
                        if (!confirmedOffRoute && old.offRouteMetres > 45) {
                            setStatus("Checking GPS alignment · previous route retained");
                            return;
                        }
                        if (!confirmedOffRoute) {
                            boolean different = RouteIntelligence.genuinelyDifferent(
                                    route.points, old.alongMetres, calculated.points);
                            double remaining = Math.max(0, route.lengthMetres
                                    - old.alongMetres);
                            int oldSeconds = (int) Math.round(
                                    route.timeSeconds * remaining
                                            / Math.max(1, route.lengthMetres));
                            boolean oldFresh = System.currentTimeMillis()
                                    - route.fetchedAtMs <= 480000;
                            int saving = oldFresh ? RouteIntelligence.meaningfulSaving(
                                    oldSeconds, calculated.timeSeconds, different) : 0;
                            if (different) {
                                if (saving > 0) {
                                    if (rerouteMode == 1) {
                                        showPendingRoute(calculated, start, saving);
                                        setStatus("Faster route available · choose Switch or Keep");
                                        return;
                                    }
                                    // Automatic mode uses only a *verified*
                                    // improvement over a recent reference ETA.
                                } else if (!oldFresh && rerouteMode == 1) {
                                    // When the old ETA is stale, an alternative
                                    // is a choice, not an automatically faster
                                    // route. Never silently switch on that basis.
                                    showPendingRoute(calculated, start, 0);
                                    setStatus("Updated route · time saving unverified");
                                    return;
                                } else {
                                    setStatus("Current route retained · no verified time saving");
                                    return;
                                }
                            }
                        }
                    }
                    installCalculatedRoute(calculated, firstRoute);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (plannedGeneration != requestGeneration) return;
                    routing = false;
                    if (route != null) {
                        setStatus("Update failed · following last known route");
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
        route = calculated;
        if (active) {
            // A new route needs an independent motion index, never a blend
            // through the unrelated polyline of the previous route.
            smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(calculated.points));
            lastCameraUpdate = 0;
            lastCameraCenter = null;
        }
        offRouteSince = 0;
        previousInstructionIndex = 0;
        stabilizedAlong = -1;
        observedSpeed = 0;
        arrivalPolicy.reset();
        spokenDistanceIndex = -1;
        spokenCloseIndex = -1;
        spokenNowIndex = -1;
        lastTrafficWarningStart = -1;
        lastTrafficWarningAt = 0;
        lastSpeedWarningAt = 0;
        showRoute(calculated, firstRoute);
        if (firstRoute) {
            showPreview(calculated);
        } else {
            setStatus("Route updated · latest TomTom traffic included");
            say("Updated route ready.");
            if (currentLocation != null) updateGuidance(currentLocation);
        }
    }

    private void showRoute(TomTomApi.Route calculated, boolean preview) {
        clearJunctionHighlight();
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = new Polyline();
        // Draw fewer vertices without changing the full route used by GPS,
        // distances, roundabouts, and manoeuvre selection.
        List<GeoMath.Point> displayPoints = DisplayPolyline.simplify(
                calculated.points, 1.5);
        List<GeoPoint> geometry = new ArrayList<>(displayPoints.size());
        for (GeoMath.Point point : displayPoints)
            geometry.add(new GeoPoint(point.lat, point.lon));
        routeLine.setPoints(geometry);
        routeLine.getOutlinePaint().setColor(BLUE);
        routeLine.getOutlinePaint().setStrokeWidth(dp(5));
        routeLine.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        routeLine.getOutlinePaint().setStrokeJoin(Paint.Join.ROUND);
        map.getOverlays().add(routeLine);
        // Keep the vehicle marker and destination pin above the route line.
        if (destinationPin != null) {
            map.getOverlays().remove(destinationPin);
            map.getOverlays().add(destinationPin);
        }
        if (entrancePin != null) {
            map.getOverlays().remove(entrancePin);
            map.getOverlays().add(entrancePin);
        }
        if (userPin != null) {
            map.getOverlays().remove(userPin);
            map.getOverlays().add(userPin);
        }
        map.invalidate();
        if (vectorMap != null) {
            if (preview) vectorMap.previewRoute(calculated.points);
            else vectorMap.setRoute(calculated.points);
        }
        if (preview && !showingVector()) {
            map.setMapOrientation(0);
            double north = -90, south = 90, east = -180, west = 180;
            for (GeoMath.Point point : calculated.points) {
                north = Math.max(north, point.lat);
                south = Math.min(south, point.lat);
                east = Math.max(east, point.lon);
                west = Math.min(west, point.lon);
            }
            final BoundingBox box = new BoundingBox(north, east, south, west);
            map.post(() -> map.zoomToBoundingBox(box, true, dp(80)));
        }
    }

    private void updateGuidance(Location fix) {
        if (!active || route == null || destination == null) return;
        GeoMath.Point current = gpsPoint(fix);
        GeoMath.Progress projected = GeoMath.project(route.points, current);
        long now = SystemClock.elapsedRealtime();
        float accuracy = fix.hasAccuracy() ? fix.getAccuracy() : 100f;

        // Use precise fixes for detecting a genuine prolonged departure from
        // the route. Do not reroute over one noisy fix or during API failures.
        if (accuracy <= 35 && projected.offRouteMetres > 90) {
            if (offRouteSince == 0) offRouteSince = now;
            if (now - offRouteSince >= 12000
                    && now - lastRerouteAttempt >= 45000
                    && now - lastRouteRequest >= 30000 && !routing) {
                offRouteSince = 0;
                lastRerouteAttempt = now;
                setStatus("Off route · recalculating safely…");
                say("Recalculating your route.");
                planRoute(false);
                return;
            }
        } else {
            offRouteSince = 0;
        }

        double measured = projected.alongMetres;
        double along = GuidanceLogic.monotonicProgress(stabilizedAlong, measured,
                projected.offRouteMetres, accuracy);
        stabilizedAlong = along;
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
            return;
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
        tripDistanceValue.setContentDescription(
                TripHeaderFormat.distanceLeft(leftMetres, metricUnits) + " remaining");

        double[] offsets = new double[route.instructions.size()];
        for (int i = 0; i < route.instructions.size(); i++)
            offsets[i] = route.instructions.get(i).routeOffsetMetres;
        int next = GuidanceLogic.nextInstructionIndex(offsets, along, previousInstructionIndex);
        if (next < 0) {
            updateRoadIntelligence(fix, along, projected.offRouteMetres,
                    accuracy, Double.POSITIVE_INFINITY);
            topInstructionText.setText("Continue along the highlighted route");
            topNextTurnText.setVisibility(View.GONE);
            clearJunctionHighlight();
            return;
        }
        previousInstructionIndex = next;
        TomTomApi.Instruction step = route.instructions.get(next);
        double untilTurn = Math.max(0, step.routeOffsetMetres - along);
        updateRoadIntelligence(fix, along, projected.offRouteMetres,
                accuracy, untilTurn);
        String direction = GuidanceLogic.actionLine(step.message, step.junctionType,
                step.exitNumber);
        String glyph = GuidanceLogic.symbol(step.maneuver, step.junctionType,
                step.turnAngleDegrees);
        maneuverIcon.update(glyph, step.exitNumber);
        maneuverIcon.setVisibility(View.VISIBLE);
        topTurnArrow.update(glyph, step.exitNumber);
        boolean roundabout = "↻".equals(glyph);
        boolean fork = "BIFURCATION".equalsIgnoreCase(step.junctionType);
        if (verifiedJunctionRoad != null) {
            String sign = step.signpost == null ? "" : step.signpost.trim();
            String road = step.street == null ? "" : step.street.trim();
            String guidance = !sign.isEmpty() ? "TOMTOM SIGN · " + sign
                    : !road.isEmpty() ? "EXIT ROAD · " + road : "";
            verifiedJunctionRoad.setText(guidance);
            verifiedJunctionRoad.setVisibility((roundabout || fork) && !guidance.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
        topTurnTitle.setText(fix.hasAccuracy() && fix.getAccuracy() > 55
                ? "GPS UNCERTAIN · CHECK ROAD SIGNS"
                : roundabout && step.exitNumber > 0
                        ? "ROUNDABOUT · " + GuidanceLogic.ordinal(step.exitNumber) + " EXIT"
                        : fork ? "ROAD FORK · FOLLOW ARROW" : "NEXT TURN");
        int iconSide = dp(roundabout || fork ? 70 : 54);
        if (topTurnArrow.getLayoutParams().width != iconSide) {
            android.view.ViewGroup.LayoutParams iconParams = topTurnArrow.getLayoutParams();
            iconParams.width = iconSide;
            iconParams.height = iconSide;
            topTurnArrow.setLayoutParams(iconParams);
        }
        updateJunctionHighlight(next,
                DrivingDisplayPolicy.junctionPreview(roundabout || fork, untilTurn));
        String nextTurnLabel = NavigationUnits.displayDistance(untilTurn, metricUnits)
                + " · " + direction;
        instructionText.setText(nextTurnLabel);
        topInstructionText.setText(nextTurnLabel);
        topInstructionText.setContentDescription(
                "Next turn in " + NavigationUnits.spokenDistance(untilTurn, metricUnits)
                + ": " + direction);

        if (next + 1 < route.instructions.size()) {
            TomTomApi.Instruction after = route.instructions.get(next + 1);
            double afterTurn = Math.max(0, after.routeOffsetMetres - step.routeOffsetMetres);
            String followUp = GuidanceLogic.actionLine(after.message, after.junctionType,
                    after.exitNumber);
            String followingTurn = "Then "
                    + GuidanceLogic.symbol(after.maneuver, after.junctionType,
                            after.turnAngleDegrees) + " "
                    + NavigationUnits.displayDistance(afterTurn, metricUnits)
                    + " later · " + followUp;
            nextTurnText.setText(followingTurn);
            boolean showThen = DrivingDisplayPolicy.showFollowingTurn(untilTurn, afterTurn);
            topNextTurnText.setText(showThen ? followingTurn : "");
            topNextTurnText.setVisibility(showThen ? View.VISIBLE : View.GONE);
            nextTurnText.setVisibility(View.GONE);
        } else {
            nextTurnText.setText("Continue towards your destination");
            topNextTurnText.setText("");
            topNextTurnText.setVisibility(View.GONE);
            nextTurnText.setVisibility(View.GONE);
        }

        nextInstruction = untilTurn <= 30 ? direction
                : "In " + NavigationUnits.spokenDistance(untilTurn, metricUnits)
                + ", " + direction;
        updateDrivingCamera(fix, projected, step.routeOffsetMetres);
        if (untilTurn <= 65 && spokenNowIndex != next) {
            spokenNowIndex = next;
            if (DrivingVoicePolicy.turn(voiceMode, 2)) say(direction);
        } else if (untilTurn > 65 && untilTurn <= 250 && spokenCloseIndex != next) {
            spokenCloseIndex = next;
            if (DrivingVoicePolicy.turn(voiceMode, 1)) say(nextInstruction);
        } else if (untilTurn > 250 && untilTurn <= 1000 && spokenDistanceIndex != next) {
            spokenDistanceIndex = next;
            if (DrivingVoicePolicy.turn(voiceMode, 0)) say(nextInstruction);
        }
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
                && age <= 480000 && !gpsLost;
        RouteIntelligence.LaneSection lanes = confident
                ? RouteIntelligence.approachingLanes(route.laneSections, along, 650) : null;
        if (laneGuidanceText != null) {
            laneGuidanceText.setText(lanes == null ? "" : lanes.display());
            laneGuidanceText.setVisibility(lanes == null ? View.GONE : View.VISIBLE);
        }
        RouteIntelligence.SpeedSection limit = RouteIntelligence.speedAt(
                route.speedSections, along, offRoute, accuracy, age);
        boolean speeding = limit != null && fix.hasSpeed()
                && RouteIntelligence.overspeed(fix.getSpeed(), limit, speedToleranceMph);
        if (speedLimitText != null) {
            if (limit == null) {
                speedLimitText.setText("Limit —");
                speedLimitText.setContentDescription("Posted speed limit unavailable");
            } else {
                int shown = metricUnits ? limit.kmh
                        : (int) Math.round(limit.kmh / 1.609344);
                String label = shown + (metricUnits ? " km/h" : " mph");
                speedLimitText.setText("Limit " + label);
                speedLimitText.setContentDescription("Mapped speed limit " + label
                        + ". Road signs take precedence.");
            }
        }
        speedText.setTextColor(speeding ? Color.rgb(255, 197, 85) : Color.WHITE);
        long now = SystemClock.elapsedRealtime();
        if (speeding && speedWarningEnabled && distanceToTurn > 300
                && (lastSpeedWarningAt == 0 || now - lastSpeedWarningAt > 120000)) {
            lastSpeedWarningAt = now;
            sayVerifiedAlert("Check your speed. You may be above the mapped speed limit.",
                    true);
        }

        RouteIntelligence.TrafficSection traffic = confident
                ? RouteIntelligence.nextTraffic(route.trafficSections, along, 2500) : null;
        if (trafficNoticeText != null) {
            if (traffic == null) {
                trafficNoticeText.setVisibility(View.GONE);
            } else {
                int minutes = (int) Math.ceil(traffic.delaySeconds / 60.0);
                String suffix = traffic.delaySeconds > 0 ? " · +" + minutes + " min" : "";
                trafficNoticeText.setText("⚠ " + traffic.label() + " in "
                        + NavigationUnits.displayDistance(
                                Math.max(0, traffic.start - along), metricUnits)
                        + suffix + "  ·  Timeline");
                trafficNoticeText.setVisibility(View.VISIBLE);
            }
        }
        if (traffic != null && hazardWarningEnabled && traffic.start > along
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

    /**
     * Highlight only the actual TomTom route geometry through a verified
     * roundabout or bifurcation. Geometry is never inferred from exit counts.
     */
    private void updateJunctionHighlight(int instructionIndex, boolean visible) {
        if (!visible || route == null || route.points.size() < 2) {
            clearJunctionHighlight();
            return;
        }
        if (highlightedJunctionIndex == instructionIndex) return;
        clearJunctionHighlight();
        TomTomApi.Instruction instruction = route.instructions.get(instructionIndex);
        RouteMotionIndex index = new RouteMotionIndex(route.points);
        double from = Math.max(0, instruction.routeOffsetMetres - 110);
        double to = Math.min(index.lengthMetres, instruction.routeOffsetMetres + 130);
        if (to - from < 10) return;
        List<GeoMath.Point> segment = new ArrayList<>();
        for (double along = from; along < to; along += 12)
            segment.add(index.pointAt(along));
        segment.add(index.pointAt(to));
        if (segment.size() < 2) return;
        List<GeoPoint> drawing = new ArrayList<>();
        for (GeoMath.Point point : segment)
            drawing.add(new GeoPoint(point.lat, point.lon));
        // A dark casing keeps the verified path legible against both day and
        // night raster roads, motorway junctions, and bright crossings.
        junctionHaloLine = new Polyline();
        junctionHaloLine.setColor(Color.rgb(12, 42, 58));
        junctionHaloLine.setWidth(dp(13));
        junctionHaloLine.setPoints(drawing);
        map.getOverlays().add(junctionHaloLine);
        junctionLine = new Polyline();
        junctionLine.setColor(Color.rgb(0, 224, 209));
        junctionLine.setWidth(dp(7));
        junctionLine.setPoints(drawing);
        map.getOverlays().add(junctionLine);
        if (vectorMap != null) vectorMap.setJunctionPreview(segment);
        if (junctionDiagram != null)
            junctionDiagram.setRoute(segment,
                    "ROUNDABOUT".equalsIgnoreCase(instruction.junctionType)
                            || instruction.maneuver.toUpperCase(Locale.UK).contains("ROUNDABOUT"),
                    instruction.exitNumber);
        highlightedJunctionIndex = instructionIndex;
        if (!showingVector()) map.invalidate();
    }

    private void clearJunctionHighlight() {
        if (junctionLine != null) {
            map.getOverlays().remove(junctionLine);
            junctionLine = null;
            map.invalidate();
        }
        if (junctionHaloLine != null) {
            map.getOverlays().remove(junctionHaloLine);
            junctionHaloLine = null;
        }
        if (junctionDiagram != null) junctionDiagram.setRoute(null, false, 0);
        if (verifiedJunctionRoad != null) verifiedJunctionRoad.setVisibility(View.GONE);
        if (highlightedJunctionIndex >= 0 && vectorMap != null)
            vectorMap.setJunctionPreview(null);
        highlightedJunctionIndex = -1;
    }

    private void stopNavigation(boolean arrived) {
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
        arrivalPolicy.reset();
        route = null;
        destination = null;
        offRouteSince = 0;
        gpsLost = false;
        networkLost = false;
        lastRerouteAttempt = 0;
        nextInstruction = "";
        clearPendingRoute();
        clearJunctionHighlight();
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        if (destinationPin != null) {
            map.getOverlays().remove(destinationPin);
            destinationPin = null;
        }
        if (entrancePin != null) {
            map.getOverlays().remove(entrancePin);
            entrancePin = null;
        }
        if (vectorMap != null) vectorMap.setEntrance(null);
        if (arrivalHintText != null && arrivalHintText.getTag() instanceof View)
            ((View) arrivalHintText.getTag()).setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        map.setMapOrientation(0);
        if (vectorMap != null) {
            vectorMap.setRoute(null);
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
        stabilizedAlong = -1;
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
        map.invalidate();
        if (offerParkedCar && !isFinishing()) offerSaveParkedCar();
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

    private void speak(String message) {
        if (speechReady && speech != null && message != null
                && !message.trim().isEmpty()) {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, guidanceVolume);
            speech.speak(message, TextToSpeech.QUEUE_FLUSH, params, "drivemate-guidance");
        }
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
        if (map != null) map.onResume();
        if (vectorMap != null) vectorMap.onResume();
        applyTheme();
        startLocation();
        gpsHandler.removeCallbacks(gpsWatchdog);
        gpsHandler.postDelayed(gpsWatchdog, 5000);
        if (active) startSmoothLoop();
    }

    @Override public void onPause() {
        foreground = false;
        stopSmoothLoop();
        gpsHandler.removeCallbacks(gpsWatchdog);
        gpsHandler.removeCallbacks(deadReckonTick);
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
        locationSubscribed = false;
        if (map != null) map.onPause();
        if (vectorMap != null) vectorMap.onPause();
        super.onPause();
    }

    @Override public void onDestroy() {
        ++requestGeneration;
        network.shutdownNow();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
        }
        if (vectorMap != null) vectorMap.onDestroy();
        if (driveTiles != null) driveTiles.close();
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
