package com.drivemate.app;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class MapReviewTest {
    TomTomApi.Route route(String provider,int speed,boolean reverse){
        List<GeoMath.Point> p=new ArrayList<>(Arrays.asList(new GeoMath.Point(53,-2),new GeoMath.Point(53.01,-2)));if(reverse)Collections.reverse(p);
        double len=GeoMath.length(p);
        return new TomTomApi.Route(p,new ArrayList<>(),len,120,0,new ArrayList<>(),Arrays.asList(new RouteIntelligence.SpeedSection(0,len,speed)),new ArrayList<>(),provider);
    }
    @Test public void twentyThirtyIsReviewOnlyAndDeduplicated(){
        MapReview review=new MapReview();TomTomApi.Route own=route("DriveMate",32,false),other=route("TomTom",48,false);
        review.observe(own,other,100);review.observe(own,other,110);
        assertEquals(1,review.snapshot().size());assertEquals(32,own.speedSections.get(0).kmh);
        assertEquals("DriveMate 20 mph · comparison 30 mph",review.snapshot().get(0).label());
    }
    @Test public void oppositeDirectionAndAgreementIgnored(){
        MapReview r=new MapReview();r.observe(route("DriveMate",32,false),route("TomTom",48,true),100);
        r.observe(route("DriveMate",32,false),route("TomTom",32,false),100);assertTrue(r.snapshot().isEmpty());
    }
    @Test public void unknownLaneMaskIsNotInvented()throws Exception{
        assertNull(DriveMateProvider.laneSection(new org.json.JSONArray("[{\"directions\":8,\"active\":64}]"),200));
        assertNotNull(DriveMateProvider.laneSection(new org.json.JSONArray("[{\"directions\":66,\"active\":64}]"),200));
    }
}
