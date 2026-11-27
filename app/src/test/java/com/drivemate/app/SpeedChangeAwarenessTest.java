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
    @Test public void entryRequiresRecentConfirmedPredecessorAndExplicitBoundary(){
        RouteIntelligence.SpeedSection a=s(0,200,48),b=s(200,400,32);
        SpeedChangeAwareness w=new SpeedChangeAwareness();java.util.List<RouteIntelligence.SpeedSection> sections=Arrays.asList(a,b);
        w.observeEntry(sections,a,190,1000);w.observeEntry(sections,null,200,2000);w.observeEntry(sections,b,205,3000);
        assertNotNull(w.pendingEntry());assertEquals(32,w.pendingEntry().kmh);w.entryAnnounced();
        w.observeEntry(sections,b,220,4000);assertNull(w.pendingEntry());
        w.clearEntry();w.observeEntry(sections,a,190,5000);w.observeEntry(sections,b,210,18000);assertNull(w.pendingEntry());
        w.clearEntry();RouteIntelligence.SpeedSection gap=s(210,400,32);
        w.observeEntry(Arrays.asList(a,gap),a,190,20000);w.observeEntry(Arrays.asList(a,gap),gap,215,21000);assertNull(w.pendingEntry());
    }
    @Test public void rejectsGapConflictUnknownAndDistant(){
        RouteIntelligence.SpeedSection a=s(0,200,64),b=s(200,400,48);
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,s(210,400,48)),a,110,18));
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,b,s(200,400,80)),a,110,18));
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,b),null,110,18));
        assertNull(SpeedChangeAwareness.next(Arrays.asList(a,b),a,10,0));
    }
}
