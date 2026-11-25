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
    private float[] routeTrace;
    private int roundaboutDirection;
    private String drivingSide="";
    public void setRoundaboutDirection(int direction,String side){roundaboutDirection=direction;drivingSide=side==null?"":side;invalidate();}
    public void setRouteTrace(float[] points) { routeTrace=points; invalidate(); }
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
        routeTrace=null; roundaboutDirection=0; drivingSide="";
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
                // Draw only the actual traversed path, never invented side roads.
                if(routeTrace!=null && routeTrace.length>=8) {
                    path.moveTo(routeTrace[0],routeTrace[1]);
                    for(int i=2;i<routeTrace.length;i+=2)path.lineTo(routeTrace[i],routeTrace[i+1]);
                    int n=routeTrace.length;
                    float x=routeTrace[n-2],y=routeTrace[n-1],dx=x-routeTrace[n-4],dy=y-routeTrace[n-3];
                    float len=(float)Math.hypot(dx,dy);
                    if(len>0.1f){dx/=len;dy/=len;head.moveTo(x-8*dx-5*dy,y-8*dy+5*dx);head.lineTo(x,y);head.lineTo(x-8*dx+5*dy,y-8*dy-5*dx);}
                    break;
                }
                // Schematic direction only if explicitly returned by the route provider.
                // The arc mirrors traffic handedness; no other exits are drawn.
                if(roundaboutDirection!=0 && ("LEFT".equals(drivingSide)||"RIGHT".equals(drivingSide))) {
                    boolean clockwise="LEFT".equals(drivingSide);
                    double theta=roundaboutDirection==-1?180:roundaboutDirection==1?0:roundaboutDirection==2?270:90;
                    double sweep=clockwise?(theta-90+360)%360:-((90-theta+360)%360);
                    if(roundaboutDirection==3)sweep=clockwise?330:-330;
                    path.moveTo(32,60); path.lineTo(32,47);
                    path.arcTo(17,17,47,47,90,(float)sweep,false);
                    double rad=Math.toRadians(90+sweep);
                    float x=32+15*(float)Math.cos(rad),y=32+15*(float)Math.sin(rad);
                    // The declared exit direction, rather than ordinal, defines the exit.
                    float dx=roundaboutDirection==-1?-1:roundaboutDirection==1?1:0;
                    float dy=roundaboutDirection==2?-1:roundaboutDirection==3?1:0;
                    float tx=x+dx*12,ty=y+dy*12;
                    path.lineTo(tx,ty);
                    head.moveTo(tx-dx*7-dy*5,ty-dy*7+dx*5);head.lineTo(tx,ty);head.lineTo(tx-dx*7+dy*5,ty-dy*7-dx*5);
                    break;
                }
                // Generic symbol if the route shape cannot establish a trustworthy trace.
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
            if(routeTrace==null)canvas.drawText(Integer.toString(roundaboutExit),32,36,paint);
            else {
                paint.setColor(Color.rgb(10,39,59)); canvas.drawCircle(51,51,11,paint);
                paint.setColor(Color.WHITE); paint.setTextSize(roundaboutExit>9?10:13);
                canvas.drawText(Integer.toString(roundaboutExit),51,56,paint);
            }
            paint.setFakeBoldText(false);
        }
        canvas.restoreToCount(state);
    }
}
