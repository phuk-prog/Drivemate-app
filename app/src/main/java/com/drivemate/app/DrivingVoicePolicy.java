package com.drivemate.app;

/** Consistent separation of route speech, system warnings and verified alerts. */
public final class DrivingVoicePolicy {
    private DrivingVoicePolicy() { }

    public static final int FULL = 0;
    public static final int NORMAL = 1;
    public static final int ALERTS_ONLY = 2;
    public static final int SILENT = 3;

    public static boolean turn(int mode, int distanceTier) {
        if (mode == FULL) return true;
        if (mode == NORMAL) return distanceTier >= 1;
        return false;
    }

    /** GPS/network warnings are factual status alerts, not guessed road hazards. */
    public static boolean systemWarning(int mode) {
        return mode != SILENT;
    }

    public static boolean verifiedAlert(int mode, boolean categoryEnabled) {
        return mode != SILENT && categoryEnabled;
    }

    public static int fromLegacy(int oldFrequency, boolean wasEnabled) {
        if (!wasEnabled) return SILENT;
        return oldFrequency == 0 ? FULL : NORMAL;
    }
}
