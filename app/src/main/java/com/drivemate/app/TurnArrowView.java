package com.drivemate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Compact, high-contrast manoeuvre icon. Intended display size is 52dp. */
public final class TurnArrowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private enum Arrow { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, ROUNDABOUT, ARRIVE }
    private Arrow arrow = Arrow.STRAIGHT;
    private int roundaboutExit;
    private int accentColor = Color.rgb(7, 214, 235);
    private int exitTextColor = Color.WHITE;

    /** Keep turn arrows and exit numbers legible in either road-map theme. */
    public void setNightMode(boolean night) {
        accentColor = night ? Color.rgb(7, 214, 235) : Color.rgb(0, 96, 130);
        exitTextColor = night ? Color.WHITE : Color.rgb(22, 48, 62);
        invalidate();
    }

    public TurnArrowView(Context context) {
        super(context);
        setContentDescription("Upcoming road direction");
    }

    public void update(String symbol, int exit) {
        if ("↻".equals(symbol)) arrow = Arrow.ROUNDABOUT;
        else if ("⤺".equals(symbol)) arrow = Arrow.UTURN;
        else if ("⚑".equals(symbol)) arrow = Arrow.ARRIVE;
        else if ("↖".equals(symbol)) arrow = Arrow.SLIGHT_LEFT;
        else if ("↗".equals(symbol)) arrow = Arrow.SLIGHT_RIGHT;
        else if ("↰".equals(symbol)) arrow = Arrow.LEFT;
        else if ("↱".equals(symbol)) arrow = Arrow.RIGHT;
        else arrow = Arrow.STRAIGHT;
        roundaboutExit = Math.max(0, exit);
        String label = arrow == Arrow.ROUNDABOUT
                ? "Roundabout" + (roundaboutExit > 0 ? ", exit " + roundaboutExit : "")
                : arrow.name().replace('_', ' ').toLowerCase();
        setContentDescription(label);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int state = canvas.save();
        float size = Math.min(getWidth(), getHeight());
        canvas.translate((getWidth() - size) * 0.5f, (getHeight() - size) * 0.5f);
        canvas.scale(size / 64f, size / 64f);
        paint.setColor(accentColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        Path head = new Path();
        switch (arrow) {
            case LEFT:
                path.moveTo(33, 53); path.lineTo(33, 30); path.quadTo(33, 22, 25, 22);
                path.lineTo(13, 22);
                head.moveTo(21, 13); head.lineTo(12, 22); head.lineTo(21, 31);
                break;
            case RIGHT:
                path.moveTo(31, 53); path.lineTo(31, 30); path.quadTo(31, 22, 39, 22);
                path.lineTo(51, 22);
                head.moveTo(43, 13); head.lineTo(52, 22); head.lineTo(43, 31);
                break;
            case SLIGHT_LEFT:
                path.moveTo(39, 53); path.lineTo(39, 40); path.quadTo(39, 36, 35, 32);
                path.lineTo(15, 16);
                head.moveTo(16, 29); head.lineTo(14, 15); head.lineTo(29, 17);
                break;
            case SLIGHT_RIGHT:
                path.moveTo(25, 53); path.lineTo(25, 40); path.quadTo(25, 36, 29, 32);
                path.lineTo(49, 16);
                head.moveTo(35, 17); head.lineTo(50, 15); head.lineTo(48, 29);
                break;
            case UTURN:
                path.moveTo(45, 54); path.lineTo(45, 29); path.cubicTo(45, 5, 17, 5, 17, 29);
                path.lineTo(17, 42);
                head.moveTo(8, 34); head.lineTo(17, 43); head.lineTo(26, 34);
                break;
            case ROUNDABOUT:
                // Compact circle-with-arrow. Number is shown separately inside the circle.
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawArc(15, 14, 49, 48, 38, 292, false, paint);
                head.moveTo(38, 10); head.lineTo(49, 16); head.lineTo(48, 28);
                break;
            case ARRIVE:
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(32, 29, 14, paint);
                canvas.drawLine(32, 17, 32, 41, paint);
                canvas.drawLine(20, 29, 44, 29, paint);
                break;
            case STRAIGHT:
            default:
                path.moveTo(32, 53); path.lineTo(32, 12);
                head.moveTo(20, 24); head.lineTo(32, 11); head.lineTo(44, 24);
                break;
        }
        canvas.drawPath(path, paint);
        canvas.drawPath(head, paint);
        if (arrow == Arrow.ROUNDABOUT && roundaboutExit > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(1);
            paint.setColor(exitTextColor);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(roundaboutExit > 9 ? 13f : 18f);
            paint.setFakeBoldText(true);
            canvas.drawText(Integer.toString(roundaboutExit), 32, 36, paint);
            paint.setFakeBoldText(false);
        }
        canvas.restoreToCount(state);
    }
}
