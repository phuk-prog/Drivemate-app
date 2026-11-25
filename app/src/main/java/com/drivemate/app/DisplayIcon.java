package com.drivemate.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Consistent resolution-independent driving controls, not font or emoji glyphs. */
public final class DisplayIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String type;
    private final int color, size;
    private int alpha = 255;
    public DisplayIcon(String type, int color, int size) {
        this.type=type; this.color=color; this.size=size; setBounds(0,0,size,size);
    }
    @Override public int getIntrinsicWidth(){ return size; }
    @Override public int getIntrinsicHeight(){ return size; }
    @Override public void setAlpha(int value){ alpha=value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter){ paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity(){ return PixelFormat.TRANSLUCENT; }
    private void line(Canvas c,float... pts){ Path p=new Path(); p.moveTo(pts[0],pts[1]); for(int i=2;i<pts.length;i+=2)p.lineTo(pts[i],pts[i+1]); c.drawPath(p,paint); }
    @Override public void draw(Canvas canvas) {
        int save=canvas.save();
        canvas.translate(getBounds().left,getBounds().top);
        canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        paint.setColor(color); paint.setAlpha(alpha); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        switch(type){
            case "close": line(canvas,6,6,18,18); line(canvas,18,6,6,18); break;
            case "start": line(canvas,7,4,19,12,7,20,7,4); break;
            case "parking": canvas.drawRoundRect(3,3,21,21,3,3,paint); line(canvas,9,17,9,7,13,7,15,9,15,11,13,13,9,13); break;
            case "search": canvas.drawCircle(10,10,6,paint);line(canvas,14.5f,14.5f,20,20);break;
            case "mic": canvas.drawRoundRect(9,3,15,15,3,3,paint);canvas.drawArc(6,7,18,19,0,180,false,paint);line(canvas,12,19,12,22,8,22);line(canvas,12,22,16,22);break;
            case "voice": case "mute": case "repeat":
                line(canvas,3,9,7,9,12,5,12,19,7,15,3,15,3,9);
                if("mute".equals(type)){line(canvas,16,9,22,15);line(canvas,22,9,16,15);}else {canvas.drawArc(9,5,22,19,-55,110,false,paint); if("repeat".equals(type))line(canvas,16,4,21,4,21,9);}break;
            case "recenter": canvas.drawCircle(12,12,7,paint);canvas.drawCircle(12,12,2,paint);line(canvas,12,1,12,5);line(canvas,12,19,12,23);line(canvas,1,12,5,12);line(canvas,19,12,23,12);break;
            case "compass": canvas.drawCircle(12,12,10,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(246,93,98));Path north=new Path();north.moveTo(12,3);north.lineTo(8,13);north.lineTo(12,11);north.lineTo(16,13);north.close();canvas.drawPath(north,paint);paint.setColor(color);Path south=new Path();south.moveTo(12,21);south.lineTo(8,13);south.lineTo(12,15);south.lineTo(16,13);south.close();canvas.drawPath(south,paint);break;
            case "route": canvas.drawCircle(5,18,2,paint);canvas.drawCircle(19,5,2,paint);line(canvas,7,18,14,18,17,15,17,12,14,9,10,9,8,7,10,5,17,5);break;
            case "settings": canvas.drawCircle(12,12,4,paint);canvas.drawCircle(12,12,8,paint);for(int i=0;i<8;i++){canvas.save();canvas.rotate(i*45,12,12);line(canvas,12,1,12,4);canvas.restore();}break;
            case "star": Path star=new Path();for(int i=0;i<10;i++){double angle=Math.toRadians(-90+i*36);float radius=i%2==0?10:4.5f;float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;if(i==0)star.moveTo(x,y);else star.lineTo(x,y);}star.close();canvas.drawPath(star,paint);break;
            default: canvas.drawCircle(12,12,8,paint);
        }
        canvas.restoreToCount(save);
    }
}
