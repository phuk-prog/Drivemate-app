package com.drivemate.app;
/** A location fix and a road match are different facts. No access path is inferred. */
public final class RouteJoinPolicy {
    private RouteJoinPolicy(){}
    public static boolean joining(boolean confirmedSinceRouteInstall, float accuracy, double startDistance,
                                  double routeDistance, NavigationMatch.Reason reason){
        return !confirmedSinceRouteInstall && Float.isFinite(accuracy) && accuracy>=0 && accuracy<=35
                && Double.isFinite(startDistance) && startDistance>=0 && startDistance<=250
                && Double.isFinite(routeDistance) && routeDistance>=0 && routeDistance<=250
                && (reason==NavigationMatch.Reason.OUTSIDE_WINDOW || reason==NavigationMatch.Reason.AMBIGUOUS
                    || reason==NavigationMatch.Reason.RECOVERING);
    }
    public static boolean waitForAccess(boolean joining,float speed){
        return joining && (!Float.isFinite(speed)||speed<2);
    }
}
