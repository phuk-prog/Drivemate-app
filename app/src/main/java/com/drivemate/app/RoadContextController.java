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
final class RoadContextController {
    final MainActivity a;

    RoadContextController(MainActivity activity) { this.a = activity; }

    void clearLiveIncidents(){
        a.incidentGeneration++;a.lastIncidentRequest=0;a.lastIncidentCentre=null;
        if(a.vectorMap!=null)a.vectorMap.setLiveIncidents(null,0);
    }

    void updateLiveIncidents(Location fix){
        if(!a.foreground||!a.active||a.route==null||!"TomTom".equals(a.route.provider)||!a.trafficEnabled
                ||a.apiKey.isEmpty()||a.gpsLost||!a.showingVector()||!a.vectorMap.supportsLiveIncidents())return;
        if(!fix.hasAccuracy()||fix.getAccuracy()>50||a.incidentBusy)return;
        long now=SystemClock.elapsedRealtime();
        GeoMath.Point p=a.gpsPoint(fix);
        if(a.lastIncidentRequest!=0 && (now-a.lastIncidentRequest<45000 ||
                now-a.lastIncidentRequest<120000 && a.lastIncidentCentre!=null && GeoMath.distance(p,a.lastIncidentCentre)<1000))return;
        a.lastIncidentRequest=now;a.lastIncidentCentre=p;a.incidentBusy=true;
        final int generation=a.incidentGeneration;
        final String key=a.apiKey;
        a.incidentWorker.execute(()->{
            List<LiveTrafficIncidents.Event> data;
            try{data=TomTomApi.liveIncidents(key,p);}catch(Exception ignored){data=null;}
            final List<LiveTrafficIncidents.Event> result=data;
            final long fetched=System.currentTimeMillis();
            a.runOnUiThread(()->{
                a.incidentBusy=false;
                if(generation!=a.incidentGeneration||!a.foreground||!a.active||a.route==null||!"TomTom".equals(a.route.provider)
                    ||!a.trafficEnabled||!key.equals(a.apiKey)||a.vectorMap==null||!a.vectorMap.supportsLiveIncidents()||a.gpsLost)return;
                if(a.currentLocation==null||GeoMath.distance(p,a.gpsPoint(a.currentLocation))>2000)return;
                if(result!=null)a.vectorMap.setLiveIncidents(result,fetched);
                // On failure the last successful snapshot expires independently; never imply clear roads.
            });
        });
    }

    void clearFreeRoadContext(){
        a.freeContextGeneration++;a.freeContextBusy=false;a.freeContextRequestedAt=0;a.freeRoadContext=null;
        a.freeRoadMatch.reset();a.freeLastLimit=-1;a.freeLastLimitAt=0;a.freeAdvanceNode=-1;a.freeContextEndpoint="";
        if(a.vectorMap!=null)a.vectorMap.setBrowseGeometry(null);
        if(a.speedLimitText!=null)a.speedLimitText.setVisibility(View.GONE);
    }

