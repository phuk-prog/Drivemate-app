package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DrivingDisplayPolicyTest {
    @Test public void closeFollowingTurnIsShownOnlyAtUsefulDistance() {
        assertTrue(DrivingDisplayPolicy.showFollowingTurn(250, 80));
        assertTrue(DrivingDisplayPolicy.showFollowingTurn(900, 450));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(1200, 80));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(150, 600));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(150, -1));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(Double.NaN, 5));
    }

    @Test public void followingTurnAdaptsToSpeed() {
        assertTrue(DrivingDisplayPolicy.showFollowingTurn(200, 70, 5));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(200, 250, 5));
        assertTrue(DrivingDisplayPolicy.showFollowingTurn(700, 250, 30));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(700, 250, 5));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(100, -1, 30));
        assertFalse(DrivingDisplayPolicy.showFollowingTurn(Double.NaN, 20, 30));
    }

    @Test public void uncertainFixAndMovingInspectionAreRejected() {
        assertTrue(DrivingDisplayPolicy.guidanceFixTrusted(55, 15000));
        assertFalse(DrivingDisplayPolicy.guidanceFixTrusted(56, 100));
        assertFalse(DrivingDisplayPolicy.guidanceFixTrusted(10, 15001));
        assertFalse(DrivingDisplayPolicy.guidanceFixTrusted(Double.NaN, 100));
        assertTrue(DrivingDisplayPolicy.canInspectManoeuvres(0, 100));
        assertFalse(DrivingDisplayPolicy.canInspectManoeuvres(1, 100));
        assertFalse(DrivingDisplayPolicy.canInspectManoeuvres(0, 7001));
    }

    @Test public void cardKeepsProviderTextAndSplitsExactRoad() {
        assertEquals("Turn left", DrivingDisplayPolicy.cardAction("Turn left onto A61", "A61", false, false, "↰"));
        assertEquals("Exit onto", DrivingDisplayPolicy.cardAction("At the roundabout take the exit onto A61", "A61", true, false, "↻"));
        assertEquals("Keep left", DrivingDisplayPolicy.cardAction("Keep left onto M1", "M1", false, true, "↖"));
        assertEquals("Take exit 33 towards Sheffield", DrivingDisplayPolicy.cardAction("Take exit 33 towards Sheffield", "M1", false, false, "↰"));
        assertFalse(DrivingDisplayPolicy.showSeparateRoad("Continue on A61", "A61"));
        assertTrue(DrivingDisplayPolicy.showSeparateRoad("Turn left", "A61"));
    }

    @Test public void twoDModesNeverTilt() {
        assertEquals(38, DrivingDisplayPolicy.tilt(38, DrivingDisplayPolicy.CAMERA_AUTO_3D), 0.01);
        assertEquals(0, DrivingDisplayPolicy.tilt(38, DrivingDisplayPolicy.CAMERA_HEADING_2D), 0.01);
        assertEquals(0, DrivingDisplayPolicy.tilt(38, DrivingDisplayPolicy.CAMERA_NORTH_2D), 0.01);
        assertFalse(DrivingDisplayPolicy.northUp(DrivingDisplayPolicy.CAMERA_HEADING_2D));
        assertTrue(DrivingDisplayPolicy.northUp(DrivingDisplayPolicy.CAMERA_NORTH_2D));
    }

    @Test public void themeOverrideAndAutoSystemChoice() {
        assertFalse(DrivingDisplayPolicy.night(DrivingDisplayPolicy.THEME_AUTO, false));
        assertTrue(DrivingDisplayPolicy.night(DrivingDisplayPolicy.THEME_AUTO, true));
        assertFalse(DrivingDisplayPolicy.night(DrivingDisplayPolicy.THEME_DAY, true));
        assertTrue(DrivingDisplayPolicy.night(DrivingDisplayPolicy.THEME_NIGHT, false));
    }

    @Test public void junctionPreviewRequiresNearVerifiedRoundabout() {
        assertTrue(DrivingDisplayPolicy.junctionPreview(true, 400));
        assertFalse(DrivingDisplayPolicy.junctionPreview(false, 20));
        assertFalse(DrivingDisplayPolicy.junctionPreview(true, 600));
        assertFalse(DrivingDisplayPolicy.junctionPreview(true, Double.NaN));
    }

    @Test public void mapStartsLightAndMenuOffersLightDarkAutomatic() {
        assertEquals(DrivingDisplayPolicy.THEME_DAY, DrivingDisplayPolicy.DEFAULT_THEME);
        assertFalse(DrivingDisplayPolicy.night(DrivingDisplayPolicy.DEFAULT_THEME, true));
        assertArrayEquals(new int[]{DrivingDisplayPolicy.THEME_DAY,
                DrivingDisplayPolicy.THEME_NIGHT, DrivingDisplayPolicy.THEME_AUTO},
                DrivingDisplayPolicy.THEME_MENU);
        assertEquals("Light", DrivingDisplayPolicy.themeName(DrivingDisplayPolicy.THEME_DAY));
        assertEquals("Dark", DrivingDisplayPolicy.themeName(DrivingDisplayPolicy.THEME_NIGHT));
        assertEquals("Automatic", DrivingDisplayPolicy.themeName(DrivingDisplayPolicy.THEME_AUTO));
    }

    @Test public void unknownStoredThemeFallsBackToLight() {
        assertEquals(DrivingDisplayPolicy.THEME_DAY, DrivingDisplayPolicy.validTheme(7));
        assertEquals(DrivingDisplayPolicy.THEME_DAY, DrivingDisplayPolicy.validTheme(-1));
        assertEquals(DrivingDisplayPolicy.THEME_NIGHT, DrivingDisplayPolicy.validTheme(2));
        assertEquals(DrivingDisplayPolicy.THEME_AUTO, DrivingDisplayPolicy.validTheme(0));
    }
}
