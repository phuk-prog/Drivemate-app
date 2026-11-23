package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingVoicePolicyTest {
    @Test public void fullAndNormalAnnounceDifferentTiers() {
        for (int tier = 0; tier < 3; tier++)
            assertTrue(DrivingVoicePolicy.turn(DrivingVoicePolicy.FULL, tier));
        assertFalse(DrivingVoicePolicy.turn(DrivingVoicePolicy.NORMAL, 0));
        assertTrue(DrivingVoicePolicy.turn(DrivingVoicePolicy.NORMAL, 1));
        assertTrue(DrivingVoicePolicy.turn(DrivingVoicePolicy.NORMAL, 2));
        assertFalse(DrivingVoicePolicy.turn(DrivingVoicePolicy.ALERTS_ONLY, 2));
        assertFalse(DrivingVoicePolicy.turn(DrivingVoicePolicy.SILENT, 2));
    }

    @Test public void alertsOnlyKeepsWarningsButSilentDoesNot() {
        assertTrue(DrivingVoicePolicy.systemWarning(DrivingVoicePolicy.ALERTS_ONLY));
        assertFalse(DrivingVoicePolicy.systemWarning(DrivingVoicePolicy.SILENT));
        assertTrue(DrivingVoicePolicy.verifiedAlert(DrivingVoicePolicy.ALERTS_ONLY, true));
        assertFalse(DrivingVoicePolicy.verifiedAlert(DrivingVoicePolicy.ALERTS_ONLY, false));
        assertFalse(DrivingVoicePolicy.verifiedAlert(DrivingVoicePolicy.SILENT, true));
    }

    @Test public void existingUserSettingsMigrateWithoutInventingSpeech() {
        assertEquals(DrivingVoicePolicy.FULL, DrivingVoicePolicy.fromLegacy(0, true));
        assertEquals(DrivingVoicePolicy.NORMAL, DrivingVoicePolicy.fromLegacy(1, true));
        assertEquals(DrivingVoicePolicy.NORMAL, DrivingVoicePolicy.fromLegacy(2, true));
        assertEquals(DrivingVoicePolicy.SILENT, DrivingVoicePolicy.fromLegacy(0, false));
    }
}
