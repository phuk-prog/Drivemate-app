package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class CrossingTypeTest {
    @Test public void explicitTypesAndConflicts(){
        assertEquals(5,CrossingType.kind("zebra","marked","no"));
        assertEquals(6,CrossingType.kind("pelican","traffic_signals","yes"));
        assertEquals(7,CrossingType.kind("puffin","",""));
        assertEquals(8,CrossingType.kind("toucan","",""));
        assertEquals(11,CrossingType.kind("","traffic_signals","yes"));
        assertEquals(2,CrossingType.kind("pelican","puffin","yes"));
        assertEquals(2,CrossingType.kind("zebra","traffic_signals","yes"));
        assertEquals(2,CrossingType.kind("pelican","","no"));
        assertEquals(2,CrossingType.kind("","marked",""));
    }
}
