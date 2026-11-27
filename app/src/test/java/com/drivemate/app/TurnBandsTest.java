package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class TurnBandsTest {
    private static String code(double turn, boolean reversed, boolean choice) {
        TurnBands.Band b = TurnBands.classify(turn, reversed, choice);
        return b == null ? null : b.code;
    }

    @Test public void smallAnglesAreNotATurn() {
        assertNull(TurnBands.classify(0, false, true));
        assertNull(TurnBands.classify(29.9, false, true));
        assertNull(TurnBands.classify(Double.NaN, false, true));
    }

    @Test public void bearBand() {
        assertEquals("BEAR_RIGHT", code(30, false, true));
        assertEquals("BEAR_LEFT", code(-45, false, true));
        assertEquals("Bear left", TurnBands.classify(-45, false, true).message);
    }

    @Test public void turnBand() {
        assertEquals("TURN_RIGHT", code(60, false, true));
        assertEquals("TURN_LEFT", code(-90, false, true));
        assertEquals("TURN_LEFT", code(-119.9, false, true));
    }

    @Test public void sharpBand() {
        assertEquals("SHARP_RIGHT", code(120, false, true));
        assertEquals("SHARP_LEFT", code(-140, false, true));
        assertEquals("Turn sharp left", TurnBands.classify(-140, false, true).message);
    }

    @Test public void uTurnOnlyWhenReversingTheSameRoad() {
        assertEquals("UTURN_RIGHT", code(170, true, false));
        assertEquals("UTURN_LEFT", code(-175, true, true));
        assertEquals("Make a U-turn", TurnBands.classify(170, true, false).message);
    }

    @Test public void hairpinOntoOtherRoadNeedsAChoice() {
        assertEquals("SHARP_RIGHT", code(165, false, true));
        assertNull(code(165, false, false));
    }

    @Test public void keepWording() {
        assertEquals("KEEP_LEFT", TurnBands.keep(true).code);     // other road leaves on the right
        assertEquals("KEEP_RIGHT", TurnBands.keep(false).code);
        assertEquals("Keep left", TurnBands.keep(true).message);
    }

    @Test public void codesAreUnderstoodByTheIconLogic() {
        assertEquals("↖", GuidanceLogic.symbol("BEAR_LEFT", "REGULAR", -40));
        assertEquals("↗", GuidanceLogic.symbol("KEEP_RIGHT", "REGULAR", 5));
        assertEquals("↰", GuidanceLogic.symbol("SHARP_LEFT", "REGULAR", -140));
        assertEquals("⤻", GuidanceLogic.symbol("UTURN_RIGHT", "REGULAR", 170));
        assertEquals("⤺", GuidanceLogic.symbol("UTURN_LEFT", "REGULAR", -170));
    }
}
