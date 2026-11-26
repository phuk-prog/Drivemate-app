package com.drivemate.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Equal-width lane cells with all source directions; selected branch is highlighted. */
public final class LaneGuidanceView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RouteIntelligence.LaneSection section;
    public LaneGuidanceView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    public void setSection(RouteIntelligence.LaneSection value){
        section=value;
        setContentDescription(value==null ? "" : "Verified route lane guidance: "+value.display());
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(section==null || !section.trusted())return;
        int count=section.lanes.size();
        float width=(float)getWidth()/count, height=getHeight();
        float scale=Math.min(width/42f,height/44f);
        if(scale<=0)return;
        for(int i=0;i<count;i++){
            RouteIntelligence.Lane lane=section.lanes.get(i);
            canvas.save();canvas.translate(width*(i+.5f),height/2f);canvas.scale(scale,scale);
            paint.setStyle(Paint.Style.FILL);paint.setColor(lane.recommended?Color.rgb(18,76,80):Color.rgb(35,50,63));
            canvas.drawRoundRect(-19,-21,19,21,5,5,paint);
            for(String direction:lane.directions) if(!lane.recommended||!direction.equals(lane.direction))
                arrow(canvas,new RouteIntelligence.Lane(direction,false).symbol(),Color.rgb(156,176,188));
            if(lane.recommended)arrow(canvas,lane.symbol(),Color.rgb(95,240,220));
            canvas.restore();
        }
    }
    private void arrow(Canvas c,String symbol,int color){
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);
        Path path=new Path();path.moveTo(0,15);float x=0,y=-13,dx=0,dy=-1;
        switch(symbol){
            case "↰": x=-13;y=-3;dx=-1;dy=0;path.lineTo(0,-3);break;
            case "↱": x=13;y=-3;dx=1;dy=0;path.lineTo(0,-3);break;
            case "↖": x=-12;y=-13;dx=-.7f;dy=-.7f;path.lineTo(0,3);break;
            case "↗": x=12;y=-13;dx=.7f;dy=-.7f;path.lineTo(0,3);break;
            case "⤺": case "⤻":
                float side=symbol.equals("⤺")?-1:1;
                path.lineTo(0,-6);path.quadTo(0,-17,side*8,-17);
                path.quadTo(side*16,-17,side*16,-6);x=side*16;y=6;dx=0;dy=1;break;
            default: break;
        }
        path.lineTo(x,y);c.drawPath(path,paint);
        Path head=new Path();head.moveTo(x-dx*6-dy*5,y-dy*6+dx*5);
        head.lineTo(x,y);head.lineTo(x-dx*6+dy*5,y-dy*6-dx*5);c.drawPath(head,paint);
    }
}
