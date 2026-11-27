package com.drivemate.app;

/**
 * Every numeric limit used to judge, bridge or time out location fixes, in one
 * place. {@link FixQualityPolicy}, {@link MapStartupPolicy}, the GPS watchdog
 * and the dead-reckoning classes all read these values, so a change here is
 * visible to every rule and covered by the same tests.
 *
 * Values are unchanged from the earlier scattered constants except where a
 * comment says otherwise. They have not been re-tuned on real drives.
 */
public final class LocationThresholds {
    private LocationThresholds() { }

    /** Fixes at least this accurate are accepted straight away. */
    public static final float GOOD_ACCURACY_METRES = 50f;
    /** Moderately poor fixes (above GOOD, up to this) are accepted after {@link #POOR_ACCEPT_AFTER_MS}. */
    public static final float POOR_ACCEPT_ACCURACY_METRES = 80f;
    public static final long POOR_ACCEPT_AFTER_MS = 5_000L;
    /** Very poor fixes (up to FALLBACK) are accepted only after a long quiet period. */
    public static final float FALLBACK_ACCURACY_METRES = 100f;
    public static final long FALLBACK_AFTER_MS = 10_000L;
    /** How long a higher-ranked source keeps priority after its last fix. */
    public static final long GPS_FRESH_MS = 8_000L;
    /** After a gap this long a far-away fix is treated as re-acquisition, not a jump. */
    public static final long REACQUIRE_AFTER_MS = 30_000L;
    /** About 216 km/h: above anything legal on UK roads, below a bad-fix teleport. */
    public static final double MAX_PLAUSIBLE_SPEED_MPS = 60.0;

    /** A fix older than this is too old to start or continue guidance. */
    public static final long MAX_NAVIGATION_FIX_AGE_MS = 20_000L;
    public static final float MAX_NAVIGATION_ACCURACY_METRES = 100f;
    /** No accepted fix for this long: report GPS lost and suspend guidance. */
    public static final long GPS_LOST_AFTER_MS = 15_000L;

    /** Display-only bridge on open road (no tunnel): stop extrapolating after this. */
    public static final long OPEN_ROAD_BRIDGE_MS = 30_000L;
    /** Display-only bridge inside a mapped tunnel: held until the signal returns, but never longer than this. */
    public static final long TUNNEL_BRIDGE_MAX_MS = 10L * 60_000L;
    /** Route-free dead reckoning (free driving) stays short: a straight line drifts quickly. */
    public static final long STRAIGHT_BRIDGE_MS = 6_000L;
    /** Stop the bridged marker this far before a manoeuvre; guidance never relies on it. */
    public static final double MANOEUVRE_HOLD_METRES = 30.0;
    /** Above this estimated uncertainty the bridge gives up and waits for a real fix. */
    public static final double BRIDGE_GIVE_UP_UNCERTAINTY_METRES = 250.0;

    /** GPS bearing is trusted for heading fusion only above this speed ... */
    public static final float BEARING_TRUST_MIN_SPEED_MPS = 3.0f;
    /** ... and within this reported bearing accuracy. */
    public static final float BEARING_TRUST_MAX_ACCURACY_DEG = 20f;
    /** A fused heading offset older than this is discarded. */
    public static final long HEADING_OFFSET_MAX_AGE_MS = 60_000L;
}
