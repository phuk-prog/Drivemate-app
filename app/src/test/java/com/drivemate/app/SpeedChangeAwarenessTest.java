package com.drivemate.app;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class SpeedChangeAwarenessTest {
    private RouteIntelligence.SpeedSection s(double a,double b,int v){return new RouteIntelligence.SpeedSection(a,b,v);}
    @Test public void warnsBeforeExplicitBoundaryOnce(){
        RouteIntelligence.SpeedSection a=s(0,200,64),b=s(200,400,48);
        SpeedChangeAwareness.Change c=SpeedChangeAwareness.next(Arrays.asList(a,b),a,110,18);
        assertNotNull(c);assertEquals(48,c.kmh);assertEquals(200,c.offset,0);
        SpeedChangeAwareness w=new SpeedChangeAwareness();assertTrue(w.shouldAnnounce(c));assertFalse(w.shouldAnnounce(c));w.reset();assertTrue(w.shouldAnnounce(c));
    }
    @Test public void rejectsGapConflictUnknownAndDistant(){
        RouteIntelligence.SpeedSection a=s(0,200,64),b=s(200,400,48);
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,s(210,400,48)),a,110,18));
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,b,s(200,400,80)),a,110,18));
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,b),null,110,18));
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,b),a,10,0));
    }
}
