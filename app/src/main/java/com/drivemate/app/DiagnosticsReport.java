package com.drivemate.app;

import java.util.Locale;

/**
 * A plain-text snapshot the driver can choose to share when something looks
 * wrong on screen (for example the car arrow pointing the wrong way).
 *
 * It deliberately contains no coordinates, addresses or destination names:
 * only accuracy, source, speed and the angles the camera and arrow use.
 * Pure Java so the format and the privacy rule can be unit tested.
 */
public final class DiagnosticsReport {
    private DiagnosticsReport() { }

    public static final class Snapshot {
        public String appVersion = "";
        public int androidSdk;
        public String device = "";
        public boolean navigating;
        public boolean gpsLost;
        public String fixSource = "";
        public String matchReason="";
        public long fixAgeMs=-1;
        public float accuracyMetres = Float.NaN;
        public int satellitesUsed = -1;
        public int l5SatellitesUsed = -1;
        public String fixRejections = "";
        public int fixesAccepted = -1;
        public String positionBridges = "";
        public int mockFixes = -1;
        public boolean estimatedPosition;
        public double headingFusion = Double.NaN;
        public float speedMps = Float.NaN;
        public double offRouteMetres = Double.NaN;
        public String cameraMode = "";
        public double cameraBearing = Double.NaN;
        public double travelBearing = Double.NaN;
        public double gpsBearing = Double.NaN;
        public boolean followingCar;
        public boolean highDetailTiles;
    }

    public static String build(Snapshot s) {
        StringBuilder out = new StringBuilder();
        out.append("DriveMate diagnostics (no location or destination included)\n");
        line(out, "App version", s.appVersion);
        line(out, "Android SDK", String.valueOf(s.androidSdk));
        line(out, "Device", s.device);
        line(out, "Navigating", yesNo(s.navigating));
        line(out, "GPS lost", yesNo(s.gpsLost));
        line(out, "Fix source", s.fixSource.isEmpty() ? "—" : s.fixSource);
        line(out,"Fix age",s.fixAgeMs<0?"—":s.fixAgeMs+" ms");
        line(out,"Route match",s.matchReason);
        line(out, "Accuracy", metres(s.accuracyMetres));
        line(out, "Satellites used", s.satellitesUsed < 0 ? "—" : String.valueOf(s.satellitesUsed));
        line(out, "Dual-frequency (L5) satellites used", s.l5SatellitesUsed < 0 ? "—" : String.valueOf(s.l5SatellitesUsed));
        line(out, "Fixes accepted", s.fixesAccepted < 0 ? "—" : String.valueOf(s.fixesAccepted));
        line(out, "Fixes rejected", s.fixRejections);
        line(out, "Estimated-position bridges", s.positionBridges);
        line(out, "Estimated position now", yesNo(s.estimatedPosition));
        line(out, "Mock location fixes", s.mockFixes < 0 ? "—" : String.valueOf(s.mockFixes));
        line(out, "Sensor-fused heading", degrees(s.headingFusion));
        line(out, "Speed", Float.isFinite(s.speedMps)
                ? String.format(Locale.UK, "%.1f m/s", s.speedMps) : "—");
        line(out, "Off route", metres(s.offRouteMetres));
        line(out, "Camera mode", s.cameraMode);
        line(out, "Following car", yesNo(s.followingCar));
        line(out, "High-detail tiles", s.highDetailTiles ? "on" : "off");
        line(out, "Camera bearing", degrees(s.cameraBearing));
        line(out, "Route travel bearing", degrees(s.travelBearing));
        line(out, "GPS bearing", degrees(s.gpsBearing));
        line(out, "Arrow vs camera", arrowOffset(s.travelBearing, s.cameraBearing));
        return out.toString();
    }

    /**
     * Signed difference the on-screen arrow shows relative to straight up:
     * 0° means the arrow points up the screen.
     */
    static String arrowOffset(double travel, double camera) {
        if (!Double.isFinite(travel) || !Double.isFinite(camera)) return "—";
        double d = ((travel - camera) % 360 + 540) % 360 - 180;
        return String.format(Locale.UK, "%+.0f°", d);
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(label).append(": ").append(value == null || value.isEmpty() ? "—" : value)
                .append('\n');
    }

    private static String yesNo(boolean value) { return value ? "yes" : "no"; }

    private static String metres(double value) {
        return Double.isFinite(value) ? String.format(Locale.UK, "%.0f m", value) : "—";
    }

    private static String degrees(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.UK, "%.0f°", ((value % 360) + 360) % 360) : "—";
    }
}
