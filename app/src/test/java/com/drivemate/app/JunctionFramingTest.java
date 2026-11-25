package com.drivemate.app;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class JunctionFramingTest {
 @Test public void paddedContextIncludesOtherSideAndExit(){
  List<GeoMath.Point> route=Arrays.asList(new GeoMath.Point(53.4,-2.2),new GeoMath.Point(53.401,-2.2),new GeoMath.Point(53.4015,-2.1995),new GeoMath.Point(53.402,-2.199));
  List<GeoMath.Point> box=JunctionFraming.context(route,111,true);assertEquals(4,box.size());
  assertTrue(box.get(0).lon < -2.2);assertTrue(box.get(2).lat>53.402);
  double fitted=JunctionFraming.fitZoom(20,route.get(1),route.get(0),box,691,1372,160,150,35);assertTrue(fitted<20);
  assertTrue(JunctionFraming.context(route,Double.NaN,true).isEmpty());
 }
 @Test public void onlyReportedExitAndDrivingPoi(){assertEquals(0,JunctionFraming.reportedExit(0));assertEquals(3,JunctionFraming.reportedExit(3));assertEquals(0,JunctionFraming.reportedExit(99));assertTrue(JunctionFraming.nonDrivingPoi("poi_shopping"));assertFalse(JunctionFraming.nonDrivingPoi("poi_fuel"));assertFalse(JunctionFraming.nonDrivingPoi("road_labels"));}
}
