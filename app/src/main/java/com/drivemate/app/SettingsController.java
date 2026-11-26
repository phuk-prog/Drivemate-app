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


/** Extracted from MainActivity (behaviour unchanged). */
final class SettingsController {
    final MainActivity a;

    SettingsController(MainActivity activity) { this.a = activity; }

    void showSettings() {
        String[] options = {
                "TomTom API key",
                a.voiceMode == DrivingVoicePolicy.SILENT ? "Enable voice guidance" : "Mute voice guidance",
                "Voice guidance · " + voiceModeName(),
                "Voice volume · " + Math.round(a.guidanceVolume * 100) + "%",
                a.trafficEnabled ? "Hide provider-matched live traffic" : "Show provider-matched live traffic",
                a.metricUnits ? "Use UK miles, yards and mph" : "Use metric kilometres and km/h",
                "Camera · " + cameraModeName(),
                a.closeZoom ? "Junction detail zoom · On" : "Junction detail zoom · Off",
                a.matchingTomTomMap() ? "Map · TomTom route-matched provider (journey)" : a.autoFallback && a.useLocalMap ? "Retry DriveMate map (TomTom fallback active)"
                        : a.useLocalMap ? "Map · "+(a.vectorMap!=null?a.vectorMap.geometryDescription():"DriveMate local vector")
                        : "Map · TomTom online raster",
                "Nearby map cache & Wi-Fi prefetch",
                "Set or change Home",
                "Set or change Work",
                "Clear recent destinations",
                "About DriveMate",
                "Map colours · " + themeModeName(),
                "Verified hazard alerts (when available) · " + (a.hazardWarningEnabled ? "On" : "Off"),
                "Verified speed alerts (when available) · " + (a.speedWarningEnabled ? "On" : "Off"),
                "Faster-route handling · " + (a.rerouteMode == 0 ? "Automatic" : "Ask first"),
                "Overspeed alert above mapped limit · " + a.speedToleranceMph + " mph",
                "Full-detail DriveMate · Stockport preview",
                "Save where I parked",
                a.places != null && a.places.parkedCar() != null
                        ? "Find my parked car" : "Find my parked car · none saved",
                "Share diagnostics (no location included)",
                "3D buildings · " + (a.buildings3d ? "On" : "Off"),
                "DriveMate-first routing and search · " + (a.ownRouting() ? "Experimental" : "Off"),
                "Journey map review · " + a.mapReview.snapshot().size() + " findings",
                "Independent map survey drafts",
                "Offline routing · UK experimental preview",
                "Offline visual map · download full UK",
                "DriveMate original stack · Wells pilot",
                "Free driving · "+(a.freeDriving?"On":"Off"),
                "Advance speed-limit voice · "+(a.advanceSpeedVoice?"On":"Off")
        };
        new AlertDialog.Builder(a).setTitle("DriveMate settings")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: showKeyDialog(); break;
                        case 1:
                            setVoiceMode(a.voiceMode == DrivingVoicePolicy.SILENT
                                    ? DrivingVoicePolicy.NORMAL : DrivingVoicePolicy.SILENT);
                            break;
                        case 2: selectVoiceMode(); break;
                        case 3: selectSpeechVolume(); break;
                        case 4:
                            a.trafficEnabled = !a.trafficEnabled;
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("traffic", a.trafficEnabled).apply();
                            a.configureTrafficTiles();
                            break;
                        case 5:
                            a.metricUnits = !a.metricUnits;
                            if(a.vectorMap!=null && a.route!=null)a.vectorMap.setRouteEvents(a.route.speedSections,a.route.trafficSections,a.metricUnits,a.route.fetchedAtMs);
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("metric_units", a.metricUnits).apply();
                            if (a.currentLocation != null) {
                                a.speedText.setText(NavigationUnits.displaySpeed(
                                        a.currentLocation.getSpeed(), a.metricUnits));
                                if (a.active) a.updateGuidance(a.currentLocation);
                            }
                            if (a.route != null && !a.active) a.showPreview(a.route);
                            break;
                        case 6: selectCameraMode(); break;
                        case 7:
                            a.closeZoom = !a.closeZoom;
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("close_zoom", a.closeZoom).apply();
                            a.lastCameraUpdate = 0;
                            if (a.active && a.currentLocation != null) a.updateGuidance(a.currentLocation);
                            break;
                        case 8:
                            if (a.vectorMap == null) {
                                Toast.makeText(a,
                                        "DriveMate local map is unavailable on this device",
                                        Toast.LENGTH_LONG).show();
                                break;
                            }
                            if (a.autoFallback && a.useLocalMap) {
                                // A single tap retries DriveMate Map rather than
                                // silently selecting the same TomTom fallback.
                                a.autoFallback = false;
                                a.vectorRoadsReady = false;
                                a.setStatus("Retrying DriveMate streets…");
                            } else {
                                a.useLocalMap = !a.useLocalMap;
                                a.autoFallback = false;
                                a.vectorRoadsReady = a.vectorMap.hasRenderedRoads();
                            }
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("drivemate_vector_map", a.useLocalMap).apply();
                            a.updateMapVisibility();
                            if (a.useLocalMap && a.currentLocation != null) {
                                a.vectorMap.setCamera(a.gpsPoint(a.currentLocation),
                                        a.map.getZoomLevelDouble(), 0);
                                a.vectorMap.prefetch(a.gpsPoint(a.currentLocation));
                            }
                            break;
                        case 9:
                            String stats = a.vectorMap == null ? "Local vector map unavailable"
                                    : a.vectorMap.cacheStats();
                            new AlertDialog.Builder(a).setTitle("DriveMate nearby map cache")
                                    .setMessage(stats + "\n\nMaps are cached privately on your phone (up to 180 MB of street tiles). "
                                            + "An approximately 20–30 km area is prefetched on unmetered Wi-Fi "
                                            + "around a GPS fix. Online use fetches missing tiles individually. "
                                            + "TomTom routing still needs internet. "
                                            + "Map data © OpenStreetMap contributors, ODbL 1.0.")
                                    .setPositiveButton("Prefetch nearby", (d, button) -> {
                                        if (a.vectorMap != null && a.currentLocation != null)
                                            a.vectorMap.prefetch(a.gpsPoint(a.currentLocation));
                                    })
                                    .setNegativeButton("Close", null).show();
                            break;
                        case 10: a.destinationController.configureQuickDestination(true); break;
                        case 11: a.destinationController.configureQuickDestination(false); break;
                        case 12:
                            new AlertDialog.Builder(a).setTitle("Clear recent destinations?")
                                    .setMessage("Favourites, Home and Work will be kept.")
                                    .setPositiveButton("Clear", (d, w) -> {
                                        a.places.clearRecent();
                                        a.homePanelController.refreshHomePanel();
                                        a.destinationController.refreshSearchSuggestions();
                                    }).setNegativeButton("Cancel", null).show();
                            break;
                        case 13:
                            new AlertDialog.Builder(a).setTitle("DriveMate v" + installedVersionName())
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
                            a.hazardWarningEnabled = !a.hazardWarningEnabled;
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("hazard_voice_alerts", a.hazardWarningEnabled).apply();
                            break;
                        case 16:
                            a.speedWarningEnabled = !a.speedWarningEnabled;
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("speed_voice_alerts", a.speedWarningEnabled).apply();
                            break;
                        case 17: selectRerouteMode(); break;
                        case 18: selectSpeedTolerance(); break;
                        case 19:
                            if(!a.active) a.startActivity(new Intent(a,DmUnifiedPreviewActivity.class).putExtra("stockport",true));
                            else Toast.makeText(a,"Stop navigation before opening map preview",Toast.LENGTH_SHORT).show();
                            break;
                        case 20: a.parkingController.promptSaveParking(); break;
                        case 21: a.parkingController.showParkedCar(); break;
                        case 22: a.reviewController.shareDiagnostics(); break;
                        case 23:
                            a.buildings3d = !a.buildings3d;
                            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("buildings_3d", a.buildings3d).apply();
                            if (a.vectorMap != null) a.vectorMap.setBuildings3D(a.buildings3d);
                            Toast.makeText(a, a.buildings3d
                                    ? "3D buildings on (estimated height; map data has no real heights)"
                                    : "Flat building outlines", Toast.LENGTH_SHORT).show();
                            break;
                        case 24: configureIndependent(); break;
                        case 25: a.reviewController.showMapReview(); break;
                        case 26: a.reviewController.showSurveyNotes(); break;
                        case 27: showOfflineRouting(); break;
                        case 28: showOfflineDisplay(); break;
                        case 29: if(!a.active) DmPilotScreen.show(a); break;
                        case 30: a.toggleFreeDriving(); break;
                        case 31: a.advanceSpeedVoice=!a.advanceSpeedVoice;
                            a.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).edit().putBoolean("advance_speed_voice",a.advanceSpeedVoice).apply(); break;
                        default: break;
                    }
                }).setNegativeButton("Close", null).show();
    }

    void showOfflineRouting(){
        if(a.active||a.route!=null||a.routing){a.showError("End or cancel the current journey before changing map data.");return;}
        LocalRoutingPack pack=new LocalRoutingPack(a);
        new AlertDialog.Builder(a).setTitle("Offline UK routing (experimental)")
            .setMessage((pack.installed()?"Verified installation present.":"Download approximately 2.40 GB over unmetered Wi-Fi; extra space needed for decompression.")+"\n\nLocal route previews and mapped place names only. No driving guidance, postcode search or nationwide performance acceptance yet. Map display uses its existing cache.")
            .setPositiveButton(pack.installed()?"Enable local previews":"Download UK routing",(d,w)->{
                if(pack.installed()){enableLocalRouting(true);return;}
                if(a.downloadingLocalPack){a.showError("Download already running");return;}
                a.downloadingLocalPack=true;a.setStatus("Preparing UK routing download…");
                a.packWorker.execute(()->{try{pack.download(message->a.runOnUiThread(()->{if(!a.isFinishing())a.setStatus(message);}));a.runOnUiThread(()->{a.downloadingLocalPack=false;if(!a.isFinishing()&&!a.active&&a.route==null&&!a.routing)enableLocalRouting(true);});}
                    catch(Exception e){a.runOnUiThread(()->{a.downloadingLocalPack=false;if(!a.isFinishing())a.showError(e.getMessage());});}});
            }).setNeutralButton("Disable local mode",(d,w)->enableLocalRouting(false)).setNegativeButton("Close",null).show();
    }

    void showOfflineDisplay(){
        if(a.active||a.route!=null||a.routing||a.downloadingLocalPack){a.showError("End navigation and wait for current downloads first.");return;}
        LocalRoutingPack pack=new LocalRoutingPack(a);
        new AlertDialog.Builder(a).setTitle("Full UK offline visual map")
            .setMessage(pack.displayInstalled()?"A complete visual map is installed. Verify it again if needed.":"Download the pinned UK visual map (approximately 1.87 GB) over unmetered Wi-Fi. Offline rendering uses this archive; this does not install the routing graph.")
            .setPositiveButton(pack.displayInstalled()?"Verify":"Download",(d,w)->{
                if(a.downloadingLocalPack||a.active||a.route!=null||a.routing)return;
                a.downloadingLocalPack=true;a.packWorker.execute(()->{try{pack.downloadDisplay(message->a.runOnUiThread(()->{if(!a.isFinishing())a.setStatus(message);}));
                    a.runOnUiThread(()->{a.downloadingLocalPack=false;if(!a.isFinishing()){a.useLocalMap=true;a.autoFallback=false;a.updateMapVisibility();}});}
                    catch(Exception e){a.runOnUiThread(()->{a.downloadingLocalPack=false;if(!a.isFinishing())a.showError(e.getMessage());});}});
            }).setNegativeButton("Close",null).show();
    }

    void enableLocalRouting(boolean enabled){
        if(a.active||a.route!=null||a.routing)return;
        a.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).edit().putBoolean("local_routing_preview",enabled).apply();
        if(enabled){a.useLocalMap=true;a.autoFallback=false;a.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).edit().putBoolean("drivemate_vector_map",true).apply();}
        a.updateMapVisibility();a.quickEtaFetchedAt=0;a.homeEtaLabel=null;a.workEtaLabel=null;
        a.setStatus(enabled?"Offline route preview mode · not for driving":"Offline preview mode disabled");
    }

    void configureIndependent(){
        if(a.active||a.route!=null||a.routing){a.showError("End or cancel the journey before changing providers.");return;}
        LinearLayout inputs=new LinearLayout(a);inputs.setOrientation(LinearLayout.VERTICAL);inputs.setPadding(a.dp(18),0,a.dp(18),0);
        EditText re=new EditText(a);re.setSingleLine(true);re.setHint("https://your-host/route");re.setText(a.ownEndpoint());inputs.addView(re);
        EditText se=new EditText(a);se.setSingleLine(true);se.setHint("https://your-host/api/ (Photon)");se.setText(a.ownSearchEndpoint());inputs.addView(se);
        android.widget.CheckBox license=new android.widget.CheckBox(a);license.setText("I have confirmed written permission for TomTom comparison use");license.setChecked(a.comparisonPermitted());inputs.addView(license);
        new AlertDialog.Builder(a).setTitle("Experimental DriveMate-first services")
            .setMessage("Trusted HTTPS endpoints only. No silent TomTom routing fallback. Comparison requires permission under your agreement. Findings stay in this session; never copy TomTom results to OSM.")
            .setView(inputs).setPositiveButton("Save",(d,w)->{
                String ru=re.getText().toString().trim(),su=se.getText().toString().trim();
                if(!ru.isEmpty()&&(!DriveMateProvider.valid(ru)||!ru.endsWith("/route"))){a.showError("Enter a trusted HTTPS /route endpoint");return;}
                if(!su.isEmpty()&&!DriveMateProvider.valid(su)){a.showError("Enter a trusted HTTPS Photon endpoint");return;}
                a.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).edit().putString("own_route_endpoint",ru).putString("own_search_endpoint",su).putBoolean("licensed_comparison",license.isChecked()).apply();
                if(!ru.isEmpty()){a.useLocalMap=true;a.autoFallback=false;a.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).edit().putBoolean("drivemate_vector_map",true).apply();a.updateMapVisibility();}
                a.setStatus(ru.isEmpty()?"TomTom routing selected":"Experimental DriveMate routing selected");a.quickEtaFetchedAt=0;a.homeEtaLabel=null;a.workEtaLabel=null;
            }).setNegativeButton("Cancel",null).show();
    }

    /** Always display the version installed in Android's package metadata.
     * Avoid hard-coded labels that stay stale after a signed APK update. */
    String installedVersionName() {
        try {
            String version = a.getPackageManager()
                    .getPackageInfo(a.getPackageName(), 0).versionName;
            return version != null && !version.isEmpty() ? version : "unknown";
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "unknown";
        }
    }

    String voiceModeName() {
        return new String[]{"Full", "Normal", "Alerts only", "Silent"}[a.voiceMode];
    }

    void setVoiceMode(int selected) {
        a.voiceMode = Math.max(DrivingVoicePolicy.FULL,
                Math.min(DrivingVoicePolicy.SILENT, selected));
        a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putInt("voice_mode_v2", a.voiceMode).apply();
        if ((a.voiceMode == DrivingVoicePolicy.SILENT
                || a.voiceMode == DrivingVoicePolicy.ALERTS_ONLY) && a.speech != null)
            a.speech.stop();
        a.updateVoiceButton();
        Toast.makeText(a, "Voice: " + voiceModeName(), Toast.LENGTH_SHORT).show();
    }

    void cycleVoiceMode() {
        setVoiceMode((a.voiceMode + 1) % 4);
    }

    void selectVoiceMode() {
        new AlertDialog.Builder(a).setTitle("Voice guidance")
                .setSingleChoiceItems(new String[]{
                        "Full · early, approach and turn",
                        "Normal · approach and turn",
                        "Alerts only · no routine turns",
                        "Silent · no navigation speech"
                }, a.voiceMode, (dialog, which) -> {
                    setVoiceMode(which);
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    String cameraModeName() {
        return new String[]{"Auto 3D", "2D direction up", "2D north up"}[a.cameraMode];
    }

    void selectCameraMode() {
        new AlertDialog.Builder(a).setTitle("Navigation camera")
                .setSingleChoiceItems(new String[]{
                        "Automatic 3D · adaptive tilt and zoom",
                        "2D · direction of travel up",
                        "2D · north always up"
                }, a.cameraMode, (dialog, which) -> {
                    a.cameraMode = which;
                    a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("camera_mode", which).apply();
                    a.lastCameraUpdate = 0;
                    a.applyHighDetailPolicy();
                    if (a.active && a.route != null && a.currentLocation != null) {
                        a.smoothFollow = new SmoothFollowCamera(new RouteMotionIndex(a.route.points));
                        a.updateGuidance(a.currentLocation);
                    } else if (which == DrivingDisplayPolicy.CAMERA_NORTH_2D) {
                        a.map.setMapOrientation(0);
                    }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    void selectRerouteMode() {
        new AlertDialog.Builder(a).setTitle("Faster-route suggestions")
                .setSingleChoiceItems(new String[]{
                        "Automatic · switch for substantial estimated savings",
                        "Ask first · stay on route until I approve"
                }, a.rerouteMode, (dialog, which) -> {
                    a.rerouteMode = which;
                    a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("reroute_mode", which).apply();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    void selectSpeedTolerance() {
        final String[] labels = {"0 mph", "3 mph", "5 mph", "7 mph", "10 mph", "15 mph"};
        final int[] values = {0, 3, 5, 7, 10, 15};
        int selected = 1;
        for (int i = 0; i < values.length; i++)
            if (values[i] == a.speedToleranceMph) selected = i;
        new AlertDialog.Builder(a).setTitle("Above mapped speed limit")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    a.speedToleranceMph = values[which];
                    a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("speed_tolerance_mph", a.speedToleranceMph).apply();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    String themeModeName() {
        return DrivingDisplayPolicy.themeName(a.themePreference);
    }

    void selectThemeMode() {
        int[] order = DrivingDisplayPolicy.THEME_MENU;
        int checked = 0;
        for (int i = 0; i < order.length; i++) if (order[i] == a.themePreference) checked = i;
        new AlertDialog.Builder(a).setTitle("Map colours")
                .setSingleChoiceItems(new String[]{
                        "Light", "Dark", "Automatic · follow phone light/dark setting"
                }, checked, (dialog, which) -> {
                    a.themePreference = order[which];
                    a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("map_theme", a.themePreference).apply();
                    a.applyTheme();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    void selectSpeechVolume() {
        SeekBar slider = new SeekBar(a);
        slider.setMax(100);
        slider.setProgress(Math.round(a.guidanceVolume * 100));
        slider.setPadding(a.dp(22), a.dp(12), a.dp(22), a.dp(12));
        new AlertDialog.Builder(a).setTitle("Spoken guidance volume")
                .setMessage("Uses Android text-to-speech navigation audio. Media volume also matters.")
                .setView(slider)
                .setPositiveButton("Save and test", (dialog, which) -> {
                    a.guidanceVolume = Math.max(0.1f, slider.getProgress() / 100f);
                    a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putFloat("speech_volume", a.guidanceVolume).apply();
                    a.say("DriveMate guidance volume");
                }).setNegativeButton("Cancel", null).show();
    }

    void showKeyDialog() {
        EditText keyInput = new EditText(a);
        keyInput.setSingleLine(true);
        keyInput.setHint("Paste your TomTom API key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setText(a.apiKey);
        FrameLayout holder = new FrameLayout(a);
        holder.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        holder.addView(keyInput, new FrameLayout.LayoutParams(-1, a.dp(55)));
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Connect TomTom")
                .setMessage("A TomTom API key with Maps, Search, Routing and Traffic access is required. The key stays on this device.")
                .setView(holder)
                .setPositiveButton("Save and open map", null)
                .setNegativeButton("Cancel", (d, w) -> {
                    if (a.apiKey.isEmpty()) a.setStatus("Enter an API key to show the map.");
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String supplied = keyInput.getText().toString().trim();
            if (supplied.isEmpty()) {
                keyInput.setError("A key is required");
                return;
            }
            if (!supplied.equals(a.apiKey)) a.stopNavigation(false);
            a.apiKey = supplied;
            a.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                    .putString("tomtom_key", supplied).apply();
            a.configureTiles();
            a.setStatus("Connected. Search for a destination.");
            a.instructionText.setText("Type an address or postcode and tap GO.");
            dialog.dismiss();
        }));
        dialog.show();
    }
}
