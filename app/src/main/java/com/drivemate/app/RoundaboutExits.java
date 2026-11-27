package com.drivemate.app;

import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Counts which exit of a roundabout the route takes, only when that can be
 * trusted. A wrong exit number is worse than none, so every doubt returns 0 and
 * the caller keeps the plain "follow the highlighted route" wording.
 *
 * Counted: ordinary roads (see {@link JunctionDetail#countableHighway}). Driveways,
 * service roads and paths are ignored. If another exit on the way round could not
 * be classified, there is no count.
 */
public final class RoundaboutExits {
    private RoundaboutExits() { }

    /** More exits than this and the instruction is too hard to follow by count. */
    public static final int MAX_EXIT = 6;

    public static final class Result {
        /** 1-based exit number, or 0 when not known. */
        public final int exit;
        /** Index in the way list of the way the route leaves on, or -1. */
        public final int exitWayIndex;
        Result(int exit, int exitWayIndex) { this.exit = exit; this.exitWayIndex = exitWayIndex; }
    }

    static boolean onRoundabout(JSONObject tags) {
        return tags != null && ("roundabout".equals(tags.optString("junction"))
                || "circular".equals(tags.optString("junction")));
    }

    /**
     * @param enter route node index at which the route joins the roundabout
     *              (ways.get(enter - 1) is not a roundabout, ways.get(enter) is)
     * @param details per-node junction details, index-aligned with the route points; may contain nulls
     */
    public static Result count(List<Long> ways, Map<Long, JSONObject> tags, JunctionDetail[] details, int enter) {
        if (details == null || enter < 1 || enter >= ways.size()) return new Result(0, -1);
        int exitNode = -1;
        for (int j = enter + 1; j < ways.size(); j++) {
            if (!onRoundabout(tags.get(ways.get(j)))) { exitNode = j; break; }
        }
        // exitNode is the node where the route leaves; ways.get(exitNode) is the exit road.
        if (exitNode < 0) return new Result(0, -1);
        int exit = 1;
        for (int k = enter + 1; k < exitNode; k++) {
            if (k >= details.length || details[k] == null) return new Result(0, -1);
            JunctionDetail d = details[k];
            if (d.unknownExit) return new Result(0, -1);
            exit += d.countableExits;
        }
        if (exit > MAX_EXIT) return new Result(0, -1);
        return new Result(exit, exitNode);
    }
}
