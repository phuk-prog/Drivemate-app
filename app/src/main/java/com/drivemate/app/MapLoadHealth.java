package com.drivemate.app;

/**
 * Decisions about the native map's "detail unavailable" state, kept apart from the Android
 * view so they can be unit tested.
 *
 * Before this class the map reported FAILED on the first painted frame, before any tile had
 * loaded, and the banner it set was never cleared when roads later appeared. Now FAILED is
 * only reported after a detail load has finished and still nothing was drawn.
 */
public final class MapLoadHealth {
    private MapLoadHealth() { }

    public enum Report { NONE, READY, FAILED }

    /**
     * @param loadFinished at least one detail load attempt has completed
     * @param loading a load is in progress right now
     * @param roadsDrawn roads drawn in the latest frame
     */
    public static Report report(boolean loadFinished, boolean loading, int roadsDrawn) {
        if (roadsDrawn > 0) return Report.READY;
        if (!loadFinished || loading) return Report.NONE;
        return Report.FAILED;
    }

    /** Retries after a failed detail load: 4 s, 8 s, 12 s ... capped at 30 s, at most {@link #MAX_RETRIES} times. */
    public static final int MAX_RETRIES = 6;

    public static long retryDelayMs(int attempt) {
        if (attempt < 1 || attempt > MAX_RETRIES) return -1;
        return Math.min(30_000L, 4_000L * attempt);
    }

    /** Short, coordinate-free reason for diagnostics, e.g. "IOException: Read timed out". */
    public static String reason(Throwable error) {
        if (error == null) return "";
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
        return text.length() <= 120 ? text : text.substring(0, 120);
    }
}
