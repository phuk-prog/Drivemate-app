package com.drivemate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Layout A speed cluster: a 64 dp current-speed circle in the bottom-left of a
 * 96 x 76 dp box, with a 44 dp UK speed-limit roundel overlapping its top-right
 * edge. A small second roundel with the distance appears to the right when the
 * limit changes within about 200 yd. The view never moves during a drive; the
 * camera reserves its height so the vehicle arrow is never drawn under it.
 */
final class SpeedClusterView extends View {
    static final int WIDTH_DP = 150, HEIGHT_DP = 76;
    private static final int SPEED_DP = 64, LIMIT_DP = 44, NEXT_DP = 30;
    private static final int DARK_FILL = Color.rgb(15, 34, 54);
    private static final int CYAN = Color.rgb(0, 209, 232);
    private static final int SIGN_RED = Color.rgb(215, 38, 46);
    private static final int ALERT_RED = Color.rgb(240, 62, 62);
    private static final long PULSE_MS = 600;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private String speed = "0", unit = "mph";
    private int limit = -1, nextLimit = -1;
    private String nextDistance = "";
    private SpeedClusterPolicy.Level level = SpeedClusterPolicy.Level.NORMAL;
    private boolean night;
    private long pulseStart = -1;

    SpeedClusterView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        describe();
    }

    private float dp(float v) { return v * density; }

    void setSpeed(double metresPerSecond, boolean metric) {
        String s = SpeedClusterPolicy.speedNumber(metresPerSecond, metric), u = SpeedClusterPolicy.unit(metric);
        if (s.equals(speed) && u.equals(unit)) return;
        speed = s; unit = u; describe(); invalidate();
    }

    void setNoSpeed() { if (!"–".equals(speed)) { speed = "–"; describe(); invalidate(); } }

    /** Posted limit in display units, or 0 or less when unknown (roundel hidden, never guessed). */
    void setLimit(int value) {
        int v = value > 0 ? value : -1;
        if (v == limit) return;
        limit = v;
        if (v < 0) { nextLimit = -1; nextDistance = ""; }
        describe(); invalidate();
    }

    /** Upcoming limit change: small roundel with its distance. Pass -1 to hide. */
    void setUpcoming(int value, String distance) {
        int v = value > 0 ? value : -1;
        String d = v > 0 && distance != null ? distance : "";
        if (v == nextLimit && d.equals(nextDistance)) return;
        nextLimit = v; nextDistance = d; describe(); invalidate();
    }

    void setLevel(SpeedClusterPolicy.Level value) {
        SpeedClusterPolicy.Level v = value == null ? SpeedClusterPolicy.Level.NORMAL : value;
        if (v == level) return;
        if (level == SpeedClusterPolicy.Level.NORMAL && v != SpeedClusterPolicy.Level.NORMAL)
            pulseStart = SystemClock.uptimeMillis();
        level = v; describe(); invalidate();
    }

    void setNight(boolean value) { if (night != value) { night = value; invalidate(); } }

    private void describe() {
        StringBuilder b = new StringBuilder("Speed ").append("–".equals(speed) ? "unavailable" : speed + " " + unit);
        if (limit > 0) b.append(". Mapped speed limit ").append(limit).append(". Road signs take precedence");
        if (level != SpeedClusterPolicy.Level.NORMAL) b.append(". Above the mapped limit");
        if (nextLimit > 0) b.append(". Limit changes to ").append(nextLimit).append(nextDistance.isEmpty() ? "" : " in " + nextDistance);
        setContentDescription(b.toString());
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) dp(WIDTH_DP), w), resolveSize((int) dp(HEIGHT_DP), h));
    }

    @Override protected void onDraw(Canvas canvas) {
        float r = dp(SPEED_DP) / 2f, cx = r, cy = dp(HEIGHT_DP) - r;
        boolean over = level != SpeedClusterPolicy.Level.NORMAL;
        boolean well = level == SpeedClusterPolicy.Level.WELL_OVER;
        int ringAlpha = night ? 204 : 255;
        // Speed circle.
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        paint.setColor(well ? ALERT_RED : DARK_FILL);
        canvas.drawCircle(cx, cy, r, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(over ? (well ? Color.WHITE : ALERT_RED) : CYAN);
        paint.setAlpha(ringAlpha);
        canvas.drawCircle(cx, cy, r - dp(1.5f), paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(over && !well ? ALERT_RED : Color.WHITE);
        paint.setTextSize(dp(speed.length() > 2 ? 22 : 26));
        canvas.drawText(speed, cx, cy + dp(5), paint);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(dp(11));
        paint.setColor(well ? Color.WHITE : Color.rgb(176, 196, 210));
        canvas.drawText(unit, cx, cy + dp(19), paint);
        // Posted limit roundel, overlapping the circle's top-right edge.
        if (limit > 0) {
            float lr = dp(LIMIT_DP) / 2f, lx = dp(96) - lr, ly = lr;
            float scale = 1f;
            if (pulseStart >= 0) {
                long t = SystemClock.uptimeMillis() - pulseStart;
                if (t < PULSE_MS) { scale = 1f + 0.15f * (float) Math.sin(Math.PI * t / PULSE_MS); postInvalidateOnAnimation(); }
                else pulseStart = -1;
            }
            drawRoundel(canvas, lx, ly, lr * scale, dp(5) * scale, limit, dp(18) * scale);
            if (nextLimit > 0 && nextLimit != limit) {
                float nr = dp(NEXT_DP) / 2f, nx = lx + lr + dp(8) + nr, ny = lr;
                drawRoundel(canvas, nx, ny, nr, dp(3.5f), nextLimit, dp(12));
                if (!nextDistance.isEmpty()) {
                    paint.setTypeface(Typeface.DEFAULT_BOLD);
                    paint.setTextSize(dp(10.5f));
                    float tw = paint.measureText(nextDistance), ty = ny + nr + dp(14);
                    paint.setColor(DARK_FILL);
                    canvas.drawRoundRect(nx - tw / 2 - dp(4), ty - dp(11), nx + tw / 2 + dp(4), ty + dp(4), dp(4), dp(4), paint);
                    paint.setColor(Color.WHITE);
                    canvas.drawText(nextDistance, nx, ty, paint);
                }
            }
        }
    }

    private void drawRoundel(Canvas canvas, float x, float y, float radius, float ring, int value, float textSize) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(dp(3), 0, dp(1), 0x55000000);
        canvas.drawCircle(x, y, radius, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ring);
        paint.setColor(SIGN_RED);
        canvas.drawCircle(x, y, radius - ring / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        String t = String.valueOf(value);
        paint.setTextSize(t.length() > 2 ? textSize * 0.8f : textSize);
        canvas.drawText(t, x, y + paint.getTextSize() * 0.35f, paint);
    }
}
