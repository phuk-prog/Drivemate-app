package com.drivemate.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
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
    private static final int DARK = Color.rgb(21, 30, 45);
    private static final long ROUTE_REFRESH_MS = 300_000L;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private MapView map;
    private EditText destinationInput;
    private Button trafficButton;
    private Button voiceButton;
    private Button followButton;
    private TextView banner;
    private TextView instructionText;
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
    private int requestGeneration;
    private int spokenDistanceIndex = -1;
    private int spokenCloseIndex = -1;
    private int spokenNowIndex = -1;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        SharedPreferences settings = getSharedPreferences(PREFS, MODE_PRIVATE);
        apiKey = settings.getString("tomtom_key", "");
        voiceEnabled = settings.getBoolean("voice", true);
        metricUnits = settings.getBoolean("metric_units", false);
        places = new DestinationStore(this);
        trafficEnabled = settings.getBoolean("traffic", true);
        buildScreen();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        speech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && speech != null) {
                int language = speech.setLanguage(Locale.UK);
                speechReady = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        if (apiKey.isEmpty()) map.post(this::showKeyDialog);
        else configureTiles();
        ensureLocationPermission();
    }

    private void buildScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(235, 241, 246));
        map = new MapView(this);
        map.setMultiTouchControls(true);
        map.setMinZoomLevel(2.0);
        map.getController().setZoom(15.5);
        map.setVisibility(apiKey.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        frame.addView(map, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(8), dp(6), dp(8), dp(6));
        search.setBackground(rounded(Color.WHITE, 16));
        search.setElevation(dp(5));
        destinationInput = new EditText(this);
        destinationInput.setSingleLine(true);
        destinationInput.setTextSize(16);
        destinationInput.setHint("Where to? Address or postcode");
        destinationInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        destinationInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        destinationInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchDestination();
                return true;
            }
            return false;
        });
        search.addView(destinationInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button saved = chip("★");
        saved.setContentDescription("Saved and recent destinations");
        saved.setOnClickListener(v -> showSavedDestinations());
        search.addView(saved, new LinearLayout.LayoutParams(dp(45), dp(48)));
        Button find = chip("GO");
        find.setOnClickListener(v -> searchDestination());
        search.addView(find, new LinearLayout.LayoutParams(dp(70), dp(48)));
        FrameLayout.LayoutParams searchPos = new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP);
        searchPos.setMargins(dp(10), dp(12), dp(10), 0);
        frame.addView(search, searchPos);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(dp(5), dp(3), dp(5), dp(3));
        tools.setBackground(rounded(Color.WHITE, 14));
        tools.setElevation(dp(4));
        Button key = chip("API key");
        key.setOnClickListener(v -> showKeyDialog());
        tools.addView(key, new LinearLayout.LayoutParams(0, dp(44), 1));
        followButton = chip("GPS ✓");
        followButton.setOnClickListener(v -> {
            followLocation = !followLocation;
            followButton.setText(followLocation ? "GPS ✓" : "GPS ○");
            if (followLocation && currentLocation != null) centreOnLocation(true);
        });
        tools.addView(followButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        trafficButton = chip(trafficEnabled ? "Traffic ✓" : "Traffic ○");
        trafficButton.setOnClickListener(v -> {
            trafficEnabled = !trafficEnabled;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("traffic", trafficEnabled).apply();
            trafficButton.setText(trafficEnabled ? "Traffic ✓" : "Traffic ○");
            configureTrafficTiles();
        });
        tools.addView(trafficButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        voiceButton = chip(voiceEnabled ? "Voice ✓" : "Voice ○");
        voiceButton.setOnClickListener(v -> {
            voiceEnabled = !voiceEnabled;
            if (!voiceEnabled && speech != null) speech.stop();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("voice", voiceEnabled).apply();
            voiceButton.setText(voiceEnabled ? "Voice ✓" : "Voice ○");
            if (voiceEnabled) say("Voice guidance on.");
        });
        tools.addView(voiceButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        unitsButton = chip(metricUnits ? "km/m" : "mi/yd");
        unitsButton.setOnClickListener(v -> {
            metricUnits = !metricUnits;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("metric_units", metricUnits).apply();
            unitsButton.setText(metricUnits ? "km/m" : "mi/yd");
            if (currentLocation != null) {
                speedText.setText(NavigationUnits.displaySpeed(currentLocation.getSpeed(), metricUnits));
                if (active) updateGuidance(currentLocation);
            }
            if (route != null && !active) showPreview(route);
        });
        tools.addView(unitsButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        FrameLayout.LayoutParams toolsPos = new FrameLayout.LayoutParams(-1, dp(50), Gravity.TOP);
        toolsPos.setMargins(dp(10), dp(82), dp(10), 0);
        frame.addView(tools, toolsPos);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(15), dp(13), dp(15), dp(13));
        bottom.setBackground(rounded(DARK, 18));
        bottom.setElevation(dp(8));
        banner = text("Ready to navigate", 13, Color.rgb(164, 214, 255), false);
        bottom.addView(banner);
        instructionText = text("Set your TomTom key, then search for a destination.", 20,
                Color.WHITE, true);
        LinearLayout.LayoutParams directionPos = new LinearLayout.LayoutParams(-1, -2);
        directionPos.topMargin = dp(7);
        bottom.addView(instructionText, directionPos);
        routeSummary = text("", 13, Color.rgb(196, 228, 255), false);
        routeSummary.setVisibility(View.GONE);
        bottom.addView(routeSummary);
        LinearLayout details = new LinearLayout(this);
        details.setGravity(Gravity.CENTER_VERTICAL);
        distanceText = text("Distance: —", 13, Color.WHITE, false);
        details.addView(distanceText, new LinearLayout.LayoutParams(0, -2, 1));
        etaText = text("ETA: —", 13, Color.WHITE, false);
        details.addView(etaText, new LinearLayout.LayoutParams(0, -2, 1));
        speedText = text(metricUnits ? "0 km/h" : "0 mph", 17, Color.WHITE, true);
        details.addView(speedText);
        LinearLayout.LayoutParams detailPos = new LinearLayout.LayoutParams(-1, -2);
        detailPos.topMargin = dp(12);
        bottom.addView(details, detailPos);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        startButton = chip("▶ Start navigation");
        startButton.setVisibility(View.GONE);
        startButton.setOnClickListener(v -> startNavigation());
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(45), 2));
        saveButton = chip("★ Save place");
        saveButton.setVisibility(View.GONE);
        saveButton.setOnClickListener(v -> saveCurrentDestination());
        actions.addView(saveButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        repeatButton = chip("Repeat voice");
        repeatButton.setVisibility(View.GONE);
        repeatButton.setOnClickListener(v -> {
            if (nextInstruction.isEmpty()) showError("No upcoming direction yet.");
            else say(nextInstruction);
        });
        actions.addView(repeatButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        refreshButton = chip("Refresh route");
        refreshButton.setVisibility(View.GONE);
        refreshButton.setOnClickListener(v -> planRoute(false));
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        LinearLayout.LayoutParams actionsPos = new LinearLayout.LayoutParams(-1, -2);
        actionsPos.topMargin = dp(10);
        bottom.addView(actions, actionsPos);
        stopButton = chip("End navigation");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopNavigation(false));
        LinearLayout.LayoutParams stopPos = new LinearLayout.LayoutParams(-1, dp(43));
        stopPos.topMargin = dp(10);
        bottom.addView(stopButton, stopPos);
        FrameLayout.LayoutParams panelPos = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        panelPos.setMargins(dp(10), 0, dp(10), dp(12));
        frame.addView(bottom, panelPos);
        setContentView(frame);
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
        map.setVisibility(View.VISIBLE);
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
        if (fix == null || (fix.hasAccuracy() && fix.getAccuracy() > 100)) return;
        if (currentLocation != null && fix.getTime() < currentLocation.getTime()
                && currentLocation.getTime() - fix.getTime() > 3000) return;
        currentLocation = fix;
        GeoPoint point = new GeoPoint(fix.getLatitude(), fix.getLongitude());
        if (userPin == null) {
            userPin = new Marker(map);
            userPin.setTitle("Your live GPS position");
            userPin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            userPin.setIcon(makeCarIcon());
            map.getOverlays().add(userPin);
        }
        userPin.setPosition(point);
        if (fix.hasBearing()) userPin.setRotation(fix.getBearing());
        speedText.setText(NavigationUnits.displaySpeed(fix.getSpeed(), metricUnits));
        if (!active && !routing && destination == null && fix.hasAccuracy()) {
            setStatus("GPS ready · accuracy ±" + Math.round(fix.getAccuracy()) + " m");
        }
        if (followLocation && SystemClock.elapsedRealtime() - lastCameraUpdate > 1400) {
            centreOnLocation(false);
            lastCameraUpdate = SystemClock.elapsedRealtime();
        }
        if (active && route != null && !routing) updateGuidance(fix);
        map.invalidate();
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
        if (forceZoom || map.getZoomLevelDouble() < 14.0)
            map.getController().setZoom(16.5);
        map.getController().animateTo(
                new GeoPoint(currentLocation.getLatitude(), currentLocation.getLongitude()));
    }

    private void searchDestination() {
        if (apiKey.isEmpty()) {
            showKeyDialog();
            return;
        }
        String query = destinationInput.getText().toString().trim();
        if (query.length() < 2) {
            destinationInput.setError("Enter an address or postcode");
            return;
        }
        destinationInput.clearFocus();
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
        routeSummary.setText(previewDetails(calculated));
        routeSummary.setVisibility(View.VISIBLE);
        instructionText.setText("Review your route before driving.");
        distanceText.setText("Distance: " + NavigationUnits.displayDistance(
                calculated.lengthMetres, metricUnits));
        etaText.setText("Drive: " + NavigationUnits.duration(calculated.timeSeconds));
        startButton.setVisibility(View.VISIBLE);
        saveButton.setVisibility(View.VISIBLE);
        saveButton.setText(places.isFavorite(destination) ? "★ Saved" : "★ Save place");
        repeatButton.setVisibility(View.GONE);
        refreshButton.setVisibility(View.GONE);
        stopButton.setText("Cancel route");
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
        followLocation = true;
        followButton.setText("GPS ✓");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        routeSummary.setVisibility(View.GONE);
        startButton.setVisibility(View.GONE);
        saveButton.setVisibility(View.GONE);
        repeatButton.setVisibility(View.VISIBLE);
        refreshButton.setVisibility(View.VISIBLE);
        stopButton.setText("End navigation");
        setStatus("Navigating to " + destination.title);
        say("Navigation started. Follow the directions.");
        centreOnLocation(true);
        updateGuidance(currentLocation);
    }

    private void selectDestination(TomTomApi.Destination selected) {
        if (currentLocation == null
                || System.currentTimeMillis() - currentLocation.getTime() > 60000) {
            showError("Waiting for a fresh GPS fix. Enable device location, then try again.");
            return;
        }
        if (route != null || active) stopNavigation(false);
        destination = selected;
        places.addRecent(selected);
        followLocation = false;
        followButton.setText("GPS ○");
        destinationInput.setText(selected.title);
        if (destinationPin == null) {
            destinationPin = new Marker(map);
            destinationPin.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            map.getOverlays().add(destinationPin);
        }
        destinationPin.setPosition(new GeoPoint(selected.point.lat, selected.point.lon));
        destinationPin.setTitle(selected.toString());
        planRoute(true);
    }

    private GeoMath.Point gpsPoint(Location location) {
        return new GeoMath.Point(location.getLatitude(), location.getLongitude());
    }

    private void planRoute(boolean firstRoute) {
        if (routing || destination == null || currentLocation == null || apiKey.isEmpty()) return;
        routing = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        final int plannedGeneration = ++requestGeneration;
        final GeoMath.Point start = gpsPoint(currentLocation);
        final GeoMath.Point end = destination.point;
        setStatus(firstRoute ? "Planning traffic-aware route…" : "Updating route for traffic / GPS…");
        network.execute(() -> {
            try {
                TomTomApi.Route calculated = TomTomApi.route(apiKey, start, end);
                runOnUiThread(() -> {
                    if (plannedGeneration != requestGeneration || isFinishing()) return;
                    routing = false;
                    route = calculated;
                    offRouteSince = 0;
                    spokenDistanceIndex = -1;
                    spokenCloseIndex = -1;
                    spokenNowIndex = -1;
                    showRoute(calculated, firstRoute);
                    if (firstRoute) {
                        showPreview(calculated);
                    } else {
                        setStatus("Route updated with latest traffic");
                        if (currentLocation != null) updateGuidance(currentLocation);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (plannedGeneration != requestGeneration) return;
                    routing = false;
                    showError(error.getMessage());
                    if (route == null) stopButton.setVisibility(View.GONE);
                });
            }
        });
    }

    private void showRoute(TomTomApi.Route calculated, boolean preview) {
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = new Polyline();
        List<GeoPoint> geometry = new ArrayList<>(calculated.points.size());
        for (GeoMath.Point point : calculated.points)
            geometry.add(new GeoPoint(point.lat, point.lon));
        routeLine.setPoints(geometry);
        routeLine.getOutlinePaint().setColor(BLUE);
        routeLine.getOutlinePaint().setStrokeWidth(dp(8));
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
        if (preview) {
            double north = -90, south = 90, east = -180, west = 180;
            for (GeoMath.Point point : calculated.points) {
                north = Math.max(north, point.lat);
                south = Math.min(south, point.lat);
                east = Math.max(east, point.lon);
                west = Math.min(west, point.lon);
            }
            final BoundingBox box = new BoundingBox(north, east, south, west);
            map.post(() -> map.zoomToBoundingBox(box, true, dp(80)));
        } else if (followLocation) centreOnLocation(true);
    }

    private void updateGuidance(Location fix) {
        if (!active || route == null || destination == null) return;
        GeoMath.Point current = gpsPoint(fix);
        GeoMath.Progress progress = GeoMath.project(route.points, current);
        if (fix.hasAccuracy() && fix.getAccuracy() < 35 && progress.offRouteMetres > 70) {
            if (offRouteSince == 0) offRouteSince = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - offRouteSince > 9000
                    && SystemClock.elapsedRealtime() - lastRouteRequest > 30000) {
                offRouteSince = 0;
                say("Recalculating route.");
                planRoute(false);
                return;
            }
        } else offRouteSince = 0;

        double distanceToDestination = GeoMath.distance(current, destination.point);
        if (distanceToDestination < 30 && (!fix.hasAccuracy() || fix.getAccuracy() < 40)) {
            stopNavigation(true);
            return;
        }
        if (SystemClock.elapsedRealtime() - lastRouteRequest > ROUTE_REFRESH_MS
                && route.lengthMetres - progress.alongMetres > 1500) {
            planRoute(false);
            return;
        }
        double leftMetres = Math.max(0, route.lengthMetres - progress.alongMetres);
        distanceText.setText("Left: " + humanDistance(leftMetres));
        long seconds = Math.max(0, Math.round(route.timeSeconds
                * (leftMetres / Math.max(1, route.lengthMetres))));
        etaText.setText("ETA: " + DateFormat.getTimeInstance(DateFormat.SHORT, Locale.UK)
                .format(new Date(System.currentTimeMillis() + seconds * 1000)));
        int next = route.instructions.size() - 1;
        for (int i = 0; i < route.instructions.size(); i++) {
            if (route.instructions.get(i).routeOffsetMetres >= progress.alongMetres + 8) {
                next = i;
                break;
            }
        }
        TomTomApi.Instruction step = route.instructions.get(next);
        double untilTurn = Math.max(0, step.routeOffsetMetres - progress.alongMetres);
        instructionText.setText(humanDistance(untilTurn) + " · " + step.message);
        if (untilTurn <= 65 && spokenNowIndex != next) {
            spokenNowIndex = next;
            say(step.message);
        } else if (untilTurn <= 250 && untilTurn > 65 && spokenCloseIndex != next) {
            spokenCloseIndex = next;
            say("In " + spokenDistance(untilTurn) + ", " + step.message);
        } else if (untilTurn <= 1000 && untilTurn > 250 && spokenDistanceIndex != next) {
            spokenDistanceIndex = next;
            say("In " + spokenDistance(untilTurn) + ", " + step.message);
        }
    }

    private String humanDistance(double metres) {
        if (metres >= 1000) return String.format(Locale.UK, "%.1f km", metres / 1000.0);
        return Math.round(metres / 10.0) * 10 + " m";
    }

    private String spokenDistance(double metres) {
        if (metres >= 1000) return String.format(Locale.UK, "%.1f kilometres", metres / 1000);
        return Math.max(10, Math.round(metres / 10f) * 10) + " metres";
    }

    private void stopNavigation(boolean arrived) {
        ++requestGeneration;
        routing = false;
        active = false;
        route = null;
        destination = null;
        offRouteSince = 0;
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        if (destinationPin != null) {
            map.getOverlays().remove(destinationPin);
            destinationPin = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stopButton.setVisibility(View.GONE);
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
        if (voiceEnabled && speechReady && speech != null) {
            speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "drivemate-guidance");
        }
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider))
            setStatus("GPS disabled. Enable location services for accurate guidance.");
    }

    @Override public void onProviderEnabled(String provider) { /* GPS fixes follow. */ }

    @Override @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        startLocation();
    }

    @Override public void onPause() {
        if (locationManager != null) locationManager.removeUpdates(this);
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override public void onDestroy() {
        ++requestGeneration;
        network.shutdownNow();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
        }
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
