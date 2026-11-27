package com.drivemate.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/**
 * Builds a lane display from OpenStreetMap lane tags ({@code turn:lanes} and
 * {@code lanes}) when the map data carries them. Nothing is shown unless the
 * tags parse cleanly, the lane count matches, and at least one lane (and not
 * every lane) serves the manoeuvre. Pure Java apart from org.json.
 */
public final class LaneTags {
    private LaneTags() { }

    /** OSM turn values mapped to the symbols RouteIntelligence.Lane understands. */
    static String direction(String osm) {
        switch (osm.trim()) {
            case "left": return "LEFT";
            case "sharp_left": return "SHARP_LEFT";
            case "slight_left": return "SLIGHT_LEFT";
            case "through": case "": case "none": return "STRAIGHT";
            case "slight_right": return "SLIGHT_RIGHT";
            case "right": return "RIGHT";
            case "sharp_right": return "SHARP_RIGHT";
            case "reverse": return "U_TURN";
            default: return null;   // merge_to_left, reversible, etc.: not drawn
        }
    }

    /** The turn:lanes value that applies to travel along the way, or "". */
    static String value(JSONObject tags) { return value(tags, Boolean.TRUE); }

    static boolean oneway(JSONObject tags) {
        if (tags == null) return false;
        String o = tags.optString("oneway", "");
        return "yes".equals(o) || "1".equals(o) || "true".equals(o) || "-1".equals(o)
                || "roundabout".equals(tags.optString("junction")) || "motorway".equals(tags.optString("highway"));
    }

    /**
     * The turn:lanes value for the direction the route travels along the way.
     * @param forward true along the way's drawing direction, false against it, null when unknown
     */
    static String value(JSONObject tags, Boolean forward) {
        if (tags == null) return "";
        if (oneway(tags)) return tags.optString("turn:lanes", "").trim();
        if (forward == null) return "";               // two-way road, direction unknown: never guess
        String own = tags.optString(forward ? "turn:lanes:forward" : "turn:lanes:backward", "").trim();
        // Older tagging: plain turn:lanes on a two-way way describes its forward direction only.
        if (own.isEmpty() && forward) own = tags.optString("turn:lanes", "").trim();
        return own;
    }

    /** Lane count for the travel direction, or 0 when not mapped (no count check then). */
    static int count(JSONObject tags, Boolean forward) {
        if (tags == null) return 0;
        if (oneway(tags)) return tags.optInt("lanes", 0);
        if (forward == null) return 0;
        String key = forward ? "lanes:forward" : "lanes:backward";
        if (tags.has(key)) return tags.optInt(key, 0);
        // Plain turn:lanes with a plain lanes count: compare against it (mismatch hides the section).
        return forward && !tags.has("turn:lanes:forward") ? tags.optInt("lanes", 0) : 0;
    }

    /** Parse "left|through;right|right" to per-lane direction lists, or null if anything is unrecognised. */
    public static List<List<String>> parse(String turnLanes, int laneCount) {
        if (turnLanes == null || turnLanes.isEmpty()) return null;
        String[] lanes = turnLanes.split("\\|", -1);
        if (lanes.length < 2 || lanes.length > 8) return null;
        if (laneCount > 0 && laneCount != lanes.length) return null;
        List<List<String>> out = new ArrayList<>();
        for (String lane : lanes) {
            List<String> dirs = new ArrayList<>();
            for (String part : lane.split(";", -1)) {
                String d = direction(part);
                if (d == null) return null;
                if (!dirs.contains(d)) dirs.add(d);
            }
            if (dirs.isEmpty() || dirs.size() > 5) return null;
            out.add(dirs);
        }
        return out;
    }

    /** Which lane directions serve this maneuver. */
    static List<String> serving(String maneuverCode, double turnDegrees) {
        String m = maneuverCode == null ? "" : maneuverCode;
        if (m.startsWith("UTURN")) return Arrays.asList("U_TURN");
        if (m.contains("SHARP_LEFT")) return Arrays.asList("SHARP_LEFT", "LEFT");
        if (m.contains("SHARP_RIGHT")) return Arrays.asList("SHARP_RIGHT", "RIGHT");
        if (m.contains("BEAR_LEFT") || m.contains("KEEP_LEFT")) return Arrays.asList("SLIGHT_LEFT", "STRAIGHT", "LEFT");
        if (m.contains("BEAR_RIGHT") || m.contains("KEEP_RIGHT")) return Arrays.asList("SLIGHT_RIGHT", "STRAIGHT", "RIGHT");
        if (m.contains("LEFT")) return Arrays.asList("LEFT", "SLIGHT_LEFT");
        if (m.contains("RIGHT")) return Arrays.asList("RIGHT", "SLIGHT_RIGHT");
        return null;   // roundabouts, continue, destination: no lane advice
    }

    /**
     * @param sectionStart route offset where the lanes start (metres)
     * @param sectionEnd   route offset of the junction
     * @return a lane section, or null when the tags are missing, malformed or give no usable recommendation
     */
    public static RouteIntelligence.LaneSection section(JSONObject wayTags, String maneuverCode,
                                                        double turnDegrees, double sectionStart,
                                                        double sectionEnd) {
        return section(wayTags, Boolean.TRUE, maneuverCode, turnDegrees, sectionStart, sectionEnd);
    }

    public static RouteIntelligence.LaneSection section(JSONObject wayTags, Boolean forward, String maneuverCode,
                                                        double turnDegrees, double sectionStart,
                                                        double sectionEnd) {
        String value = value(wayTags, forward);
        if (value.isEmpty()) return null;
        List<List<String>> parsed = parse(value, count(wayTags, forward));
        if (parsed == null) return null;
        List<String> serving = serving(maneuverCode, turnDegrees);
        if (serving == null) return null;
        String m = maneuverCode == null ? "" : maneuverCode;
        if (m.contains("BEAR_") || m.contains("KEEP_")) {
            // A bear only uses a straight-on lane when no lane is marked for that side.
            boolean right = m.endsWith("RIGHT");
            boolean sideLane = false;
            for (List<String> dirs : parsed)
                for (String d : dirs) if (right ? d.endsWith("RIGHT") : d.endsWith("LEFT")) sideLane = true;
            if (sideLane) { serving = new ArrayList<>(serving); serving.remove("STRAIGHT"); }
        }
        List<RouteIntelligence.Lane> lanes = new ArrayList<>();
        int recommended = 0;
        for (List<String> dirs : parsed) {
            boolean rec = false;
            String chosen = dirs.get(0);
            for (String d : dirs) if (serving.contains(d)) { rec = true; chosen = d; break; }
            if (rec) recommended++;
            lanes.add(new RouteIntelligence.Lane(chosen, rec, dirs));
        }
        // Every lane serving the turn is shown too (all lit), so the driver knows any lane works.
        if (recommended == 0) return null;
        RouteIntelligence.LaneSection section = new RouteIntelligence.LaneSection(sectionStart, sectionEnd, lanes);
        return section.trusted() ? section : null;
    }
}
