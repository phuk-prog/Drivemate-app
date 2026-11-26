package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuidanceLogicTest {
    @Test public void showsTurnsAndRoundabouts() {
        assertEquals("↻", GuidanceLogic.symbol("TURN_RIGHT", "ROUNDABOUT", 40));
        assertEquals("↰", GuidanceLogic.symbol("TURN_LEFT", "REGULAR", -70));
        assertEquals("↱", GuidanceLogic.symbol("TURN_RIGHT", "REGULAR", 70));
        assertEquals("↑", GuidanceLogic.symbol("STRAIGHT", "REGULAR", 0));
        assertTrue(GuidanceLogic.actionLine("At roundabout", "ROUNDABOUT", 2)
                .contains("2nd exit"));
    }
    @Test public void verifiedForksUseBearArrowsInsteadOfSharpTurns() {
        assertEquals("↖", GuidanceLogic.symbol("KEEP_LEFT", "BIFURCATION", -35));
        assertEquals("↗", GuidanceLogic.symbol("KEEP_RIGHT", "BIFURCATION", 35));
        assertEquals("↑", GuidanceLogic.symbol("STRAIGHT", "BIFURCATION", 0));
        assertEquals("↻", GuidanceLogic.symbol("ROUNDABOUT_RIGHT", "ROUNDABOUT", 45));
    }

    @Test public void explicitForkAndSlightDirectionWinsOverAngle() {
        assertEquals("↖", GuidanceLogic.symbol("KEEP_LEFT","BIFURCATION",45));
        assertEquals("↗", GuidanceLogic.symbol("KEEP_RIGHT","BIFURCATION",-45));
        assertEquals("↑", GuidanceLogic.symbol("STRAIGHT","BIFURCATION",45));
        assertEquals("↗", GuidanceLogic.symbol("RIGHT","Y_JUNCTION",0));
        assertEquals("↖", GuidanceLogic.symbol("LEFT","FORK",Double.NaN));
        assertEquals("↖", GuidanceLogic.symbol("SLIGHT_LEFT","REGULAR",-15));
        assertEquals("↗", GuidanceLogic.symbol("SLIGHT_RIGHT","REGULAR",15));
    }

    @Test public void avoidsBacktrackingThroughRoundabout() {
        double[] offsets = {0, 70, 90, 130, 250};
        assertEquals(4, GuidanceLogic.nextInstructionIndex(offsets, 150, 2));
        assertEquals(4, GuidanceLogic.nextInstructionIndex(offsets, 77, 4));
        assertEquals(1, GuidanceLogic.nextInstructionIndex(offsets, 0, 0));
    }
    @Test public void etaUsesTrafficBaselineAndBoundedSpeed() {
        assertEquals(600, GuidanceLogic.remainingSeconds(3000, 3000, 600, 0));
        long fast = GuidanceLogic.remainingSeconds(3000, 3000, 600, 17);
        assertTrue(fast < 600 && fast > 440);
        long stopped = GuidanceLogic.remainingSeconds(1500, 3000, 600, 0);
        assertEquals(300, stopped);
    }
    @Test public void speechModesHavePredictableThresholds() {
        assertTrue(GuidanceLogic.announce(0, 1000, 0));
        assertFalse(GuidanceLogic.announce(1, 1000, 0));
        assertTrue(GuidanceLogic.announce(1, 200, 1));
        assertFalse(GuidanceLogic.announce(2, 200, 1));
        assertTrue(GuidanceLogic.announce(2, 55, 2));
    }
    @Test public void progressResistsSmallGpsBacktracking() {
        assertEquals(192, GuidanceLogic.monotonicProgress(200, 170, 10, 5), 0.01);
        assertEquals(200, GuidanceLogic.monotonicProgress(200, 600, 120, 5), 0.01);
        assertEquals(200, GuidanceLogic.monotonicProgress(200, 600, 15, 95), 0.01);
    }
    @Test public void normalizesOnlyFullPostcodes() {
        assertEquals("SK3 0UX", UkSearch.normalize("sk30ux"));
        assertEquals("SW1A 1AA", UkSearch.normalize("sw1a1aa"));
        assertEquals("Bestway Stockport", UkSearch.normalize("Bestway Stockport"));
    }

    @Test public void roundaboutLineNamesTheExitRoadWithoutCountingExits() {
        assertEquals("At the roundabout, take the exit onto Carrs Road",
                GuidanceLogic.roundaboutLine("Carrs Road",
                        "At the roundabout take the first exit onto Carrs Road"));
        // No road name: TomTom's message is the only information, so keep it.
        assertEquals("At the roundabout take the second exit",
                GuidanceLogic.roundaboutLine("  ", "At the roundabout take the second exit"));
        assertEquals("", GuidanceLogic.roundaboutLine(null, null));
    }
}
