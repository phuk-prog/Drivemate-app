package com.drivemate.app;

import java.util.Locale;

/** Speech cadence by selectable detail level; call per GPS update. */
public final class VoiceCuePlanner {
    public static final String MINIMAL = "minimal";
    public static final String NORMAL = "normal";
    public static final String DETAILED = "detailed";

    private VoiceCuePlanner() {}

    public static int stage(String mode, double distanceMeters, float speedMps) {
        double d = Math.max(0, distanceMeters);
        double now = Math.max(40, Math.min(100, speedMps * 4.0));
        double close = Math.max(170, Math.min(420, speedMps * 14));
        double early = Math.max(650, Math.min(1300, speedMps * 35));
        if (d <= now) return 3;
        if (MINIMAL.equals(mode)) return 0;
        if (d <= close) return 2;
        if (DETAILED.equals(mode) && d <= early) return 1;
        return 0;
    }

    public static String announce(String mode, int stage, double distanceMeters,
                                  boolean metric, TomTomApi.Instruction step,
                                  TomTomApi.Instruction following, double followingDistance) {
        String message = step == null ? "Continue" : step.message;
        if (message.isEmpty()) message = ManeuverGuide.title(step);
        String cue = stage >= 3 ? message
                : "In " + NavigationUnits.spokenDistance(distanceMeters, metric) + ", " + message;
        if (DETAILED.equals(mode) && stage >= 2 && following != null
                && followingDistance < 270 && followingDistance > 0) {
            cue += ". Then " + following.message;
        }
        return cue;
    }

    public static String normalizeMode(String mode) {
        if (MINIMAL.equals(mode) || DETAILED.equals(mode)) return mode;
        return NORMAL;
    }
}
