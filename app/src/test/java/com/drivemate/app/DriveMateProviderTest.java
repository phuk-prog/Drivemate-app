package com.drivemate.app;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriveMateProviderTest {
    @Test public void preservesDistinctJunctionManeuvers(){
        int[] types={12,13,17,18,19,20,21,22,23,24,25,26,27,37,38};
        String[] expected={"RIGHT_U_TURN","LEFT_U_TURN","RAMP_STRAIGHT","RAMP_RIGHT","RAMP_LEFT","MOTORWAY_EXIT_RIGHT","MOTORWAY_EXIT_LEFT","STRAIGHT","KEEP_RIGHT","KEEP_LEFT","MERGE","ROUNDABOUT","ROUNDABOUT_EXIT","MERGE_RIGHT","MERGE_LEFT"};
        for(int i=0;i<types.length;i++)assertEquals(expected[i],DriveMateProvider.maneuver(types[i]));
        for(int i=17;i<=21;i++)assertEquals("",DriveMateProvider.junctionType(i));
        for(int i=22;i<=24;i++)assertEquals("BIFURCATION",DriveMateProvider.junctionType(i));
        assertEquals("ROUNDABOUT",DriveMateProvider.junctionType(27));
    }
    @Test public void explicitOsmLimitNotGuess()throws Exception{
        assertEquals(32,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"20 mph\"}")));
        assertEquals(48,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"30 mph\"}")));
        assertEquals(0,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"20 mph\",\"maxspeed:conditional\":\"30 mph @ (Mo-Fr)\"}")));
        assertEquals(0,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed:forward\":\"20 mph\"}")));
        assertEquals(0,DriveMateProvider.explicitLimit(new JSONObject("{\"maxspeed\":\"signals\"}")));
    }
}
