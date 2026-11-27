package com.drivemate.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Plays navigation phrases on a chosen output (phone speaker, Bluetooth media or the
 * Bluetooth hands-free call channel). Android's text-to-speech cannot be pointed at a
 * device, so each phrase is synthesised to a short file and played here with the
 * preferred-device routing of MediaPlayer. Automatic mode is not handled here: the
 * activity speaks directly as before.
 */
final class VoiceOutput {
    interface Listener { void finished(); }

    private final Context app;
    private final AudioManager audio;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<File> ready = new ArrayDeque<>();
    private final Map<String, Integer> pending = new HashMap<>();
    private final File dir;
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private int mode = VoiceRouting.AUTOMATIC;
    private float volume = 1f;
    private int generation, counter;
    private boolean callLinkOpen;

    VoiceOutput(Context context, Listener listener) {
        app = context.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
        dir = new File(app.getCacheDir(), "voice");
        if (!dir.isDirectory()) dir.mkdirs();
        tts = new TextToSpeech(app, status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) return;
            int lang = tts.setLanguage(Locale.UK);
            ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(() -> synthesised(id, true)); }
                @Override public void onError(String id) { main.post(() -> synthesised(id, false)); }
            });
        });
    }

    void setMode(int value) { mode = VoiceRouting.clamp(value); }
    int mode() { return mode; }

    /** True when this class plays the voice (a non-automatic output that this phone supports). */
    boolean routed() { return mode != VoiceRouting.AUTOMATIC && ttsReady && Build.VERSION.SDK_INT >= 28; }

    boolean busy() { return player != null || !ready.isEmpty() || !pending.isEmpty(); }

    /** @param flush interrupt whatever is playing (a more urgent phrase) */
    void say(String text, boolean flush, float vol) {
        if (tts == null || text == null || text.trim().isEmpty()) { listener.finished(); return; }
        volume = Math.max(0f, Math.min(1f, vol));
        if (flush) stop();
        String id = "dm-voice-" + (++counter);
        File out = new File(dir, id + ".wav");
        pending.put(id, generation);
        Bundle params = new Bundle();
        if (tts.synthesizeToFile(text, params, out, id) != TextToSpeech.SUCCESS) {
            pending.remove(id);
            listener.finished();
        }
    }

    private void synthesised(String id, boolean ok) {
        Integer gen = pending.remove(id);
        File f = new File(dir, id + ".wav");
        if (gen == null || gen != generation || !ok || !f.isFile()) {
            f.delete();
            if (gen != null && gen == generation) listener.finished();
            return;
        }
        ready.add(f);
        if (player == null) playNext();
    }

    private void playNext() {
        File f = ready.poll();
        if (f == null) { closeCallLink(); return; }
        if (mode == VoiceRouting.BLUETOOTH_CALL && !callLinkOpen && openCallLink()) {
            int gen = generation;
            main.postDelayed(() -> { if (gen == generation) start(f); else f.delete(); }, VoiceRouting.CALL_LINK_DELAY_MS);
            return;
        }
        start(f);
    }

    private void start(File f) {
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            boolean call = mode == VoiceRouting.BLUETOOTH_CALL && callLinkOpen;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(call ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                            : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(f.getPath());
            mp.prepare();
            mp.setVolume(volume, volume);
            AudioDeviceInfo device = call ? null : outputDevice(mode);
            if (device != null && Build.VERSION.SDK_INT >= 28) mp.setPreferredDevice(device);
            mp.setOnCompletionListener(done -> finish(mp, f));
            mp.setOnErrorListener((bad, what, extra) -> { finish(mp, f); return true; });
            mp.start();
        } catch (Exception e) {
            finish(mp, f);
        }
    }

    private void finish(MediaPlayer mp, File f) {
        try { mp.release(); } catch (Exception ignored) { }
        f.delete();
        if (player == mp) player = null;
        listener.finished();
        if (player == null) playNext();
    }

    /** Stop now and drop anything queued (used for a more urgent phrase or when guidance ends). */
    void stop() {
        generation++;
        pending.clear();
        for (File f : ready) f.delete();
        ready.clear();
        MediaPlayer mp = player;
        player = null;
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) { } try { mp.release(); } catch (Exception ignored) { } }
        closeCallLink();
    }

    void shutdown() {
        stop();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
    }

    private AudioDeviceInfo outputDevice(int m) {
        int type = m == VoiceRouting.PHONE_SPEAKER ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                : m == VoiceRouting.BLUETOOTH_MEDIA ? AudioDeviceInfo.TYPE_BLUETOOTH_A2DP : -1;
        if (type < 0 || audio == null) return null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (d.getType() == type) return d;
        return null;
    }

    /** Name of the connected Bluetooth car audio, or "" (used to remember the choice per car). */
    String carName() {
        if (audio == null) return "";
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                CharSequence n = d.getProductName();
                return n == null ? "" : n.toString();
            }
        return "";
    }

    private boolean openCallLink() {
        if (Build.VERSION.SDK_INT < 31 || audio == null) return false;
        for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices())
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                callLinkOpen = audio.setCommunicationDevice(d);
                return callLinkOpen;
            }
        return false;
    }

    private void closeCallLink() {
        if (!callLinkOpen) return;
        callLinkOpen = false;
        if (Build.VERSION.SDK_INT >= 31 && audio != null) audio.clearCommunicationDevice();
    }
}
