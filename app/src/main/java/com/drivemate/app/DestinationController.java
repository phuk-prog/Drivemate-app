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
final class DestinationController {
    final MainActivity a;

    DestinationController(MainActivity activity) { this.a = activity; }

    void refreshSearchSuggestions() {
        if (a.destinationInput == null || a.places == null) return;
        String text = a.destinationInput.getText().toString().trim().toLowerCase(Locale.UK);
        a.suggestionMatches.clear();
        List<String> labels = new ArrayList<>();
        List<TomTomApi.Destination> candidates = new ArrayList<>(a.places.favorites());
        candidates.addAll(a.places.recent());
        for (TomTomApi.Destination candidate : candidates) {
            if (!text.isEmpty()
                    && !candidate.toString().toLowerCase(Locale.UK).contains(text)) continue;
            boolean duplicate = false;
            for (TomTomApi.Destination prior : a.suggestionMatches) {
                if (GeoMath.distance(prior.point, candidate.point) < 3) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            a.suggestionMatches.add(candidate);
            labels.add(candidate.toString());
            if (labels.size() >= 8) break;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a,
                android.R.layout.simple_dropdown_item_1line, labels);
        a.destinationInput.setAdapter(adapter);
        a.destinationInput.setOnItemClickListener((parent, view, position, id) -> {
            Object selected = parent.getItemAtPosition(position);
            for (TomTomApi.Destination place : new ArrayList<>(a.suggestionMatches)) {
                if (place.toString().equals(selected)) {
                    a.destinationInput.dismissDropDown();
                    a.selectDestination(place);
                    return;
                }
            }
        });
    }

    void openQuickDestination(boolean home) {
        TomTomApi.Destination place = home ? a.places.home() : a.places.work();
        if (place == null) {
            configureQuickDestination(home);
        } else {
            a.selectDestination(place);
        }
    }

    void configureQuickDestination(boolean home) {
        String label = home ? "Home" : "Work";
        EditText entry = new EditText(a);
        entry.setSingleLine(true);
        entry.setHint("Enter " + label + " address or postcode");
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        FrameLayout container = new FrameLayout(a);
        container.setPadding(a.dp(18), a.dp(6), a.dp(18), a.dp(4));
        container.addView(entry, new FrameLayout.LayoutParams(-1, a.dp(55)));
        new AlertDialog.Builder(a).setTitle("Set " + label)
                .setMessage("Choose a destination to save as " + label + ".")
                .setView(container)
                .setPositiveButton("Search", (dialog, which) -> {
                    String query = entry.getText().toString().trim();
                    if (query.length() < 2) {
                        a.showError("Enter a complete address or postcode.");
                        return;
                    }
                    if (!a.ownRouting() && a.apiKey.isEmpty()) {
                        a.settingsController.showKeyDialog();
                        return;
                    }
                    GeoMath.Point near = a.currentLocation == null ? null : a.gpsPoint(a.currentLocation);
                    a.setStatus("Finding " + label + "…");
                    a.network.execute(() -> {
                        try {
                            List<TomTomApi.Destination> options =
                                    a.providerSearch(query, near);
                            a.runOnUiThread(() -> {
                                if (a.isFinishing()) return;
                                if (options.isEmpty()) {
                                    a.showError("No matching addresses. Try a fuller address.");
                                    return;
                                }
                                String[] labels = new String[options.size()];
                                for (int i = 0; i < options.size(); i++) {
                                    labels[i] = options.get(i).toString();
                                }
                                new AlertDialog.Builder(a)
                                        .setTitle("Save " + label + " location")
                                        .setItems(labels, (d, index) -> {
                                            TomTomApi.Destination chosen = options.get(index);
                                            // A new Home/Work needs a fresh drive time.
                                            a.quickEtaFetchedAt = 0;
                                            a.homeEtaLabel = null;
                                            a.workEtaLabel = null;
                                            if (home) a.places.setHome(chosen);
                                            else a.places.setWork(chosen);
                                            a.homePanelController.refreshHomePanel();
                                            Toast.makeText(a, label + " saved",
                                                    Toast.LENGTH_SHORT).show();
                                            a.selectDestination(chosen);
                                        })
                                        .setNegativeButton("Cancel", null).show();
                            });
                        } catch (Exception error) {
                            a.runOnUiThread(() -> {
                                if (!a.isFinishing()) a.showError(error.getMessage());
                            });
                        }
                    });
                }).setNegativeButton("Cancel", null).show();
    }

    void voiceSearch() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an address or postcode");
            a.startActivityForResult(intent, MainActivity.VOICE_SEARCH_REQUEST);
        } catch (Exception e) {
            a.showError("Speech search is not available on this phone.");
        }
    }

    void searchDestination() {
        if (!a.ownRouting() && a.apiKey.isEmpty()) {
            a.settingsController.showKeyDialog();
            return;
        }
        String query = UkSearch.normalize(a.destinationInput.getText().toString());
        if (query.length() < 2) {
            a.destinationInput.setError("Enter an address or postcode");
            return;
        }
        a.destinationInput.dismissDropDown();
        a.destinationInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager)
                a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(a.destinationInput.getWindowToken(), 0);
        }
        a.setStatus("Finding " + query + "…");
        GeoMath.Point near = a.currentLocation == null ? null : a.gpsPoint(a.currentLocation);
        final int searchId = ++a.requestGeneration;
        a.network.execute(() -> {
            try {
                List<TomTomApi.Destination> found = a.providerSearch(query, near);
                a.runOnUiThread(() -> {
                    if (searchId != a.requestGeneration || a.isFinishing()) return;
                    if (found.isEmpty()) {
                        a.showError("No matching destinations. Try a fuller address.");
                        return;
                    }
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).toString();
                    new AlertDialog.Builder(a)
                            .setTitle("Choose destination")
                            .setItems(labels, (d, which) -> a.selectDestination(found.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                    a.setStatus("Select the matching destination.");
                });
            } catch (Exception error) {
                a.runOnUiThread(() -> {
                    if (searchId == a.requestGeneration) a.showError(error.getMessage());
                });
            }
        });
    }

    /** Select saved/recent destinations without making another search API call. */
    void showSavedDestinations() {
        List<TomTomApi.Destination> favorites = a.places.favorites();
        List<TomTomApi.Destination> recents = a.places.recent();
        if (favorites.isEmpty() && recents.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Places")
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
        new AlertDialog.Builder(a).setTitle("Saved & recent places")
                .setItems(descriptions.toArray(new String[0]), (dialog, which) ->
                        a.selectDestination(results.get(which)))
                .setNeutralButton("Remove favourite", (dialog, which) -> showRemoveFavorite())
                .setNegativeButton("Close", null).show();
    }

    void showRemoveFavorite() {
        List<TomTomApi.Destination> favorites = a.places.favorites();
        if (favorites.isEmpty()) {
            Toast.makeText(a, "No saved favourites to remove.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[favorites.size()];
        for (int i = 0; i < favorites.size(); i++) names[i] = favorites.get(i).toString();
        new AlertDialog.Builder(a).setTitle("Remove a favourite")
                .setItems(names, (dialog, which) -> {
                    a.places.removeFavorite(favorites.get(which));
                    if (a.destination != null && !a.active && a.route != null) a.showPreview(a.route);
                    Toast.makeText(a, "Favourite removed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    void saveCurrentDestination() {
        if (a.destination == null) return;
        if (a.places.isFavorite(a.destination)) {
            Toast.makeText(a, "Already saved in favourites", Toast.LENGTH_SHORT).show();
            return;
        }
        a.places.addFavorite(a.destination);
        a.saveButton.setText("★ Saved");
        a.homePanelController.refreshHomePanel();
        Toast.makeText(a, "Saved to favourites", Toast.LENGTH_SHORT).show();
    }
}
