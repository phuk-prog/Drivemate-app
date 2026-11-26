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
final class GuidanceDialogs {
    final MainActivity a;

    GuidanceDialogs(MainActivity activity) { this.a = activity; }

    void showRemainingRouteOverview(){
        if(!a.active||a.route==null||a.route.points.size()<2)return;
        a.followLocation=false;a.stopSmoothLoop();a.updateFollowButton();
        List<GeoMath.Point> remaining=GeoMath.slice(a.route.points,Math.max(0,a.stabilizedAlong),GeoMath.length(a.route.points));
        if(remaining.size()<2)remaining=a.route.points;
        if(a.showingVector()&&a.vectorMap!=null){
            int top=(a.topMapOverlay!=null?a.topMapOverlay.getHeight():a.dp(140))+a.dp(18);
            int bottom=a.navigationPanel.getHeight()+a.dp(18);
            a.vectorMap.previewRoute(remaining,top,bottom,a.dp(36));
        }else{
            double n=-90,s=90,e=-180,w=180;
            for(GeoMath.Point p:remaining){n=Math.max(n,p.lat);s=Math.min(s,p.lat);e=Math.max(e,p.lon);w=Math.min(w,p.lon);}
            if(n-s<.0001){n+=.0001;s-=.0001;}if(e-w<.0001){e+=.0001;w-=.0001;}
            a.map.setMapOrientation(0);a.map.zoomToBoundingBox(new BoundingBox(n,e,s,w),true,a.dp(100));
        }
    }

    void showUpcomingManoeuvres(){
        if(!a.canInspectGuidance()){
            Toast.makeText(a,"Stop safely with a fresh GPS fix to view turns",Toast.LENGTH_SHORT).show();return;
        }
        a.dismissUpcoming();
        StringBuilder list=new StringBuilder();
        for(int i=Math.max(0,a.previousInstructionIndex);i<a.route.instructions.size()&&i<a.previousInstructionIndex+8;i++){
            TomTomApi.Instruction step=a.route.instructions.get(i);
            String glyph=GuidanceLogic.symbol(step.maneuver,step.junctionType,step.turnAngleDegrees);
            int exit="↻".equals(glyph)?JunctionFraming.reportedExit(step.exitNumber):0;
            if(list.length()>0)list.append("\n\n");
            list.append(NavigationUnits.displayDistance(Math.max(0,step.routeOffsetMetres-a.stabilizedAlong),a.metricUnits)).append(" · ").append(IntelligentGuidance.direction(step,glyph,exit));
        }
        a.upcomingDialog=new AlertDialog.Builder(a).setTitle("Upcoming manoeuvres").setMessage(list.length()==0?"No further instructions":list.toString()).setPositiveButton("Close",null).create();
        a.upcomingDialog.show();
    }

    void showTrafficTimeline() {
        if (!a.active || a.route == null || a.currentLocation == null) return;
        if (System.currentTimeMillis() - a.route.fetchedAtMs > 480000) {
            Toast.makeText(a, "Route traffic information is old; refresh the route",
                    Toast.LENGTH_LONG).show();
            return;
        }
        GeoMath.Progress progress = GeoMath.project(a.route.points, a.gpsPoint(a.currentLocation));
        StringBuilder timeline = new StringBuilder();
        int count = 0;
        for (RouteIntelligence.TrafficSection item : a.route.trafficSections) {
            if (item.end < progress.alongMetres) continue;
            timeline.append(item.label()).append(" · ")
                    .append(NavigationUnits.displayDistance(
                            Math.max(0, item.start - progress.alongMetres), a.metricUnits))
                    .append(" ahead");
            if (item.delaySeconds > 0)
                timeline.append(" · approx +")
                        .append((int) Math.ceil(item.delaySeconds / 60.0)).append(" min");
            timeline.append("\n\n");
            if (++count >= 5) break;
        }
        if (count == 0) timeline.append("No route-positioned incidents reported.");
        new AlertDialog.Builder(a).setTitle("Upcoming traffic · TomTom")
                .setMessage(timeline.toString()
                        + "\nTraffic conditions can change. Follow road signs.")
                .setPositiveButton("Close", null).show();
    }
}
