package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class SpeedClusterPolicyTest {
    private static final double TOL = SpeedClusterPolicy.tolerance(2, false);

    @Test public void classifiesAgainstLimitAndTolerance() {
        assertEquals(SpeedClusterPolicy.Level.NORMAL, SpeedClusterPolicy.classify(30, 30, TOL));
        assertEquals(SpeedClusterPolicy.Level.NORMAL, SpeedClusterPolicy.classify(32, 30, TOL));
        assertEquals(SpeedClusterPolicy.Level.OVER, SpeedClusterPolicy.classify(32.5, 30, TOL));
        assertEquals(SpeedClusterPolicy.Level.WELL_OVER, SpeedClusterPolicy.classify(34, 30, TOL));
        // Small limits: 10% over is inside the tolerance, so never "well over" before "over".
        assertEquals(SpeedClusterPolicy.Level.NORMAL, SpeedClusterPolicy.classify(21.5, 20, TOL));
        assertEquals(SpeedClusterPolicy.Level.WELL_OVER, SpeedClusterPolicy.classify(23, 20, TOL));
    }

    @Test public void unknownLimitIsNeverOver() {
        assertEquals(SpeedClusterPolicy.Level.NORMAL, SpeedClusterPolicy.classify(90, -1, TOL));
        assertEquals(SpeedClusterPolicy.Level.NORMAL, SpeedClusterPolicy.classify(Double.NaN, 30, TOL));
    }

    @Test public void overMustLastTwoSecondsButSlowingShowsAtOnce() {
        SpeedClusterPolicy p = new SpeedClusterPolicy();
        assertEquals(SpeedClusterPolicy.Level.NORMAL, p.update(SpeedClusterPolicy.Level.OVER, 1000));
        assertEquals(SpeedClusterPolicy.Level.NORMAL, p.update(SpeedClusterPolicy.Level.OVER, 2500));
        assertEquals(SpeedClusterPolicy.Level.OVER, p.update(SpeedClusterPolicy.Level.OVER, 3000));
        assertEquals(SpeedClusterPolicy.Level.NORMAL, p.update(SpeedClusterPolicy.Level.NORMAL, 3100));
        // A brief spike does not turn the cluster red.
        p.update(SpeedClusterPolicy.Level.WELL_OVER, 4000);
        assertEquals(SpeedClusterPolicy.Level.NORMAL, p.update(SpeedClusterPolicy.Level.NORMAL, 4500));
        assertEquals(SpeedClusterPolicy.Level.NORMAL, p.update(SpeedClusterPolicy.Level.WELL_OVER, 5000));
    }

    @Test public void metricToleranceAndNumbers() {
        assertEquals(3.22, SpeedClusterPolicy.tolerance(2, true), 0.01);
        assertEquals("30", SpeedClusterPolicy.speedNumber(13.41, false));
        assertEquals("48", SpeedClusterPolicy.speedNumber(13.41, true));
        assertEquals("–", SpeedClusterPolicy.speedNumber(-1, false));
        assertEquals("mph", SpeedClusterPolicy.unit(false));
    }
}
