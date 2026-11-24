package com.drivemate.app;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class NavigationMatchTest {
    private GeoMath.Point p(double lat,double lon){return new GeoMath.Point(lat,lon);}
    @Test public void rejectsForwardJumpAndDuplicateFix(){
        NavigationMatch m=new NavigationMatch(Arrays.asList(p(51,0),p(51.02,0)));
        assertTrue(m.match(p(51.0001,0),5,10,0,1000).confident);
        NavigationMatch.State jump=m.match(p(51.015,0),5,10,0,2000);
        assertFalse(jump.confident);assertTrue(jump.progress.alongMetres<30);
        assertFalse(m.match(p(51.0002,0),5,10,0,2000).confident);
    }
    @Test public void oppositeHeadingDoesNotAdvance(){
        NavigationMatch m=new NavigationMatch(Arrays.asList(p(51,0),p(51.02,0)));
        assertFalse(m.match(p(51.0001,0),5,10,180,1000).confident);
    }
    @Test public void requiresConfirmedTurn(){
        NavigationMatch m=new NavigationMatch(Arrays.asList(p(51,0),p(51.02,0)));
        double[] offsets={100,130,500};
        assertEquals(0,m.instruction(offsets,99,0));
        assertEquals(0,m.instruction(offsets,110,0));
        assertEquals(1,m.instruction(offsets,120,0));
        assertEquals(1,m.instruction(offsets,139,1));
        assertEquals(2,m.instruction(offsets,150,1));
    }
    @Test public void reacquiresOnlyAfterThreeConsistentFixes(){
        NavigationMatch m=new NavigationMatch(Arrays.asList(p(51,0),p(51.02,0)));
        assertTrue(m.match(p(51.0001,0),5,10,0,1000).confident);
        assertFalse(m.match(p(51.002,0),5,10,0,2000).confident);
        assertFalse(m.match(p(51.002,0),5,10,0,10000).confident);
        assertFalse(m.match(p(51.0021,0),5,10,0,11000).confident);
        NavigationMatch.State recovered=m.match(p(51.0022,0),5,10,0,12000);
        assertTrue(recovered.confident);assertTrue(recovered.reacquired);
        assertTrue(recovered.progress.alongMetres>200);
    }
    @Test public void duplicateDoesNotReportFalseOffRoute(){
        NavigationMatch m=new NavigationMatch(Arrays.asList(p(51,0),p(51.02,0)));
        assertTrue(m.match(p(51.0001,0),5,0,Double.NaN,1000).confident);
        assertEquals(NavigationMatch.Reason.DUPLICATE,m.match(p(51.0001,0),5,0,Double.NaN,1000).reason);
    }
    @Test public void fasterRoadsGetEarlierWarnings(){
        assertTrue(NavigationMatch.approachDistance(31)>NavigationMatch.approachDistance(8));
        assertTrue(NavigationMatch.earlyDistance(31)>NavigationMatch.earlyDistance(8));
    }
    @Test public void laneSelectionRequiresCurrentManeuver(){
        RouteIntelligence.Lane lane=new RouteIntelligence.Lane("RIGHT",true,
                Arrays.asList("STRAIGHT","RIGHT"));
        RouteIntelligence.LaneSection section=new RouteIntelligence.LaneSection(100,200,Arrays.asList(lane));
        assertSame(section,RouteIntelligence.lanesForManeuver(Arrays.asList(section),50,400,200));
        assertNull(RouteIntelligence.lanesForManeuver(Arrays.asList(section),50,400,80));
        assertNull(RouteIntelligence.lanesForManeuver(Arrays.asList(section,section),50,400,200));
    }
}
