package com.drivemate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Equal-width vector lanes, displayed only for trusted provider data. */
public final class LaneGuidanceView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RouteIntelligence.LaneSection section;

    public LaneGuidanceView(Context context) { super(context); }

    public void setLanes(RouteIntelligence.LaneSection candidate) {
        section = candidate != null && candidate.trusted() ? candidate : null;
        setContentDescription(section == null ? "" : "Recommended lanes highlighted: " + section.display());
        setVisibility(section == null ? GONE : VISIBLE);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (section == null || !section.trusted() || getWidth() <= 0 || getHeight() <= 0) return;
        int count = section.lanes.size();
        float cell = Math.min(getWidth() / (float) count, getHeight() * 1.35f);
        float start = (getWidth() - count * cell) / 2;
        for (int i = 0; i < count; i++) {
            RouteIntelligence.Lane lane = section.lanes.get(i);
            int save = canvas.save();
            float scale = Math.min(cell / 48f, getHeight() / 48f);
            canvas.translate(start + i * cell + (cell - 48 * scale) / 2,
                    (getHeight() - 48 * scale) / 2);
            canvas.scale(scale, scale);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(lane.recommended ? Color.rgb(21, 69, 81) : Color.rgb(20, 43, 57));
            canvas.drawRoundRect(new RectF(2, 2, 46, 46), 7, 7, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(3.5f);
            paint.setColor(lane.recommended ? Color.rgb(7, 214, 235) : Color.rgb(166, 185, 195));
            Path shaft = new Path(), head = new Path();
            String symbol = lane.symbol();
            boolean left = "↰".equals(symbol) || "↖".equals(symbol) || "⤺".equals(symbol);
            if ("⤺".equals(symbol) || "⤻".equals(symbol)) {
                if (!left) { canvas.translate(48, 0); canvas.scale(-1, 1); }
                shaft.moveTo(34, 40); shaft.lineTo(34, 19); shaft.cubicTo(34, 5, 12, 5, 12, 19);
                shaft.lineTo(12, 31); head.moveTo(6, 25); head.lineTo(12, 31); head.lineTo(18, 25);
            } else if ("↰".equals(symbol) || "↱".equals(symbol)) {
                if (!left) { canvas.translate(48, 0); canvas.scale(-1, 1); }
                shaft.moveTo(27, 40); shaft.lineTo(27, 23); shaft.quadTo(27, 17, 21, 17); shaft.lineTo(9, 17);
                head.moveTo(16, 10); head.lineTo(9, 17); head.lineTo(16, 24);
            } else if ("↖".equals(symbol) || "↗".equals(symbol)) {
                if (!left) { canvas.translate(48, 0); canvas.scale(-1, 1); }
                shaft.moveTo(28, 40); shaft.lineTo(28, 29); shaft.lineTo(12, 12);
                head.moveTo(12, 22); head.lineTo(12, 12); head.lineTo(22, 12);
            } else {
                shaft.moveTo(24, 40); shaft.lineTo(24, 8);
                head.moveTo(15, 17); head.lineTo(24, 8); head.lineTo(33, 17);
            }
            canvas.drawPath(shaft, paint); canvas.drawPath(head, paint);
            canvas.restoreToCount(save);
        }
    }
}
