package com.drivemate.app;

import java.util.List;
import java.util.Locale;

/** TomTom coded manoeuvres; never infer roundabout exit or road name. */
public final class ManeuverGuide {
    public enum Arrow { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, ROUNDABOUT, ARRIVE }
    private ManeuverGuide() { }

    public static Arrow arrow(String maneuver, String junctionType, double angle) {
        String code = maneuver == null ? "" : maneuver.toUpperCase(Locale.ROOT);
        String junction = junctionType == null ? "" : junctionType.toUpperCase(Locale.ROOT);
        if (code.startsWith("ARRIVE") || code.startsWith("WAYPOINT")) return Arrow.ARRIVE;
        if ("ROUNDABOUT".equals(junction) || code.contains("ROUNDABOUT")) return Arrow.ROUNDABOUT;
        if (code.contains("UTURN") || code.contains("U_TURN")) return Arrow.UTURN;
        if (code.contains("KEEP_LEFT") || code.contains("BEAR_LEFT")) return Arrow.SLIGHT_LEFT;
        if (code.contains("KEEP_RIGHT") || code.contains("BEAR_RIGHT")) return Arrow.SLIGHT_RIGHT;
        if (code.contains("LEFT")) return Arrow.LEFT;
        if (code.contains("RIGHT")) return Arrow.RIGHT;
        if (code.contains("STRAIGHT") || code.contains("FOLLOW") || code.equals("DEPART"))
            return Arrow.STRAIGHT;
        if (angle < -25) return Arrow.LEFT;
        if (angle > 25) return Arrow.RIGHT;
        return Arrow.STRAIGHT;
    }

    /** Route offset may contain a non-turn DEPART/ARRIVE; skip only depart notices. */
    public static int nextIndex(List<TomTomApi.Instruction> steps, double progressMeters) {
        if (steps == null || steps.isEmpty()) return -1;
        for (int i = 0; i < steps.size(); i++) {
            TomTomApi.Instruction step = steps.get(i);
            if ("LOCATION_DEPARTURE".equals(step.instructionType)
                    || "DEPART".equals(step.maneuver)) continue;
            if (step.routeOffsetMetres + 8 >= progressMeters) return i;
        }
        return steps.size() - 1;
    }

    public static int followingIndex(List<TomTomApi.Instruction> steps, int next) {
        if (steps == null || next < 0) return -1;
        for (int i = next + 1; i < steps.size(); i++) {
            TomTomApi.Instruction instruction = steps.get(i);
            if (!"LOCATION_DEPARTURE".equals(instruction.instructionType)) return i;
        }
        return -1;
    }

    public static String title(TomTomApi.Instruction instruction) {
        if (instruction == null) return "Continue";
        if (instruction.roundaboutExitNumber > 0) {
            return "Roundabout · " + ordinal(instruction.roundaboutExitNumber) + " exit";
        }
        return instruction.message == null || instruction.message.isEmpty()
                ? "Follow the route" : instruction.message;
    }

    public static String ordinal(int number) {
        int mod100 = number % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th"
                : number % 10 == 1 ? "st"
                : number % 10 == 2 ? "nd" : number % 10 == 3 ? "rd" : "th";
        return number + suffix;
    }

    public static String afterLabel(TomTomApi.Instruction instruction, double metres, boolean metric) {
        return "Then " + NavigationUnits.displayDistance(metres, metric)
                + " · " + title(instruction);
    }

    public static String streetLabel(TomTomApi.Instruction step) {
        if (step == null) return "";
        if (!step.street.isEmpty()) return "Onto " + step.street;
        if (!step.signpostText.isEmpty()) return "Towards " + step.signpostText;
        return "";
    }
}
