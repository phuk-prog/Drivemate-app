package com.drivemate.app;
import java.util.*;
/** In-memory candidate discrepancies. Never modifies map or navigation. */
public final class MapReview {
    public static final class Finding {
        public final GeoMath.Point point;public final int mapKmh,otherKmh;public final double bearing;
        Finding(GeoMath.Point p,int m,int o,double b){point=p;mapKmh=m;otherKmh=o;bearing=b;}
        public String label(){return "DriveMate "+mph(mapKmh)+" mph · comparison "+mph(otherKmh)+" mph";}
    }
    private final List<Finding> findings=new ArrayList<>();
    public void clear(){findings.clear();}
    public List<Finding> snapshot(){return new ArrayList<>(findings);}
    private static int mph(int k){return (int)Math.round(k/1.609344);}
    public void observe(TomTomApi.Route map,TomTomApi.Route ref,double along){
        if(map==null||ref==null||!"DriveMate".equals(map.provider)||!"TomTom".equals(ref.provider)||System.currentTimeMillis()-ref.fetchedAtMs>480000||along<12||along+12>map.lengthMetres)return;
        RouteIntelligence.SpeedSection ms=RouteIntelligence.speedAt(map.speedSections,along,0,0,0);
        if(ms==null||along-12<ms.start+3||along+12>ms.end-3)return;
        GeoMath.Point a=GeoMath.pointAtDistance(map.points,along-12),b=GeoMath.pointAtDistance(map.points,along+12),c=GeoMath.pointAtDistance(map.points,along);
        GeoMath.Progress pa=GeoMath.project(ref.points,a),pb=GeoMath.project(ref.points,b),pc=GeoMath.project(ref.points,c);
        if(Math.max(pa.offRouteMetres,Math.max(pb.offRouteMetres,pc.offRouteMetres))>8||pb.alongMetres-pa.alongMetres<12||pb.alongMetres-pa.alongMetres>40||pc.alongMetres<pa.alongMetres||pc.alongMetres>pb.alongMetres)return;
        RouteIntelligence.SpeedSection ts=RouteIntelligence.speedAt(ref.speedSections,pc.alongMetres,0,0,0);
        if(ts==null||pa.alongMetres<ts.start+3||pb.alongMetres>ts.end-3||Math.abs(mph(ms.kmh)-mph(ts.kmh))<2)return;
        double bearing=GeoMath.bearingDegrees(a,b);
        for(Finding old:findings)if(old.mapKmh==ms.kmh&&old.otherKmh==ts.kmh&&GeoMath.distance(old.point,c)<150&&Math.abs((old.bearing-bearing+540)%360-180)<30)return;
        if(findings.size()<100)findings.add(new Finding(c,ms.kmh,ts.kmh,bearing));
    }
}
