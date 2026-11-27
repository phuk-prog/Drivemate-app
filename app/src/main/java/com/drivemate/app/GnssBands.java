package com.drivemate.app;

/**
 * Identifies dual-frequency (L5 / E5a) satellites from the carrier frequency
 * Android reports. Dual-frequency phones resolve multipath better, which is
 * what matters most in urban canyons, so the diagnostics report counts them.
 * Pure Java so the band edges can be unit tested.
 */
public final class GnssBands {
    private GnssBands() { }

    /** GPS L5, Galileo E5a, NavIC L5 and QZSS L5 share this carrier. */
    public static final double L5_HZ = 1_176_450_000.0;
    private static final double TOLERANCE_HZ = 5_000_000.0;

    public static boolean isL5(double carrierHz) {
        return Double.isFinite(carrierHz) && Math.abs(carrierHz - L5_HZ) <= TOLERANCE_HZ;
    }
}
