package com.drivemate.app;
import org.json.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class MappedEnforcementTest {
    private JSONArray member(String role,long id){return new JSONArray().put(role).put("n").put(id);}
    private JSONArray relation(String kind,boolean average)throws Exception{
        JSONArray members=new JSONArray().put(member("from",1)).put(member("device",8)).put(member("to",3));
        if(average)members.put(member("device",9));
        return new JSONArray().put(new JSONArray().put(55).put(new JSONObject().put("type","enforcement").put("enforcement",kind)).put(members));
    }
    private Map<Long,Double> nodes(double from,double to){Map<Long,Double> n=new HashMap<>();n.put(1L,from);n.put(3L,to);return n;}
    @Test public void onlyForwardOnOneVerifiedWay()throws Exception{
        JSONArray rows=relation("maxspeed",false);
        MappedEnforcement.Match result=MappedEnforcement.match(rows,nodes(10,210),Collections.emptySet(),Arrays.asList(12L,12L,12L),new double[]{0,10,100,210});
        assertEquals(1,result.notices.size());assertEquals("Mapped speed camera",result.notices.get(0).label);
        assertEquals(10,result.notices.get(0).offset,0);
        assertTrue(MappedEnforcement.match(rows,nodes(210,10),Collections.emptySet(),Arrays.asList(12L,12L,12L),new double[]{0,10,100,210}).notices.isEmpty());
        assertTrue(MappedEnforcement.match(rows,nodes(10,210),Collections.emptySet(),Arrays.asList(12L,12L,13L),new double[]{0,10,100,210}).notices.isEmpty());
        assertTrue(MappedEnforcement.match(rows,nodes(10,210),Collections.singleton(1L),Arrays.asList(12L,12L,12L),new double[]{0,10,100,210}).notices.isEmpty());
    }
    @Test public void averageZoneBoundariesAndUnknownRejected()throws Exception{
        JSONArray rows=relation("average_speed",true);
        List<Long> ways=Arrays.asList(12L,12L,12L);double[] offsets={0,10,100,210};
        MappedEnforcement.Match r=MappedEnforcement.match(rows,nodes(10,210),Collections.emptySet(),ways,offsets);
        assertEquals(2,r.notices.size());assertEquals(1,r.zones.size());
        assertFalse(r.zones.get(0).active(9));assertTrue(r.zones.get(0).active(100));assertFalse(r.zones.get(0).active(210));
        assertTrue(MappedEnforcement.match(relation("average_speed",false),nodes(10,210),Collections.emptySet(),ways,offsets).zones.isEmpty());
    }
}
