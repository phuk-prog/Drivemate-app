package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceRoutingTest {
    @Test public void labelsAndClamp() {
        assertEquals("Automatic", VoiceRouting.label(VoiceRouting.AUTOMATIC));
        assertEquals("Phone speaker", VoiceRouting.label(VoiceRouting.PHONE_SPEAKER));
        assertEquals("Automatic", VoiceRouting.label(9));
        assertEquals(VoiceRouting.NAMES.length, VoiceRouting.HELP.length);
    }

    @Test public void choiceIsRememberedPerCar() {
        assertEquals("voice_output", VoiceRouting.prefKey(""));
        assertEquals("voice_output", VoiceRouting.prefKey(null));
        assertEquals("voice_output@vw_car_audio", VoiceRouting.prefKey("VW Car-Audio"));
        assertNotEquals(VoiceRouting.prefKey("Ford SYNC"), VoiceRouting.prefKey("VW Car-Audio"));
    }

    @Test public void pauseOrLowerMusic() {
        assertEquals(2, VoiceRouting.focusGain(true));   // AUDIOFOCUS_GAIN_TRANSIENT
        assertEquals(3, VoiceRouting.focusGain(false));  // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    }
}
