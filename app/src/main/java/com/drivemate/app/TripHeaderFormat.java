package com.drivemate.app;

import java.util.Locale;

/** Compact, readable driving-summary values, independent of the Android UI. */
public final class TripHeaderFormat {
    private TripHeaderFormat() { }

    public static String minutesLeft(long remainingSeconds) {
        long seconds = Math.max(0, remainingSeconds);
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " hr" + (minutes % 60 > 0 ? " " + minutes % 60 + " min" : "");
    }

    public static String distanceLeft(double metres, boolean metric) {
        double positive = Math.max(0, metres);
        if (metric) {
            double km = positive / 1000.0;
            return km < 10 ? String.format(Locale.UK, "%.1f km", km)
                    : String.format(Locale.UK, "%.0f km", km);
        }
        double miles = positive / 1609.344;
        if (miles > 0 && miles < 0.1) return "<0.1 mi";
        return miles < 100 ? String.format(Locale.UK, "%.1f mi", miles)
                : String.format(Locale.UK, "%.0f mi", miles);
    }
}
