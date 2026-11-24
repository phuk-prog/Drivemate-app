package com.drivemate.app;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class RouteJoinPolicyTest {
    @Test public void parkedOffRoadThenJoinsWithoutRestart(){
        GeoMath.Point a=new GeoMath.Point(53.4,-2.15),b=new GeoMath.Point(53.41,-2.15);
        NavigationMatch matcher=new NavigationMatch(Arrays.asList(a,b));
        GeoMath.Point parked=new GeoMath.Point(53.40005,-2.151);
        NavigationMatch.State first=matcher.match(parked,8,0,Double.NaN,1000);
        assertFalse(first.confident);
        assertTrue(RouteJoinPolicy.joining(false,8,GeoMath.distance(a,parked),first.progress.offRouteMetres,first.reason));
        assertTrue(RouteJoinPolicy.waitForAccess(true,0));
        assertFalse(RouteJoinPolicy.waitForAccess(true,5));
        NavigationMatch.State joined=matcher.match(a,8,3,0,2000);
        assertTrue(joined.confident);
        assertFalse(RouteJoinPolicy.joining(true,8,5,5,NavigationMatch.Reason.OUTSIDE_WINDOW));
    }
    @Test public void genuinePoorGpsOrLargeDeviationNeverPresentedAsReady(){
        assertFalse(RouteJoinPolicy.joining(false,70,20,20,NavigationMatch.Reason.POOR_ACCURACY));
        assertFalse(RouteJoinPolicy.joining(false,8,251,251,NavigationMatch.Reason.OUTSIDE_WINDOW));
        assertFalse(RouteJoinPolicy.joining(false,8,20,20,NavigationMatch.Reason.DUPLICATE));
    }
}
