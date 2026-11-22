package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ManeuverGuideTest {
    private TomTomApi.Instruction step(double offset, String maneuver, String street, int exit) {
        return new TomTomApi.Instruction(offset, "Continue", maneuver, "TURN",
                exit > 0 ? "ROUNDABOUT" : "REGULAR", street, "", exit, 0, 50);
    }
    @Test public void arrowsFollowTomTomCodes() {
        assertEquals(ManeuverGuide.Arrow.LEFT, ManeuverGuide.arrow("TURN_LEFT", "", 0));
        assertEquals(ManeuverGuide.Arrow.RIGHT, ManeuverGuide.arrow("TURN_RIGHT", "", 0));
        assertEquals(ManeuverGuide.Arrow.ROUNDABOUT, ManeuverGuide.arrow("ROUNDABOUT_LEFT", "", 0));
        assertEquals(ManeuverGuide.Arrow.UTURN, ManeuverGuide.arrow("MAKE_UTURN", "", 0));
        assertEquals(ManeuverGuide.Arrow.STRAIGHT, ManeuverGuide.arrow("FUTURE_CODE", "", 0));
    }
    @Test public void followingStepAndRoundaboutExitUseActualFields() {
        TomTomApi.Instruction a = step(120, "ROUNDABOUT_LEFT", "High Street", 3);
        TomTomApi.Instruction b = step(300, "TURN_RIGHT", "Station Road", 0);
        assertEquals(0, ManeuverGuide.nextIndex(Arrays.asList(a,b), 50));
        assertEquals(1, ManeuverGuide.followingIndex(Arrays.asList(a,b), 0));
        assertEquals("Roundabout · 3rd exit", ManeuverGuide.title(a));
        assertEquals("Onto High Street", ManeuverGuide.streetLabel(a));
        assertEquals("11th", ManeuverGuide.ordinal(11));
        assertEquals("22nd", ManeuverGuide.ordinal(22));
    }
    @Test public void lastStepHasNoFollowingStep() {
        assertEquals(-1, ManeuverGuide.followingIndex(
                Arrays.asList(step(10, "STRAIGHT", "",0)), 0));
    }
}
