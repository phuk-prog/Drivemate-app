package com.drivemate.app;

/**
 * The vehicle arrow represents travel direction in screen coordinates.
 * It is independent of the map-camera heading and works in both north-up
 * and direction-up modes. GPS/route decisions are not changed here.
 */
public final class CameraHeadingPolicy {
    private CameraHeadingPolicy() { }

    /** Clockwise angle of the vehicle relative to the top of the screen. */
    public static double screenDegrees(double travelBearing, double cameraBearing) {
        double delta = (travelBearing - cameraBearing + 540) % 360 - 180;
        return delta == -180 ? 180 : delta;
    }

    /** osmdroid's flat marker rotates opposite to its setRotation value. */
    public static float rasterMarkerRotation(double travelBearing, double cameraBearing) {
        return (float) -screenDegrees(travelBearing, cameraBearing);
    }

    /** Below this speed a GNSS bearing is noise; hold the previous heading. */
    public static final float MIN_BEARING_SPEED_MPS = 1.5f;
    /** Widest bearing uncertainty still trusted, in degrees. */
    public static final float MAX_BEARING_ERROR_DEGREES = 30f;

    /**
     * Whether a fix's bearing can steer the vehicle arrow or dead reckoning.
     * Needs a bearing, a speed above walking pace, and a bearing accuracy
     * (when the device reports one) of at most {@link #MAX_BEARING_ERROR_DEGREES}.
     *
     * @param bearingAccuracyDegrees NaN or negative when the device gives none
     */
    public static boolean usableBearing(boolean hasBearing, boolean hasSpeed, float speedMps,
                                        float bearingAccuracyDegrees) {
        if (!hasBearing || !hasSpeed || !(speedMps > MIN_BEARING_SPEED_MPS)) return false;
        boolean reported = Float.isFinite(bearingAccuracyDegrees) && bearingAccuracyDegrees >= 0;
        return !reported || bearingAccuracyDegrees <= MAX_BEARING_ERROR_DEGREES;
    }
}
