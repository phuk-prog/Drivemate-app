package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class RoundaboutExitsTest {
    private static JSONObject road(String name) throws Exception {
        return new JSONObject().put("highway", "residential").put("name", name);
    }
    private static JSONObject ring() throws Exception {
        return new JSONObject().put("highway", "residential").put("junction", "roundabout");
    }

    /** Route: arm A (way 1) -> ring ways 2,3,4 -> exit B (way 5). Nodes 0..5, ring nodes 1..4. */
    private static Map<Long, JSONObject> tags() throws Exception {
        Map<Long, JSONObject> m = new HashMap<>();
        m.put(1L, road("A Road")); m.put(2L, ring()); m.put(3L, ring()); m.put(4L, ring()); m.put(5L, road("B Road"));
        return m;
    }
    private static final List<Long> WAYS = Arrays.asList(1L, 2L, 3L, 4L, 5L);

    private static JunctionDetail[] details(JunctionDetail... d) { return d; }

    @Test public void firstExitWhenNothingIsPassed() throws Exception {
        // Enter at node 1, leave at node 3: ways 2 then 3? use ways [1,2,5]
        List<Long> ways = Arrays.asList(1L, 2L, 5L);
        RoundaboutExits.Result r = RoundaboutExits.count(ways, tags(), details(null, null, null, null), 1);
        assertEquals(1, r.exit);
        assertEquals(2, r.exitWayIndex);
    }

    @Test public void countsOrdinaryRoadsPassed() throws Exception {
        // Enter node 1, pass nodes 2 and 3 (one exit each), leave at node 4.
        JunctionDetail[] d = details(null, null, new JunctionDetail(1, false, false, false),
                new JunctionDetail(1, false, false, false), null, null);
        RoundaboutExits.Result r = RoundaboutExits.count(WAYS, tags(), d, 1);
        assertEquals(3, r.exit);
        assertEquals(4, r.exitWayIndex);
    }

    @Test public void ignoresDrivewaysThatAreNotCounted() throws Exception {
        JunctionDetail[] d = details(null, null, new JunctionDetail(0, false, false, false),
                new JunctionDetail(1, false, false, false), null, null);
        assertEquals(2, RoundaboutExits.count(WAYS, tags(), d, 1).exit);
    }

    @Test public void unknownExitMeansNoNumber() throws Exception {
        JunctionDetail[] d = details(null, null, new JunctionDetail(1, true, false, false),
                new JunctionDetail(0, false, false, false), null, null);
        assertEquals(0, RoundaboutExits.count(WAYS, tags(), d, 1).exit);
    }

    @Test public void missingDetailMeansNoNumber() throws Exception {
        JunctionDetail[] d = details(null, null, null, new JunctionDetail(0, false, false, false), null, null);
        assertEquals(0, RoundaboutExits.count(WAYS, tags(), d, 1).exit);
        assertEquals(0, RoundaboutExits.count(WAYS, tags(), null, 1).exit);
    }

    @Test public void tooManyExitsGivesNoNumber() throws Exception {
        JunctionDetail[] d = details(null, null, new JunctionDetail(7, false, false, false),
                new JunctionDetail(0, false, false, false), null, null);
        assertEquals(0, RoundaboutExits.count(WAYS, tags(), d, 1).exit);
    }

    @Test public void routeThatNeverLeavesTheRoundaboutGivesNoNumber() throws Exception {
        List<Long> ways = Arrays.asList(1L, 2L, 3L);
        assertEquals(0, RoundaboutExits.count(ways, tags(), details(null, null, null, null), 1).exit);
    }

    @Test public void badIndexIsSafe() throws Exception {
        assertEquals(0, RoundaboutExits.count(WAYS, tags(), details(null), 0).exit);
        assertEquals(0, RoundaboutExits.count(WAYS, tags(), details(null), 99).exit);
    }
}
