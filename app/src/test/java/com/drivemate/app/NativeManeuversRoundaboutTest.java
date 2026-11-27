package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

/** Roundabout and road-number rules from the Roscoe's Roundabout (Cheadle) drive. */
public class NativeManeuversRoundaboutTest {
    private static GeoMath.Point p(double lat, double lon) { return new GeoMath.Point(lat, lon); }
    private static JSONObject t(String name) throws Exception { return new JSONObject().put("highway", "residential").put("name", name); }

    /** Westbound approach, 5 m on the A560 roundabout, then out onto residential Carrs Road (as in OSM). */
    private static List<TomTomApi.Instruction> roscoes() throws Exception {
        List<GeoMath.Point> pts = Arrays.asList(p(53.397879, -2.199088), p(53.397667, -2.199730),
                p(53.397642, -2.199798), p(53.397442, -2.199698), p(53.396928, -2.194945));
        Map<Long, JSONObject> tags = new HashMap<>();
        tags.put(4246266L, new JSONObject().put("highway", "primary").put("name", "Stockport Road").put("ref", "A560").put("oneway", "yes").put("lanes", 3));
        tags.put(566783175L, new JSONObject().put("highway", "primary").put("junction", "circular").put("name", "Roscoe's Roundabout").put("ref", "A560"));
        tags.put(4953964L, t("Carrs Road").put("oneway", "yes"));
        tags.put(4953963L, t("Carrs Road"));
        JunctionDetail[] details = new JunctionDetail[5];
        details[1] = JunctionDetail.NONE; details[2] = JunctionDetail.NONE; details[3] = JunctionDetail.NONE;
        return NativeManeuvers.steps(pts, Arrays.asList(4246266L, 566783175L, 4953964L, 4953963L), tags, new int[]{0, 2, 2, 1, 0}, details);
    }

    @Test public void immediateFirstExitSaysKeepLeftAndCarriesNoRoundaboutRef() throws Exception {
        TomTomApi.Instruction enter = roscoes().get(0);
        assertEquals("ROUNDABOUT", enter.maneuver);
        assertEquals(1, enter.exitNumber);
        assertEquals("Carrs Road", enter.street);
        assertTrue(enter.message, enter.message.contains("keep left") && enter.message.contains("straight away"));
        // Carrs Road has no road number: the roundabout's A560 must not be shown with it.
        assertEquals("", enter.roadNumbers);
    }

    @Test public void exitNumberMustAgreeWithOverallTurn() {
        assertTrue(RoundaboutExits.plausible(1, -90));      // 1st exit = left
        assertTrue(RoundaboutExits.plausible(2, 0));        // straight on
        assertFalse(RoundaboutExits.plausible(1, 90));      // a "1st exit" that turns right is wrong
        assertFalse(RoundaboutExits.plausible(4, -150));    // a late exit that is a hard left is wrong
        assertTrue(RoundaboutExits.plausible(3, 90));
        assertTrue(RoundaboutExits.plausible(2, Double.NaN));
        assertFalse(RoundaboutExits.plausible(0, 0));
    }

    @Test public void destinationWordingIsPlain() throws Exception {
        List<TomTomApi.Instruction> steps = roscoes();
        assertEquals("Arrive at your destination", steps.get(steps.size() - 1).message);
    }
}
