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

    // ---- batch A: turn bands, forks, roundabout exits, lanes ----
    private static JSONObject road(String name)throws Exception{return t(name);}
    private static String code(List<TomTomApi.Instruction> steps,int i){return steps.get(i).maneuver;}

    @Test public void gentleBendOntoDifferentRoadIsBear()throws Exception{
        // North, then north-east (about 45 degrees right) onto another road.
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.002,-1.9985));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("B Road"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,2,0});
        assertEquals("BEAR_RIGHT",code(steps,0));assertEquals("Bear right onto B Road",steps.get(0).message);
    }

    @Test public void rightAngleStaysTurn()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.001,-1.999));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("B Road"));
        assertEquals("TURN_RIGHT",code(NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,2,0}),0));
    }

    @Test public void hairpinOntoOtherRoadWithChoiceIsSharp()throws Exception{
        // North then back south-east-ish: about 160 degrees right.
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53,-1.9996));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("B Road"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,2,0});
        assertEquals("SHARP_RIGHT",code(steps,0));
    }

    @Test public void hairpinWithoutChoiceKeepsCautiousWording()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53,-1.9996));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("B Road"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,1,0});
        assertEquals("CONTINUE",code(steps,0));assertTrue(steps.get(0).message.contains("check road signs"));
    }

    @Test public void reversingTheSameRoadIsUTurn()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53,-2.00003));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("A Road"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,2,0});
        assertTrue(code(steps,0).startsWith("UTURN"));assertEquals("Make a U-turn onto A Road",steps.get(0).message);
    }

    @Test public void forkSaysKeepWhenTheOtherRoadLeavesOnOneSide()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.002,-2));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("A Road"));
        JunctionDetail[] d=new JunctionDetail[]{null,new JunctionDetail(1,false,false,true),null};
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,2,0},d);
        assertEquals("KEEP_LEFT",code(steps,0));
    }

    @Test public void noKeepWhenForksOnBothSidesOrNoDetails()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.002,-2));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,road("A Road"));tags.put(2L,road("A Road"));
        JunctionDetail[] both=new JunctionDetail[]{null,new JunctionDetail(2,false,true,true),null};
        assertEquals(1,NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,3,0},both).size());   // destination only
        assertEquals(1,NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,3,0},null).size());
    }

    @Test public void roundaboutGetsExitNumberWhenCertain()throws Exception{
        // Arm A north, ring way 2 then ring way 3, exit B. Nodes: 0 arm, 1 entry, 2 pass, 3 exit, 4 end.
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.0012,-1.9996),p(53.0008,-1.9992),p(53.001,-1.9985));
        Map<Long,JSONObject> tags=new HashMap<>();
        tags.put(1L,road("A Road"));tags.put(2L,new JSONObject().put("highway","residential").put("junction","roundabout"));
        tags.put(3L,new JSONObject().put("highway","residential").put("junction","roundabout"));tags.put(4L,road("B Road"));
        JunctionDetail[] d=new JunctionDetail[]{null,null,new JunctionDetail(1,false,false,false),null,null};
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L,3L,4L),tags,new int[]{0,2,2,2,0},d);
        TomTomApi.Instruction enter=steps.get(0);
        assertEquals("ROUNDABOUT",enter.maneuver);assertEquals(2,enter.exitNumber);
        assertEquals("B Road",enter.street);assertEquals("At the roundabout, take the 2nd exit onto B Road",enter.message);
    }

    @Test public void roundaboutKeepsPlainWordingWhenUncertain()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.001,-2),p(53.0012,-1.9996),p(53.0008,-1.9992),p(53.001,-1.9985));
        Map<Long,JSONObject> tags=new HashMap<>();
        tags.put(1L,road("A Road"));tags.put(2L,new JSONObject().put("highway","residential").put("junction","roundabout"));
        tags.put(3L,new JSONObject().put("highway","residential").put("junction","roundabout"));tags.put(4L,road("B Road"));
        JunctionDetail[] d=new JunctionDetail[]{null,null,new JunctionDetail(1,true,false,false),null,null};
        TomTomApi.Instruction enter=NativeManeuvers.steps(points,Arrays.asList(1L,2L,3L,4L),tags,new int[]{0,2,2,2,0},d).get(0);
        assertEquals(0,enter.exitNumber);assertEquals("Follow the highlighted route at the roundabout",enter.message);
    }

    @Test public void lanesBuiltOnlyFromTags()throws Exception{
        List<GeoMath.Point> points=Arrays.asList(p(53,-2),p(53.003,-2),p(53.003,-1.997));
        Map<Long,JSONObject> tags=new HashMap<>();
        tags.put(1L,road("A Road").put("turn:lanes","left|through|through;right").put("lanes",3));tags.put(2L,road("B Road"));
        List<TomTomApi.Instruction> steps=NativeManeuvers.steps(points,Arrays.asList(1L,2L),tags,new int[]{0,2,0});
        assertEquals("TURN_RIGHT",steps.get(0).maneuver);
        List<RouteIntelligence.LaneSection> lanes=NativeManeuvers.lanes(points,Arrays.asList(1L,2L),tags,steps);
        assertEquals(1,lanes.size());assertTrue(lanes.get(0).lanes.get(2).recommended);
        // No tags, no lanes.
        tags.put(1L,road("A Road"));
        assertTrue(NativeManeuvers.lanes(points,Arrays.asList(1L,2L),tags,steps).isEmpty());
    }
}
