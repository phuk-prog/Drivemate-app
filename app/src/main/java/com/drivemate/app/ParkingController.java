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
final class ParkingController {
    final MainActivity a;

    ParkingController(MainActivity activity) { this.a = activity; }

    /** Parking information is requested only on demand and never guessed. */
    void findNearbyParking() {
        if (a.ownRouting()) { a.showError("Independent parking search not configured yet."); return; }
        if (a.destination == null || a.apiKey.isEmpty()) {
            a.showError("Choose a destination and configure TomTom before finding parking.");
            return;
        }
        if (a.currentLocation == null || !a.currentLocation.hasSpeed()
                || a.currentLocation.getSpeed() > 1.2f
                || System.currentTimeMillis() - a.currentLocation.getTime() > 7000) {
            a.showError("Stop safely before finding or selecting parking.");
            return;
        }
        final TomTomApi.Destination searchedFor = a.destination;
        final String key = a.apiKey;
        a.setStatus("Looking for map-listed parking near destination…");
        a.network.execute(() -> {
            try {
                List<TomTomApi.Destination> results =
                        TomTomApi.parkingNearby(key, searchedFor.point);
                results.removeIf(item -> GeoMath.distance(
                        item.point, searchedFor.point) > 1400);
                results.sort((a, b) -> Double.compare(
                        GeoMath.distance(a.point, searchedFor.point),
                        GeoMath.distance(b.point, searchedFor.point)));
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.destination == null
                            || GeoMath.distance(a.destination.point, searchedFor.point) > 2)
                        return;
                    if (results.isEmpty()) {
                        new AlertDialog.Builder(a).setTitle("Nearby parking")
                                .setMessage("TomTom returned no verified nearby parking POIs. "
                                        + "Parking availability and access are not guaranteed.")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    String[] names = new String[results.size()];
                    for (int i = 0; i < results.size(); i++) {
                        TomTomApi.Destination item = results.get(i);
                        names[i] = item.title + " · " + NavigationUnits.displayDistance(
                                GeoMath.distance(item.point, searchedFor.point), a.metricUnits);
                    }
                    new AlertDialog.Builder(a)
                            .setTitle("Map-listed parking · availability unknown")
                            .setItems(names, (dialog, which) -> {
                                TomTomApi.Destination chosen = results.get(which);
                                new AlertDialog.Builder(a)
                                        .setTitle("Preview route to " + chosen.title + "?")
                                        .setMessage("This will replace your current destination. "
                                                + "Parking capacity, pricing and vehicle access "
                                                + "have not been verified. Follow signs.")
                                        .setPositiveButton("Preview route", (d, w) ->
                                                a.selectDestination(chosen))
                                        .setNegativeButton("Keep destination", null).show();
                            })
                            .setNegativeButton("Cancel", null).show();
                });
            } catch (Exception error) {
                a.runOnUiThread(() -> {
                    if (!a.isFinishing() && a.destination != null
                            && GeoMath.distance(a.destination.point, searchedFor.point) <= 2)
                        a.showError("Nearby parking unavailable: " + error.getMessage());
                });
            }
        });
    }

    void offerSaveParkedCar() {
        new AlertDialog.Builder(a).setTitle("Save where you parked?")
                .setMessage("Store this GPS position on this phone only. "
                        + "You can view or clear it from the DriveMate home screen.")
                .setPositiveButton("Save location", (dialog, which) -> saveParkedCarNow())
                .setNegativeButton("Not now", null).show();
    }

    void saveParkedCarNow() {
        if (a.currentLocation == null || !ArrivalPolicy.canSaveParking(
                a.currentLocation.hasAccuracy() ? a.currentLocation.getAccuracy() : Float.MAX_VALUE,
                a.currentLocation.hasSpeed() ? a.currentLocation.getSpeed() : Float.MAX_VALUE,
                a.currentLocation.getTime(), System.currentTimeMillis())) {
            a.showError("Parking location not saved · wait for fresh precise stationary GPS.");
            return;
        }
        a.places.saveParkedCar(a.gpsPoint(a.currentLocation), System.currentTimeMillis());
        a.homePanelController.refreshHomePanel();
        Toast.makeText(a, "Parked car saved locally", Toast.LENGTH_LONG).show();
    }

    /** Settings → "Save where I parked": only saves a precise, stationary GPS fix. */
    void promptSaveParking() {
        if (a.currentLocation == null || !ArrivalPolicy.canSaveParking(
                a.currentLocation.hasAccuracy() ? a.currentLocation.getAccuracy() : Float.MAX_VALUE,
                a.currentLocation.hasSpeed() ? a.currentLocation.getSpeed() : Float.MAX_VALUE,
                a.currentLocation.getTime(), System.currentTimeMillis())) {
            a.showError("Park safely and wait for precise stationary GPS before saving.");
            return;
        }
        new AlertDialog.Builder(a).setTitle("Save parked-car location?")
                .setMessage("Only the current GPS estimate is saved on this phone. "
                        + "It can be cleared from Settings → Find my parked car.")
                .setPositiveButton("Save", (d, w) -> saveParkedCarNow())
                .setNegativeButton("Cancel", null).show();
    }

    void showParkedCar() {
        GeoMath.Point saved = a.places.parkedCar();
        if (saved == null) {
            Toast.makeText(a, "No saved parking position", Toast.LENGTH_SHORT).show();
            a.homePanelController.refreshHomePanel();
            return;
        }
        // Leave the saved position visible rather than having the next live
        // GPS fix immediately drag the map back to the driver's location.
        a.followLocation = false;
        a.updateFollowButton();
        if (a.parkedCarPin != null) a.map.getOverlays().remove(a.parkedCarPin);
        a.parkedCarPin = new Marker(a.map);
        a.parkedCarPin.setPosition(new GeoPoint(saved.lat, saved.lon));
        a.parkedCarPin.setTitle("Saved parked-car position · GPS estimate");
        a.map.getOverlays().add(a.parkedCarPin);
        if (a.vectorMap != null) {
            a.vectorMap.setParkedCar(saved);
            a.vectorMap.setCamera(saved, 17.0, 0);
        }
        a.map.getController().setZoom(17.0);
        a.map.getController().setCenter(new GeoPoint(saved.lat, saved.lon));
        a.map.invalidate();
        new AlertDialog.Builder(a).setTitle("Parked car · saved on this phone")
                .setMessage("Showing the GPS position you explicitly saved. "
                        + "It may not be an exact parking bay. "
                        + "DriveMate does not provide a walking route to your car.")
                .setPositiveButton("Done", null)
                .setNeutralButton("Clear saved position", (dialog, which) -> {
                    a.places.clearParkedCar();
                    if (a.parkedCarPin != null) {
                        a.map.getOverlays().remove(a.parkedCarPin);
                        a.parkedCarPin = null;
                    }
                    if (a.vectorMap != null) a.vectorMap.setParkedCar(null);
                    a.homePanelController.refreshHomePanel();
                    a.map.invalidate();
                }).show();
    }
}
