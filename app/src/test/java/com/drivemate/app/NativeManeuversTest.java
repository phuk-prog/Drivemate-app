package com.drivemate.app;

import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;

public class NativeManeuversTest {
    private static GeoMath.Point p(double y,double x){return new GeoMath.Point(y,x);}
    private static JSONObject t(String name)throws Exception{return new JSONObject().put("highway","residential").put("name",name);}
    @Test public void exactWayTransitionAndMappedLimits()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.001,-2.001),p(53.002,-2.001));
        List<Long> ways=Arrays.asList(1L,2L,2L);Map<Long,JSONObject> tags=new HashMap<>();
        tags.put(1L,t("A Road").put("maxspeed","30 mph"));tags.put(2L,t("B Road").put("maxspeed","20 mph"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,ways,tags,new int[]{0,2,1,0});
        assertEquals(2,steps.size());assertEquals("TURN_LEFT",steps.get(0).maneuver);
        assertEquals("B Road",steps.get(0).street);assertEquals(GeoMath.distance(points.get(0),points.get(1)),steps.get(0).routeOffsetMetres,.01);
        assertEquals("DESTINATION",steps.get(1).maneuver);
        List<RouteIntelligence.SpeedSection> limits=NativeManeuvers.limits(points,ways,tags);
        assertEquals(2,limits.size());assertEquals(48,limits.get(0).kmh);assertEquals(32,limits.get(1).kmh);
        tags.get(2L).put("maxspeed:conditional","10 mph @ (school)");
        assertEquals(1,NativeManeuvers.limits(points,ways,tags).size());
    }
    @Test public void roundaboutDoesNotInventExitNumber()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.001,-2.001),p(53.002,-2.001));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,t("A"));tags.put(2L,t("").put("junction","roundabout"));tags.put(3L,t("B"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L,3L),tags,new int[]{0,2,2,0});
        assertEquals("ROUNDABOUT",steps.get(0).maneuver);assertEquals(0,steps.get(0).exitNumber);
        assertEquals("ROUNDABOUT_EXIT",steps.get(1).maneuver);assertEquals(0,steps.get(1).exitNumber);
        assertTrue(NativeManeuvers.limits(points,Arrays.asList(1L,2L,3L),tags).isEmpty());
    }
    @Test public void nativeCandidateCannotStartUnvalidatedDriving(){
        assertTrue(NavigationStartPolicy.previewOnly(NativeRegionalRouter.PROVIDER));
    }
}
