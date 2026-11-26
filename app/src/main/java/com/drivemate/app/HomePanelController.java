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
final class HomePanelController {
    final MainActivity a;

    HomePanelController(MainActivity activity) { this.a = activity; }

    Button quickTile(int iconRes, String label, String detail) {
        Button button = new Button(a);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setCompoundDrawablePadding(a.dp(3));
        button.setCompoundDrawablesWithIntrinsicBounds(null, a.getDrawable(iconRes), null, null);
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable background = a.rounded(Color.rgb(25, 69, 85), 12);
        background.setStroke(a.dp(1), MainActivity.CYAN);
        button.setBackground(background);
        setTileText(button, label, detail);
        return button;
    }

    /** Bold label on the first line, smaller destination name (or hint) below. */
    void setTileText(Button tile, String label, String detail) {
        SpannableString content = new SpannableString(label + "\n" + detail);
        content.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                label.length() + 1, content.length(), 0);
        tile.setText(content);
    }

    void setHomeVisible(boolean show) {
        a.homeMode = show;
        if(!show&&a.freeDriving){a.freeDriving=false;a.freeDriveBearing=Double.NaN;a.roadContextController.clearFreeRoadContext();a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
        if (a.homePanel == null || a.navigationPanel == null) return;
        a.homePanel.setVisibility(show && !a.keyboardOpen && !a.freeDriving ? View.VISIBLE : View.GONE);
        a.navigationPanel.setVisibility(!show && !a.keyboardOpen ? View.VISIBLE : View.GONE);
        if (a.speedBadgeView != null) {
            // Speed only while actually navigating: not on home or route preview.
            a.speedBadgeView.setVisibility(a.keyboardOpen || (!a.active&&!a.freeDriving) || (show&&!a.freeDriving)
                    ? View.GONE : View.VISIBLE);
        }
        if (a.followButton != null) {
            a.followButton.setVisibility(a.keyboardOpen ? View.GONE : View.VISIBLE);
            a.updateFollowButton();
        }
        if (a.speedBadgePos != null && a.gpsButtonPos != null) {
            // During navigation the compact control row is only ~60dp tall.
            // Keep GPS speed and recenter reachable without hiding the road.
            // Off the home screen, sit just above the bottom panel (its own
            // 9 dp margin plus a 10 dp gap). Before its first layout, fall back
            // to the previous estimates.
            int navHeight = a.navigationPanel.getHeight();
            int above = navHeight > 0 ? navHeight + a.dp(19) : a.dp(a.active ? 100 : 225);
            a.speedBadgePos.bottomMargin = a.freeDriving ? a.dp(16) : show ? a.dp(320) : above;
            a.gpsButtonPos.bottomMargin = a.freeDriving ? a.dp(16) : show ? a.dp(327) : above;
            if(a.speedBadgeView!=null)a.speedBadgeView.requestLayout();
            if(a.followButton!=null)a.followButton.requestLayout();
        }
        a.applyNavigationChrome();
        if (show && !a.keyboardOpen) refreshHomePanel();
    }

    void refreshHomePanel() {
        if (a.places == null || a.recentList == null) return;
        TomTomApi.Destination home = a.places.home();
        TomTomApi.Destination work = a.places.work();
        a.homeTile.setContentDescription(home == null ? "Set Home" : "Navigate Home to " + home);
        a.workTile.setContentDescription(work == null ? "Set Work" : "Navigate Work to " + work);
        // Real drive time from TomTom when available, otherwise the place name.
        setTileText(a.homeTile, "Home", home == null ? "Not set"
                : a.homeEtaLabel != null ? a.homeEtaLabel : home.title);
        setTileText(a.workTile, "Work", work == null ? "Not set"
                : a.workEtaLabel != null ? a.workEtaLabel : work.title);
        maybeFetchQuickEtas();
        a.recentList.removeAllViews();
        List<TomTomApi.Destination> recents = a.places.recent();
        if (recents.isEmpty()) {
            TextView empty = a.text("No recent journeys yet. Find a destination to begin.",
                    12, Color.rgb(202, 219, 224), false);
            empty.setPadding(a.dp(2), a.dp(18), a.dp(2), a.dp(8));
            a.recentList.addView(empty);
        } else {
            for (TomTomApi.Destination item : recents) {
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(a.dp(7), 0, a.dp(6), 0);
                TextView name = a.text(item.title, 15, Color.WHITE, false);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                if (a.currentLocation != null && item.point != null) {
                    // Straight-line distance from the live GPS fix, shown on the right.
                    TextView distance = a.text(NavigationUnits.displayDistance(
                            GeoMath.distance(a.gpsPoint(a.currentLocation), item.point),
                            a.metricUnits), 13, Color.rgb(143, 176, 192), false);
                    distance.setPadding(a.dp(8), 0, 0, 0);
                    row.addView(distance);
                }
                // Remove just this entry; "Clear recent destinations" in
                // Settings still clears them all.
                TextView remove = a.text("×", 22, Color.rgb(143, 176, 192), false);
                remove.setGravity(Gravity.CENTER);
                remove.setContentDescription("Remove " + item.title + " from recent destinations");
                remove.setOnClickListener(v -> {
                    a.places.removeRecent(item);
                    refreshHomePanel();
                    a.destinationController.refreshSearchSuggestions();
                });
                row.addView(remove, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));
                row.setOnClickListener(v -> a.selectDestination(item));
                a.recentList.addView(row, new LinearLayout.LayoutParams(-1, a.dp(46)));
                View line = new View(a);
                line.setBackgroundColor(Color.rgb(40, 86, 104));
                a.recentList.addView(line, new LinearLayout.LayoutParams(-1, a.dp(1)));
            }
        }
    }

    /**
     * Fetch real Home/Work drive times (with traffic) from TomTom when the home
     * screen is showing. At most once every 5 minutes, and only with a fresh
     * GPS fix and a TomTom key, to keep API use low.
     */
    void maybeFetchQuickEtas() {
        if (a.localRouting() || (!a.ownRouting() && a.apiKey.isEmpty()) || a.places == null || a.currentLocation == null || a.active) return;
        if (!MapStartupPolicy.freshForRouting(
                a.currentLocation.getLatitude(), a.currentLocation.getLongitude(),
                System.currentTimeMillis() - MainActivity.fixAgeMs(a.currentLocation), System.currentTimeMillis(),
                a.currentLocation.hasAccuracy() ? a.currentLocation.getAccuracy() : 101f)) return;
        long now = SystemClock.elapsedRealtime();
        if (a.quickEtaFetchedAt != 0 && now - a.quickEtaFetchedAt < 5 * 60_000L) return;
        TomTomApi.Destination home = a.places.home();
        TomTomApi.Destination work = a.places.work();
        if (home == null && work == null) return;
        a.quickEtaFetchedAt = now;
        GeoMath.Point from = a.gpsPoint(a.currentLocation);
        String key = a.apiKey, own = a.ownEndpoint();
        a.network.execute(() -> {
            String homeLabel = home == null ? null : quickEta(key, own, from, home);
            String workLabel = work == null ? null : quickEta(key, own, from, work);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                a.homeEtaLabel = homeLabel;
                a.workEtaLabel = workLabel;
                if (a.homeMode) refreshHomePanel();
            });
        });
    }

    /** Drive time such as "14 min", or null if TomTom can't route right now. */
    static String quickEta(String key, String own, GeoMath.Point from, TomTomApi.Destination to) {
        if (to == null || to.point == null) return null;
        try {
            TomTomApi.Route trip = own.isEmpty()?TomTomApi.route(key,from,to.point):DriveMateProvider.route(own,from,to.point);
            return NavigationUnits.duration(trip.timeSeconds);
        } catch (Exception error) {
            return null;
        }
    }
}
