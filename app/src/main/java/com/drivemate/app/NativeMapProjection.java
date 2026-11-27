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
        final double cx,cy,scale,cos,sin,pitch,tiltSin,focal;final float anchorX,anchorY;
        Camera(double lat,double lon,double zoom,double bearing,double tilt,int width,int height){
            cx=x(lon);cy=y(lat);scale=256*Math.pow(2,Math.max(2,Math.min(21,zoom)));
            double a=Math.toRadians(bearing);cos=Math.cos(a);sin=Math.sin(a);
            double radians=Math.toRadians(Math.max(0,Math.min(55,tilt)));
            pitch=Math.cos(radians);tiltSin=Math.sin(radians);
            focal=Math.max(1,Math.max(width,height)*1.25);
            anchorX=width*.5f;anchorY=height*(tilt>0?.62f:.5f);
        }
        double depth(double x,double y){return 1-((-(x-cx)*scale*sin+(y-cy)*scale*cos)*tiltSin/focal);}
        boolean segment(double x0,double y0,double x1,double y1,float[] a,float[] b){
            double d0=depth(x0,y0),d1=depth(x1,y1),near=.051;
            if(d0<near&&d1<near)return false;
            if(d0<near){double f=(near-d0)/(d1-d0);x0+=(x1-x0)*f;y0+=(y1-y0)*f;}
            else if(d1<near){double f=(near-d1)/(d0-d1);x1+=(x0-x1)*f;y1+=(y0-y1)*f;}
            screen(x0,y0,a);screen(x1,y1,b);return Float.isFinite(a[0])&&Float.isFinite(a[1])&&Float.isFinite(b[0])&&Float.isFinite(b[1]);
        }
        void screen(double x,double y,float[] out){
            double dx=(x-cx)*scale,dy=(y-cy)*scale;
            double lateral=dx*cos+dy*sin,forward=-dx*sin+dy*cos;
            // Pinhole camera on a tilted ground plane. Behind-camera points
            // are outside the visible domain, not reflected into the map.
            double depth=1-forward*tiltSin/focal;
            if(depth<.05){out[0]=Float.NaN;out[1]=Float.NaN;return;}
            out[0]=(float)(anchorX+lateral/depth);
            out[1]=(float)(anchorY+forward*pitch/depth);
        }
        GeoMath.Point unproject(float sx,float sy){
            double sy0=sy-anchorY,den=pitch+sy0*tiltSin/focal;
            if(den<=.05)return new GeoMath.Point(lat(cy),lon(cx));
            double forward=sy0/den,depth=1-forward*tiltSin/focal;
            double a=(sx-anchorX)*depth/scale,b=forward/scale;
            return new GeoMath.Point(lat(cy+a*sin+b*cos),lon(cx+a*cos-b*sin));
        }
    }
}