    void updateFreeRoadIntelligence(Location fix){
        GeoMath.Point point=a.gpsPoint(fix);
        final String endpoint=a.ownEndpoint();
        if(a.freeRoadContext!=null && (!endpoint.equals(a.freeContextEndpoint)||!a.freeRoadContext.freshAt(point))){
            a.freeRoadContext=null;a.freeRoadMatch.reset();a.freeLastLimit=-1;
            if(a.vectorMap!=null)a.vectorMap.setBrowseGeometry(null);
        }
        if(endpoint.isEmpty()){a.freeRoadContext=null;a.freeRoadMatch.reset();a.freeLastLimit=-1;if(a.vectorMap!=null)a.vectorMap.setBrowseGeometry(null);}
        long now=SystemClock.elapsedRealtime();
        if(a.freeRoadContext==null&&!a.freeContextBusy&&!endpoint.isEmpty()
                &&(a.freeContextRequestedAt==0||now-a.freeContextRequestedAt>=15000)){
            a.freeContextBusy=true;a.freeContextRequestedAt=now;
            final int generation=a.freeContextGeneration;
            a.network.execute(()->{
                DriveMateProvider.RoadContext result;
                try{result=DriveMateProvider.roadContext(endpoint,point);}catch(Exception e){result=null;}
                final DriveMateProvider.RoadContext context=result;
                a.runOnUiThread(()->{
                    if(generation!=a.freeContextGeneration||!a.freeDriving||a.active)return;
                    a.freeContextBusy=false;
                    if(endpoint.equals(a.ownEndpoint())&&a.currentLocation!=null&&context!=null&&context.freshAt(a.gpsPoint(a.currentLocation))){
                        a.freeRoadContext=context;a.freeContextEndpoint=endpoint;
                        if(a.vectorMap!=null&&a.followLocation)a.vectorMap.setBrowseGeometry(context.map);
                    }
                });
            });
        }
        FreeDriveRoadMatch.Match match=null;
        if(a.freeRoadContext!=null&&!a.gpsLost&&fix.hasBearing()&&fix.hasSpeed()&&fix.hasAccuracy()
                &&(!fix.hasBearingAccuracy()||fix.getBearingAccuracyDegrees()<=20)){
            try{match=a.freeRoadMatch.confirm(FreeDriveRoadMatch.match(a.freeRoadContext.ways,point,fix.getAccuracy(),fix.getBearing(),fix.getSpeed()),fix.getTime());}
            catch(Exception ignored){a.freeRoadMatch.reset();}
        }else a.freeRoadMatch.reset();
        if(match==null){
            a.speedLimitText.setVisibility(View.GONE);a.speedText.setTextColor(Color.WHITE);
            if(now-a.freeLastLimitAt>10000)a.freeLastLimit=-1;
            return;
        }
        int shown=a.metricUnits?match.kmh:(int)Math.round(match.kmh/1.609344);
        a.speedLimitText.setText(String.valueOf(shown));a.speedLimitText.setVisibility(View.VISIBLE);
        a.speedLimitText.setContentDescription("Mapped speed limit "+shown+(a.metricUnits?" km/h":" mph")+". Check road signs.");
        String status="Free driving · mapped limit "+shown+(a.metricUnits?" km/h":" mph")+(match.name.isEmpty()?"":" · "+match.name);
        FreeDriveRoadMatch.Upcoming next=null;
        try{next=FreeDriveRoadMatch.upcoming(a.freeRoadContext.ways,match,point,fix.getSpeed(),a.freeRoadContext.bounds);}catch(Exception ignored){}
        if(next!=null){
            int future=a.metricUnits?next.kmh:(int)Math.round(next.kmh/1.609344);
            status+=" · "+future+(a.metricUnits?" km/h":" mph")+" in "+NavigationUnits.displayDistance(next.distance,a.metricUnits)+" if continuing";
            if(a.advanceSpeedVoice&&a.voiceMode!=DrivingVoicePolicy.SILENT&&a.speechReady&&a.speech!=null&&!a.speech.isSpeaking()&&a.freeAdvanceNode!=next.node){
                a.freeAdvanceNode=next.node;
                a.speak("If continuing on this road, mapped limit "+future+(a.metricUnits?" kilometres per hour in ":" miles per hour in ")
                    +NavigationUnits.spokenDistance(next.distance,a.metricUnits)+". Check road signs.");
            }
        }
        a.setStatus(status);
        boolean speeding=fix.getSpeed()*2.2369362921>match.kmh/1.609344+a.speedToleranceMph;
        a.speedText.setTextColor(speeding?Color.rgb(255,197,85):Color.WHITE);
        if(speeding&&a.speedWarningEnabled&&a.speechReady&&a.speech!=null&&!a.speech.isSpeaking()
            &&(a.lastSpeedWarningAt==0||now-a.lastSpeedWarningAt>120000)){
            a.lastSpeedWarningAt=now;a.sayVerifiedAlert("Check your speed. You may be above the mapped speed limit.",true);
        }
        if(a.freeLastLimit>0&&a.freeLastLimit!=match.kmh&&a.advanceSpeedVoice&&a.voiceMode!=DrivingVoicePolicy.SILENT
                &&a.speechReady&&a.speech!=null&&!a.speech.isSpeaking())
            a.speak("Mapped speed limit now "+shown+(a.metricUnits?" kilometres per hour.":" miles per hour.")+" Check road signs.");
        a.freeLastLimit=match.kmh;a.freeLastLimitAt=now;
    }
}
