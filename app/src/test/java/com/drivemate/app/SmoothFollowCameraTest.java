package com.drivemate.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SmoothFollowCameraTest {
    private final List<GeoMath.Point> east = Arrays.asList(
            new GeoMath.Point(53,-2.0),new GeoMath.Point(53,-1.985));
    private final RouteMotionIndex index = new RouteMotionIndex(east);

    private NavigationCamera.Frame target(double along, double zoom, double bearing, double ahead) {
        return new NavigationCamera.Frame(index.pointAt(along + 100), zoom, bearing, ahead);
    }

    @Test public void displaysIntermediatePositionsBetweenOneSecondGpsFixes() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,2,5,14,500,
                target(100,16,90,500),false,1000);
        SmoothFollowCamera.Frame first = motion.frame(1000);
        SmoothFollowCamera.Frame middle = motion.frame(1500);
        SmoothFollowCamera.Frame later = motion.frame(1900);
        assertTrue(middle.vehicle.lon > first.vehicle.lon);
        assertTrue(later.vehicle.lon > middle.vehicle.lon);
        assertTrue(later.predicted);
        assertTrue(middle.center.lon >= first.center.lon);
    }

    @Test public void avoidsRunawayDeadReckoningOnGpsDropout() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,1,5,25,500,
                target(100,16,90,500),false,1000);
        motion.frame(1000);
        SmoothFollowCamera.Frame last = motion.frame(2200);
        SmoothFollowCamera.Frame muchLater = motion.frame(8000);
        assertFalse(last.predicted);
        assertEquals(last.vehicle.lon,muchLater.vehicle.lon,0.0000001);
    }

    @Test public void neverJumpsToAHighErrorGpsFix() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,0,5,6,300,
                target(100,16,90,300),false,1000);
        motion.frame(1000);
        GeoMath.Point far = new GeoMath.Point(53.01,-2.08);
        motion.onGps(far,800,120,100,20,300,
                target(700,19,270,300),false,2000);
        SmoothFollowCamera.Frame frame = motion.frame(2100);
        assertTrue(GeoMath.distance(frame.vehicle, far)>200);
        assertTrue(frame.zoom < 17);
        assertFalse(frame.predicted);
    }

    @Test public void smoothsZoomAndSuppressesSmallZoomHunting() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,0,5,8,500,
                target(100,16,90,500),false,1000);
        motion.frame(1000);
        motion.onGps(index.pointAt(110),110,0,5,8,490,
                target(110,16.10,90,490),false,2000);
        SmoothFollowCamera.Frame held = motion.frame(2100);
        assertEquals(16.0, held.zoom, 0.015);
        motion.onGps(index.pointAt(130),130,0,5,8,150,
                target(130,18,90,150),false,3000);
        double last = held.zoom;
        for (long now=3100;now<=4000;now+=100){
            SmoothFollowCamera.Frame step=motion.frame(now);
            assertTrue(step.zoom>=last-0.001);
            assertTrue(step.zoom-last<=0.076);
            last=step.zoom;
        }
        assertTrue(last>16 && last<18);
    }

    @Test public void postTurnHoldsCloseZoomBeforeZoomingOut() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,0,5,8,45,
                target(100,18,90,45),false,1000);
        motion.frame(1000);
        motion.onGps(index.pointAt(130),130,0,5,8,850,
                target(130,15,90,850),false,2000);
        SmoothFollowCamera.Frame soon=motion.frame(2100);
        assertEquals(18.0,soon.zoom,0.005);
        motion.onGps(index.pointAt(160),160,0,5,8,820,
                target(160,15,90,820),false,4400);
        SmoothFollowCamera.Frame later=motion.frame(4500);
        assertTrue(later.zoom<18.0);
    }

    @Test public void bearingTakesShortestPathAroundNorth() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,0,5,10,300,
                target(100,16,350,300),false,1000);
        motion.frame(1000);
        motion.onGps(index.pointAt(120),120,0,5,10,280,
                target(120,16,10,280),false,2000);
        SmoothFollowCamera.Frame after=motion.frame(2100);
        assertTrue(after.bearing>345 || after.bearing<15);
    }

    @Test public void stationaryGpsDoesNotSpinCamera() {
        SmoothFollowCamera motion = new SmoothFollowCamera(index);
        motion.onGps(index.pointAt(100),100,0,6,0,200,
                target(100,16,90,200),false,1000);
        motion.frame(1000);
        motion.onGps(index.pointAt(102),102,0,6,0,195,
                target(102,16,270,195),false,2000);
        assertEquals(90,motion.frame(2100).bearing,0.001);
    }
}
