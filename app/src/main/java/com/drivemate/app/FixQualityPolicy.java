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
 * 5. Worse fixes are accepted only as a fallback when no accepted fix has
 *    arrived for {@link #FALLBACK_AFTER_MS}, and only up to
 *    {@link #FALLBACK_ACCURACY_METRES}.
 * 6. A jump implying a speed above {@link #MAX_PLAUSIBLE_SPEED_MPS} is rejected,
 *    unless the previous accepted fix is older than {@link #REACQUIRE_AFTER_MS}
 *    (re-acquisition after a long gap is allowed).
 */
public final class FixQualityPolicy {
    public static final float GOOD_ACCURACY_METRES = 50f;
    public static final float FALLBACK_ACCURACY_METRES = 100f;
    public static final long FALLBACK_AFTER_MS = 10_000L;
    /** How long a higher-ranked source keeps priority after its last fix. */
    public static final long GPS_FRESH_MS = 8_000L;

    public static final int SOURCE_NETWORK = 0;
    public static final int SOURCE_GPS = 1;
    public static final int SOURCE_FUSED = 2;
    public static final long REACQUIRE_AFTER_MS = 30_000L;
    /** About 216 km/h: above anything legal on UK roads, below a bad-fix teleport. */
    public static final double MAX_PLAUSIBLE_SPEED_MPS = 60.0;

    private FixQualityPolicy() { }

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
        if (candidate == null) return false;
        if (!validCoordinate(candidate.lat, candidate.lon)) return false;
        if (!Float.isFinite(candidate.accuracyMetres) || candidate.accuracyMetres < 0) return false;

        if (previous == null) {
            return candidate.accuracyMetres <= FALLBACK_ACCURACY_METRES;
        }

        long dt = candidate.timeMs - previous.timeMs;
        if (dt <= 0) return false;

        if (candidate.source < previous.source && dt < GPS_FRESH_MS) {
            return false;
        }

        if (candidate.accuracyMetres > GOOD_ACCURACY_METRES) {
            if (dt < FALLBACK_AFTER_MS) return false;
            if (candidate.accuracyMetres > FALLBACK_ACCURACY_METRES) return false;
        }

        if (dt <= REACQUIRE_AFTER_MS) {
            double metres = GeoMath.distance(
                    new GeoMath.Point(previous.lat, previous.lon),
                    new GeoMath.Point(candidate.lat, candidate.lon));
            // Allow for the uncertainty of both fixes so good GPS isn't rejected.
            double allowance = previous.accuracyMetres + candidate.accuracyMetres;
            double seconds = dt / 1000.0;
            if (metres - allowance > MAX_PLAUSIBLE_SPEED_MPS * seconds) return false;
        }
        return true;
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -85.0 && lat <= 85.0
                && lon >= -180.0 && lon <= 180.0;
    }
}
