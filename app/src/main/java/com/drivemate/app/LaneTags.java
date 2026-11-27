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
    static String value(JSONObject tags) {
        if (tags == null) return "";
        String s = tags.optString("turn:lanes:forward", "");
        if (s.isEmpty()) s = tags.optString("turn:lanes", "");
        return s.trim();
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
        String value = value(wayTags);
        if (value.isEmpty()) return null;
        int count = wayTags.optInt("lanes:forward", wayTags.optInt("lanes", 0));
        List<List<String>> parsed = parse(value, count);
        if (parsed == null) return null;
        List<String> serving = serving(maneuverCode, turnDegrees);
        if (serving == null) return null;
        List<RouteIntelligence.Lane> lanes = new ArrayList<>();
        int recommended = 0;
        for (List<String> dirs : parsed) {
            boolean rec = false;
            String chosen = dirs.get(0);
            for (String d : dirs) if (serving.contains(d)) { rec = true; chosen = d; break; }
            if (rec) recommended++;
            lanes.add(new RouteIntelligence.Lane(chosen, rec, dirs));
        }
        if (recommended == 0 || recommended == lanes.size()) return null;
        RouteIntelligence.LaneSection section = new RouteIntelligence.LaneSection(sectionStart, sectionEnd, lanes);
        return section.trusted() ? section : null;
    }
}
