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

    /** Below this the turn card says "now". */
    static final double NOW_METRES = 15;

    /**
     * Turn-card distance in the steps UK drivers know: quarter miles (¼, ½, ¾ mi),
     * then 50-yard steps from 100 yd, 10-yard steps below, then "now".
     */
    public static String turnDistance(double metres, boolean metric) {
        double safe = Double.isFinite(metres) ? Math.max(0, metres) : 0;
        if (safe < NOW_METRES) return "now";
        if (metric) {
            if (safe >= 1000) return String.format(Locale.UK, "%.1f km", safe / 1000);
            if (safe >= 100) return Math.round(safe / 50.0) * 50 + " m";
            return Math.round(safe / 10.0) * 10 + " m";
        }
        double miles = safe / METRES_PER_MILE;
        if (miles >= 0.95) return String.format(Locale.UK, "%.1f mi", miles);
        double yards = safe / METRES_PER_YARD;
        if (yards >= 400) {
            long quarters = Math.round(miles * 4);
            if (quarters >= 4) return "1.0 mi";
            return (quarters == 3 ? "¾" : quarters == 2 ? "½" : "¼") + " mi";
        }
        if (yards >= 100) return Math.round(yards / 50.0) * 50 + " yd";
        return Math.round(yards / 10.0) * 10 + " yd";
    }

    /** "1 kilometre", "2 kilometres", "1.5 kilometres": never "1.0 kilometres". */
    static String spokenUnits(double value, String singular, String plural) {
        double rounded = Math.round(value * 10.0) / 10.0;
        if (rounded == Math.rint(rounded)) {
            long whole = (long) rounded;
            return whole + " " + (whole == 1 ? singular : plural);
        }
        return String.format(Locale.UK, "%.1f %s", rounded, plural);
    }

    public static String spokenDistance(double metres, boolean metric) {
        double safe = Math.max(0, metres);
        if (metric) {
            if (safe >= 1000) {
                return spokenUnits(safe / 1000.0, "kilometre", "kilometres");
            }
            return Math.max(10, Math.round(safe / 10.0) * 10) + " metres";
        }
        if (safe >= 880) {
            return spokenUnits(safe / METRES_PER_MILE, "mile", "miles");
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
