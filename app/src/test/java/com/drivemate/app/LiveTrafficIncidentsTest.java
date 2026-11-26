package com.drivemate.app;
import org.json.*;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveTrafficIncidentsTest {
    private JSONObject event(String id,int code,String time,String end,String geometry)throws Exception{
        JSONObject p=new JSONObject().put("id",id).put("iconCategory",code).put("timeValidity",time);
        if(end!=null)p.put("endTime",end);
        return new JSONObject().put("type","Feature").put("properties",p).put("geometry",new JSONObject(geometry));
    }
    @Test public void explicitPresentAndGeometryOnly()throws Exception{
        long now=Instant.parse("2026-11-26T08:00:00Z").toEpochMilli();
        String line="{\"type\":\"LineString\",\"coordinates\":[[-2,53],[-2.001,53.001]]}";
        String point="{\"type\":\"Point\",\"coordinates\":[-2,53]}";
        JSONArray a=new JSONArray().put(event("a",1,"present",null,line))
            .put(event("a",1,"present",null,line))
            .put(event("b",8,"future",null,point))
            .put(event("c",14,"present","2026-11-26T07:59:00Z",point))
            .put(event("d",999,"present",null,point))
            .put(event("e",11,"present","2026-11-26T08:01:00Z",point))
            .put(event("f",5,"present",null,"{\"type\":\"Point\",\"coordinates\":[300,53]}"));
        List<LiveTrafficIncidents.Event> result=LiveTrafficIncidents.parse(new JSONObject().put("incidents",a),now);
        assertEquals(2,result.size());assertEquals("Accident",result.get(0).label);
        assertEquals(2,result.get(0).points.size());assertEquals(-2.001,result.get(0).points.get(1).lon,1e-9);
        assertEquals("Flooding",result.get(1).label);assertTrue(result.get(1).present(now));
        assertFalse(result.get(1).present(now+60000));
    }
}
