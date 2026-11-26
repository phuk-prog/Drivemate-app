package com.drivemate.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class DmGuidanceTest{
    @Test public void neverGuessLimitsOrDirectionalLanes()throws Exception{
        JSONObject tags=new JSONObject().put("maxspeed","30 mph").put("oneway","yes").put("lanes","2").put("turn:lanes","left|through");
        assertEquals(Integer.valueOf(30),DmGuidance.explicitMph(tags));
        assertFalse(DmGuidance.mappedLaneDescription(tags).isEmpty());
        tags.put("maxspeed:conditional","20 mph @ (Mo-Fr)");assertNull(DmGuidance.explicitMph(tags));
        tags.put("oneway","no");assertTrue(DmGuidance.mappedLaneDescription(tags).isEmpty());
        assertNull(DmGuidance.explicitMph(new JSONObject().put("maxspeed","GB:nsl_single")));
    }
    @Test public void possibleTurnsOnlyOnNamedWayChanges()throws Exception{
        List<GeoMath.Point> pts=Arrays.asList(new GeoMath.Point(51,-2),new GeoMath.Point(51.001,-2),new GeoMath.Point(51.001,-1.999));
        Map<Long,JSONObject> tags=new HashMap<>();tags.put(1L,new JSONObject().put("name","First"));tags.put(2L,new JSONObject().put("name","Second"));
        List<DmGuidance.Step> steps=DmGuidance.preview(pts,Arrays.asList(1L,2L),tags);
        assertEquals(2,steps.size());assertEquals("Possible right onto Second",steps.get(0).instruction);
        tags.get(2L).put("junction","roundabout");assertEquals(1,DmGuidance.preview(pts,Arrays.asList(1L,2L),tags).size());
    }
}
