package com.drivemate.app;

/**
 * Keeps a usable heading when the GPS bearing is missing or unreliable (slow,
 * noisy, urban canyon) by combining it with the phone's rotation sensor.
 *
 * The sensor azimuth is relative to however the phone sits in its mount, so it
 * is only used as a change: while GPS bearing is trustworthy the class learns
 * the offset between GPS bearing and sensor azimuth. When GPS bearing is not
 * trustworthy it returns {@code sensorAzimuth + offset}. The learned offset
 * expires, so a phone that has moved in its mount stops being trusted.
 *
 * Pure Java: the Android wiring only passes numbers in. Display and dead
 * reckoning only; never guidance decisions.
 */
public final class HeadingFusion {
    private double offsetDeg;
    private boolean haveOffset;
    private long offsetAtMs = -1;
    private double lastAzimuth = Double.NaN;

    /** Is this GPS bearing good enough to learn from? */
    public static boolean gpsBearingTrusted(boolean hasBearing, float speedMps, float bearingAccuracyDeg,
                                            boolean hasBearingAccuracy) {
        return hasBearing && speedMps >= LocationThresholds.BEARING_TRUST_MIN_SPEED_MPS
                && (!hasBearingAccuracy || bearingAccuracyDeg <= LocationThresholds.BEARING_TRUST_MAX_ACCURACY_DEG);
    }

    /** Feed the latest sensor azimuth (degrees clockwise from the phone's reference). */
    public void onSensorAzimuth(double azimuthDeg) {
        if (Double.isFinite(azimuthDeg)) lastAzimuth = normalise(azimuthDeg);
    }

    /** Feed a GPS bearing that passed {@link #gpsBearingTrusted}. */
    public void onTrustedGpsBearing(double bearingDeg, long timeMs) {
        if (!Double.isFinite(bearingDeg) || !Double.isFinite(lastAzimuth)) return;
        double measured = signedDifference(bearingDeg, lastAzimuth);
        if (!haveOffset) {
            offsetDeg = measured;
        } else {
            // Smooth along the circle so 359 -> 1 degrees does not average to 180.
            offsetDeg = normalise(offsetDeg + 0.25 * signedDifference(measured, offsetDeg));
        }
        haveOffset = true;
        offsetAtMs = timeMs;
    }

    /**
     * Heading from the sensor and learned offset, or NaN when there is no valid
     * offset (never learned, or older than the maximum age).
     */
    public double fusedHeading(long nowMs) {
        if (!haveOffset || !Double.isFinite(lastAzimuth)) return Double.NaN;
        if (nowMs - offsetAtMs > LocationThresholds.HEADING_OFFSET_MAX_AGE_MS) return Double.NaN;
        return normalise(lastAzimuth + offsetDeg);
    }

    public void reset() {
        haveOffset = false;
        offsetAtMs = -1;
        lastAzimuth = Double.NaN;
        offsetDeg = 0;
    }

    static double normalise(double deg) { return ((deg % 360) + 360) % 360; }

    /** a - b wrapped to (-180, 180]. */
    static double signedDifference(double a, double b) {
        double d = ((a - b) % 360 + 540) % 360 - 180;
        return d;
    }
}
