package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationStartPolicyTest {
    /** The owner lifted the preview-only guard; flipping the constant restores it. */
    @Test public void localRoutesCanBeStartedWhileGuardIsLifted() {
        assertFalse(NavigationStartPolicy.LOCAL_ROUTES_PREVIEW_ONLY);
        assertFalse(NavigationStartPolicy.previewOnly("DriveMate local preview"));
        assertFalse(NavigationStartPolicy.previewOnly("TomTom"));
    }
    @Test public void trafficIsNotInventedForIndependentRouting() {
        assertEquals(" · live traffic not verified",NavigationStartPolicy.trafficStatus("DriveMate"));
        assertEquals(" · traffic checked",NavigationStartPolicy.trafficStatus("TomTom"));
    }
    @Test public void startRequiresFreshAccurateFix() {
        long now=100000;
        assertTrue(NavigationStartPolicy.freshFix(53.4,-2.1,now-15000,now,50));
        assertFalse(NavigationStartPolicy.freshFix(53.4,-2.1,now-15001,now,50));
        assertFalse(NavigationStartPolicy.freshFix(53.4,-2.1,now+1,now,50));
        assertFalse(NavigationStartPolicy.freshFix(53.4,-2.1,now,now,101));
        assertFalse(NavigationStartPolicy.freshFix(53.4,-2.1,now,now,Float.NaN));
    }
}
