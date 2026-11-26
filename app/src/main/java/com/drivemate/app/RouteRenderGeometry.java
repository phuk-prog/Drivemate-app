package com.drivemate.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable full-detail route geometry. No smoothing, vertex dropping or road snapping. */
final class RouteRenderGeometry {
    private final List<GeoMath.Point> points;
    private final double[] offsets;
    final double length;
    RouteRenderGeometry(List<GeoMath.Point> path) {
        points=path==null?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(path));
        offsets=new double[points.size()];
        for(int i=1;i<points.size();i++)offsets[i]=offsets[i-1]+GeoMath.distance(points.get(i-1),points.get(i));
        length=offsets.length==0?0:offsets[offsets.length-1];
    }
    List<GeoMath.Point> full(){return points;}
    private int upper(double distance){
        int lo=0,hi=offsets.length;
        while(lo<hi){int mid=(lo+hi)>>>1;if(offsets[mid]<=distance)lo=mid+1;else hi=mid;}
        return lo;
    }
    GeoMath.Point at(double distance){
        if(points.isEmpty())return null;
        if(distance<=0)return points.get(0);
        if(distance>=length)return points.get(points.size()-1);
        int i=upper(distance);
        if(i==0||i>=points.size())return points.get(points.size()-1);
        GeoMath.Point a=points.get(i-1),b=points.get(i);
        double t=(distance-offsets[i-1])/(offsets[i]-offsets[i-1]);
        if(t==0)return a;
        return new GeoMath.Point(a.lat+(b.lat-a.lat)*t,a.lon+(b.lon-a.lon)*t);
    }
    List<GeoMath.Point> remaining(double distance){
        if(!Double.isFinite(distance)||distance<=0)return points;
        if(distance>=length||points.size()<2)return Collections.emptyList();
        int i=upper(distance);
        List<GeoMath.Point> result=new ArrayList<>(points.size()-i+1);
        result.add(at(distance));result.addAll(points.subList(i,points.size()));
        return result;
    }
    // Diagnostic only: nearest displayed road centerline may be a different road.
    // No inference of routability, road identity, direction or snapping.
    static double nearestRoadDistance(GeoMath.Point point,List<List<GeoMath.Point>> roads){
        double best=Double.POSITIVE_INFINITY;
        for(List<GeoMath.Point> road:roads)if(road.size()>1)
            best=Math.min(best,GeoMath.project(road,point).offRouteMetres);
        return best;
    }
}
