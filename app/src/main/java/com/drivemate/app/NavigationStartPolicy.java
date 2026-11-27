package com.drivemate.app;

/** Pure policy shared by preview and start controls; separate from Android views. */
final class NavigationStartPolicy {
    private NavigationStartPolicy() {}
    /**
     * The owner lifted the preview-only restriction: routes from the app's own routing engine
     * can be started for driving like any other route. Set this back to true to hide Start and
     * label local routes "not for driving" again; every call site below still honours it.
     */
    static final boolean LOCAL_ROUTES_PREVIEW_ONLY = false;
    static boolean previewOnly(String provider) {
        return LOCAL_ROUTES_PREVIEW_ONLY && "DriveMate local preview".equals(provider);
    }
    static boolean freshFix(double lat, double lon, long fixTime, long now, float accuracy) {
        return MapStartupPolicy.freshForRouting(lat,lon,fixTime,now,accuracy)
                && now-fixTime<=15000;
    }
    static String trafficStatus(String provider) {
        return "TomTom".equals(provider) ? " · traffic checked" : " · live traffic not verified";
    }
}
