package com.drivemate.app;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class RouteRoadsideAwarenessTest {
    @Test public void onlyExplicitNonDirectionalFeatures(){
        assertEquals("Mapped traffic lights",RouteRoadsideAwareness.label("traffic_signals","","",false));
        assertEquals("Mapped crossing",RouteRoadsideAwareness.label("crossing","","both",false));
        assertEquals("",RouteRoadsideAwareness.label("speed_camera","","",false));
        assertEquals("Mapped speed camera",RouteRoadsideAwareness.label("speed_camera","","both",false));
        assertEquals("",RouteRoadsideAwareness.label("speed_camera","","forward",false));
        assertEquals("",RouteRoadsideAwareness.label("traffic_signals","","forward",false));
        assertEquals("",RouteRoadsideAwareness.label("crossing","","",true));
    }
    @Test public void specificCrossingRequiresExplicitNonConflictingType(){
        assertEquals("Mapped pelican crossing",RouteRoadsideAwareness.label("crossing","","",false,"pelican","traffic_signals","yes"));
        assertEquals("Mapped crossing",RouteRoadsideAwareness.label("crossing","","",false,"puffin","pelican","yes"));
        assertEquals("Mapped signal-controlled crossing",RouteRoadsideAwareness.label("crossing","","",false,"","traffic_signals","yes"));
        assertEquals("",RouteRoadsideAwareness.label("crossing","","forward",false,"zebra","","no"));
    }
    @Test public void nearestWithinHorizonAndOncePerRoute(){
        RouteRoadsideAwareness.Event a=new RouteRoadsideAwareness.Event(1,100,"Mapped crossing"),b=new RouteRoadsideAwareness.Event(2,250,"Mapped traffic lights");
        assertSame(a,RouteRoadsideAwareness.next(Arrays.asList(b,a),50,10));
        assertNull(RouteRoadsideAwareness.next(Arrays.asList(a),95,10));
        assertNull(RouteRoadsideAwareness.next(Arrays.asList(b),50,0));
        RouteRoadsideAwareness w=new RouteRoadsideAwareness();assertTrue(w.announce(a));assertFalse(w.announce(a));w.reset();assertTrue(w.announce(a));
    }
}
