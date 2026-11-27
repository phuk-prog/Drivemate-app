package com.drivemate.app;

import java.util.Locale;

/**
 * Where the navigation voice plays. Pure Java: names, preference keys and the
 * per-car memory, so the choice is unit tested without Android audio.
 */
final class VoiceRouting {
    private VoiceRouting() { }

    static final int AUTOMATIC = 0, PHONE_SPEAKER = 1, BLUETOOTH_MEDIA = 2, BLUETOOTH_CALL = 3;
    static final String[] NAMES = {"Automatic", "Phone speaker", "Bluetooth (media)", "Bluetooth (as a phone call)"};
    static final String[] HELP = {
            "Android decides: Bluetooth media when connected, otherwise the phone",
            "Always from the phone, even while music plays over Bluetooth",
            "Mixed into the music over Bluetooth; music lowers while it speaks",
            "Through the car's hands-free channel, heard even when the stereo is on radio"};

    static int clamp(int mode) { return mode < AUTOMATIC || mode > BLUETOOTH_CALL ? AUTOMATIC : mode; }

    static String label(int mode) { return NAMES[clamp(mode)]; }

    /** Preference key: one choice per car (Bluetooth device name), and a default when none is connected. */
    static String prefKey(String carName) {
        String n = carName == null ? "" : carName.trim().toLowerCase(Locale.UK).replaceAll("[^a-z0-9]+", "_");
        return n.isEmpty() ? "voice_output" : "voice_output@" + (n.length() > 40 ? n.substring(0, 40) : n);
    }

    /** The hands-free channel needs a moment to open before the first word, or it is clipped. */
    static final long CALL_LINK_DELAY_MS = 900;

    /** Android audio-focus gain: pause other audio, or let it lower its volume (duck). */
    static int focusGain(boolean pauseMusic) {
        return pauseMusic ? 2 /* AUDIOFOCUS_GAIN_TRANSIENT */ : 3 /* AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK */;
    }
}
