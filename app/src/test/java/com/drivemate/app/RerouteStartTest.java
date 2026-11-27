package com.drivemate.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class RerouteStartTest {
    private static final GeoMath.Point RAW = new GeoMath.Point(53, -2);

    @Test public void usesSmoothedWhenClose() {
        GeoMath.Point smooth = new GeoMath.Point(53.0001, -2);   // about 11 m
        assertSame(smooth, RerouteStart.choose(RAW, smooth));
    }

    @Test public void usesRawWhenSmoothedHasDrifted() {
        GeoMath.Point smooth = new GeoMath.Point(53.001, -2);    // about 111 m
        assertSame(RAW, RerouteStart.choose(RAW, smooth));
    }

    @Test public void handlesMissingPoints() {
        assertSame(RAW, RerouteStart.choose(RAW, null));
        assertSame(RAW, RerouteStart.choose(null, RAW));
        assertNull(RerouteStart.choose(null, null));
    }
}
