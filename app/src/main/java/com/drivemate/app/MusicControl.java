package com.drivemate.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controls whatever music app is playing (Spotify, YouTube Music, podcasts) through
 * Android media sessions: track, artist, play/pause, next, previous. Needs the
 * driver to grant notification access once; without it everything stays hidden.
 */
final class MusicControl {
    private final Context app;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController current;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { main.post(changed); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { main.post(changed); }
        @Override public void onSessionDestroyed() { main.post(() -> pick(null)); }
    };

    MusicControl(Context context, Runnable changed) {
        app = context.getApplicationContext();
        this.changed = changed;
        start();
    }

    static boolean accessGranted(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(c.getPackageName() + "/");
    }

    /** Opens Android's notification-access screen so the driver can allow music control. */
    static Intent accessIntent() { return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); }

    /** (Re)connect: call after the driver returns from the access screen. */
    void start() {
        if (!accessGranted(app) || sessionsListener != null) { main.post(changed); return; }
        try {
            sessions = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName listener = new ComponentName(app, MusicListenerService.class);
            sessionsListener = this::pick;
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, main);
            pick(sessions.getActiveSessions(listener));
        } catch (SecurityException | NullPointerException noAccess) {
            sessionsListener = null;
            main.post(changed);
        }
    }

    private void pick(List<MediaController> list) {
        if (list == null) {
            try { list = sessions == null ? null : sessions.getActiveSessions(new ComponentName(app, MusicListenerService.class)); }
            catch (SecurityException e) { list = null; }
        }
        List<MediaController> all = list == null ? Collections.emptyList() : list;
        List<String> packages = new ArrayList<>();
        List<Boolean> playing = new ArrayList<>();
        for (MediaController c : all) {
            packages.add(c.getPackageName());
            PlaybackState s = c.getPlaybackState();
            playing.add(s != null && s.getState() == PlaybackState.STATE_PLAYING);
        }
        int index = MusicPolicy.choose(packages, playing);
        MediaController next = index < 0 ? null : all.get(index);
        if (current != null && (next == null || !next.getSessionToken().equals(current.getSessionToken())))
            current.unregisterCallback(callback);
        if (next != null && (current == null || !next.getSessionToken().equals(current.getSessionToken())))
            next.registerCallback(callback, main);
        current = next;
        main.post(changed);
    }

    boolean hasSession() { return current != null; }

    boolean playing() {
        PlaybackState s = current == null ? null : current.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    String line() {
        MediaMetadata m = current == null ? null : current.getMetadata();
        if (m == null) return "";
        return MusicPolicy.line(m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_ARTIST));
    }

    String appName() {
        if (current == null) return "";
        try {
            return app.getPackageManager().getApplicationLabel(
                    app.getPackageManager().getApplicationInfo(current.getPackageName(), 0)).toString();
        } catch (Exception e) { return ""; }
    }

    void playPause() {
        if (current == null) return;
        if (playing()) current.getTransportControls().pause(); else current.getTransportControls().play();
    }

    void next() { if (current != null) current.getTransportControls().skipToNext(); }

    void previous() { if (current != null) current.getTransportControls().skipToPrevious(); }

    /** Launch Spotify when nothing is playing; false when it is not installed. */
    boolean openSpotify(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(MusicPolicy.SPOTIFY);
        if (i == null) return false;
        c.startActivity(i);
        return true;
    }

    void release() {
        if (current != null) current.unregisterCallback(callback);
        current = null;
        if (sessions != null && sessionsListener != null) sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        sessionsListener = null;
    }
}
