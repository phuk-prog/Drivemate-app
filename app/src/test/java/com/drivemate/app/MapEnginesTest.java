package com.drivemate.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class MapEnginesTest {
    @Test public void ownGpuEngineIsTheDefaultAndUnknownValuesFallBackToIt() {
        assertEquals(MapEngines.GPU, MapEngines.DEFAULT);
        assertEquals(MapEngines.DEFAULT, MapEngines.normalize(null));
        assertEquals(MapEngines.DEFAULT, MapEngines.normalize("something-old"));
        assertEquals(MapEngines.CLASSIC, MapEngines.normalize("classic"));
    }

    @Test public void everyEngineHasAPlainLabel() {
        for (String id : MapEngines.ALL) {
            assertNotEquals("", MapEngines.label(id));
            assertNotEquals("", MapEngines.description(id));
        }
        assertEquals("DriveMate classic", MapEngines.label(MapEngines.CLASSIC));
    }
}
