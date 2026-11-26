package com.drivemate.app;

/** Pure policy shared by preview and start controls; separate from Android views. */
final class NavigationStartPolicy {
    private NavigationStartPolicy() {}
    static boolean previewOnly(String provider) {
        return "DriveMate local preview".equals(provider);
    }
    static boolean freshFix(double lat, double lon, long fixTime, long now, float accuracy) {
        return MapStartupPolicy.freshForRouting(lat,lon,fixTime,now,accuracy)
                && now-fixTime<=15000;
    }
    static String trafficStatus(String provider) {
        return "TomTom".equals(provider) ? " · traffic checked" : " · live traffic not verified";
    }
}
