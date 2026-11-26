package com.drivemate.app;
import org.json.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class FreeDriveRoadMatchTest {
    private JSONArray way(long id,long lon,String tags)throws Exception{
        return new JSONArray().put(id).put(new JSONObject(tags)).put(new JSONArray()
            .put(new JSONArray().put(530000000).put(lon)).put(new JSONArray().put(530010000).put(lon)))
            .put(new JSONArray().put(1).put(2));
    }
    @Test public void uniqueExplicitLimitAndDistinctFixes()throws Exception{
        List<JSONArray> ways=Arrays.asList(way(1,-20000000,"{\"highway\":\"residential\",\"maxspeed\":\"30 mph\"}"));
        GeoMath.Point p=new GeoMath.Point(53.0005,-2);
        FreeDriveRoadMatch.Match m=FreeDriveRoadMatch.match(ways,p,5,0,10);
        assertNotNull(m);assertEquals(48,m.kmh);
        FreeDriveRoadMatch w=new FreeDriveRoadMatch();assertNull(w.confirm(m,1000));assertNull(w.confirm(m,1000));assertNotNull(w.confirm(m,2000));
        assertNull(FreeDriveRoadMatch.match(ways,p,20,0,10));
        assertNull(FreeDriveRoadMatch.match(ways,p,5,90,10));
    }
    @Test public void rejectsAmbiguousParallelAndConditional()throws Exception{
        JSONArray a=way(1,-20000000,"{\"highway\":\"residential\",\"maxspeed\":\"30 mph\"}");
        JSONArray b=way(2,-19999000,"{\"highway\":\"residential\"}");
        GeoMath.Point p=new GeoMath.Point(53.0005,-2);
        assertNull(FreeDriveRoadMatch.match(Arrays.asList(a,b),p,5,0,10));
        assertNull(FreeDriveRoadMatch.match(Arrays.asList(way(1,-20000000,"{\"highway\":\"residential\",\"maxspeed\":\"30 mph\",\"maxspeed:conditional\":\"20 mph @ (school hours)\"}")),p,5,0,10));
        assertNull(FreeDriveRoadMatch.match(Arrays.asList(way(1,-20000000,"{\"highway\":\"residential\",\"maxspeed\":\"30 mph\",\"oneway\":\"yes\"}")),p,5,180,10));
        assertNotNull(FreeDriveRoadMatch.match(Arrays.asList(a),p,5,180,10));
    }
}
