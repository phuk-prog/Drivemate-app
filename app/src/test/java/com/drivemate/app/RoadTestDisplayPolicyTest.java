package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class RoadTestDisplayPolicyTest {
    @Test public void estimatedMetricsExpire(){assertTrue(RoadTestDisplayPolicy.retainMetrics(1000,31000));assertFalse(RoadTestDisplayPolicy.retainMetrics(1000,31001));assertFalse(RoadTestDisplayPolicy.retainMetrics(-1,1000));}
    @Test public void speedBoundaryMustCoincideWithTurn(){assertTrue(RoadTestDisplayPolicy.atTurn(130,100,30));assertFalse(RoadTestDisplayPolicy.atTurn(145,100,30));assertFalse(RoadTestDisplayPolicy.atTurn(90,100,0));}
    @Test public void trafficStages(){assertTrue(RoadTestDisplayPolicy.trafficLabel("Roadworks",350,450,0,false).contains(" in "));assertEquals("Roadworks ahead now",RoadTestDisplayPolicy.trafficLabel("Roadworks",350,450,345,false));assertEquals("In roadworks zone",RoadTestDisplayPolicy.trafficLabel("Roadworks",350,450,360,false));assertEquals("Roadworks passed",RoadTestDisplayPolicy.trafficLabel("Roadworks",350,450,450,false));}
    @Test public void turnMarkersHaveBoundedHorizon(){assertTrue(RoadTestDisplayPolicy.showManeuver("←",27,5));assertFalse(RoadTestDisplayPolicy.showManeuver("←",2000,5));assertFalse(RoadTestDisplayPolicy.showManeuver("↑",27,5));}
}
