package com.drivemate.app;
import java.util.*;
/** Route-scoped presentation: a calculated path is not a junction topology. */
final class JunctionIntelligence {
 private JunctionIntelligence(){}
 enum Kind {
 UNKNOWN("NEXT MANOEUVRE"), STRAIGHT("CONTINUE"), TURN("NEXT TURN"), CROSSROADS("CROSSROADS"), T_JUNCTION("T-JUNCTION"), FORK("ROAD FORK"), STAGGERED("SUCCESSIVE JUNCTIONS"), ROUNDABOUT("ROUNDABOUT"), MINI_ROUNDABOUT("MINI-ROUNDABOUT"), DOUBLE_ROUNDABOUT("ROUNDABOUTS"), SPIRAL_ROUNDABOUT("ROUNDABOUT"), MULTI_ARM("COMPLEX JUNCTION"), MOTORWAY_EXIT("MOTORWAY EXIT"), RAMP("SLIP ROAD"), MERGE("MERGE"), LANE_DROP("LANE DROP"), INTERCHANGE("INTERCHANGE"), U_TURN("U-TURN"), DUAL_CARRIAGEWAY("CARRIAGEWAY CROSSING"), ARRIVE("DESTINATION");
 final String title; Kind(String title){this.title=title;}
 }
 static String token(String v){return v==null?"":v.trim().toUpperCase(Locale.ROOT);}
 static Kind classify(String maneuver,String junction){
 String m=token(maneuver),j=token(junction);
 if(m.contains("ARRIVE")||m.contains("DESTINATION"))return Kind.ARRIVE;
 if(m.contains("UTURN")||m.contains("U_TURN"))return Kind.U_TURN;
 switch(j){
 case "MINI_ROUNDABOUT":return Kind.MINI_ROUNDABOUT;
 case "DOUBLE_ROUNDABOUT":return Kind.DOUBLE_ROUNDABOUT;
 case "SPIRAL_ROUNDABOUT":return Kind.SPIRAL_ROUNDABOUT;
 case "ROUNDABOUT":return Kind.ROUNDABOUT;
 case "BIFURCATION":case "FORK":case "Y_JUNCTION":return Kind.FORK;
 case "CROSSROADS":return Kind.CROSSROADS;
 case "T_JUNCTION":return Kind.T_JUNCTION;
 case "STAGGERED":case "STAGGERED_CROSSROADS":return Kind.STAGGERED;
 case "MULTI_ARM":case "MULTI_ARM_JUNCTION":return Kind.MULTI_ARM;
 case "INTERCHANGE":return Kind.INTERCHANGE;
 case "DUAL_CARRIAGEWAY_CROSSING":return Kind.DUAL_CARRIAGEWAY;
 case "LANE_DROP":return Kind.LANE_DROP;
 }
 if(m.contains("ROUNDABOUT"))return Kind.ROUNDABOUT;
 if(m.startsWith("MOTORWAY_EXIT")||m.equals("TAKE_EXIT"))return Kind.MOTORWAY_EXIT;
 if(m.startsWith("ENTER_MOTORWAY")||m.startsWith("MERGE")||m.equals("JOIN_MOTORWAY"))return Kind.MERGE;
 if(m.contains("RAMP")||m.contains("SLIP_ROAD"))return Kind.RAMP;
 if(m.equals("KEEP_LEFT")||m.equals("KEEP_RIGHT")||m.equals("BEAR_LEFT")||m.equals("BEAR_RIGHT"))return Kind.FORK;
 if(m.equals("STRAIGHT")||m.equals("CONTINUE")||m.equals("GO_STRAIGHT"))return Kind.STRAIGHT;
 if(m.contains("LEFT")||m.contains("RIGHT")||m.contains("TURN"))return Kind.TURN;
 return Kind.UNKNOWN;
 }
 static boolean roundabout(Kind k){return k==Kind.ROUNDABOUT||k==Kind.MINI_ROUNDABOUT||k==Kind.DOUBLE_ROUNDABOUT||k==Kind.SPIRAL_ROUNDABOUT;}
 static boolean complex(Kind k){return k!=Kind.UNKNOWN&&k!=Kind.STRAIGHT&&k!=Kind.ARRIVE&&k!=Kind.TURN;}
 static boolean highway(Kind k){return k==Kind.MOTORWAY_EXIT||k==Kind.MERGE||k==Kind.RAMP||k==Kind.LANE_DROP||k==Kind.INTERCHANGE;}
 static String glyph(TomTomApi.Instruction step,Kind k){
 if(roundabout(k))return "↻";
 if(k==Kind.U_TURN)return token(step.maneuver).contains("RIGHT")?"⤻":"⤺";
 return GuidanceLogic.symbol(step.maneuver,step.junctionType,step.turnAngleDegrees);
 }
 static boolean preview(Kind k,double distance,float speed){
 if(k==Kind.UNKNOWN||k==Kind.ARRIVE||k==Kind.STRAIGHT||!Double.isFinite(distance)||distance<0)return false;
 double v=Float.isFinite(speed)?Math.max(0,speed):0;
 double horizon=highway(k)?Math.max(450,Math.min(1200,250+v*25)):complex(k)?Math.max(220,Math.min(650,120+v*18)):Math.max(90,Math.min(300,60+v*8));
 return distance<=horizon;
 }
 static boolean showFollowing(double distance,double gap,float speed,int height,boolean other){
 return DrivingDisplayPolicy.showFollowingTurn(distance,gap,speed)&&height>=560&&(!other||height>=700||gap<=60);
 }
 static List<GeoMath.Point> path(List<GeoMath.Point> route,double offset,double previous,double following,Kind k){
 if(route==null||route.size()<2||!Double.isFinite(offset)||offset<0||Double.isNaN(previous)||Double.isNaN(following)||previous>=offset||following<=offset||k==Kind.UNKNOWN||k==Kind.ARRIVE||k==Kind.STRAIGHT)return Collections.emptyList();
 double length=GeoMath.length(route);
 if(!Double.isFinite(length)||offset>=length)return Collections.emptyList();
 double start=Math.max(0,Math.max(offset-(highway(k)?100:roundabout(k)?65:35),previous+5));
 double end=Math.min(length,Math.min(offset+(highway(k)||roundabout(k)?180:75),following-5));
 if(offset-start<8||end-offset<12)return Collections.emptyList();
 List<GeoMath.Point> slice=GeoMath.slice(route,start,end);
 for(GeoMath.Point p:slice)if(!Double.isFinite(p.lat)||!Double.isFinite(p.lon)||Math.abs(p.lat)>90||Math.abs(p.lon)>180)return Collections.emptyList();
 return slice.size()>=3?slice:Collections.emptyList();
 }
 static boolean pathAgrees(List<GeoMath.Point> path,String glyph,Kind kind){
 if(path==null||path.size()<3||roundabout(kind)||kind==Kind.U_TURN)return false;
 double length=GeoMath.length(path); if(!Double.isFinite(length)||length<25)return false;
 double d=Math.min(8,length/4);
 double b1=GeoMath.bearingDegrees(path.get(0),GeoMath.pointAtDistance(path,d));
 double b2=GeoMath.bearingDegrees(GeoMath.pointAtDistance(path,length-d),path.get(path.size()-1));
 double a=(b2-b1+540)%360-180;
 if("↰".equals(glyph))return a<=-25&&a>=-155;
 if("↱".equals(glyph))return a>=25&&a<=155;
 if("↖".equals(glyph))return a<=-10&&a>=-100;
 if("↗".equals(glyph))return a>=10&&a<=100;
 return "↑".equals(glyph)&&Math.abs(a)<=25;
 }
 static List<GeoMath.Point> context(List<GeoMath.Point> path,Kind k){
 if(path==null||path.size()<2)return Collections.emptyList();
 double s=90,n=-90,w=180,e=-180;
 for(GeoMath.Point p:path){s=Math.min(s,p.lat);n=Math.max(n,p.lat);w=Math.min(w,p.lon);e=Math.max(e,p.lon);}
 double margin=roundabout(k)?65:highway(k)?70:30;
 double dy=margin/111000,dx=margin/(111000*Math.max(.1,Math.cos(Math.toRadians((s+n)/2))));
 return Arrays.asList(new GeoMath.Point(s-dy,w-dx),new GeoMath.Point(s-dy,e+dx),new GeoMath.Point(n+dy,w-dx),new GeoMath.Point(n+dy,e+dx));
 }
}
