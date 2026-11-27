package com.drivemate.app;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The single place that decides whether a location fix is used, and keeps the
 * counters the diagnostics report shows. Pure Java: no Android types, so the
 * whole path from raw fix to accepted fix is unit tested, including replays.
 *
 * It owns the last accepted fix, so callers no longer track that themselves.
 * Mock locations are counted and flagged but not blocked: a developer or a
 * simulated drive is allowed to run, the driver is just told once.
 */
public final class LocationPipeline {
    /** Outcome of offering one fix. */
    public static final class Decision {
        public final FixQualityPolicy.Reason reason;
        public final boolean accepted;
        public final boolean mock;
        /** True exactly once, the first time a mock fix is seen. */
        public final boolean firstMock;
        Decision(FixQualityPolicy.Reason reason, boolean mock, boolean firstMock) {
            this.reason = reason;
            this.accepted = reason == FixQualityPolicy.Reason.ACCEPTED;
            this.mock = mock;
            this.firstMock = firstMock;
        }
    }

    private final Map<FixQualityPolicy.Reason, Integer> counts =
            new EnumMap<>(FixQualityPolicy.Reason.class);
    private FixQualityPolicy.Sample lastAccepted;
    private int mockFixes;
    private long bridgedMs;
    private int bridgeEpisodes;

    /** Judge one fix. {@code mock} comes from the Android location object. */
    public Decision offer(FixQualityPolicy.Sample sample, boolean mock) {
        FixQualityPolicy.Reason reason = FixQualityPolicy.decide(lastAccepted, sample);
        counts.merge(reason, 1, Integer::sum);
        boolean first = false;
        if (mock) {
            first = mockFixes == 0;
            mockFixes++;
        }
        if (reason == FixQualityPolicy.Reason.ACCEPTED) lastAccepted = sample;
        return new Decision(reason, mock, first);
    }

    public FixQualityPolicy.Sample lastAccepted() { return lastAccepted; }

    /** Record that the display bridged a gap of this length with an estimated position. */
    public void recordBridge(long durationMs) {
        if (durationMs <= 0) return;
        bridgedMs += durationMs;
        bridgeEpisodes++;
    }

    public int count(FixQualityPolicy.Reason reason) {
        Integer n = counts.get(reason);
        return n == null ? 0 : n;
    }

    public int accepted() { return count(FixQualityPolicy.Reason.ACCEPTED); }

    public int rejected() {
        int total = 0;
        for (Map.Entry<FixQualityPolicy.Reason, Integer> e : counts.entrySet()) {
            if (e.getKey() != FixQualityPolicy.Reason.ACCEPTED) total += e.getValue();
        }
        return total;
    }

    public int mockFixes() { return mockFixes; }
    public long bridgedMs() { return bridgedMs; }
    public int bridgeEpisodes() { return bridgeEpisodes; }

    /** e.g. "POOR_ACCURACY 4, TELEPORT 1", or "none". Contains no coordinates. */
    public String rejectionSummary() {
        StringBuilder out = new StringBuilder();
        for (FixQualityPolicy.Reason reason : FixQualityPolicy.Reason.values()) {
            if (reason == FixQualityPolicy.Reason.ACCEPTED) continue;
            int n = count(reason);
            if (n == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(reason.name()).append(' ').append(n);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    public String bridgeSummary() {
        return String.format(Locale.UK, "%d gaps, %.1f s", bridgeEpisodes, bridgedMs / 1000.0);
    }

    public void reset() {
        counts.clear();
        lastAccepted = null;
        mockFixes = 0;
        bridgedMs = 0;
        bridgeEpisodes = 0;
    }
}
