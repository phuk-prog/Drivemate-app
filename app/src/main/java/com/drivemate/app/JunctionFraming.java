package com.drivemate.app;
import java.util.*;
/** Camera-only geometry. A padded context is not a claim about untraversed road topology. */
final class JunctionFraming {
    private JunctionFraming(){}
    static int reportedExit(int value){return value>=1&&value<=20?value:0;}
    static List<GeoMath.Point> context(List<GeoMath.Point> route,double offset,boolean roundabout){
        if(route==null||route.size()<2||!Double.isFinite(offset))return Collections.emptyList();
        double length=GeoMath.length(route);if(offset<0||offset>length+1)return Collections.emptyList();
        List<GeoMath.Point> slice=GeoMath.slice(route,Math.max(0,offset-(roundabout?65:35)),Math.min(length,offset+(roundabout?180:75)));
        if(slice.isEmpty())return Collections.emptyList();
        double south=90,north=-90,west=180,east=-180;
        for(GeoMath.Point p:slice){south=Math.min(south,p.lat);north=Math.max(north,p.lat);west=Math.min(west,p.lon);east=Math.max(east,p.lon);}
        // Include the untraversed side of ordinary UK roundabouts and feeder roads.
        // Oversized roundabouts may need additional verified map geometry later.
        double margin=roundabout?75:30;
        double dy=margin/111000,dx=margin/(111000*Math.max(.1,Math.cos(Math.toRadians((south+north)/2))));
        return Collections.unmodifiableList(Arrays.asList(new GeoMath.Point(south-dy,west-dx),new GeoMath.Point(south-dy,east+dx),new GeoMath.Point(north+dy,west-dx),new GeoMath.Point(north+dy,east+dx)));
    }
    static double fitZoom(double requested,GeoMath.Point center,GeoMath.Point car,List<GeoMath.Point> context,int width,int height,int top,int bottom,int margin){
        if(center==null||car==null||context==null||context.isEmpty()||width<100||height-top-bottom<120)return requested;
        // Conservatively fit a circle around the camera target so bearing changes do not crop the context.
        double radius=GeoMath.distance(center,car);
        for(GeoMath.Point p:context)radius=Math.max(radius,GeoMath.distance(center,p));
        // The actual screen centre can be offset from the usable viewport centre.
        double half=Math.min(width/2.0,Math.min(height/2.0-top,height/2.0-bottom))-margin;
        if(half<35)half=Math.min(width/2.0,(height-top-bottom)/2.0)-margin;
        if(half<20||radius<1)return requested;
        double metresPerPixel=radius*1.25/half;
        double zoom=Math.log(156543.03392*Math.max(.1,Math.cos(Math.toRadians(center.lat)))/metresPerPixel)/Math.log(2);
        return Math.min(requested,Math.max(NavigationCamera.MIN_ZOOM,zoom));
    }
    static boolean nonDrivingPoi(String layerId){
        if(layerId==null)return false;String id=layerId.toLowerCase(Locale.ROOT);
        if(!id.contains("poi")&&!id.contains("shop")&&!id.contains("restaurant")&&!id.contains("business"))return false;
        for(String keep:new String[]{"fuel","petrol","gas_station","charging","parking","hospital","service_station","ferry","traffic","airport","railway","train_station"})if(id.contains(keep))return false;
        return true;
    }
}
