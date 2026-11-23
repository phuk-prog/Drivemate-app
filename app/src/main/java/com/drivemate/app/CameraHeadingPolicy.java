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
}
