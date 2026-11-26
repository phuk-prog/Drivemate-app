package com.drivemate.app;
import java.util.List;

/** Never infer road identity from nearest visible pixels. */
final class RouteMapPolicy {
    private RouteMapPolicy(){}
    static boolean useTomTomMap(String provider,boolean keyAvailable){return false;} // Vector display by default; raster remains an explicit preference or startup fallback.
    static boolean useTomTomVector(String provider,boolean keyAvailable){return keyAvailable&&"TomTom".equals(provider);}
    static String description(String provider,boolean shared){
        return shared?"DriveMate shared road geometry":"DriveMate vector map · road alignment not verified";
    }
    static double[] corridorBounds(List<GeoMath.Point> points){
        if(points==null||points.size()<2)return null;
        double west=180,east=-180,south=90,north=-90;
        for(GeoMath.Point q:points){if(!Double.isFinite(q.lat)||!Double.isFinite(q.lon))return null;west=Math.min(west,q.lon);east=Math.max(east,q.lon);south=Math.min(south,q.lat);north=Math.max(north,q.lat);}
        // Approx 350 m of context. Reject large or out-of-coverage requests.
        west-=.006;east+=.006;south-=.004;north+=.004;
        if(west< -9||east>3||south<49||north>61||east-west>.12||north-south>.12)return null;
        return new double[]{west,south,east,north};
    }
    static boolean insideBounds(GeoMath.Point p,double[] b){return p!=null&&b!=null&&b.length==4&&p.lon>=b[0]&&p.lon<=b[2]&&p.lat>=b[1]&&p.lat<=b[3];}
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
