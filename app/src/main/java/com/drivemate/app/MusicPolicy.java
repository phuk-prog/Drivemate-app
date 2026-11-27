package com.drivemate.app;

import java.util.List;

/** Pure rules for the in-car music bar. */
final class MusicPolicy {
    private MusicPolicy() { }

    static final String SPOTIFY = "com.spotify.music";
    /** The music strip hides this close to a manoeuvre so nothing competes with the turn. */
    static final double HIDE_BEFORE_TURN_METRES = 185;

    /** Pick the session to show: a playing one first, Spotify preferred, else the first paused one. */
    static int choose(List<String> packages, List<Boolean> playing) {
        if (packages == null || playing == null || packages.isEmpty() || packages.size() != playing.size()) return -1;
        int firstPlaying = -1;
        for (int i = 0; i < packages.size(); i++) if (Boolean.TRUE.equals(playing.get(i))) {
            if (SPOTIFY.equals(packages.get(i))) return i;
            if (firstPlaying < 0) firstPlaying = i;
        }
        if (firstPlaying >= 0) return firstPlaying;
        for (int i = 0; i < packages.size(); i++) if (SPOTIFY.equals(packages.get(i))) return i;
        return 0;
    }

    /** "Song · Artist", trimmed; "" when nothing is known. */
    static String line(String title, String artist) {
        String t = title == null ? "" : title.trim(), a = artist == null ? "" : artist.trim();
        if (t.isEmpty()) return a;
        return a.isEmpty() ? t : t + " · " + a;
    }

    /** Show the driving strip? Never close to a turn. */
    static boolean showWhileDriving(boolean hasSession, double metresToTurn) {
        return hasSession && !(metresToTurn >= 0 && metresToTurn < HIDE_BEFORE_TURN_METRES);
    }
}
