package com.drivemate.app;

import java.util.Locale;

/** Navigation display and ETA decisions. Pure Java to allow device-free tests. */
public final class GuidanceLogic {
    private GuidanceLogic() { }

    /** Manoeuvre icons must remain meaningful even when the API adds new codes. */
    public static String symbol(String maneuver, String junction, double angle) {
        String m = maneuver == null ? "" : maneuver.toUpperCase(Locale.US);
        String j = junction == null ? "" : junction.toUpperCase(Locale.US);
        if ("ROUNDABOUT".equals(j) || m.contains("ROUNDABOUT")) return "↻";
        if (m.contains("UTURN") || m.contains("U_TURN")) return "⤺";
        if (m.contains("ARRIVE") || m.contains("DESTINATION")) return "⚑";
        if (m.contains("LEFT") || (m.contains("TURN") && angle < -20)) return "↰";
        if (m.contains("RIGHT") || (m.contains("TURN") && angle > 20)) return "↱";
        if (angle <= -30) return "↰";
        if (angle >= 30) return "↱";
        return "↑";
    }

    public static String actionLine(String message, String junction, int exit) {
        String clean = message == null ? "" : message.trim();
        if (exit > 0 && "ROUNDABOUT".equalsIgnoreCase(junction)) {
            String label = ordinal(exit) + " exit";
            if (!clean.toLowerCase(Locale.UK).contains("exit")) {
                return "Roundabout: " + label + " · " + clean;
            }
        }
        return clean;
    }

    static String ordinal(int value) {
        int mod100 = value % 100;
        if (mod100 >= 11 && mod100 <= 13) return value + "th";
        switch (value % 10) {
            case 1: return value + "st";
            case 2: return value + "nd";
            case 3: return value + "rd";
            default: return value + "th";
        }
    }

    /** Avoid falling back to the first instruction or going backwards on GPS jitter. */
    public static int nextInstructionIndex(double[] offsets, double alongMetres, int previous) {
        if (offsets == null || offsets.length == 0) return -1;
        int candidate = offsets.length - 1;
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] >= alongMetres + 8) {
                candidate = i;
                break;
            }
        }
        // A new route resets previous to zero; normal GPS noise cannot jump back.
        return Math.max(candidate, Math.min(Math.max(0, previous), offsets.length - 1));
    }

    /**
     * Blends baseline TomTom traffic-aware route pace with a light, rolling
     * observed driving-speed influence (not a prediction of future traffic).
     */
    public static long remainingSeconds(double distanceRemaining, double lengthMetres,
                                        int originalSeconds, double speedMetresPerSecond) {
        if (distanceRemaining <= 0) return 0;
        double baselinePace = Math.max(2.5, lengthMetres / Math.max(1, originalSeconds));
        double effective = baselinePace;
        if (speedMetresPerSecond >= 3) {
            double observed = Math.max(baselinePace * 0.60,
                    Math.min(baselinePace * 1.45, speedMetresPerSecond));
            effective = 0.80 * baselinePace + 0.20 * observed;
        }
        return Math.max(1, Math.round(distanceRemaining / effective));
    }

    /** Progress smoothing is only for display and manoeuvre advancement. */
    public static double monotonicProgress(double lastMetres, double measuredMetres,
                                           double offRouteMetres, double accuracyMetres) {
        if (lastMetres < 0 || offRouteMetres > 80 || accuracyMetres > 55)
            return Math.max(0, measuredMetres);
        return Math.max(lastMetres - 8, measuredMetres);
    }

    public static boolean announce(int mode, double metresToTurn, int tier) {
        // Tier 0: early ~1000m, tier 1: approach ~250m, tier 2: turn ~65m
        if (mode == 2) return tier == 2 && metresToTurn <= 100;
        if (mode == 1) return tier >= 1; // normal
        return true; // detailed
    }
}
