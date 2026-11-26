package com.drivemate.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrafficCauseTest {
    private JSONObject section(String simple,int... codes)throws Exception{
        JSONArray causes=new JSONArray();for(int code:codes)causes.put(new JSONObject().put("mainCauseCode",code));
        return new JSONObject().put("simpleCategory",simple).put("tec",new JSONObject().put("causes",causes));
    }
    @Test public void exactCausesAndUnknownFallback()throws Exception{
        assertEquals("ACCIDENT",TomTomApi.trafficCategory(section("OTHER",2)));
        assertEquals("OBSTRUCTION",TomTomApi.trafficCategory(section("OTHER",10)));
        assertEquals("VEHICLE_HAZARD",TomTomApi.trafficCategory(section("JAM",13)));
        assertEquals("MULTIPLE_HAZARDS",TomTomApi.trafficCategory(section("OTHER",2,10)));
        assertEquals("ROAD_CLOSURE",TomTomApi.trafficCategory(section("ROAD_CLOSURE",2)));
        assertEquals("OTHER",TomTomApi.trafficCategory(section("OTHER",999)));
    }
}
