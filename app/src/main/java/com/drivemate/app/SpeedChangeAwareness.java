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
    private RouteIntelligence.SpeedSection previous;
    private double previousAlong=Double.NaN;
    private long previousAt=-1;
    private Change pendingEntry;
    private boolean entryConfirmed;
    void reset(){lastOffset=Double.NaN;lastLimit=-1;clearEntry();}
    void clearEntry(){previous=null;previousAlong=Double.NaN;previousAt=-1;pendingEntry=null;entryConfirmed=false;}
    /** Entry requires recent trusted fixes on explicitly adjacent sections. */
    void observeEntry(List<RouteIntelligence.SpeedSection> sections,RouteIntelligence.SpeedSection current,double along,long now){
        entryConfirmed=false;
        if(!Double.isFinite(along)||now<0){clearEntry();return;}
        if(previous!=null&&(now<previousAt||now-previousAt>12000||along<previousAlong||along-previousAlong>180))clearEntry();
        if(pendingEntry!=null&&(along-pendingEntry.offset>100||(current!=null&&current.kmh!=pendingEntry.kmh)))pendingEntry=null;
        if(current==null)return;
        if(previous!=null&&previous.kmh!=current.kmh){
            Change c=next(sections,previous,previousAlong,0);
            if(c!=null&&c.kmh==current.kmh&&Math.abs(c.offset-current.start)<=1&&along>=c.offset+3&&along-c.offset<=100)pendingEntry=c;
        }
        entryConfirmed=pendingEntry!=null&&current.kmh==pendingEntry.kmh;
        previous=current;previousAlong=along;previousAt=now;
    }
    Change pendingEntry(){return entryConfirmed?pendingEntry:null;}
    void entryAnnounced(){pendingEntry=null;entryConfirmed=false;}
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
