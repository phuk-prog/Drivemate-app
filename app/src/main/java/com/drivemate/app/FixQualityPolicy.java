package com.drivemate.app;

/**
 * Decides which location fixes are good enough to drive on.
 * Pure Java, so the rules can be unit tested without Android location services.
 *
 * Rules, in order:
 * 1. Invalid coordinates, non-finite or negative accuracy are rejected.
 * 2. Fixes must move forward in time; stale or duplicate fixes are rejected.
 * 3. Sources are ranked fused > GPS > network. A fix from a lower-ranked
 *    source is rejected while a fix from a higher-ranked source is recent,
 *    so the best source in use drives the display without interleaving.
 * 4. Fixes better than {@link #GOOD_ACCURACY_METRES} are accepted.
 * 5. Moderately poor fixes (up to {@link #POOR_ACCEPT_ACCURACY_METRES}) are
 *    accepted once {@link #POOR_ACCEPT_AFTER_MS} has passed since the last
 *    accepted fix and the implied speed is plausible. Worse fixes are accepted
 *    only as a fallback when no accepted fix has arrived for
 *    {@link #FALLBACK_AFTER_MS}, and only up to {@link #FALLBACK_ACCURACY_METRES}.
 * 6. A jump implying a speed above {@link #MAX_PLAUSIBLE_SPEED_MPS} is rejected,
 *    unless the previous accepted fix is older than {@link #REACQUIRE_AFTER_MS}
 *    (re-acquisition after a long gap is allowed).
 */
public final class FixQualityPolicy {
    public static final float GOOD_ACCURACY_METRES = LocationThresholds.GOOD_ACCURACY_METRES;
    public static final float POOR_ACCEPT_ACCURACY_METRES = LocationThresholds.POOR_ACCEPT_ACCURACY_METRES;
    public static final long POOR_ACCEPT_AFTER_MS = LocationThresholds.POOR_ACCEPT_AFTER_MS;
    public static final float FALLBACK_ACCURACY_METRES = LocationThresholds.FALLBACK_ACCURACY_METRES;
    public static final long FALLBACK_AFTER_MS = LocationThresholds.FALLBACK_AFTER_MS;
    /** How long a higher-ranked source keeps priority after its last fix. */
    public static final long GPS_FRESH_MS = LocationThresholds.GPS_FRESH_MS;

    public static final int SOURCE_NETWORK = 0;
    public static final int SOURCE_GPS = 1;
    public static final int SOURCE_FUSED = 2;
    public static final long REACQUIRE_AFTER_MS = LocationThresholds.REACQUIRE_AFTER_MS;
    /** About 216 km/h: above anything legal on UK roads, below a bad-fix teleport. */
    public static final double MAX_PLAUSIBLE_SPEED_MPS = LocationThresholds.MAX_PLAUSIBLE_SPEED_MPS;

    private FixQualityPolicy() { }

    /** Why a fix was accepted or rejected; used for diagnostics counters. */
    public enum Reason {
        ACCEPTED, MISSING, INVALID_COORDINATE, INVALID_ACCURACY, STALE_OR_DUPLICATE,
        LOWER_RANK_SOURCE, POOR_ACCURACY, TOO_POOR, TELEPORT, FIRST_FIX_TOO_POOR
    }

    /** A fix reduced to what the policy needs. */
    public static final class Sample {
        public final double lat;
        public final double lon;
        public final long timeMs;
        public final float accuracyMetres;
        /** One of SOURCE_NETWORK, SOURCE_GPS, SOURCE_FUSED. */
        public final int source;

        public Sample(double lat, double lon, long timeMs, float accuracyMetres, int source) {
            this.lat = lat;
            this.lon = lon;
            this.timeMs = timeMs;
            this.accuracyMetres = accuracyMetres;
            this.source = source;
        }

        /** Shorthand for GPS (true) or network (false) fixes. */
        public Sample(double lat, double lon, long timeMs, float accuracyMetres, boolean gps) {
            this(lat, lon, timeMs, accuracyMetres, gps ? SOURCE_GPS : SOURCE_NETWORK);
        }
    }

    /**
     * @param previous the last accepted fix, or null if there is none
     * @param candidate the new fix to judge
     * @return true if the candidate should be used for display and guidance
     */
    public static boolean accept(Sample previous, Sample candidate) {
        return decide(previous, candidate) == Reason.ACCEPTED;
    }

    /** Same rules as {@link #accept}, but says why a fix was rejected. */
    public static Reason decide(Sample previous, Sample candidate) {
        if (candidate == null) return Reason.MISSING;
        if (!validCoordinate(candidate.lat, candidate.lon)) return Reason.INVALID_COORDINATE;
        if (!Float.isFinite(candidate.accuracyMetres) || candidate.accuracyMetres < 0) {
            return Reason.INVALID_ACCURACY;
        }

        if (previous == null) {
            return candidate.accuracyMetres <= FALLBACK_ACCURACY_METRES
                    ? Reason.ACCEPTED : Reason.FIRST_FIX_TOO_POOR;
        }

        long dt = candidate.timeMs - previous.timeMs;
        if (dt <= 0) return Reason.STALE_OR_DUPLICATE;

        // A genuinely precise raw satellite fix may replace poor fused data.
        boolean betterSatellite = candidate.source == SOURCE_GPS && previous.source == SOURCE_FUSED
                && candidate.accuracyMetres <= 25 && previous.accuracyMetres > 35
                && previous.accuracyMetres-candidate.accuracyMetres >= 15;
        if (candidate.source < previous.source && dt < GPS_FRESH_MS && !betterSatellite) {
            return Reason.LOWER_RANK_SOURCE;
        }

        if (previous.source==SOURCE_GPS && previous.accuracyMetres<=25 && candidate.source==SOURCE_FUSED
                && candidate.accuracyMetres>35 && dt<GPS_FRESH_MS) return Reason.LOWER_RANK_SOURCE;
        if (candidate.accuracyMetres > GOOD_ACCURACY_METRES) {
            if (candidate.accuracyMetres > FALLBACK_ACCURACY_METRES) return Reason.TOO_POOR;
            if (candidate.accuracyMetres <= POOR_ACCEPT_ACCURACY_METRES) {
                if (dt < POOR_ACCEPT_AFTER_MS) return Reason.POOR_ACCURACY;
                // A moderately poor fix must also imply a plausible speed on its own,
                // without the allowance good fixes get below.
                double metresPoor = GeoMath.distance(
                        new GeoMath.Point(previous.lat, previous.lon),
                        new GeoMath.Point(candidate.lat, candidate.lon));
                if (dt <= REACQUIRE_AFTER_MS
                        && metresPoor > MAX_PLAUSIBLE_SPEED_MPS * (dt / 1000.0)) {
                    return Reason.TELEPORT;
                }
            } else if (dt < FALLBACK_AFTER_MS) {
                return Reason.POOR_ACCURACY;
            }
        }

        if (dt <= REACQUIRE_AFTER_MS) {
            double metres = GeoMath.distance(
                    new GeoMath.Point(previous.lat, previous.lon),
                    new GeoMath.Point(candidate.lat, candidate.lon));
            // Allow for the uncertainty of both fixes so good GPS isn't rejected.
            double allowance = previous.accuracyMetres + candidate.accuracyMetres;
            double seconds = dt / 1000.0;
            if (metres - allowance > MAX_PLAUSIBLE_SPEED_MPS * seconds) return Reason.TELEPORT;
        }
        return Reason.ACCEPTED;
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -85.0 && lat <= 85.0
                && lon >= -180.0 && lon <= 180.0;
    }
}
