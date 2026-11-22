package com.drivemate.app;

import java.util.Locale;

/** Distance/speed formatting kept independent of Android for repeatable tests. */
public final class NavigationUnits {
    private static final double METRES_PER_MILE = 1609.344;
    private static final double METRES_PER_YARD = 0.9144;
    private NavigationUnits() {}

    public static String displayDistance(double metres, boolean metric) {
        double safe = Math.max(0, metres);
        if (metric) {
            if (safe >= 1000) return String.format(Locale.UK, "%.1f km", safe / 1000);
            return Math.round(safe / 10.0) * 10 + " m";
        }
        if (safe >= 880) return String.format(Locale.UK, "%.1f mi", safe / METRES_PER_MILE);
        return Math.round(safe / METRES_PER_YARD / 10.0) * 10 + " yd";
    }

    public static String spokenDistance(double metres, boolean metric) {
        double safe = Math.max(0, metres);
        if (metric) {
            if (safe >= 1000) {
                return String.format(Locale.UK, "%.1f kilometres", safe / 1000.0);
            }
            return Math.max(10, Math.round(safe / 10.0) * 10) + " metres";
        }
        if (safe >= 880) {
            return String.format(Locale.UK, "%.1f miles", safe / METRES_PER_MILE);
        }
        return Math.max(10, Math.round(safe / METRES_PER_YARD / 10.0) * 10)
                + " yards";
    }

    public static String displaySpeed(double metresPerSecond, boolean metric) {
        double factor = metric ? 3.6 : 2.2369362921;
        return String.format(Locale.UK, "%.0f %s", Math.max(0, metresPerSecond) * factor,
                metric ? "km/h" : "mph");
    }

    public static String duration(long seconds) {
        long minutes = Math.max(1, (Math.max(0, seconds) + 59) / 60);
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours + " hr" + (remainder > 0 ? " " + remainder + " min" : "");
    }
}
