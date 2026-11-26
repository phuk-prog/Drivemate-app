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
final class ReviewController {
    final MainActivity a;

    ReviewController(MainActivity activity) { this.a = activity; }

    void requestComparison(){
        if(!a.active||!a.comparisonPermitted()||a.apiKey.isEmpty()||a.route==null||!"DriveMate".equals(a.route.provider)||a.comparisonBusy||a.currentLocation==null||a.destination==null||!a.hasNetwork())return;
        long now=SystemClock.elapsedRealtime();if(a.comparisonRequestedAt>0&&now-a.comparisonRequestedAt<180000)return;
        a.comparisonRequestedAt=now;a.comparisonBusy=true;final int generation=a.reviewGeneration;
        final GeoMath.Point start=a.gpsPoint(a.currentLocation),end=a.destination.point;final String key=a.apiKey;
        a.network.execute(()->{TomTomApi.Route reference=null;try{reference=TomTomApi.route(key,start,end);}catch(Exception ignored){}
            final TomTomApi.Route result=reference;a.runOnUiThread(()->{if(generation!=a.reviewGeneration)return;a.comparisonBusy=false;if(a.active)a.comparisonRoute=result;});});
    }

    void showMapReview(){
        if(a.active || a.currentLocation==null || !a.currentLocation.hasSpeed() || a.currentLocation.getSpeed()>=0.5f || System.currentTimeMillis()-a.currentLocation.getTime()>10000){a.showError("Review map differences only after ending navigation and parking safely.");return;}
        List<MapReview.Finding> items=a.mapReview.snapshot();
        if(items.isEmpty()){new AlertDialog.Builder(a).setTitle("Journey map review").setMessage("No comparable differences recorded. Comparison requires an authorised TomTom connection and aligned road data.").setPositiveButton("Close",null).show();return;}
        String[] labels=new String[items.size()];for(int i=0;i<labels.length;i++)labels[i]=items.get(i).label();
        new AlertDialog.Builder(a).setTitle("Map review · "+items.size()+" possible differences").setItems(labels,(d,i)->surveyFinding(items.get(i))).setNeutralButton("Clear",(d,w)->a.mapReview.clear()).setNegativeButton("Close",null).show();
    }

    boolean safelyParked(){
        return !a.active&&a.currentLocation!=null&&a.currentLocation.hasSpeed()&&a.currentLocation.getSpeed()<0.5f&&System.currentTimeMillis()-a.currentLocation.getTime()<10000;
    }

    void showSurveyNotes(){
        if(!safelyParked()){a.showError("Review surveys only when safely parked.");return;}
        SurveyNotes notes=new SurveyNotes(a);List<String> saved=notes.list();
        new AlertDialog.Builder(a).setTitle("Independent map survey drafts")
            .setItems(saved.toArray(new String[0]),(d,i)->new AlertDialog.Builder(a).setTitle("Independent survey")
                .setMessage(saved.get(i)).setPositiveButton("Share",(dd,w)->shareSurvey(saved.get(i)))
                .setNeutralButton("Delete",(dd,w)->new AlertDialog.Builder(a).setMessage("Delete this private survey draft?").setPositiveButton("Delete",(ddd,www)->notes.delete(i)).setNegativeButton("Cancel",null).show()).setNegativeButton("Close",null).show())
            .setPositiveButton("New independent survey",(d,w)->editSurvey()).setNegativeButton("Close",null).show();
    }

    void editSurvey(){
        if(!safelyParked())return;
        EditText entry=new EditText(a);entry.setHint("Independent road observation: location, direction, sign, date and evidence");
        new AlertDialog.Builder(a).setTitle("New survey").setMessage("Use only your own observations or compatible official data. No TomTom values or copied comparison locations.")
            .setView(entry).setPositiveButton("Save draft",(d,w)->{
                if(!safelyParked())return;
                if(!new SurveyNotes(a).add(entry.getText().toString()))a.showError("Enter evidence (up to 2000 characters; maximum 100 drafts).");
                else a.setStatus("Independent survey saved privately");
            }).setNegativeButton("Cancel",null).show();
    }

