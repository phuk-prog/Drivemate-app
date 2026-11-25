package com.drivemate.app;
import java.util.List;

/** Never infer road identity from nearest visible pixels. */
final class RouteMapPolicy {
    private RouteMapPolicy(){}
    static boolean useTomTomMap(String provider,boolean keyAvailable){return keyAvailable&&"TomTom".equals(provider);}
    static String description(String provider,boolean shared){
        if("TomTom".equals(provider))return "TomTom map and route (same provider)";
        return shared?"Stockport shared road geometry":"DriveMate map · road alignment not verified";
    }
    static int rasterWidthDp(double zoom){
        if(!Double.isFinite(zoom)||zoom<14)return 4;
        if(zoom<17)return 5;
        if(zoom<19)return 7;
        return 9;
    }
    // Inset avoids clipped road features at the regional extract boundary.
    static boolean insideStockport(GeoMath.Point p){return p!=null&&p.lat>=53.392&&p.lat<=53.423&&p.lon>=-2.188&&p.lon<=-2.137;}
    static boolean insideStockport(List<GeoMath.Point> p){
        if(p==null||p.size()<2)return false;
        for(GeoMath.Point q:p)if(!insideStockport(q))return false;
        return true;
    }
    // E6 rounding may shift each coordinate by centimetres. Verify both endpoints
    // against one identified original segment; never jump across a bend or snap to it.
    static boolean onSameSegment(GeoMath.Point a,GeoMath.Point b,GeoMath.Point p,GeoMath.Point q){
        if(GeoMath.distance(a,b)<0.01||GeoMath.distance(p,q)<0.01)return false;
        List<GeoMath.Point> segment=java.util.Arrays.asList(a,b);
        return GeoMath.project(segment,p).offRouteMetres<=0.20&&GeoMath.project(segment,q).offRouteMetres<=0.20;
    }
    static boolean samePoints(List<GeoMath.Point> a,List<GeoMath.Point> b){
        if(a==null||b==null||a.size()!=b.size()||a.size()<2)return false;
        for(int i=0;i<a.size();i++)if(Math.round(a.get(i).lat*1e6)!=Math.round(b.get(i).lat*1e6)
            ||Math.round(a.get(i).lon*1e6)!=Math.round(b.get(i).lon*1e6))return false;
        return true;
    }
}
