package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class LaneTagsTest {
    @Test public void parsesLanesAndMultipleDirections() {
        List<List<String>> lanes = LaneTags.parse("left|through;right|right", 3);
        assertEquals(3, lanes.size());
        assertEquals(Arrays.asList("LEFT"), lanes.get(0));
        assertEquals(Arrays.asList("STRAIGHT", "RIGHT"), lanes.get(1));
    }

    @Test public void emptyValueMeansThrough() {
        List<List<String>> lanes = LaneTags.parse("left||right", 3);
        assertEquals(Arrays.asList("STRAIGHT"), lanes.get(1));
    }

    @Test public void rejectsMismatchedOrUnknown() {
        assertNull(LaneTags.parse("left|through", 3));            // lane count differs
        assertNull(LaneTags.parse("left|merge_to_left", 2));      // not drawn
        assertNull(LaneTags.parse("left", 1));                    // single lane
        assertNull(LaneTags.parse("", 2));
    }

    private static JSONObject way(String turnLanes, int lanes) throws Exception {
        return new JSONObject().put("highway", "primary").put("turn:lanes", turnLanes).put("lanes", lanes);
    }

    @Test public void recommendsLanesForLeftTurn() throws Exception {
        RouteIntelligence.LaneSection s = LaneTags.section(way("left|through|through;right", 3), "TURN_LEFT", -90, 0, 300);
        assertNotNull(s);
        assertTrue(s.trusted());
        assertTrue(s.lanes.get(0).recommended);
        assertFalse(s.lanes.get(1).recommended);
        assertFalse(s.lanes.get(2).recommended);
    }

    @Test public void recommendsRightmostForRightTurn() throws Exception {
        RouteIntelligence.LaneSection s = LaneTags.section(way("through|through|through;right", 3), "TURN_RIGHT", 90, 0, 300);
        assertNotNull(s);
        assertTrue(s.lanes.get(2).recommended);
        assertEquals("Use the right lane", IntelligentGuidance.lanePhrase(s));
    }

    @Test public void usesForwardTagWhenPresent() throws Exception {
        JSONObject w = new JSONObject().put("turn:lanes:forward", "left|through").put("lanes:forward", 2)
                .put("turn:lanes:backward", "through|right");
        assertNotNull(LaneTags.section(w, "TURN_LEFT", -90, 0, 300));
    }

    @Test public void noSectionWhenEveryLaneServesTheTurn() throws Exception {
        assertNull(LaneTags.section(way("left|left", 2), "TURN_LEFT", -90, 0, 300));
    }

    @Test public void noSectionWhenNoLaneServesTheTurn() throws Exception {
        assertNull(LaneTags.section(way("through|through", 2), "TURN_LEFT", -90, 0, 300));
    }

    @Test public void noSectionForRoundaboutsOrMissingTags() throws Exception {
        assertNull(LaneTags.section(way("left|through", 2), "ROUNDABOUT", 0, 0, 300));
        assertNull(LaneTags.section(new JSONObject().put("highway", "primary"), "TURN_LEFT", -90, 0, 300));
        assertNull(LaneTags.section(null, "TURN_LEFT", -90, 0, 300));
    }

    @Test public void bearAndKeepUseSlightAndStraightLanes() throws Exception {
        RouteIntelligence.LaneSection s = LaneTags.section(way("slight_left|through|right", 3), "BEAR_LEFT", -40, 0, 300);
        assertNotNull(s);
        assertTrue(s.lanes.get(0).recommended);
        assertFalse(s.lanes.get(2).recommended);
    }
}
