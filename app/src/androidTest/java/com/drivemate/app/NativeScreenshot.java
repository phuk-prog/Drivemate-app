package com.drivemate.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real compositor capture only. No synthetic drawing or assertion waiver. */
final class NativeScreenshot {
    private NativeScreenshot(){}
    static Bitmap capture(Instrumentation ins,Activity activity)throws Exception{
        for(int attempt=0;attempt<3;attempt++){
            ins.waitForIdleSync();
            Bitmap shot=ins.getUiAutomation().takeScreenshot();
            if(shot!=null)return shot;
            SystemClock.sleep(350);
        }
        // UiAutomation can return null on headless emulators even when the
        // application surface has rendered. Copy that actual window instead.
        for(int attempt=0;attempt<3;attempt++){
            final Bitmap[] bitmap=new Bitmap[1];
            CountDownLatch done=new CountDownLatch(1);
            AtomicInteger status=new AtomicInteger(-1);
            ins.runOnMainSync(()->{
                android.view.View decor=activity.getWindow().getDecorView();
                if(activity.isFinishing()||decor.getWidth()<100||decor.getHeight()<100){done.countDown();return;}
                bitmap[0]=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
                try{
                    PixelCopy.request(activity.getWindow(),bitmap[0],r->{status.set(r);done.countDown();},new Handler(Looper.getMainLooper()));
                }catch(IllegalArgumentException unavailable){done.countDown();}
            });
            if(!done.await(8,TimeUnit.SECONDS))throw new AssertionError("Window screenshot timed out");
            if(status.get()==PixelCopy.SUCCESS&&bitmap[0]!=null){
                android.util.Log.i("UnifiedZoom","Captured actual application window via PixelCopy");
                return bitmap[0];
            }
            if(bitmap[0]!=null)bitmap[0].recycle();
            SystemClock.sleep(350);
        }
        throw new AssertionError("Neither display nor actual window screenshot available");
    }
}
