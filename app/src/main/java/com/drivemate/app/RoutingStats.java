package com.drivemate.app;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counters about route searches for the diagnostics report. Holds no
 * coordinates, only counts, times and snap distances.
 */
public final class RoutingStats {
    private int requests, successes, alternativesTried, alternativesOffered;
    private final Map<String, Integer> failures = new TreeMap<>();
    private int lastExpansions = -1;
    private long lastMs = -1;
    private double lastStartSnap = Double.NaN, lastEndSnap = Double.NaN;

    public synchronized void recordRequest() { requests++; }

    public synchronized void recordSuccess(int expansions, long ms) {
        successes++;
        lastExpansions = expansions;
        lastMs = ms;
    }

    public synchronized void recordFailure(String kind) {
        failures.merge(kind == null || kind.isEmpty() ? "other" : kind, 1, Integer::sum);
    }

    public synchronized void recordSnap(double startMetres, double endMetres) {
        lastStartSnap = startMetres;
        lastEndSnap = endMetres;
    }

    public synchronized void recordAlternative(boolean offered) {
        alternativesTried++;
        if (offered) alternativesOffered++;
    }

    public synchronized String summary() {
        StringBuilder out = new StringBuilder();
        out.append(requests).append(" searches, ").append(successes).append(" found");
        if (!failures.isEmpty()) {
            out.append(", failed: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : failures.entrySet()) {
                if (!first) out.append(", ");
                out.append(e.getKey()).append(' ').append(e.getValue());
                first = false;
            }
        }
        if (lastMs >= 0) {
            out.append(String.format(Locale.UK, "; last %d nodes in %d ms", lastExpansions, lastMs));
        }
        if (Double.isFinite(lastStartSnap)) {
            out.append(String.format(Locale.UK, "; snaps %.0f m / %.0f m", lastStartSnap, lastEndSnap));
        }
        if (alternativesTried > 0) {
            out.append("; alternatives ").append(alternativesOffered).append('/').append(alternativesTried);
        }
        return out.toString();
    }

    public synchronized void reset() {
        requests = successes = alternativesTried = alternativesOffered = 0;
        failures.clear();
        lastExpansions = -1;
        lastMs = -1;
        lastStartSnap = lastEndSnap = Double.NaN;
    }
}
