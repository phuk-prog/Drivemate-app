package com.drivemate.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which settings item belongs to which group on the settings screen. Indices are those of
 * MainActivity.settingsLabels() / runSetting(). Pure Java so the grouping is unit tested.
 */
final class SettingsGroups {
    private SettingsGroups() { }

    static final class Group {
        final String title;
        final int[] items;
        final boolean developer;
        Group(String title, boolean developer, int... items) { this.title = title; this.items = items; this.developer = developer; }
    }

    static final Group[] GROUPS = {
            new Group("Voice and sound", false, 1, 2, 3, 33, 34, 35, 31),
            new Group("Music", false, 36),
            new Group("Map and display", false, 6, 7, 14, 23, 30),
            new Group("Routes and traffic", false, 17, 4, 0),
            new Group("Speed and hazard alerts", false, 16, 18, 15),
            new Group("Places and parking", false, 10, 11, 20, 21, 12),
            new Group("Offline maps", false, 32, 28, 27, 9),
            new Group("Units and language", false, 5),
            new Group("About and diagnostics", false, 13, 22),
            new Group("Developer", true, 37, 8, 19, 24, 25, 26, 29),
    };

    /** Number of items the activity provides; every index 0..TOTAL-1 is in exactly one group. */
    static final int TOTAL = 38;

    /** Items that act on the map (find parked car, free driving, pilot screen): the settings screen closes first. */
    static final int[] CLOSES_SCREEN = {21, 29, 30};

    static boolean closesScreen(int index) {
        for (int i : CLOSES_SCREEN) if (i == index) return true;
        return false;
    }

    /** Taps on the title that reveal the Developer group. */
    static final int DEVELOPER_TAPS = 7;

    /** "Voice volume · 80%" → {"Voice volume", "80%"}; no separator → {label, ""}. */
    static String[] split(String label) {
        String l = label == null ? "" : label.trim();
        int at = l.lastIndexOf(" · ");
        if (at <= 0) return new String[] {l, ""};
        return new String[] {l.substring(0, at).trim(), l.substring(at + 3).trim()};
    }

    /** Items of a group that match the search text (case-insensitive, title or value). */
    static List<Integer> matching(Group g, String[] labels, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.UK);
        List<Integer> out = new ArrayList<>();
        for (int index : g.items) {
            if (index < 0 || index >= labels.length) continue;
            if (q.isEmpty() || labels[index].toLowerCase(Locale.UK).contains(q)
                    || g.title.toLowerCase(Locale.UK).contains(q)) out.add(index);
        }
        return out;
    }
}
