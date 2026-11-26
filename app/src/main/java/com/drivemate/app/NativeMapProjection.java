package com.drivemate.app;

/** DriveMate original Web Mercator camera. Coordinates are normalized world units. */
final class NativeMapProjection {
    static final double MAX_LAT=85.05112878;
    private NativeMapProjection(){}
    static double x(double lon){return (lon+180)/360;}
    static double y(double lat){
        double r=Math.toRadians(Math.max(-MAX_LAT,Math.min(MAX_LAT,lat)));
        return .5-Math.log(Math.tan(Math.PI/4+r/2))/(2*Math.PI);
    }
    static double lon(double x){return x*360-180;}
    static double lat(double y){return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*y))));}
    static final class Camera {
        final double cx,cy,scale,cos,sin,pitch;final float anchorX,anchorY;
        Camera(double lat,double lon,double zoom,double bearing,double tilt,int width,int height){
            cx=x(lon);cy=y(lat);scale=256*Math.pow(2,Math.max(2,Math.min(21,zoom)));
            double a=Math.toRadians(bearing);cos=Math.cos(a);sin=Math.sin(a);
            pitch=Math.cos(Math.toRadians(Math.max(0,Math.min(55,tilt))));
            anchorX=width*.5f;anchorY=height*(tilt>0?.62f:.5f);
        }
        void screen(double x,double y,float[] out){
            double dx=(x-cx)*scale,dy=(y-cy)*scale;
            out[0]=(float)(anchorX+dx*cos+dy*sin);
            out[1]=(float)(anchorY+(-dx*sin+dy*cos)*pitch);
        }
        GeoMath.Point unproject(float sx,float sy){
            double a=(sx-anchorX)/scale,b=(sy-anchorY)/(scale*pitch);
            return new GeoMath.Point(lat(cy+a*sin+b*cos),lon(cx+a*cos-b*sin));
        }
    }
}
