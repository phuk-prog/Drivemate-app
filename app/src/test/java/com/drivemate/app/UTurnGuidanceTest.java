package com.drivemate.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Ulverston/Aldi report: "Turn left Daltongate, then Try to make a U-turn at Daltongate". */
public class UTurnGuidanceTest {
    private static JSONObject route(double seconds, String... maneuvers) throws Exception {
        JSONArray steps = new JSONArray();
        double offset = 0;
        for (String m : maneuvers) { steps.put(new JSONObject().put("maneuver", m).put("routeOffsetInMeters", offset)); offset += 120; }
        return new JSONObject().put("summary", new JSONObject().put("travelTimeInSeconds", seconds))
                .put("guidance", new JSONObject().put("instructions", steps));
    }

    @Test public void routeWithAMidRouteUTurnLosesToASlightlySlowerOne() throws Exception {
        JSONArray routes = new JSONArray()
                .put(route(100, "DEPART", "TURN_LEFT", "TRY_MAKE_UTURN", "ARRIVE"))
                .put(route(130, "DEPART", "TURN_RIGHT", "ARRIVE"));
        assertTrue(TomTomApi.needlessUTurn(routes.getJSONObject(0)));
        assertEquals(1, TomTomApi.preferredRoute(routes));
    }

    @Test public void muchSlowerAlternativeIsNotWorthAvoidingTheUTurn() throws Exception {
        JSONArray routes = new JSONArray()
                .put(route(100, "DEPART", "TURN_LEFT", "MAKE_UTURN", "ARRIVE"))
                .put(route(600, "DEPART", "TURN_RIGHT", "ARRIVE"));
        assertEquals(0, TomTomApi.preferredRoute(routes));
    }

    @Test public void uTurnAtTheStartIsNormal() throws Exception {
        JSONObject r = new JSONObject().put("guidance", new JSONObject().put("instructions", new JSONArray()
                .put(new JSONObject().put("maneuver", "MAKE_UTURN").put("routeOffsetInMeters", 0))));
        assertFalse(TomTomApi.needlessUTurn(r));
        assertEquals(0, TomTomApi.preferredRoute(new JSONArray().put(route(100, "DEPART", "ARRIVE"))));
    }

    @Test public void uTurnIsWordedByDriveMateNotTheProvider() {
        TomTomApi.Instruction step = new TomTomApi.Instruction(500, "Try to make a U-turn at Daltongate", "TRY_MAKE_UTURN",
                "REGULAR", 0, 180, "Daltongate", "");
        assertEquals("Make a U-turn", IntelligentGuidance.action(step, "⤺", 0));
        assertEquals("Make a U-turn at Daltongate", IntelligentGuidance.direction(step, "⤺", 0));
        assertEquals("Then ⤺  Make a U-turn", IntelligentGuidance.thenLine(step, 40, false));
        assertEquals("Make a U-turn when possible", IntelligentGuidance.ownWords("Try to make a U-turn when possible"));
    }
}
