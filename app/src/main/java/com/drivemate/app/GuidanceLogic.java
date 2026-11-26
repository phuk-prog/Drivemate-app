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
        // TomTom's verified BIFURCATION means keep left/right at a fork,
        // not a sharp turn. Do not use a turn icon for this junction type.
        if ("BIFURCATION".equals(j) || "FORK".equals(j) || "Y_JUNCTION".equals(j)) {
            // Explicit direction is authoritative; the geometric angle is only a fallback.
            if (m.contains("LEFT")) return "↖";
            if (m.contains("RIGHT")) return "↗";
            if ("STRAIGHT".equals(m) || "CONTINUE".equals(m) || "GO_STRAIGHT".equals(m)) return "↑";
            return angle < 0 ? "↖" : angle > 0 ? "↗" : "↑";
        }
        if (m.contains("UTURN") || m.contains("U_TURN")) return m.contains("RIGHT") ? "⤻" : "⤺";
        if ("KEEP_LEFT".equals(m)||"BEAR_LEFT".equals(m)||"SLIGHT_LEFT".equals(m)||"MOTORWAY_EXIT_LEFT".equals(m)) return "↖";
        if ("KEEP_RIGHT".equals(m)||"BEAR_RIGHT".equals(m)||"SLIGHT_RIGHT".equals(m)||"MOTORWAY_EXIT_RIGHT".equals(m)) return "↗";
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

    /**
     * Roundabout instruction without an exit count. TomTom's exit numbering
     * can differ from the exits a driver counts (slip roads, accesses), so the
     * exit road and the drawn roundabout lead instead. Without a road name the
     * TomTom message is the only information, so it is kept.
     */
    public static String roundaboutLine(String street, String fallbackMessage) {
        String road = street == null ? "" : street.trim();
        if (!road.isEmpty()) return "At the roundabout, take the exit onto " + road;
        return fallbackMessage == null ? "" : fallbackMessage.trim();
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
        if (lastMetres < 0) return Math.max(0, measuredMetres);
        // Do not advance on a position outside the drivable route or a
        // poor-quality fix. Wait for accurate GPS or a new route.
        if (offRouteMetres > 80 || accuracyMetres > 55) return lastMetres;
        return Math.max(lastMetres - 8, measuredMetres);
    }

    public static boolean announce(int mode, double metresToTurn, int tier) {
        // Tier 0: early ~1000m, tier 1: approach ~250m, tier 2: turn ~65m
        if (mode == 2) return tier == 2 && metresToTurn <= 100;
        if (mode == 1) return tier >= 1; // normal
        return true; // detailed
    }
}
