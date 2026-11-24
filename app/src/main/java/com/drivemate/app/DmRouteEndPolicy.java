package com.drivemate.app;
/** Experimental route-end classification; reaching a road is not reaching a place. */
public final class DmRouteEndPolicy {
    public enum State { CONTINUE, ROAD_END_ACCESS_UNVERIFIED, ARRIVAL_PREVIEW }
    private int consecutive;
    private long last=-1;
    public void reset(){consecutive=0;last=-1;}
    public State observe(double remaining,double endpointDistance,double pinDistance,double accuracy,double speed,long time){
        if(!Double.isFinite(remaining)||!Double.isFinite(endpointDistance)||!Double.isFinite(pinDistance)
                ||!Double.isFinite(accuracy)||accuracy<0||accuracy>25||remaining<0||remaining>25
                ||endpointDistance<0||endpointDistance>25||pinDistance<0||time<0){reset();return State.CONTINUE;}
        if(pinDistance>35){reset();return State.ROAD_END_ACCESS_UNVERIFIED;}
        if(!Double.isFinite(speed)||speed<0||speed>1.5){reset();return State.CONTINUE;}
        if(last>=0&&time<=last){reset();return State.CONTINUE;}
        if(last>=0&&time-last>5000)consecutive=0;
        if(last<0||time-last>=900){consecutive++;last=time;}
        return consecutive>=3?State.ARRIVAL_PREVIEW:State.CONTINUE;
    }
}
