package com.drivemate.app;

/**
 * Pure rules for the speed cluster (current speed + posted limit). Speeds and
 * limits are in the units shown to the driver (mph or km/h).
 */
final class SpeedClusterPolicy {
    enum Level { NORMAL, OVER, WELL_OVER }

    /** Over the limit must last this long before the cluster turns red. */
    static final long OVER_HOLD_MS = 2000;
    /** Default tolerance above the limit, in mph. */
    static final double DEFAULT_TOLERANCE_MPH = 2;

    private Level shown = Level.NORMAL;
    private Level pending = Level.NORMAL;
    private long pendingSince = -1;

    /** Raw level, no hold. A limit of 0 or less means unknown: never over. */
    static Level classify(double speed, double limit, double tolerance) {
        if (!Double.isFinite(speed) || !Double.isFinite(limit) || limit <= 0) return Level.NORMAL;
        double tol = Double.isFinite(tolerance) ? Math.max(0, tolerance) : 0;
        if (speed <= limit + tol) return Level.NORMAL;
        return speed > limit * 1.10 ? Level.WELL_OVER : Level.OVER;
    }

    /** Tolerance in display units for a tolerance set in mph. */
    static double tolerance(double toleranceMph, boolean metric) {
        double mph = Double.isFinite(toleranceMph) ? Math.max(0, toleranceMph) : DEFAULT_TOLERANCE_MPH;
        return metric ? mph * 1.609344 : mph;
    }

    /**
     * Level to display. Going up needs the raw level held for {@link #OVER_HOLD_MS};
     * going down (slowing) shows at once.
     */
    Level update(Level raw, long nowMs) {
        if (raw == null) raw = Level.NORMAL;
        if (raw.ordinal() <= shown.ordinal()) {
            shown = raw; pending = raw; pendingSince = -1;
            return shown;
        }
        if (raw != pending || pendingSince < 0) { pending = raw; pendingSince = nowMs; }
        if (nowMs - pendingSince >= OVER_HOLD_MS) shown = raw;
        return shown;
    }

    void reset() { shown = Level.NORMAL; pending = Level.NORMAL; pendingSince = -1; }

    /** Number shown in the speed circle: rounded, never negative; "–" when unknown. */
    static String speedNumber(double metresPerSecond, boolean metric) {
        if (!Double.isFinite(metresPerSecond) || metresPerSecond < 0) return "–";
        double factor = metric ? 3.6 : 2.2369362921;
        return String.valueOf(Math.round(metresPerSecond * factor));
    }

    static String unit(boolean metric) { return metric ? "km/h" : "mph"; }
}
