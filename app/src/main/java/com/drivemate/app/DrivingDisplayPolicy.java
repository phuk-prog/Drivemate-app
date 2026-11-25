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
    /** New installs start with the light map; the driver can pick Dark or Automatic. */
    public static final int DEFAULT_THEME = THEME_DAY;
    /** Settings menu order: Light, Dark, Automatic. Stored values are unchanged. */
    public static final int[] THEME_MENU = {THEME_DAY, THEME_NIGHT, THEME_AUTO};

    /** Name shown in Settings for a stored theme value. */
    public static String themeName(int preference) {
        if (preference == THEME_NIGHT) return "Dark";
        if (preference == THEME_AUTO) return "Automatic";
        return "Light";
    }

    /** Clamp a stored value; anything unknown falls back to the light default. */
    public static int validTheme(int stored) {
        return stored == THEME_AUTO || stored == THEME_DAY || stored == THEME_NIGHT
                ? stored : DEFAULT_THEME;
    }

    /** Only advertise a second manoeuvre when it follows the first closely. */
    public static boolean showFollowingTurn(double metresToFirst, double gapMetres) {
        return Double.isFinite(metresToFirst) && Double.isFinite(gapMetres)
                && metresToFirst >= 0 && metresToFirst <= 900
                && gapMetres >= 0 && gapMetres <= 450;
    }

    /** Speed-adaptive horizon, with bounded town and motorway values. */
    public static boolean showFollowingTurn(double first, double gap, double speed) {
        double v = Double.isFinite(speed) ? Math.max(0, speed) : 0;
        return Double.isFinite(first) && Double.isFinite(gap)
                && first >= 0 && gap >= 0
                && first <= Math.max(250, Math.min(900, v * 28))
                && gap <= Math.max(80, Math.min(450, v * 12));
    }

    public static double signpostHorizon(double speed) {
        double v = Double.isFinite(speed) ? Math.max(0, speed) : 0;
        return Math.max(250, Math.min(900, v * 28));
    }

    public static boolean junctionPreview(boolean complex, double distance, double speed) {
        return complex && Double.isFinite(distance) && distance >= 0
                && distance <= signpostHorizon(speed);
    }

    public static boolean guidanceFixTrusted(double accuracy, long ageMs) {
        return Double.isFinite(accuracy) && accuracy >= 0 && accuracy <= 55
                && ageMs >= 0 && ageMs <= 15000;
    }

    public static boolean canInspectManoeuvres(double speed, long ageMs) {
        return Double.isFinite(speed) && speed >= 0 && speed <= 0.5
                && ageMs >= 0 && ageMs <= 7000;
    }

    /** Strip only an exact, terminal street suffix; keep other provider wording. */
    public static String cardAction(String direction, String street, boolean roundabout,
                                    boolean fork, String glyph) {
        String message = direction == null ? "" : direction.trim();
        String road = street == null ? "" : street.trim();
        if (roundabout && !road.isEmpty()) return "Exit onto";
        if (fork && !road.isEmpty()) {
            if ("↖".equals(glyph)) return "Keep left";
            if ("↗".equals(glyph)) return "Keep right";
        }
        if (!road.isEmpty()) {
            for (String connector : new String[]{" onto ", " on ", " into "}) {
                String suffix = connector + road;
                if (message.length() > suffix.length() && message.regionMatches(true,
                        message.length() - suffix.length(), suffix, 0, suffix.length())) {
                    return message.substring(0, message.length() - suffix.length()).trim();
                }
            }
        }
        return message.isEmpty() ? "Follow road signs" : message;
    }

    public static boolean showSeparateRoad(String action, String road) {
        return road != null && !road.trim().isEmpty() && (action == null
                || !action.toLowerCase(java.util.Locale.ROOT)
                    .contains(road.trim().toLowerCase(java.util.Locale.ROOT)));
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
