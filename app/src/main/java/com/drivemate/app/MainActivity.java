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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
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
                    say("GPS signal lost. Waiting for location.");
                }
            }
            if (active && !hasNetwork()) {
                if (!networkLost) {
                    networkLost = true;
                    lastNetworkWarning = SystemClock.elapsedRealtime();
                    setStatus("Internet unavailable · following cached on-screen route");
                    say("Internet connection lost. Continue carefully.");
                }
            } else if (active && networkLost) {
                networkLost = false;
                setStatus("Internet restored · route updates available");
            }
            gpsHandler.postDelayed(this, 5000);
        }
    };
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
    private int speechMode = 1; // 0 detailed, 1 normal, 2 minimal
    private float guidanceVolume = 0.85f;
    private boolean northUp;
    private boolean closeZoom;
    private int previousInstructionIndex;
    private double stabilizedAlong = -1;
    private double observedSpeed;
    private long lastNetworkWarning;
    private boolean networkLost;
    private long lastRerouteAttempt;

    private LocationManager locationManager;
    private Location currentLocation;
    private Marker userPin;
    private Marker destinationPin;
    private Polyline routeLine;
    private TilesOverlay trafficTiles;
    private TextToSpeech speech;
    private boolean speechReady;
    private boolean voiceEnabled = true;
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
        voiceEnabled = settings.getBoolean("voice", true);
        metricUnits = settings.getBoolean("metric_units", false);
        speechMode = Math.max(0, Math.min(2, settings.getInt("speech_mode", 1)));
        guidanceVolume = Math.max(0.1f, Math.min(1f, settings.getFloat("speech_volume", 0.85f)));
        northUp = settings.getBoolean("north_up", false);
        closeZoom = settings.getBoolean("close_zoom", false);
        places = new DestinationStore(this);
        trafficEnabled = settings.getBoolean("traffic", false);
        useLocalMap = settings.getBoolean("drivemate_vector_map", true);
        vectorSavedState = savedInstanceState;
        try {
            driveTiles = new DriveTileServer(this);
        } catch (java.io.IOException e) {
            driveTiles = null;
            useLocalMap = false;
        }
        buildScreen();
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
        map.setMinZoomLevel(2.0);
        map.getController().setZoom(15.5);
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
        TextView subtitle = text("UK NAV  ·  v1.4.2", 10, Color.rgb(180, 222, 235), true);
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
        Button find = chip("Find");
        find.setTextColor(DARK);
        find.setTextSize(14);
        find.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CYAN));
        find.setOnClickListener(v -> searchDestination());
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(dp(62), dp(44));
        findParams.leftMargin = dp(5);
        search.addView(find, findParams);
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
        repeatButton = chip("🔊 Repeat");
        repeatButton.setContentDescription("Repeat next spoken turn");
        repeatButton.setVisibility(View.GONE);
        repeatButton.setOnClickListener(v -> {
            if (nextInstruction.isEmpty()) showError("No upcoming direction yet.");
            else say(nextInstruction);
        });
        navActions.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        refreshButton = chip("↻ Refresh");
        refreshButton.setContentDescription("Refresh traffic-aware route");
        refreshButton.setVisibility(View.GONE);
        refreshButton.setOnClickListener(v -> planRoute(false));
        navActions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(45), 1));
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
        homePanel.setPadding(dp(13), dp(15), dp(13), dp(10));
        homePanel.setBackground(rounded(DARK, 23));
        homePanel.setElevation(dp(9));
        LinearLayout homeHeading = new LinearLayout(this);
        TextView heading = text("YOUR DESTINATIONS", 12, Color.rgb(189, 227, 239), true);
        heading.setLetterSpacing(.08f);
        homeHeading.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ready = text("READY FOR THE ROAD", 10, CYAN, true);
        homeHeading.addView(ready);
        homePanel.addView(homeHeading);

        LinearLayout tiles = new LinearLayout(this);
        homeTile = quickTile("⌂", "Home");
        workTile = quickTile("▣", "Work");
        Button savedPlaces = quickTile("★", "Saved Places");
        homeTile.setOnClickListener(v -> openQuickDestination(true));
        homeTile.setOnLongClickListener(v -> { configureQuickDestination(true); return true; });
        workTile.setOnClickListener(v -> openQuickDestination(false));
        workTile.setOnLongClickListener(v -> { configureQuickDestination(false); return true; });
        savedPlaces.setOnClickListener(v -> showSavedDestinations());
        tiles.addView(homeTile, new LinearLayout.LayoutParams(0, dp(74), 1));
        LinearLayout.LayoutParams workPos = new LinearLayout.LayoutParams(0, dp(74), 1);
        workPos.setMargins(dp(6), 0, dp(6), 0);
        tiles.addView(workTile, workPos);
        tiles.addView(savedPlaces, new LinearLayout.LayoutParams(0, dp(74), 1));
        LinearLayout.LayoutParams tilesPos = new LinearLayout.LayoutParams(-1, -2);
        tilesPos.topMargin = dp(10);
        homePanel.addView(tiles, tilesPos);

        TextView recentTitle = text("RECENT DESTINATIONS", 12,
                Color.rgb(189, 227, 239), true);
        recentTitle.setLetterSpacing(.07f);
        LinearLayout.LayoutParams recentHeaderPos = new LinearLayout.LayoutParams(-1, -2);
        recentHeaderPos.topMargin = dp(12);
        homePanel.addView(recentTitle, recentHeaderPos);

        ScrollView recentScroll = new ScrollView(this);
        recentScroll.setFillViewport(false);
        recentScroll.setVerticalScrollBarEnabled(false);
        recentList = new LinearLayout(this);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentScroll.addView(recentList);
        LinearLayout.LayoutParams recentPos = new LinearLayout.LayoutParams(-1, dp(147));
        recentPos.topMargin = dp(3);
        homePanel.addView(recentScroll, recentPos);
        FrameLayout.LayoutParams homePos = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        homePos.setMargins(dp(4), 0, dp(4), 0);
        frame.addView(homePanel, homePos);

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
        speedBadgePos = new FrameLayout.LayoutParams(dp(86), dp(86), Gravity.BOTTOM | Gravity.LEFT);
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
            followButton.setText("◎");
            if (currentLocation != null && active && route != null) {
                lastCameraUpdate = 0;
                updateGuidance(currentLocation);
            } else if (currentLocation != null) {
                centreOnLocation(true);
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
        container.addView(metrics, new LinearLayout.LayoutParams(-1, dp(67)));

        LinearLayout turnCard = new LinearLayout(this);
        turnCard.setOrientation(LinearLayout.VERTICAL);
        turnCard.setPadding(dp(12), dp(9), dp(12), dp(10));
        turnCard.setBackground(rounded(Color.rgb(14, 47, 66), 14));
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
        return container;
    }

    private TextView addDrivingMetric(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        TextView caption = text(label, 10, Color.rgb(189, 220, 230), true);
        caption.setGravity(Gravity.CENTER);
        cell.addView(caption);
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

    private Button quickTile(String symbol, String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(symbol + "\n" + label);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        GradientDrawable background = rounded(Color.rgb(25, 69, 85), 12);
        background.setStroke(dp(1), CYAN);
        button.setBackground(background);
        return button;
    }

    private void setHomeVisible(boolean show) {
        homeMode = show;
        if (homePanel == null || navigationPanel == null) return;
        homePanel.setVisibility(show && !keyboardOpen ? View.VISIBLE : View.GONE);
        navigationPanel.setVisibility(!show && !keyboardOpen ? View.VISIBLE : View.GONE);
        if (speedBadgeView != null) {
            speedBadgeView.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
        }
        if (followButton != null) {
            followButton.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
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
        recentList.removeAllViews();
        List<TomTomApi.Destination> recents = places.recent();
        if (recents.isEmpty()) {
            TextView empty = text("No recent journeys yet. Find a destination to begin.",
                    12, Color.rgb(202, 219, 224), false);
            empty.setPadding(dp(2), dp(18), dp(2), dp(8));
            recentList.addView(empty);
        } else {
            for (TomTomApi.Destination item : recents) {
                TextView row = text("↺    " + item.title, 14, Color.WHITE, false);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setPadding(dp(7), dp(8), dp(6), dp(8));
                row.setOnClickListener(v -> selectDestination(item));
                recentList.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));
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
                voiceEnabled ? "Mute voice guidance" : "Enable voice guidance",
                "Voice instructions · " + speechModeName(),
                "Voice volume · " + Math.round(guidanceVolume * 100) + "%",
                trafficEnabled ? "Hide live traffic overlay" : "Show live traffic overlay",
                metricUnits ? "Use UK miles, yards and mph" : "Use metric kilometres and km/h",
                northUp ? "Map orientation · north up" : "Map orientation · direction up",
                closeZoom ? "Zoom · junction detail" : "Zoom · automatic",
                autoFallback && useLocalMap ? "Retry DriveMate map (TomTom fallback active)"
                        : useLocalMap ? "Map · DriveMate local vector"
                        : "Map · TomTom online raster",
                "Nearby map cache & Wi-Fi prefetch",
                "Set or change Home",
                "Set or change Work",
                "Clear recent destinations",
                "About DriveMate"
        };
        new AlertDialog.Builder(this).setTitle("DriveMate settings")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: showKeyDialog(); break;
                        case 1:
                            voiceEnabled = !voiceEnabled;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("voice", voiceEnabled).apply();
                            if (!voiceEnabled && speech != null) speech.stop();
                            else say("Voice guidance enabled.");
                            break;
                        case 2: selectSpeechMode(); break;
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
                        case 6:
                            northUp = !northUp;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("north_up", northUp).apply();
                            lastCameraUpdate = 0;
                            if (northUp) map.setMapOrientation(0);
                            if (active && currentLocation != null) updateGuidance(currentLocation);
                            break;
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
                            new AlertDialog.Builder(this).setTitle("DriveMate v1.4.2")
                                    .setMessage("DriveMate Map © OpenStreetMap contributors (ODbL 1.0). "
                                            + "Cached vector tiles are map display only. "
                                            + "Online TomTom routing, traffic and spoken directions. " +
                                            "Road-matching and route camera are experimental. " +
                                            "Keep DriveMate open; never use it as your sole navigator " +
                                            "before testing directions on a real device.")
                                    .setPositiveButton("OK", null).show();
                            break;
                        default: break;
                    }
                }).setNegativeButton("Close", null).show();
    }

    private String speechModeName() {
        return new String[]{"Detailed", "Normal", "Minimal"}[speechMode];
    }

    private void selectSpeechMode() {
        new AlertDialog.Builder(this).setTitle("Voice instruction frequency")
                .setSingleChoiceItems(new String[]{"Detailed · early and close prompts",
                        "Normal · approach and turn", "Minimal · turn only"}, speechMode,
                        (dialog, which) -> {
                            speechMode = which;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putInt("speech_mode", which).apply();
                            dialog.dismiss();
                        }).setNegativeButton("Cancel", null).show();
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
        map.setTileSource(tileSource("DriveMate-TomTom-Map",
                "map/1/tile/basic/main/"));
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
        else setStatus("Precise location permission is required for driving GPS guidance.");
    }

    private void startLocation() {
        if (locationManager == null
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
        locationManager.removeUpdates(this);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        1000L, 1f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        2500L, 5f, this);
            }
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < 60000) {
                onLocationChanged(last);
            }
        } catch (SecurityException ignored) {
            setStatus("Location permission denied.");
        }
    }

    @Override public void onLocationChanged(Location fix) {
        if (fix == null || (fix.hasAccuracy() && fix.getAccuracy() > 100)
                || System.currentTimeMillis() - fix.getTime() > 15000) return;
        if (gpsLost) {
            gpsLost = false;
            if (active) {
                setStatus("GPS signal restored · guidance resumed");
                say("GPS signal restored.");
            }
        }
        if (currentLocation != null && fix.getTime() < currentLocation.getTime()
                && currentLocation.getTime() - fix.getTime() > 3000) return;
        currentLocation = fix;
        GeoMath.Point smoothed = vehicleFilter.apply(gpsPoint(fix), fix.getTime(),
                fix.hasAccuracy() ? fix.getAccuracy() : 99f,
                fix.hasSpeed() ? fix.getSpeed() : 0f);
        GeoPoint point = new GeoPoint(smoothed.lat, smoothed.lon);
        if (userPin == null) {
            userPin = new Marker(map);
            userPin.setTitle("Your live GPS position");
            userPin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userPin.setIcon(makeCarIcon());
            map.getOverlays().add(userPin);
        }
        userPin.setPosition(point);
        if (vectorMap != null && useLocalMap) {
            vectorMap.setVehicle(smoothed);
            if (!autoFallback) vectorMap.prefetch(gpsPoint(fix));
        }
        if (fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() > 1.5f)
            userPin.setRotation(fix.getBearing());
        float filteredSpeed = fix.hasSpeed() && fix.getSpeed() >= 0.9f
                ? fix.getSpeed() : 0f;
        speedText.setText(NavigationUnits.displaySpeed(filteredSpeed, metricUnits));
        if (!active && !routing && destination == null && fix.hasAccuracy()) {
            setStatus("GPS ready · accuracy ±" + Math.round(fix.getAccuracy()) + " m");
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
                    + (routing ? "  ·  updating traffic route…" : ""));
        }
        if (active && route != null && !routing) updateGuidance(fix);
        if (!showingVector()) map.postInvalidateOnAnimation();
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
    private void updateDrivingCamera(Location fix, GeoMath.Progress progress,
                                     double nextJunctionOffsetMetres) {
        if (!active || !followLocation || route == null || gpsLost
                || (fix.hasAccuracy() && fix.getAccuracy() > 55)
                || progress.offRouteMetres > 110) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // GPS arrives about once a second. Do not queue another animated pan:
        // by the time osmdroid finishes it, the vehicle has already moved.
        if (lastCameraUpdate > 0 && now - lastCameraUpdate < 600) return;
        if (map.getHeight() < dp(350)) return;

        // Use measured overlays rather than the old 145dp search/header
        // assumption: the driving ETA and turn card now occupy the top.
        int headerHeight = topMapOverlay != null && topMapOverlay.getHeight() > 0
                ? topMapOverlay.getHeight() + dp(5) : dp(260);
        int bottomPanelHeight = navigationPanel.getHeight() > 0
                ? navigationPanel.getHeight() + dp(18) : dp(95);
        NavigationCamera.Frame camera = NavigationCamera.calculate(
                route.points, gpsPoint(fix), progress.alongMetres,
                nextJunctionOffsetMetres, fix.hasSpeed() ? fix.getSpeed() : 0,
                map.getHeight(), headerHeight, bottomPanelHeight);

        boolean vector = showingVector();
        double targetZoom = Math.min(19.0, camera.zoom + (closeZoom ? 0.45 : 0));
        double currentZoom = vector ? vectorMap.currentZoom() : map.getZoomLevelDouble();
        double wantedOrientation = northUp ? 0
                : (vector ? camera.roadBearingDegrees : -camera.roadBearingDegrees);
        double currentOrientation = vector ? vectorMap.currentBearing() : map.getMapOrientation();
        double headingChange = MapFollowPolicy.shortestHeadingDifference(
                wantedOrientation, currentOrientation);
        double centerMovement = lastCameraCenter == null ? Double.POSITIVE_INFINITY
                : GeoMath.distance(lastCameraCenter, camera.center);
        float speed = fix.hasSpeed() ? fix.getSpeed() : 0f;
        if (!MapFollowPolicy.shouldMove(lastCameraUpdate, now, centerMovement,
                speed, targetZoom - currentZoom, headingChange)) return;

        double boundedZoom = currentZoom + Math.max(-0.5,
                Math.min(0.5, targetZoom - currentZoom));
        if (vector) {
            double bearing = northUp ? 0 : currentOrientation +
                    (speed >= 1.8f ? Math.max(-20, Math.min(20, headingChange)) : 0);
            // Moving the hidden raster map too would request the same roads
            // twice and undo much of the GPU renderer's responsiveness gain.
            vectorMap.setCamera(camera.center, boundedZoom, bearing);
        } else {
            if (Math.abs(targetZoom - currentZoom) > 0.30)
                map.getController().setZoom(boundedZoom);
            if (northUp) {
                if (Math.abs(headingChange) >= 1) map.setMapOrientation(0);
            } else if (speed >= 1.8f && Math.abs(headingChange) >= 8) {
                float adjusted = (float) (currentOrientation
                        + Math.max(-20, Math.min(20, headingChange)));
                map.setMapOrientation(adjusted);
            }
            map.getController().setCenter(new GeoPoint(camera.center.lat, camera.center.lon));
            if (useLocalMap && !autoFallback && vectorMap != null)
                vectorMap.setCamera(camera.center, boundedZoom,
                        northUp ? 0 : camera.roadBearingDegrees);
        }
        lastCameraCenter = camera.center;
        lastCameraUpdate = now;
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
        keyboardOpen = false;
        destinationInput.clearFocus();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(destinationInput.getWindowToken(), 0);
        setHomeVisible(false);
        followLocation = true;
        followButton.setText("◎");
        topTurnTitle.setText("NEXT TURN · " + destination.title);
        maneuverIcon.setVisibility(View.VISIBLE);
        nextTurnText.setVisibility(View.VISIBLE);
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
    }

    private void selectDestination(TomTomApi.Destination selected) {
        if (currentLocation == null
                || System.currentTimeMillis() - currentLocation.getTime() > 60000) {
            showError("Waiting for a fresh GPS fix. Enable device location, then try again.");
            return;
        }
        if (route != null || active) stopNavigation(false);
        setHomeVisible(false);
        destination = selected;
        places.addRecent(selected);
        followLocation = false;
        followButton.setText("◎");
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
                    route = calculated;
                    offRouteSince = 0;
                    previousInstructionIndex = 0;
                    stabilizedAlong = -1;
                    observedSpeed = 0;
                    spokenDistanceIndex = -1;
                    spokenCloseIndex = -1;
                    spokenNowIndex = -1;
                    showRoute(calculated, firstRoute);
                    if (firstRoute) {
                        showPreview(calculated);
                    } else {
                        setStatus("Route updated · latest TomTom traffic included");
                        say("Updated route ready.");
                        if (currentLocation != null) updateGuidance(currentLocation);
                    }
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

    private void showRoute(TomTomApi.Route calculated, boolean preview) {
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
        if (distanceToDestination < 30 && accuracy < 40 && leftMetres < 120) {
            stopNavigation(true);
            return;
        }

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
        // These three values live at the TOP of the driving map.
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
            topInstructionText.setText("Continue along the highlighted route");
            topNextTurnText.setText("Follow road signs");
            return;
        }
        previousInstructionIndex = next;
        TomTomApi.Instruction step = route.instructions.get(next);
        double untilTurn = Math.max(0, step.routeOffsetMetres - along);
        String direction = GuidanceLogic.actionLine(step.message, step.junctionType,
                step.exitNumber);
        String glyph = GuidanceLogic.symbol(step.maneuver, step.junctionType,
                step.turnAngleDegrees);
        maneuverIcon.update(glyph, step.exitNumber);
        maneuverIcon.setVisibility(View.VISIBLE);
        topTurnArrow.update(glyph, step.exitNumber);
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
            topNextTurnText.setText(followingTurn);
            nextTurnText.setVisibility(View.GONE);
        } else {
            nextTurnText.setText("Continue towards your destination");
            topNextTurnText.setText("Continue towards your destination");
            nextTurnText.setVisibility(View.GONE);
        }

        nextInstruction = untilTurn <= 30 ? direction
                : "In " + NavigationUnits.spokenDistance(untilTurn, metricUnits)
                + ", " + direction;
        updateDrivingCamera(fix, projected, step.routeOffsetMetres);
        if (untilTurn <= 65 && spokenNowIndex != next) {
            spokenNowIndex = next;
            if (GuidanceLogic.announce(speechMode, untilTurn, 2)) say(direction);
        } else if (untilTurn > 65 && untilTurn <= 250 && spokenCloseIndex != next) {
            spokenCloseIndex = next;
            if (GuidanceLogic.announce(speechMode, untilTurn, 1)) say(nextInstruction);
        } else if (untilTurn > 250 && untilTurn <= 1000 && spokenDistanceIndex != next) {
            spokenDistanceIndex = next;
            if (GuidanceLogic.announce(speechMode, untilTurn, 0)) say(nextInstruction);
        }
    }

    private void stopNavigation(boolean arrived) {
        ++requestGeneration;
        routing = false;
        active = false;
        route = null;
        destination = null;
        offRouteSince = 0;
        gpsLost = false;
        networkLost = false;
        lastRerouteAttempt = 0;
        nextInstruction = "";
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        if (destinationPin != null) {
            map.getOverlays().remove(destinationPin);
            destinationPin = null;
        }
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
        followButton.setText("◎");
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
        if (voiceEnabled && speechReady && speech != null && message != null
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

    @Override public void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        if (vectorMap != null) vectorMap.onResume();
        startLocation();
        gpsHandler.removeCallbacks(gpsWatchdog);
        gpsHandler.postDelayed(gpsWatchdog, 5000);
    }

    @Override public void onPause() {
        gpsHandler.removeCallbacks(gpsWatchdog);
        if (locationManager != null) locationManager.removeUpdates(this);
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
