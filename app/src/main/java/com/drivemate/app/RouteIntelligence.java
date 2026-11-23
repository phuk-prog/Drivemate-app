package com.drivemate.app;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Source-constrained driving intelligence. Every item originates from a
 * TomTom section of the current drivable route, never an inferred road class.
 */
public final class RouteIntelligence {
    private RouteIntelligence() { }

    public static final class Lane {
        public final String direction;
        public final boolean recommended;
        public Lane(String direction, boolean recommended) {
            this.direction = direction == null ? "" : direction;
            this.recommended = recommended;
        }
        public String symbol() {
            switch (direction.toUpperCase(Locale.US)) {
                case "LEFT": case "SHARP_LEFT": return "↰";
                case "SLIGHT_LEFT": return "↖";
                case "STRAIGHT": return "↑";
                case "SLIGHT_RIGHT": return "↗";
                case "RIGHT": case "SHARP_RIGHT": return "↱";
                case "UTURN": case "U_TURN": case "LEFT_U_TURN": return "⤺";
                case "RIGHT_U_TURN": return "⤻";
                default: return "?";
            }
        }
    }

    public static final class LaneSection {
        public final double start, end;
        public final List<Lane> lanes;
        public LaneSection(double start, double end, List<Lane> lanes) {
            this.start = start;
            this.end = end;
            this.lanes = lanes == null ? Collections.emptyList() : lanes;
        }
        public boolean trusted() {
            if (lanes.isEmpty() || lanes.size() > 8) return false;
            boolean hasRecommendation = false;
            for (Lane lane : lanes) {
                if ("?".equals(lane.symbol())) return false;
                hasRecommendation |= lane.recommended;
            }
            return hasRecommendation;
        }
        public String display() {
            if (!trusted()) return "";
            StringBuilder out = new StringBuilder("LANES  ");
            for (int i = 0; i < lanes.size(); i++) {
                if (i > 0) out.append("   ");
                Lane lane = lanes.get(i);
                if (lane.recommended) out.append("[");
                out.append(lane.symbol());
                if (lane.recommended) out.append("]");
            }
            return out.toString();
        }
    }

    public static final class SpeedSection {
        public final double start, end;
        public final int kmh;
        public SpeedSection(double start, double end, int kmh) {
            this.start = start; this.end = end; this.kmh = kmh;
        }
    }

    public static final class TrafficSection {
        public final double start, end;
        public final int delaySeconds;
        public final String category;
        public TrafficSection(double start, double end, int delaySeconds, String category) {
            this.start = start; this.end = end;
            this.delaySeconds = Math.max(0, delaySeconds);
            this.category = category == null ? "" : category.toUpperCase(Locale.US);
        }
        public String label() {
            switch (category) {
                case "JAM": return "Congestion";
                case "ROAD_WORK": return "Roadworks";
                case "ROAD_CLOSURE": return "Road closure";
                default: return "Reported traffic";
            }
        }
    }

    /** Prefer only verified recommended lanes on the upcoming route. */
    public static LaneSection approachingLanes(List<LaneSection> sections,
                                               double progress, double horizon) {
        if (sections == null || !Double.isFinite(progress)) return null;
        LaneSection result = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (LaneSection s : sections) {
            if (!s.trusted() || s.end <= progress + 2 || s.start > progress + horizon)
                continue;
            double distance = Math.max(0, s.start - progress);
            if (distance < nearest) { nearest = distance; result = s; }
        }
        return result;
    }

    /**
     * Display no posted limit unless the route match and fix are trustworthy,
     * the planned information is recent and the driver is inside that section.
     */
    public static SpeedSection speedAt(List<SpeedSection> sections, double progress,
                                       double offRoute, float accuracy, long ageMs) {
        if (sections == null || !Double.isFinite(progress)
                || !Double.isFinite(offRoute) || !Float.isFinite(accuracy)
                || offRoute > 20 || accuracy > 25 || ageMs < 0 || ageMs > 480000)
            return null;
        SpeedSection selected = null;
        for (SpeedSection s : sections) {
            if (s.kmh >= 5 && s.kmh <= 140 && progress >= s.start + 3
                    && progress <= s.end - 3) {
                // Conflicting overlapping values are ambiguous: show unknown.
                if (selected != null && selected.kmh != s.kmh) return null;
                selected = s;
            }
        }
        return selected;
    }

    public static boolean overspeed(double metresPerSecond, SpeedSection section,
                                    double toleranceMph) {
        if (section == null || !Double.isFinite(metresPerSecond)
                || metresPerSecond < 0) return false;
        double mph = metresPerSecond * 2.2369362921;
        return mph >= section.kmh / 1.609344 + Math.max(0, toleranceMph);
    }

    public static TrafficSection nextTraffic(List<TrafficSection> sections,
                                              double progress, double horizon) {
        if (sections == null || !Double.isFinite(progress)) return null;
        TrafficSection selected = null;
        for (TrafficSection t : sections) {
            if (t.end < progress || t.start - progress > horizon
                    || t.start < 0 || t.end <= t.start) continue;
            if (selected == null || t.start < selected.start) selected = t;
        }
        return selected;
    }

    /** Conservative comparison of two polylines from the same current GPS. */
    public static boolean genuinelyDifferent(List<GeoMath.Point> old,
                                              double oldProgress,
                                              List<GeoMath.Point> proposed) {
        if (old == null || proposed == null || old.size() < 2 || proposed.size() < 2)
            return false;
        RouteMotionIndex oldIndex = new RouteMotionIndex(old);
        RouteMotionIndex newIndex = new RouteMotionIndex(proposed);
        double remain = oldIndex.lengthMetres - oldProgress;
        if (remain < 400 || newIndex.lengthMetres < 200) return false;
        double[] fractions = {0.25, 0.50, 0.75};
        for (double fraction : fractions) {
            GeoMath.Point a = oldIndex.pointAt(oldProgress + fraction * remain);
            GeoMath.Point b = newIndex.pointAt(fraction * newIndex.lengthMetres);
            if (GeoMath.distance(a, b) > 160) return true;
        }
        return false;
    }

    /** Require substantial estimated savings to avoid distracting route churn. */
    public static int meaningfulSaving(int oldRemainingSeconds, int newSeconds,
                                        boolean different) {
        if (!different || newSeconds <= 0 || oldRemainingSeconds <= 0) return 0;
        int saving = oldRemainingSeconds - newSeconds;
        return saving >= 180 && saving >= oldRemainingSeconds * 0.15 ? saving : 0;
    }
}
