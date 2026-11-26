package com.drivemate.app;

import java.util.*;

/** A mapped point is eligible for speech only after exact route-node association. */
final class RouteRoadsideAwareness {
    static final class Event {
        final long id;
        final double offset;
        final String label;
        Event(long id,double offset,String label){this.id=id;this.offset=offset;this.label=label;}
    }
    private final Set<Long> announced=new HashSet<>();
    void reset(){announced.clear();}
    static Event next(List<Event> events,double along,double speed){
        if(events==null||!Double.isFinite(along))return null;
        double horizon=Math.max(70,Math.min(400,Math.max(0,Double.isFinite(speed)?speed:0)*9+40));
        Event best=null;
        for(Event e:events)if(e!=null&&Double.isFinite(e.offset)&&e.offset>along+8&&e.offset-along<=horizon
            &&(best==null||e.offset<best.offset))best=e;
        return best;
    }
    boolean announce(Event event){return event!=null&&announced.add(event.id);}
    static String label(String highway,String calming,String direction,boolean conditional){
        // Do not turn an unknown-direction camera into a warning, nor claim a
        // mapped signal is operational. Exact node membership is also required.
        if(conditional||!(direction.isEmpty()||"both".equals(direction)))return "";
        if("traffic_signals".equals(highway))return "Mapped traffic lights";
        if("crossing".equals(highway))return "Mapped crossing";
        if(Arrays.asList("bump","hump","table","cushion","rumble_strip","chicane").contains(calming))return "Mapped traffic calming";
        return "";
    }
}
