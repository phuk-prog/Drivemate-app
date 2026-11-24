package com.drivemate.app;
import org.json.*;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class TomTomEmptyRouteTest {
    private JSONObject response(boolean geometry,int length)throws Exception{
        JSONArray points=new JSONArray().put(new JSONObject().put("latitude",53.4).put("longitude",-2.15));
        points.put(new JSONObject().put("latitude",geometry?53.401:53.4).put("longitude",-2.15));
        JSONObject route=new JSONObject().put("summary",new JSONObject().put("lengthInMeters",length).put("travelTimeInSeconds",30).put("trafficDelayInSeconds",0))
            .put("legs",new JSONArray().put(new JSONObject().put("points",points)))
            .put("guidance",new JSONObject().put("instructions",new JSONArray().put(new JSONObject().put("message","Continue").put("routeOffsetInMeters",0))));
        return new JSONObject().put("routes",new JSONArray().put(route));
    }
    @Test public void retriesEmptyGeometryOnce()throws Exception{
        JSONObject empty=response(false,100),valid=response(true,100);AtomicInteger calls=new AtomicInteger();
        TomTomApi.Route route=TomTomApi.route("test",new GeoMath.Point(53.4,-2.15),new GeoMath.Point(53.401,-2.15),url->{
            assertTrue(url.contains("routeRepresentation=polyline"));
            if(calls.incrementAndGet()==1){assertTrue(url.contains("sectionType=lanes"));return empty;}
            assertFalse(url.contains("sectionType="));return valid;
        });
        assertEquals(2,calls.get());assertEquals(2,route.points.size());
    }
    @Test public void zeroLengthNearbyNeverCreatesRoadOrConfirmsArrival()throws Exception{
        JSONObject empty=response(false,0);AtomicInteger calls=new AtomicInteger();
        try{TomTomApi.route("test",new GeoMath.Point(53.4,-2.15),new GeoMath.Point(53.4001,-2.15),url->{calls.incrementAndGet();return empty;});fail();}
        catch(IOException expected){assertTrue(expected.getMessage().contains("nearby"));assertTrue(expected.getMessage().contains("no driving route"));}
        assertEquals(2,calls.get());
    }
    @Test public void noRetryOnRateLimit()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        try{TomTomApi.route("test",new GeoMath.Point(53.4,-2.15),new GeoMath.Point(53.401,-2.15),url->{calls.incrementAndGet();throw new IOException("TomTom HTTP 429.");});fail();}
        catch(IOException expected){assertEquals("TomTom HTTP 429.",expected.getMessage());}
        assertEquals(1,calls.get());
    }
}
