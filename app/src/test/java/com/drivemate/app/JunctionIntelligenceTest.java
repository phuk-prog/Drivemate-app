package com.drivemate.app;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.drivemate.app.JunctionIntelligence.*;
public class JunctionIntelligenceTest {
 @Test public void explicitAndFallbackTypes(){
 String[][] cases={
 {"TURN_LEFT","REGULAR","TURN"},{"TURN_RIGHT","","TURN"},{"SHARP_LEFT","","TURN"},{"SHARP_RIGHT","","TURN"},{"STRAIGHT","","STRAIGHT"},{"CONTINUE","","STRAIGHT"},{"GO_STRAIGHT","","STRAIGHT"},
 {"KEEP_LEFT","","KEEP"},{"KEEP_RIGHT","","KEEP"},{"BEAR_LEFT","","KEEP"},{"BEAR_RIGHT","","KEEP"},{"LEFT","BIFURCATION","FORK"},{"RIGHT","Y_JUNCTION","FORK"},{"LEFT","FORK","FORK"},
 {"LEFT","CROSSROADS","CROSSROADS"},{"RIGHT","T_JUNCTION","T_JUNCTION"},{"LEFT","STAGGERED","STAGGERED"},{"RIGHT","STAGGERED_CROSSROADS","STAGGERED"},{"LEFT","MULTI_ARM","MULTI_ARM"},{"RIGHT","MULTI_ARM_JUNCTION","MULTI_ARM"},
 {"ROUNDABOUT_LEFT","REGULAR","ROUNDABOUT"},{"ROUNDABOUT_CROSS","ROUNDABOUT","ROUNDABOUT"},{"ROUNDABOUT_BACK","ROUNDABOUT","ROUNDABOUT"},{"ROUNDABOUT_RIGHT","MINI_ROUNDABOUT","MINI_ROUNDABOUT"},{"ROUNDABOUT","DOUBLE_ROUNDABOUT","DOUBLE_ROUNDABOUT"},{"ROUNDABOUT","SPIRAL_ROUNDABOUT","SPIRAL_ROUNDABOUT"},
 {"MOTORWAY_EXIT_LEFT","","MOTORWAY_EXIT"},{"MOTORWAY_EXIT_RIGHT","","MOTORWAY_EXIT"},{"TAKE_EXIT","","MOTORWAY_EXIT"},{"MERGE_LEFT","","MERGE"},{"MERGE_RIGHT","","MERGE"},{"ENTER_MOTORWAY","","MERGE"},{"JOIN_MOTORWAY","","MERGE"},{"RAMP_LEFT","","RAMP"},{"RAMP_RIGHT","","RAMP"},{"SLIP_ROAD","","RAMP"},{"STRAIGHT","LANE_DROP","LANE_DROP"},{"STRAIGHT","INTERCHANGE","INTERCHANGE"},{"STRAIGHT","DUAL_CARRIAGEWAY_CROSSING","DUAL_CARRIAGEWAY"},
 {"U_TURN_LEFT","","U_TURN"},{"RIGHT_U_TURN","","U_TURN"},{"UTURN","","U_TURN"},{"ARRIVE","","ARRIVE"},{"DESTINATION","","ARRIVE"},{"UNKNOWN","","UNKNOWN"},{"","REGULAR","UNKNOWN"},{"TURN_LEFT","UNSUPPORTED","TURN"},{"turn_right","regular","TURN"},{"","UNKNOWN","UNKNOWN"},{"FUTURE_CODE","","UNKNOWN"}};
 for(String[] row:cases)assertEquals(Arrays.toString(row),Kind.valueOf(row[2]),classify(row[0],row[1]));
 assertEquals(Kind.UNKNOWN,classify(null,null));
 }
 @Test public void horizonsAndFollowing(){
 assertTrue(preview(Kind.MOTORWAY_EXIT,900,30));assertFalse(preview(Kind.TURN,900,30));assertFalse(preview(Kind.UNKNOWN,20,10));assertFalse(preview(Kind.FORK,Double.NaN,10));assertFalse(preview(Kind.MERGE,-1,20));
 assertTrue(showFollowing(100,45,10,600,true));assertFalse(showFollowing(100,85,10,600,true));assertTrue(showFollowing(100,85,10,750,true));assertFalse(showFollowing(100,45,10,500,false));
 }
 @Test public void routePathNeverInventsArms(){
 List<GeoMath.Point> points=Arrays.asList(new GeoMath.Point(51,-1),new GeoMath.Point(51.001,-1),new GeoMath.Point(51.001,-.998));
 double offset=GeoMath.distance(points.get(0),points.get(1));
 List<GeoMath.Point> slice=path(points,offset,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Kind.CROSSROADS);
 assertTrue(slice.size()>=3);assertTrue(pathAgrees(slice,"↱",Kind.CROSSROADS));assertFalse(pathAgrees(slice,"↰",Kind.CROSSROADS));
 assertTrue(path(points,offset,offset-9,offset+9,Kind.CROSSROADS).isEmpty());
 assertTrue(path(points,offset,0,999,Kind.UNKNOWN).isEmpty());
 assertEquals(4,context(slice,Kind.CROSSROADS).size());
 }
 @Test public void distinctMergeAndRampArrows(){
 TomTomApi.Instruction merge=new TomTomApi.Instruction(100,"Merge right","MERGE_RIGHT","",0,45,"","");
 TomTomApi.Instruction ramp=new TomTomApi.Instruction(100,"Take slip road left","RAMP_LEFT","",0,-45,"","");
 assertEquals("↗",glyph(merge,Kind.MERGE));assertEquals("↖",glyph(ramp,Kind.RAMP));
 TomTomApi.Instruction exit=new TomTomApi.Instruction(100,"Exit roundabout onto High Street","ROUNDABOUT_EXIT","ROUNDABOUT",0,0,"High Street","");
 assertEquals("Exit roundabout onto High Street",IntelligentGuidance.direction(exit,"↻",0));
 }
 @Test public void rightUTurn(){
 TomTomApi.Instruction i=new TomTomApi.Instruction(50,"Make a U-turn","RIGHT_U_TURN","REGULAR",0,180,"","");
 assertEquals("⤻",glyph(i,Kind.U_TURN));
 }
}
