package com.drivemate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small geometry-only junction preview.
 *
 * The line is sampled exclusively from the calculated TomTom route. It never
 * draws unverified exits, junction islands, lane markings or road signs.
 * The incoming approach is rotated upwards for rapid visual interpretation.
 */
public final class JunctionManeuverView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<GeoMath.Point> route = Collections.emptyList();
    private boolean roundabout;
    private int exitNumber;
    private boolean night = true;

    public JunctionManeuverView(Context context) { super(context); }

    public void setNightMode(boolean enabled) {
        night = enabled;
        invalidate();
    }

    public void setRoute(List<GeoMath.Point> geometry, boolean roundabout, int exitNumber) {
        this.route = geometry == null ? Collections.emptyList()
                : new ArrayList<>(geometry);
        this.roundabout = roundabout;
        this.exitNumber = Math.max(0, exitNumber);
        setVisibility(this.route.size() >= 2 ? View.VISIBLE : View.GONE);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (route.size() < 2 || getWidth() < 40 || getHeight() < 40) return;
        float w = getWidth(), h = getHeight();
        paint.setColor(night ? Color.rgb(14, 43, 60) : Color.rgb(228, 242, 246));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(0, 0, w, h), dp(10), dp(10), paint);

        GeoMath.Point start = route.get(0);
        GeoMath.Point heading = route.get(Math.min(route.size() - 1, 4));
        double bearing = Math.toRadians(GeoMath.bearingDegrees(start, heading));
        double co = Math.cos(bearing), si = Math.sin(bearing);
        double latFactor = Math.max(0.1, Math.cos(Math.toRadians(start.lat)));
        float[] xs = new float[route.size()], ys = new float[route.size()];
        float lowX = Float.POSITIVE_INFINITY, highX = Float.NEGATIVE_INFINITY;
        float lowY = Float.POSITIVE_INFINITY, highY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < route.size(); i++) {
            GeoMath.Point p = route.get(i);
            double east = (p.lon - start.lon) * 111320 * latFactor;
            double north = (p.lat - start.lat) * 111320;
            xs[i] = (float) (east * co - north * si);
            ys[i] = (float) (east * si + north * co);
            lowX = Math.min(lowX, xs[i]); highX = Math.max(highX, xs[i]);
            lowY = Math.min(lowY, ys[i]); highY = Math.max(highY, ys[i]);
        }
        float left = dp(18), top = dp(18), right = dp(18), bottom = dp(8);
        float width = Math.max(5, w - left - right);
        float height = Math.max(5, h - top - bottom);
        float spanX = Math.max(12, highX - lowX);
        float spanY = Math.max(12, highY - lowY);
        float scale = Math.min(width / spanX, height / spanY);
        float xPad = (width - (highX - lowX) * scale) / 2f;
        float yPad = (height - (highY - lowY) * scale) / 2f;
        Path actualRoute = new Path();
        float startX = 0, startY = 0;
        for (int i = 0; i < route.size(); i++) {
            float x = left + xPad + (xs[i] - lowX) * scale;
            float y = top + yPad + (highY - ys[i]) * scale;
            if (i == 0) { actualRoute.moveTo(x, y); startX = x; startY = y; }
            else actualRoute.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(8));
        paint.setColor(night ? Color.rgb(9, 28, 41) : Color.WHITE);
        canvas.drawPath(actualRoute, paint);
        paint.setStrokeWidth(dp(4.5f));
        paint.setColor(night ? Color.rgb(37, 236, 210) : Color.rgb(0, 123, 160));
        canvas.drawPath(actualRoute, paint);
        // Mark the route direction, not guessed side roads or junction exits.
        int last = route.size() - 1, previous = last - 1;
        while (previous > 0 && Math.hypot(xs[last] - xs[previous], ys[last] - ys[previous]) * scale < dp(8)) previous--;
        float endX = left + xPad + (xs[last] - lowX) * scale;
        float endY = top + yPad + (highY - ys[last]) * scale;
        double angle = Math.atan2(-(ys[last] - ys[previous]), xs[last] - xs[previous]);
        if (Math.hypot(xs[last] - xs[previous], ys[last] - ys[previous]) > 0.1) {
            Path tip = new Path();
            tip.moveTo(endX - dp(9) * (float)Math.cos(angle - 0.6), endY - dp(9) * (float)Math.sin(angle - 0.6));
            tip.lineTo(endX, endY);
            tip.lineTo(endX - dp(9) * (float)Math.cos(angle + 0.6), endY - dp(9) * (float)Math.sin(angle + 0.6));
            canvas.drawPath(tip, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(startX, startY, dp(5.0f), paint);
        paint.setColor(night ? Color.rgb(0, 113, 149) : Color.rgb(4, 118, 147));
        canvas.drawCircle(startX, startY, dp(3.2f), paint);

        paint.setColor(night ? Color.rgb(191, 226, 239) : Color.rgb(35, 76, 94));
        paint.setTextSize(dp(9f));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("CALCULATED PATH", dp(9), dp(12), paint);
        if (roundabout && exitNumber > 0) {
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("EXIT " + exitNumber, w - dp(8), dp(12), paint);
        }
    }

    private float dp(float raw) {
        return getResources().getDisplayMetrics().density * raw;
    }
}
