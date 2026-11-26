package com.drivemate.app;

/** UI-only display policy; never used to invent route or road data. */
public final class DisplayScreenPolicy {
    private DisplayScreenPolicy() {}
    public static boolean speedKnown(double speed, long ageMs) {
        return Double.isFinite(speed) && speed >= 0 && ageMs >= 0 && ageMs <= 15000;
    }
    public static boolean trafficFresh(long ageMs) { return ageMs >= 0 && ageMs <= 480000; }
    public static int progress(double along, double total) {
        if (!Double.isFinite(along) || !Double.isFinite(total) || total <= 0) return 0;
        return (int)Math.round(1000 * Math.max(0, Math.min(1, along/total)));
    }
    public static boolean redrawRoute(double previous, double along, long elapsedMs) {
        if (!Double.isFinite(along) || along < 0) return false;
        return previous < 0 || (elapsedMs >= 2000 && Math.abs(along - previous) >= 12);
    }
    public static boolean compact(int screenHeightDp, float fontScale) {
        return screenHeightDp < 700 || fontScale > 1.2f;
    }
}
