package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceCuePlannerTest {
    @Test public void minimalOnlySpeaksAtTurn() {
        assertEquals(0, VoiceCuePlanner.stage("minimal", 180, 10));
        assertEquals(3, VoiceCuePlanner.stage("minimal", 50, 10));
    }
    @Test public void normalHasCloseButNoEarly() {
        assertEquals(2, VoiceCuePlanner.stage("normal", 180, 10));
        assertEquals(0, VoiceCuePlanner.stage("normal", 700, 10));
    }
    @Test public void detailedIncludesEarlyCue() {
        assertEquals(1, VoiceCuePlanner.stage("detailed", 700, 10));
    }
    @Test public void stageTransitionIsMonotonicAsDistanceShrinks() {
        int a=VoiceCuePlanner.stage("detailed",1000,20);
        int b=VoiceCuePlanner.stage("detailed",400,20);
        int c=VoiceCuePlanner.stage("detailed",40,20);
        assertTrue(a <= b && b <= c);
    }
}
