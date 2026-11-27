package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class TurnPenaltyTest {
    private static final GeoMath.Point BEFORE = new GeoMath.Point(53.000, -2.000);
    private static final GeoMath.Point AT = new GeoMath.Point(53.001, -2.000);   // travelling north

    @Test public void straightOnIsFree() {
        assertEquals(0, TurnPenalty.compute(TurnPenalty.turnDegrees(BEFORE, AT, new GeoMath.Point(53.002, -2.000))), 0);
    }

    @Test public void rightTurnCostsMoreThanLeft() {
        double right = TurnPenalty.compute(TurnPenalty.turnDegrees(BEFORE, AT, new GeoMath.Point(53.001, -1.999)));
        double left = TurnPenalty.compute(TurnPenalty.turnDegrees(BEFORE, AT, new GeoMath.Point(53.001, -2.001)));
        assertEquals(TurnPenalty.RIGHT_TURN_SECONDS, right, 0);
        assertEquals(TurnPenalty.LEFT_TURN_SECONDS, left, 0);
        assertTrue(right > left);
    }

    @Test public void uTurnIsExpensive() {
        assertEquals(TurnPenalty.U_TURN_SECONDS, TurnPenalty.compute(TurnPenalty.turnDegrees(BEFORE, AT, BEFORE)), 0);
    }

    @Test public void slightBendCostsLittle() {
        assertEquals(TurnPenalty.SLIGHT_TURN_SECONDS, TurnPenalty.compute(40), 0);
        assertEquals(TurnPenalty.SLIGHT_TURN_SECONDS, TurnPenalty.compute(-40), 0);
        assertEquals(0, TurnPenalty.compute(20), 0);
    }

    @Test public void signConvention() {
        assertTrue(TurnPenalty.turnDegrees(BEFORE, AT, new GeoMath.Point(53.001, -1.999)) > 0);   // east = right
        assertTrue(TurnPenalty.turnDegrees(BEFORE, AT, new GeoMath.Point(53.001, -2.001)) < 0);   // west = left
    }

    @Test public void nullOrNonFiniteIsZero() {
        assertEquals(0, TurnPenalty.seconds(null, AT, BEFORE), 0);
        assertEquals(0, TurnPenalty.compute(Double.NaN), 0);
    }

    /** The emulator route test on real tiles changed a verified route when this was on. */
    @Test public void routerPenaltyIsOffUntilValidatedOnRealRoutes() {
        assertFalse(TurnPenalty.ENABLED);
        assertEquals(0, TurnPenalty.seconds(BEFORE, AT, BEFORE), 0);
    }

    @Test public void penaltiesStayModest() {
        assertTrue(TurnPenalty.RIGHT_TURN_SECONDS <= 10);
        assertTrue(TurnPenalty.U_TURN_SECONDS <= 60);
    }
}
