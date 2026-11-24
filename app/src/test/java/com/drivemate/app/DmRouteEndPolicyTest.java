package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class DmRouteEndPolicyTest {
    @Test public void roadEndDoesNotInventPlaceArrival(){
        DmRouteEndPolicy p=new DmRouteEndPolicy();
        assertEquals(DmRouteEndPolicy.State.ROAD_END_ACCESS_UNVERIFIED,p.observe(.05,1,166,5,0,1000));
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(.05,1,10,5,8,2000));
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(.05,1,10,5,0,3000));
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(.05,1,10,5,0,4000));
        assertEquals(DmRouteEndPolicy.State.ARRIVAL_PREVIEW,p.observe(.05,1,10,5,0,5000));
    }
    @Test public void noArrivalOnStaleOrUncertainOrMovingFix(){
        DmRouteEndPolicy p=new DmRouteEndPolicy();
        p.observe(1,1,1,5,0,1000);p.observe(1,1,1,5,0,2000);
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(1,1,1,5,0,10000));
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(1,1,1,70,0,11000));
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(1,100,1,5,0,12000));
        assertEquals(DmRouteEndPolicy.State.CONTINUE,p.observe(1,1,1,5,Double.NaN,13000));
    }
}
