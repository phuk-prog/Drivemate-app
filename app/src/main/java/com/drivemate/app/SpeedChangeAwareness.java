package com.drivemate.app;

import java.util.List;

/** Advance notice only for explicit, adjacent, unambiguous route speed sections. */
final class SpeedChangeAwareness {
    static final class Change {
        final double offset;
        final int kmh;
        Change(double offset, int kmh) { this.offset=offset; this.kmh=kmh; }
    }
    private double lastOffset=Double.NaN;
    private int lastLimit=-1;
    void reset(){lastOffset=Double.NaN;lastLimit=-1;}
    boolean shouldAnnounce(Change c){
        if(c==null || (Double.isFinite(lastOffset) && Math.abs(c.offset-lastOffset)<5 && c.kmh==lastLimit)) return false;
        lastOffset=c.offset;lastLimit=c.kmh;return true;
    }
    static Change next(List<RouteIntelligence.SpeedSection> sections, RouteIntelligence.SpeedSection current,
                       double progress, double speedMps) {
        if(sections==null || current==null || !Double.isFinite(progress) || !Double.isFinite(current.end)) return null;
        double speed=Double.isFinite(speedMps)?Math.max(0,speedMps):0;
        double horizon=Math.max(90, Math.min(700, speed*12+40));
        double distance=current.end-progress;
        if(distance<=0 || distance>horizon) return null;
        RouteIntelligence.SpeedSection next=null;
        for(RouteIntelligence.SpeedSection section:sections){
            if(section==current || !Double.isFinite(section.start) || !Double.isFinite(section.end)
                    || section.end<=section.start || section.kmh<5 || section.kmh>140) continue;
            if(section.start<current.end-1 && section.end>current.end-1 && section.kmh!=current.kmh) return null;
            if(Math.abs(section.start-current.end)>1 || section.end<section.start+6) continue;
            if(next!=null && next.kmh!=section.kmh) return null;
            next=section;
        }
        if(next==null || next.kmh==current.kmh) return null;
        for(RouteIntelligence.SpeedSection section:sections)
            if(section!=next && section!=current && section.start<next.start+6
                    && section.end>next.start+1 && section.kmh!=next.kmh) return null;
        return new Change(next.start,next.kmh);
    }
}