    void shareSurvey(String note){
        if(!safelyParked())return;
        android.content.Intent share=new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,"Independent road survey: "+note);
        a.startActivity(android.content.Intent.createChooser(share,"Share independent survey"));
    }

    void surveyFinding(MapReview.Finding item){
        EditText evidence=new EditText(a);evidence.setHint("Independently observed location, sign, direction and date");
        new AlertDialog.Builder(a).setTitle(item.label()).setMessage(String.format(Locale.UK,"DriveMate location: %.5f, %.5f · travel heading %.0f°\n\nNot proof of a map error. Verify road signs or permitted official records independently. Only independent observations can be shared; never copy TomTom results.",item.point.lat,item.point.lon,item.bearing))
            .setView(evidence).setPositiveButton("Save independent draft",(d,w)->{
                if(!safelyParked())return;
                if(!new SurveyNotes(a).add(evidence.getText().toString()))a.showError("Independent survey evidence required (up to 2000 characters).");
                else a.setStatus("Independent survey saved privately");
            }).setNegativeButton("Close",null).show();
    }

    /**
     * Settings → "Share diagnostics": a text snapshot of GPS quality and the
     * camera/arrow angles, for reporting display problems such as a sideways
     * car arrow. No coordinates, addresses or destination names are included,
     * and nothing is sent unless the driver picks an app to share it with.
     */
    void shareDiagnostics() {
        DiagnosticsReport.Snapshot s = new DiagnosticsReport.Snapshot();
        s.appVersion = a.settingsController.installedVersionName();
        s.androidSdk = Build.VERSION.SDK_INT;
        s.device = Build.MANUFACTURER + " " + Build.MODEL;
        s.navigating = a.active;
        s.gpsLost = a.gpsLost;
        s.fixSource = a.lastAcceptedFix == null ? ""
                : a.lastAcceptedFix.source == FixQualityPolicy.SOURCE_FUSED ? "fused"
                : a.lastAcceptedFix.source == FixQualityPolicy.SOURCE_GPS ? "gps" : "network";
        if (a.fusedActive && s.fixSource.isEmpty()) s.fixSource = "fused (no fix yet)";
        if (a.currentLocation != null) {
            if (a.currentLocation.hasAccuracy()) s.accuracyMetres = a.currentLocation.getAccuracy();
            if (a.currentLocation.hasSpeed()) s.speedMps = a.currentLocation.getSpeed();
            if (a.currentLocation.hasBearing()) s.gpsBearing = a.currentLocation.getBearing();
        }
        s.satellitesUsed = a.satellitesUsed;
        s.offRouteMetres = a.active ? a.lastOffRouteMetres : Double.NaN;
        s.matchReason=(a.joiningRoute?"JOINING_ROUTE / "+a.lastMatchReason:a.lastMatchReason)
                +(a.route==null?"":" / map: "+(a.vectorMap!=null&&a.showingVector()?a.vectorMap.geometryDescription():RouteMapPolicy.description(a.route.provider,false)))
                +(a.showingVector()&&a.vectorMap!=null?" / visual alignment: "+a.vectorMap.routeAlignmentDiagnostic():" / raster alignment not measured")
                +" / base map "+DriveTileServer.MAP_RELEASE;
        s.fixAgeMs=a.currentLocation==null?-1:Math.max(0,System.currentTimeMillis()-a.currentLocation.getTime());
        s.cameraMode = a.settingsController.cameraModeName();
        s.followingCar = a.followLocation;
        s.highDetailTiles = false; // vector default; explicit raster fallback remains possible
        s.cameraBearing = a.lastCameraBearing;
        s.travelBearing = a.lastTravelBearing;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "DriveMate diagnostics")
                .putExtra(Intent.EXTRA_TEXT, DiagnosticsReport.build(s));
        a.startActivity(Intent.createChooser(share, "Share DriveMate diagnostics"));
    }
}
