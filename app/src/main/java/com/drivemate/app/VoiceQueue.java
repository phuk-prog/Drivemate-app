package com.drivemate.app;

/**
 * Decides what to do with a new spoken message while another may still be playing.
 * Today every message flushes the one before, so a trivial status line can cut off
 * a turn instruction mid-sentence. Pure Java: the caller reports when speech starts
 * and ends, so the rules are unit tested without a text-to-speech engine.
 *
 * Priorities: STATUS (system status) below INFO (route and map notes) below TURN
 * (turn instructions) below URGENT (the turn is now).
 */
public final class VoiceQueue {
    public static final int STATUS = 0, INFO = 1, TURN = 2, URGENT = 3;

    public enum Action { PLAY_NOW, QUEUE, DROP }

    /** The same text inside this window is a repeat, not a new instruction. */
    public static final long DUPLICATE_WINDOW_MS = 5_000L;
    /** A turn instruction queued behind another is too late after this long. */
    public static final long STALE_AFTER_MS = 8_000L;
    /** Rough spoken length: 400 ms plus 70 ms per character, at most 12 s. */
    public static long estimateMs(String text) {
        int n = text == null ? 0 : text.length();
        return Math.min(12_000L, 400L + 70L * n);
    }

    private int currentPriority = -1;
    private long currentEndsAt = -1;
    private String lastText = "";
    private long lastTextAt = -1_000_000L;
    private int queued;

    /** Decide, and record the message as played or queued when the answer is not DROP. */
    public Action offer(int priority, String text, long nowMs) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return Action.DROP;
        if (clean.equals(lastText) && nowMs - lastTextAt < DUPLICATE_WINDOW_MS) return Action.DROP;
        boolean speaking = nowMs < currentEndsAt;
        if (!speaking) {
            play(priority, clean, nowMs);
            return Action.PLAY_NOW;
        }
        if (priority > currentPriority) {
            play(priority, clean, nowMs);
            return Action.PLAY_NOW;
        }
        // Lower or equal priority while something is speaking.
        if (priority >= TURN && currentPriority >= TURN && queued < 1) {
            queued++;
            lastText = clean;
            lastTextAt = nowMs;
            currentEndsAt += estimateMs(clean);
            return Action.QUEUE;
        }
        return Action.DROP;
    }

    private void play(int priority, String text, long nowMs) {
        currentPriority = priority;
        currentEndsAt = nowMs + estimateMs(text);
        lastText = text;
        lastTextAt = nowMs;
        queued = 0;
    }

    /** The engine finished speaking everything queued. */
    public void finished() {
        currentPriority = -1;
        currentEndsAt = -1;
        queued = 0;
    }

    /** True when a queued message has waited so long it should be skipped. */
    public static boolean stale(long queuedAtMs, long nowMs) {
        return nowMs - queuedAtMs > STALE_AFTER_MS;
    }

    public void reset() {
        finished();
        lastText = "";
        lastTextAt = -1_000_000L;
    }
}
