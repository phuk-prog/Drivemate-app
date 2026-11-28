package com.drivemate.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Same place, same camera moves, every engine: time to first roads, UI frame pacing during a scripted pan,
 * and how much of the map is still blank afterwards. Results: unified-zoom/engine-comparison.json + screenshots.
 * DriveMate's own GPU engine becomes the default once it matches or beats MapLibre here.
 */
@RunWith(AndroidJUnit4.class)
public class EngineComparisonTest {
    @Test public void compareEnginesOnTheSameDrive() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        Context app = ins.getTargetContext();
        DmUnifiedRegion region = new DmUnifiedRegion(app, true);
        DmTileEngine.Route sample = region.sample(true);
        GeoMath.Point center = GeoMath.pointAtDistance(sample.points, sample.metres * .4);
        JSONArray results = new JSONArray();
        NativeMapScene scene = region.nativeScene(sample);
        for (String engine : MapEngines.ALL) results.put(run(ins, app, engine, center, sample.points, scene));
        File dir = new File(app.getExternalFilesDir(null), "unified-zoom");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, "engine-comparison.json"))) {
            out.write(results.toString(2).getBytes("UTF-8"));
        }
        Log.i("EngineComparison", results.toString());
        DmUnifiedZoomTest.engine(app, MapEngines.DEFAULT);
    }

    private static JSONObject run(Instrumentation ins, Context app, String engine, GeoMath.Point center, List<GeoMath.Point> route, NativeMapScene scene) throws Exception {
        DmUnifiedZoomTest.engine(app, engine);
        MainActivity a = (MainActivity) ins.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject r = new JSONObject().put("engine", engine);
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("vectorMap");
            field.setAccessible(true);
            DriveMap map = (DriveMap) field.get(a);
            assertNotNull(map);
            r.put("name", map.engineName());
            long start = SystemClock.elapsedRealtime();
            ins.runOnMainSync(() -> { map.setSharedRoute(scene, route, NativeRegionalRouter.PROVIDER); map.setCamera(center, 17, 0); });
            AtomicBoolean roads = new AtomicBoolean();
            long deadline = start + 90000;
            while (SystemClock.elapsedRealtime() < deadline) {
                ins.runOnMainSync(() -> roads.set(map.hasRenderedRoads()));
                if (roads.get()) break;
                SystemClock.sleep(250);
            }
            r.put("roadsAfterMs", roads.get() ? SystemClock.elapsedRealtime() - start : -1);
            assertTrue(engine + " drew no roads", roads.get());
            SystemClock.sleep(3000);
            // Scripted pan + zoom: 240 frames, the camera moves every frame like a finger drag.
            List<Long> stamps = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            ins.runOnMainSync(() -> Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                int frame;
                @Override public void doFrame(long nanos) {
                    stamps.add(nanos);
                    double t = frame / 240.0;
                    GeoMath.Point p = new GeoMath.Point(center.lat + .004 * Math.sin(t * Math.PI * 2), center.lon + .008 * t);
                    map.setCamera(p, 17 - 2 * Math.sin(t * Math.PI), 0);
                    if (++frame < 240) Choreographer.getInstance().postFrameCallback(this);
                    else done.countDown();
                }
            }));
            assertTrue(done.await(60, TimeUnit.SECONDS));
            int janky = 0; double total = 0, worst = 0;
            for (int i = 1; i < stamps.size(); i++) {
                double ms = (stamps.get(i) - stamps.get(i - 1)) / 1e6;
                total += ms; worst = Math.max(worst, ms);
                if (ms > 33) janky++;
            }
            r.put("meanFrameMs", Math.round(total / Math.max(1, stamps.size() - 1) * 10) / 10.0);
            r.put("worstFrameMs", Math.round(worst * 10) / 10.0);
            r.put("jankyFrames", janky);
            // Let tiles settle, then measure blank map in the middle band (no panels).
            SystemClock.sleep(5000);
            Bitmap shot = NativeScreenshot.capture(ins, a);
            assertNotNull(shot);
            r.put("blankPercent", blankPercent(shot));
            File dir = new File(app.getExternalFilesDir(null), "unified-zoom");
            assertTrue(dir.isDirectory() || dir.mkdirs());
            try (FileOutputStream out = new FileOutputStream(new File(dir, "engine-" + engine + ".png"))) {
                shot.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally { shot.recycle(); }
            Log.i("EngineComparison", String.format(Locale.UK, "%s %s", engine, r));
        } finally {
            ins.runOnMainSync(a::finish);
            SystemClock.sleep(1500);
        }
        return r;
    }

    /** Share of pixels in the middle band that are plain background (no map drawn). */
    static double blankPercent(Bitmap shot) {
        Bitmap b = shot.getConfig() == Bitmap.Config.ARGB_8888 ? shot : shot.copy(Bitmap.Config.ARGB_8888, false);
        int[] backgrounds = {0xffeef1e9, 0xffF3F6F3, 0xff132634, 0xff101C2B, 0xff000000, 0xffffffff};
        int top = (int) (b.getHeight() * .3), bottom = (int) (b.getHeight() * .7), blank = 0, total = 0;
        for (int y = top; y < bottom; y += 4)
            for (int x = 0; x < b.getWidth(); x += 4) {
                int c = b.getPixel(x, y); total++;
                for (int bg : backgrounds) if (close(c, bg)) { blank++; break; }
            }
        return Math.round(blank * 1000.0 / Math.max(1, total)) / 10.0;
    }

    private static boolean close(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) <= 3 && Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) <= 3
                && Math.abs((a & 255) - (b & 255)) <= 3;
    }
}
