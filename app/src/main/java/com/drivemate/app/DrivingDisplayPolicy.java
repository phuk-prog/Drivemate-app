package com.drivemate.app;

/** Small, device-independent decisions for the live driving display. */
public final class DrivingDisplayPolicy {
    private DrivingDisplayPolicy() { }

    public static final int CAMERA_AUTO_3D = 0;
    public static final int CAMERA_HEADING_2D = 1;
    public static final int CAMERA_NORTH_2D = 2;
    public static final int THEME_AUTO = 0;
    public static final int THEME_DAY = 1;
    public static final int THEME_NIGHT = 2;

    /** Only advertise a second manoeuvre when it follows the first closely. */
    public static boolean showFollowingTurn(double metresToFirst, double gapMetres) {
        return Double.isFinite(metresToFirst) && Double.isFinite(gapMetres)
                && metresToFirst >= 0 && metresToFirst <= 900
                && gapMetres >= 0 && gapMetres <= 450;
    }

    public static boolean northUp(int cameraMode) {
        return cameraMode == CAMERA_NORTH_2D;
    }

    public static double tilt(double routeTilt, int cameraMode) {
        return cameraMode == CAMERA_AUTO_3D ? routeTilt : 0;
    }

    public static boolean night(int preference, boolean systemNight) {
        if (preference == THEME_DAY) return false;
        if (preference == THEME_NIGHT) return true;
        return systemNight;
    }

    public static boolean junctionPreview(boolean complexJunction, double metresToTurn) {
        return complexJunction && Double.isFinite(metresToTurn)
                && metresToTurn >= 0 && metresToTurn <= 520;
    }
}
