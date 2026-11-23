package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TripHeaderFormatTest {
    @Test public void minutesRoundUpUntilArrival() {
        assertEquals("0 min", TripHeaderFormat.minutesLeft(0));
        assertEquals("1 min", TripHeaderFormat.minutesLeft(1));
        assertEquals("9 min", TripHeaderFormat.minutesLeft(520));
        assertEquals("61 min", TripHeaderFormat.minutesLeft(3601));
    }

    @Test public void uKDrivingSummaryAlwaysShowsMiles() {
        assertEquals("2.2 mi", TripHeaderFormat.distanceLeft(3540.5568, false));
        assertEquals("<0.1 mi", TripHeaderFormat.distanceLeft(50, false));
        assertEquals("0.0 mi", TripHeaderFormat.distanceLeft(0, false));
    }

    @Test public void optionalMetricPreferenceStillWorks() {
        assertEquals("2.2 km", TripHeaderFormat.distanceLeft(2200, true));
        assertEquals("12 km", TripHeaderFormat.distanceLeft(12000, true));
    }
}
