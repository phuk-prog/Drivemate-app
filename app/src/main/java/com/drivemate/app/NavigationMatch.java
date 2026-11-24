package com.drivemate.app;

import java.util.List;

/** Route-only map matching; never claims physical lane occupancy. */
public final class NavigationMatch {
    private final List<GeoMath.Point> path;
    private final double[] offsets;
    private double confirmed = -1;
    private long confirmedAt = -1, lastSample = -1;
    private int pendingInstruction = -1, confirmations;
    public static final class State {
        public final GeoMath.Progress progress;
        public final boolean confident;
        State(GeoMath.Progress progress, boolean confident) {
            this.progress = progress; this.confident = confident;
        }
    }
    public NavigationMatch(List<GeoMath.Point> path) {
        this.path = path;
        offsets = new double[path.size()];
        for (int i=1;i<path.size();i++)
            offsets[i] = offsets[i-1] + GeoMath.distance(path.get(i-1),path.get(i));
    }
    public State match(GeoMath.Point gps, float accuracy, float speed, double heading, long time) {
        double total = offsets[offsets.length-1];
        double previous = Math.max(0, confirmed);
        double dt = confirmedAt < 0 ? 1 : Math.max(0, Math.min(12,(time-confirmedAt)/1000.0));
        double velocity = Float.isFinite(speed) ? Math.max(0,Math.min(45,speed)) : 0;
        double low = Math.max(0,previous-25);
        double high = Math.min(total,previous+(confirmed<0 ? 150 : Math.max(30,velocity*dt*1.6+25)));
        double best = Double.POSITIVE_INFINITY, bestAlong=previous, bestOff=Double.POSITIVE_INFINITY;
        double alternate = Double.POSITIVE_INFINITY, alternateAlong=previous;
        double minOff = Double.POSITIVE_INFINITY;
        for(int i=1;i<path.size();i++) {
            if(offsets[i]<low || offsets[i-1]>high) continue;
            double len=offsets[i]-offsets[i-1]; if(len<0.1) continue;
            GeoMath.Point a=path.get(i-1), b=path.get(i);
            double latScale=111195, lonScale=latScale*Math.cos(Math.toRadians((a.lat+b.lat)/2));
            double x=(b.lon-a.lon)*lonScale,y=(b.lat-a.lat)*latScale;
            double gx=(gps.lon-a.lon)*lonScale,gy=(gps.lat-a.lat)*latScale;
            double fraction=(gx*x+gy*y)/(x*x+y*y);
            // Clip to the physically plausible progress window, not just the segment.
            fraction=Math.max(Math.max(0,(low-offsets[i-1])/len),
                    Math.min(Math.min(1,(high-offsets[i-1])/len),fraction));
            double along=offsets[i-1]+fraction*len;
            double off=Math.hypot(gx-fraction*x,gy-fraction*y);
            minOff=Math.min(minOff,off);
            double angle=Double.isFinite(heading)&&velocity>=3
                    ? Math.abs((GeoMath.bearingDegrees(a,b)-heading+540)%360-180) : 0;
            double score=off+angle*0.20+Math.abs(along-(previous+velocity*dt))*0.08;
            if(angle>100 && velocity>=3) continue;
            if(score<best) {
                alternate=best; alternateAlong=bestAlong;
                best=score; bestAlong=along; bestOff=off;
            } else if(score<alternate) {alternate=score;alternateAlong=along;}
        }
        boolean distinct=time>lastSample;
        lastSample=Math.max(lastSample,time);
        boolean ambiguous=Math.abs(alternateAlong-bestAlong)>35 && alternate-best<8;
        boolean good=distinct && Float.isFinite(accuracy) && accuracy<=35
                && bestOff<=Math.min(30,Math.max(12,accuracy*1.3)) && !ambiguous;
        if(good) {confirmed=Math.max(previous,bestAlong);confirmedAt=time;}
        return new State(new GeoMath.Progress(Math.max(0,confirmed),
                good?bestOff:minOff,total),good);
    }
    /** Require two distinct confirmed fixes beyond a manoeuvre, not a predicted marker. */
    public int instruction(double[] offsets, double along, int previous) {
        if(offsets.length==0)return -1;
        int current=Math.max(0,Math.min(previous,offsets.length-1));
        if(current==offsets.length-1 || along<offsets[current]+8) {
            pendingInstruction=-1;confirmations=0;return current;
        }
        if(pendingInstruction!=current){pendingInstruction=current;confirmations=1;return current;}
        if(++confirmations<2)return current;
        pendingInstruction=-1;confirmations=0;
        // Do not skip a closely spaced next instruction without its own confirmation.
        return current+1;
    }
    public static double immediateDistance(float speed){return bound(25+speed*2.5,35,110);}
    public static double approachDistance(float speed){return bound(80+speed*10,120,420);}
    public static double earlyDistance(float speed){return bound(180+speed*27,300,1200);}
    private static double bound(double x,double min,double max){return Double.isFinite(x)?Math.max(min,Math.min(max,x)):min;}
}
