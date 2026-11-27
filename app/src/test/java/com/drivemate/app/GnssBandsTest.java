package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class GnssBandsTest {
    @Test public void recognisesL5() {
        assertTrue(GnssBands.isL5(1_176_450_000.0));
        assertTrue(GnssBands.isL5(1_178_000_000.0));
    }

    @Test public void rejectsL1AndL2AndGarbage() {
        assertFalse(GnssBands.isL5(1_575_420_000.0));   // GPS L1
        assertFalse(GnssBands.isL5(1_227_600_000.0));   // GPS L2
        assertFalse(GnssBands.isL5(Double.NaN));
        assertFalse(GnssBands.isL5(0));
    }
}
