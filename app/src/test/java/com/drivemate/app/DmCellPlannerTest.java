package com.drivemate.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class DmCellPlannerTest{
    private JSONObject info(long bytes,String target,long ms)throws Exception{
        JSONObject cross=new JSONObject();if(target!=null)cross.put(target,ms);
        return new JSONObject().put("bytes",bytes).put("crossing_ms",cross);
    }
    @Test public void directedLowerBoundAndCoverageBudget()throws Exception{
        JSONObject tiles=new JSONObject().put("1020_-20",info(100,"1021_-20",300)).put("1021_-20",info(200,"1022_-20",400)).put("1022_-20",info(300,null,0)).put("1028_-20",info(100,null,0));
        DmCellPlanner p=new DmCellPlanner(tiles);
        assertEquals(700L,(long)p.lowerBounds("1022_-20").get("1020_-20"));
        assertFalse(p.lowerBounds("1020_-20").containsKey("1022_-20"));
        List<GeoMath.Point> route=Arrays.asList(new GeoMath.Point(51.025,-.975),new GeoMath.Point(51.125,-.975));
        Set<String> must=new HashSet<>(Arrays.asList("1020_-20","1022_-20"));
        DmCellPlanner.Coverage c=p.coverage(route,must,48.3,500);
        assertTrue(c.cells.containsAll(must));assertTrue(c.bytes<=500);assertFalse(c.complete);
        DmCellPlanner.Coverage full=p.coverage(route,must,48.3,1000);
        assertTrue(full.cells.contains("1028_-20"));assertTrue(full.complete);
    }
}
