package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class StartSnapTest {
    @Test public void unknownHeadingAddsNoPenalty() {
        assertEquals(0, StartSnap.penaltyMetres(Double.NaN, new double[] {10, 200}), 0);
    }

    @Test public void noExitsAddsNoPenalty() {
        assertEquals(0, StartSnap.penaltyMetres(90, new double[0]), 0);
        assertEquals(0, StartSnap.penaltyMetres(90, null), 0);
    }

    @Test public void exitAlongTravelDirectionIsFree() {
        assertEquals(0, StartSnap.penaltyMetres(90, new double[] {100, 270}), 0);
    }

    @Test public void exitRoughlySideways() {
        assertEquals(StartSnap.ROUGH_PENALTY_METRES, StartSnap.penaltyMetres(0, new double[] {70, 250}), 0);
    }

    @Test public void onlyExitsBehindTheCarArePenalisedHard() {
        assertEquals(StartSnap.OPPOSED_PENALTY_METRES, StartSnap.penaltyMetres(0, new double[] {180}), 0);
        assertEquals(StartSnap.OPPOSED_PENALTY_METRES, StartSnap.penaltyMetres(10, new double[] {170, 200}), 0);
    }

    @Test public void wrapsAroundNorth() {
        assertEquals(0, StartSnap.penaltyMetres(350, new double[] {10}), 0);
    }

    /** A junction 20 m behind the car loses to one 80 m ahead once the heading is known. */
    @Test public void aheadJunctionBeatsCloserJunctionBehind() {
        double heading = 0;                                   // driving north
        double behind = 20 + StartSnap.penaltyMetres(heading, new double[] {180});   // only exit leads south
        double ahead = 80 + StartSnap.penaltyMetres(heading, new double[] {0, 180});
        assertTrue(ahead < behind);
    }
}
