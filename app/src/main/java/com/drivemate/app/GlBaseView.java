package com.drivemate.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * DriveMate's own GPU map engine (OpenGL ES 3).
 *
 * Each tile is tessellated once on a background thread ({@link GlTessellator}) and uploaded once; a frame is then a
 * camera matrix per tile. The projection is the same pinhole model as {@link NativeMapProjection}, so the classic
 * view on top (turn arrow, signs, pin, car, gestures) lines up exactly.
 *
 * Drawn here, in order: land and water, road casings, road fills, bridges, raised buildings (when tilted and
 * close), the route (grey behind the car, blue ahead, amber/red where traffic is slow or stopped), then place and
 * road names (placed by {@link NativeBaseRenderer}: priority, no overlaps, along the road, road-number shields,
 * fade-in). Road edges are anti-aliased in the shader on every phone; new tiles fade in; day and night colours
 * cross-fade; caches are sized to the phone; a lost GL context is rebuilt from the CPU copies.
 */
final class GlBaseView extends GLSurfaceView implements NativeMapView.GpuBase {
    private static final String TAG = "DriveMateGPU";
    private static final long TILE_FADE_MS = 280, NIGHT_FADE_MS = 1500, KEEP_DRAWING_MS = 450;

    private final int maxMeshes, maxTextures;
    private volatile NativeMapView.GpuFrame next;
    private volatile long lastFrameAt;
    private volatile int segmentsDrawn, meshesReady, meshesPending, labelsDrawn, texturesHeld;
    private volatile long gpuFrameNanos, worstFrameNanos;
    private volatile float fps;
    private final ExecutorService tessellator = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "dm-gpu-tessellate"); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    private final Object lock = new Object();
    /** Finished CPU meshes waiting for upload, and CPU meshes kept for re-upload after a context loss. */
    private final Map<Object, GlTessellator.Mesh[]> ready = new IdentityHashMap<>();
    private final LinkedHashMap<Object, GlTessellator.Mesh[]> cpu = new LinkedHashMap<>(64, .75f, true);
    private final Set<Object> pending = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    static boolean supported(Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    GlBaseView(Context context) {
        super(context);
        Log.i(TAG, "DriveMate GPU engine starting");
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int memory = am == null ? 192 : am.getMemoryClass();
        // Caches sized to the phone: fewer tiles kept on low-memory phones.
        maxMeshes = memory >= 384 ? 300 : memory >= 256 ? 240 : memory >= 192 ? 180 : 110;
        maxTextures = memory >= 256 ? 420 : 240;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(new Chooser());
        setPreserveEGLContextOnPause(true);
        setRenderer(new Renderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    // ---------------------------------------------------------------- NativeMapView.GpuBase

    @Override public void frame(NativeMapView.GpuFrame f) {
        next = f;
        lastFrameAt = SystemClock.uptimeMillis();
        requestRender();
    }

    @Override public int roadsDrawn() { return segmentsDrawn; }

    @Override public String stats() {
        return String.format(java.util.Locale.UK, "GPU %.0f fps · frame %.1f ms (worst %.1f) · tiles %d, preparing %d · %,d road segments · %d names · %d textures",
                fps, gpuFrameNanos / 1e6, worstFrameNanos / 1e6, meshesReady, meshesPending, segmentsDrawn, labelsDrawn, texturesHeld);
    }

    /** Tessellate tiles before they come on screen (the route ahead), so they never pop in. */
    @Override public void prepare(List<List<NativeMapScene.Shape>> groups) {
        if (groups == null) return;
        for (List<NativeMapScene.Shape> g : groups) request(g);
    }

    void dispose() { tessellator.shutdownNow(); }

    private volatile boolean failed;
    private Runnable onFailure;
    private volatile long framesDrawn;
    long framesDrawn() { return framesDrawn; }
    void setOnFailure(Runnable r) { onFailure = r; }
    private void fail(Throwable e) {
        if (failed) return;
        failed = true;
        Log.e(TAG, "DriveMate GPU engine stopped; using the classic renderer", e);
        post(() -> { setVisibility(GONE); if (onFailure != null) onFailure.run(); });
    }

    private void request(List<NativeMapScene.Shape> group) {
        synchronized (lock) {
            GlTessellator.Mesh[] kept = cpu.get(group);
            if (kept != null) { ready.put(group, kept); return; }
            if (!pending.add(group)) return;
        }
        tessellator.execute(() -> {
            GlTessellator.Mesh[] built;
            try { built = new GlTessellator.Mesh[]{GlTessellator.build(group, false), GlTessellator.build(group, true)}; }
            catch (Throwable e) { Log.w(TAG, "tile tessellation failed", e); built = new GlTessellator.Mesh[]{new GlTessellator.Mesh(), new GlTessellator.Mesh()}; }
            synchronized (lock) {
                pending.remove(group);
                cpu.put(group, built);
                while (cpu.size() > maxMeshes + 60) { Iterator<Object> it = cpu.keySet().iterator(); it.next(); it.remove(); }
                ready.put(group, built);
            }
            requestRender();
        });
    }

    // ---------------------------------------------------------------- renderer

    private static final class Gpu {
        final GlTessellator.Mesh mesh;
        int areaBuf, lineBuf, indexBuf, jointBuf, buildingBuf;
        long uploadedAt;
        Gpu(GlTessellator.Mesh mesh) { this.mesh = mesh; }
    }

    /** The route as float vertices (long routes need more precision than a tile's shorts). */
    private static final class RouteMesh {
        double originX, originY, scale;
        float[] lines = new float[0]; int[] index = new int[0]; float[] joints = new float[0];
        int lineBuf, indexBuf, jointBuf;
    }

    private final class Renderer implements GLSurfaceView.Renderer {
        private final LinkedHashMap<Object, Gpu[]> meshes = new LinkedHashMap<>(64, .75f, true);
        private int areaProgram, lineProgram, jointProgram, buildingProgram, labelProgram;
        private final HashMap<String, Integer> uniforms = new HashMap<>();
        private int width, height;
        private final float[] matrix = new float[16];
        private float nightAmount = -1;
        private long lastDrawAt, fpsWindowStart; private int fpsFrames;
        private List<GeoMath.Point> routeFor; private double routeProgress = -1; private Object trafficFor;
        private RouteMesh fullRoute, aheadRoute;
        private final LinkedHashMap<String, int[]> textures = new LinkedHashMap<>(128, .75f, true);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            try { create(); } catch (Throwable e) { fail(e); }
        }

        private void create() {
            Log.i(TAG, "GPU surface created: " + GLES30.glGetString(GLES30.GL_RENDERER) + " · " + GLES30.glGetString(GLES30.GL_VERSION));
            // A new context has none of the old buffers or textures: rebuild them from the CPU copies.
            meshes.clear(); textures.clear(); uniforms.clear(); fullRoute = aheadRoute = null; routeFor = null;
            synchronized (lock) { ready.putAll(cpu); }
            areaProgram = program(AREA_VS, FILL_FS);
            lineProgram = program(LINE_VS, LINE_FS);
            jointProgram = program(JOINT_VS, JOINT_FS);
            buildingProgram = program(BUILDING_VS, FILL_FS);
            labelProgram = program(LABEL_VS, LABEL_FS);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            halo.setTypeface(text.getTypeface()); halo.setStyle(Paint.Style.STROKE); halo.setStrokeJoin(Paint.Join.ROUND);
        }

        private int u(int program, String name) {
            String key = program + ":" + name;
            Integer v = uniforms.get(key);
            if (v == null) { v = GLES30.glGetUniformLocation(program, name); uniforms.put(key, v); }
            return v;
        }

        @Override public void onSurfaceChanged(GL10 unused, int w, int h) { width = w; height = h; GLES30.glViewport(0, 0, w, h); }

        @Override public void onDrawFrame(GL10 unused) {
            if (failed) return;
            try { draw(); framesDrawn++; } catch (Throwable e) { fail(e); }
        }

        private void draw() {
            long start = System.nanoTime(), now = SystemClock.uptimeMillis();
            NativeMapView.GpuFrame f = next;
            // Day/night cross-fade instead of a sudden switch.
            float wantNight = f != null && f.night ? 1 : 0;
            if (nightAmount < 0) nightAmount = wantNight;
            boolean animating = false;
            if (nightAmount != wantNight) {
                float step = lastDrawAt == 0 ? 1 : (now - lastDrawAt) / (float) NIGHT_FADE_MS;
                nightAmount = wantNight > nightAmount ? Math.min(wantNight, nightAmount + step) : Math.max(wantNight, nightAmount - step);
                animating = true;
            }
            lastDrawAt = now;
            float[] bg = mix(0xffeef1e9, 0xff132634);
            GLES30.glClearColor(bg[0], bg[1], bg[2], 1);
            GLES30.glClearDepthf(0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT | GLES30.GL_STENCIL_BUFFER_BIT);
            if (f == null || f.camera == null || width == 0) return;
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            uploadFinished(now);
            List<List<NativeMapScene.Shape>> groups = groups(f.scenes);
            List<Gpu[]> visible = new ArrayList<>();
            int waiting = 0;
            for (List<NativeMapScene.Shape> g : groups) {
                Gpu[] m = meshes.get(g);
                if (m == null) { request(g); waiting++; continue; }
                if (onScreen(f.camera, m[0].mesh)) visible.add(m);
            }
            meshesPending = waiting;
            boolean fadingTiles = false;
            for (Gpu[] m : visible) if (now - m[0].uploadedAt < TILE_FADE_MS) fadingTiles = true;
            // 1 areas
            GLES30.glUseProgram(areaProgram);
            float[] land = mix(0xffdce9ce, 0xff243c37), water = mix(0xffa6d5e9, 0xff254d6b), building = mix(0xffd7cbb9, 0xff334654);
            GLES30.glUniform4fv(u(areaProgram, "u_area"), 3, new float[]{land[0], land[1], land[2], 1, water[0], water[1], water[2], 1,
                    building[0], building[1], building[2], 1}, 0);
            for (Gpu[] m : visible) drawAreas(f, m[0], now);
            // 2 roads: all casings, then all fills (lines, then round joints); then bridges the same way
            int segments = 0;
            for (int layer = 0; layer < 2; layer++)
                for (int pass = 0; pass < 2; pass++) {
                    boolean casing = pass == 0;
                    GLES30.glUseProgram(lineProgram); lineStyle(f, lineProgram, casing);
                    for (Gpu[] m : visible) segments += drawLines(f, m[layer], now);
                    GLES30.glUseProgram(jointProgram); lineStyle(f, jointProgram, casing);
                    for (Gpu[] m : visible) drawJoints(f, m[layer], now);
                }
            segmentsDrawn = segments / 2;
            // 3 raised buildings when tilted and close
            if (f.extrude && f.tilt > 10 && f.zoom >= 16.5) drawBuildings(f, visible);
            // 4 route, 5 names
            drawRoute(f);
            animating |= drawLabels(f);
            meshesReady = meshes.size();
            evict(groups);
            long took = System.nanoTime() - start;
            gpuFrameNanos = (gpuFrameNanos * 7 + took) / 8;
            if (now - fpsWindowStart >= 1000) {
                fps = fpsFrames * 1000f / Math.max(1, now - fpsWindowStart); fpsFrames = 0; fpsWindowStart = now; worstFrameNanos = took;
            } else worstFrameNanos = Math.max(worstFrameNanos, took);
            fpsFrames++;
            // Steady frame clock while the map moves, tiles fade in or colours change.
            if (animating || fadingTiles || now - lastFrameAt < KEEP_DRAWING_MS) requestRender();
        }

        private List<List<NativeMapScene.Shape>> groups(List<NativeMapScene> scenes) {
            List<List<NativeMapScene.Shape>> out = new ArrayList<>();
            Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            if (scenes != null) for (NativeMapScene s : scenes) {
                if (s == null) continue;
                List<List<NativeMapScene.Shape>> g = s.groups;
                if (g == null) { if (seen.add(s.shapes)) out.add(s.shapes); }
                else for (List<NativeMapScene.Shape> one : g) if (seen.add(one)) out.add(one);
            }
            return out;
        }

        private float[] mix(int day, int night) {
            float t = Math.max(0, Math.min(1, nightAmount));
            float[] a = rgba(day), b = rgba(night);
            return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t};
        }

        // ----- mesh lifecycle

        private void uploadFinished(long now) {
            List<Map.Entry<Object, GlTessellator.Mesh[]>> done;
            synchronized (lock) { done = new ArrayList<>(ready.entrySet()); ready.clear(); }
            for (Map.Entry<Object, GlTessellator.Mesh[]> e : done) {
                if (meshes.containsKey(e.getKey())) continue;
                Gpu[] g = {upload(e.getValue()[0]), upload(e.getValue()[1])};
                g[0].uploadedAt = g[1].uploadedAt = now;
                meshes.put(e.getKey(), g);
            }
        }

        private void evict(List<List<NativeMapScene.Shape>> inUseList) {
            if (meshes.size() <= maxMeshes) return;
            Set<Object> inUse = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            inUse.addAll(inUseList);
            Iterator<Map.Entry<Object, Gpu[]>> it = meshes.entrySet().iterator();
            while (meshes.size() > maxMeshes && it.hasNext()) {
                Map.Entry<Object, Gpu[]> e = it.next();
                if (inUse.contains(e.getKey())) continue;
                for (Gpu g : e.getValue()) release(g);
                it.remove();
            }
        }

        private Gpu upload(GlTessellator.Mesh m) {
            Gpu g = new Gpu(m);
            int[] ids = new int[5];
            GLES30.glGenBuffers(5, ids, 0);
            g.areaBuf = ids[0]; g.lineBuf = ids[1]; g.indexBuf = ids[2]; g.jointBuf = ids[3]; g.buildingBuf = ids[4];
            shorts(g.areaBuf, m.areas); shorts(g.lineBuf, m.lines); shorts(g.jointBuf, m.joints); shorts(g.buildingBuf, m.buildings3d);
            ints(g.indexBuf, m.lineIndex);
            return g;
        }

        private void shorts(int id, short[] data) {
            ShortBuffer sb = ByteBuffer.allocateDirect(Math.max(2, data.length * 2)).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(data).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 2, sb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        private void floats(int id, float[] data) {
            FloatBuffer fb = ByteBuffer.allocateDirect(Math.max(4, data.length * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(data).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, fb, GLES30.GL_DYNAMIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        private void ints(int id, int[] data) {
            IntBuffer ib = ByteBuffer.allocateDirect(Math.max(4, data.length * 4)).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(data).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.length * 4, ib, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        private void release(Gpu g) { GLES30.glDeleteBuffers(5, new int[]{g.areaBuf, g.lineBuf, g.indexBuf, g.jointBuf, g.buildingBuf}, 0); }

        // ----- projection: identical to NativeMapProjection.Camera.screen, as a matrix per mesh

        private boolean onScreen(NativeMapProjection.Camera c, GlTessellator.Mesh m) {
            if (!(m.w <= m.e)) return false;
            float[] p = new float[2];
            float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
            for (int i = 0; i < 4; i++) {
                c.screen((i & 1) == 0 ? m.w : m.e, (i & 2) == 0 ? m.s : m.n, p);
                if (!Float.isFinite(p[0]) || !Float.isFinite(p[1])) return true;
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            return maxX >= -80 && minX <= width + 80 && maxY >= -80 && minY <= height + 80;
        }

        private void matrix(NativeMapProjection.Camera c, double originX, double originY, double meshScale) {
            double a = c.scale / meshScale, bx = (originX - c.cx) * c.scale, by = (originY - c.cy) * c.scale;
            double Lu = a * c.cos, Lv = a * c.sin, L0 = c.cos * bx + c.sin * by;
            double Fu = -a * c.sin, Fv = a * c.cos, F0 = -c.sin * bx + c.cos * by;
            double t = c.tiltSin / c.focal;
            double Wu = -t * Fu, Wv = -t * Fv, W0 = 1 - t * F0;
            double Xu = c.anchorX * Wu + Lu, Xv = c.anchorX * Wv + Lv, X0 = c.anchorX * W0 + L0;
            double Yu = c.anchorY * Wu + c.pitch * Fu, Yv = c.anchorY * Wv + c.pitch * Fv, Y0 = c.anchorY * W0 + c.pitch * F0;
            double kx = 2.0 / width, ky = 2.0 / height;
            matrix[0] = (float) (kx * Xu - Wu); matrix[1] = (float) (Wu - ky * Yu); matrix[2] = (float) Wu; matrix[3] = (float) Wu;
            matrix[4] = (float) (kx * Xv - Wv); matrix[5] = (float) (Wv - ky * Yv); matrix[6] = (float) Wv; matrix[7] = (float) Wv;
            matrix[8] = 0; matrix[9] = 0; matrix[10] = 0; matrix[11] = 0;
            matrix[12] = (float) (kx * X0 - W0); matrix[13] = (float) (W0 - ky * Y0); matrix[14] = (float) (W0 - 0.1); matrix[15] = (float) W0;
        }

        private float fade(Gpu g, long now) { return Math.min(1f, (now - g.uploadedAt) / (float) TILE_FADE_MS); }

        // ----- drawing

        private void drawAreas(NativeMapView.GpuFrame f, Gpu g, long now) {
            if (g.mesh.areas.length == 0) return;
            matrix(f.camera, g.mesh.originX, g.mesh.originY, g.mesh.scale);
            GLES30.glUniformMatrix4fv(u(areaProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glUniform1f(u(areaProgram, "u_alpha"), fade(g, now));
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.areaBuf);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_SHORT, false, 6, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, g.mesh.areas.length / 3);
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        /** Width (px) and colour of every line slot, set once per pass (not per tile). UK-style colours:
         *  blue motorways, amber main roads, white streets with grey edges, grey tunnels. */
        private void lineStyle(NativeMapView.GpuFrame f, int program, boolean casing) {
            float[] widths = new float[16], colours = new float[64];
            float px = f.density;
            for (int slot = 0; slot < 16; slot++) {
                int kind = slot & 7; boolean tunnel = slot >= GlTessellator.LINE_TUNNEL;
                float w; float[] col;
                if (slot == 1) { w = casing ? 0 : 2 * px; col = mix(0xff8bc6e2, 0xff376989); }
                else if (slot == 2) { w = casing ? 0 : 1.3f * px; col = mix(0xff969c9e, 0xff687782); }
                else if (kind >= NativeMapScene.PATH && kind <= NativeMapScene.MOTORWAY) {
                    float base = MapLayers.roadWidth(kind, f.zoom) * px;
                    boolean cased = MapLayers.casing(kind, f.zoom);
                    if (casing) {
                        w = cased ? base + 2f * px : 0;
                        col = kind == NativeMapScene.MOTORWAY ? mix(0xff2f5e9e, 0xff0d1b28) : kind == NativeMapScene.MAIN ? mix(0xffb48b2f, 0xff0d1b28) : mix(0xffa9b1b3, 0xff0d1b28);
                    } else {
                        w = base;
                        col = tunnel ? mix(0xffd6d8d6, 0xff465461)
                                : kind == NativeMapScene.MOTORWAY ? mix(0xff7fa7e0, 0xff4f78b0)
                                : kind == NativeMapScene.MAIN ? mix(0xfff6cf6c, 0xff8c7a4e)
                                : !cased ? mix(0xffc4cacd, 0xff50616d) : mix(0xffffffff, 0xff586a77);
                    }
                } else { w = 0; col = new float[4]; }
                widths[slot] = w;
                System.arraycopy(col, 0, colours, slot * 4, 4);
            }
            GLES30.glUniform1fv(u(program, "u_w"), 16, widths, 0);
            GLES30.glUniform4fv(u(program, "u_col"), 16, colours, 0);
            GLES30.glUniform2f(u(program, "u_view"), width, height);
        }

        private int drawLines(NativeMapView.GpuFrame f, Gpu g, long now) {
            GlTessellator.Mesh m = g.mesh;
            if (m.lineIndex.length == 0) return 0;
            matrix(f.camera, m.originX, m.originY, m.scale);
            GLES30.glUniformMatrix4fv(u(lineProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glUniform1f(u(lineProgram, "u_alpha"), fade(g, now));
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.lineBuf);
            GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1); GLES30.glEnableVertexAttribArray(2);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_SHORT, false, 12, 0);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_SHORT, false, 12, 4);
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_SHORT, false, 12, 8);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.indexBuf);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.lineIndex.length, GLES30.GL_UNSIGNED_INT, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLES30.glDisableVertexAttribArray(0); GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            return m.segmentCount();
        }

        private void drawJoints(NativeMapView.GpuFrame f, Gpu g, long now) {
            GlTessellator.Mesh m = g.mesh;
            if (m.joints.length == 0) return;
            matrix(f.camera, m.originX, m.originY, m.scale);
            GLES30.glUniformMatrix4fv(u(jointProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glUniform1f(u(jointProgram, "u_alpha"), fade(g, now));
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.jointBuf);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_SHORT, false, 6, 0);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, m.joints.length / 3);
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        private void drawBuildings(NativeMapView.GpuFrame f, List<Gpu[]> visible) {
            NativeMapProjection.Camera c = f.camera;
            double metresPerUnit = 40075016.7 * Math.max(.15, Math.cos(Math.toRadians(NativeMapProjection.lat(c.cy))));
            float pxPerMetre = (float) (c.scale / metresPerUnit * c.tiltSin);
            GLES30.glUseProgram(buildingProgram);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_GREATER);   // nearer = larger w = larger depth here
            GLES30.glUniform1f(u(buildingProgram, "u_lift"), 2f * pxPerMetre / height * 0.1f);   // decimetres → clip units
            float[] wall = mix(0xffcbbca7, 0xff3a4f60);
            GLES30.glUniform4f(u(buildingProgram, "u_col"), wall[0], wall[1], wall[2], 1);
            for (Gpu[] gm : visible) {
                Gpu g = gm[0];
                if (g.mesh.buildings3d.length == 0) continue;
                matrix(c, g.mesh.originX, g.mesh.originY, g.mesh.scale);
                GLES30.glUniformMatrix4fv(u(buildingProgram, "u_m"), 1, false, matrix, 0);
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.buildingBuf);
                GLES30.glEnableVertexAttribArray(0);
                GLES30.glVertexAttribPointer(0, 4, GLES30.GL_SHORT, false, 8, 0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, g.mesh.buildings3d.length / 4);
                GLES30.glDisableVertexAttribArray(0);
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        }

        // ----- route: grey behind the car, blue ahead, amber/red where traffic is slow or stopped

        private void drawRoute(NativeMapView.GpuFrame f) {
            if (f.route == null || f.route.size() < 2) { routeFor = null; return; }
            if (f.route != routeFor) {
                releaseRoute(fullRoute); releaseRoute(aheadRoute);
                fullRoute = routeMesh(f.route, 0, null, true);
                routeFor = f.route; routeProgress = -1; aheadRoute = null;
            }
            if (aheadRoute == null || Math.abs(f.progress - routeProgress) >= 8 || f.traffic != trafficFor) {
                releaseRoute(aheadRoute);
                aheadRoute = routeMesh(f.route, f.progress, f.traffic, false);
                routeProgress = f.progress; trafficFor = f.traffic;
            }
            float px = f.density;
            float zoomK = (float) Math.max(.55, Math.min(1.35, 1 + (f.zoom - 16) * .12));
            float[] w = new float[16], col = new float[64];
            float[] grey = mix(0xffaeb9bb, 0xff516270), blue = rgba(0xff16a6f4), amber = rgba(0xfff29d1d), red = rgba(0xffd7352b), edge = rgba(0xff143d64);
            for (int pass = 0; pass < 2; pass++) {
                boolean casing = pass == 0;
                for (int s = 0; s < 4; s++) {
                    w[s] = (casing ? 9 : 6) * px * zoomK;
                    float[] c = casing ? (s == 0 ? grey : edge) : s == 0 ? grey : s == 1 ? blue : s == 2 ? amber : red;
                    System.arraycopy(c, 0, col, s * 4, 4);
                }
                drawRouteMesh(fullRoute, w, col, f);
                drawRouteMesh(aheadRoute, w, col, f);
            }
        }

        private void drawRouteMesh(RouteMesh r, float[] w, float[] col, NativeMapView.GpuFrame f) {
            if (r == null || r.index.length == 0) return;
            if (r.lineBuf == 0) {
                int[] ids = new int[3]; GLES30.glGenBuffers(3, ids, 0);
                r.lineBuf = ids[0]; r.indexBuf = ids[1]; r.jointBuf = ids[2];
                floats(r.lineBuf, r.lines); ints(r.indexBuf, r.index); floats(r.jointBuf, r.joints);
            }
            matrix(f.camera, r.originX, r.originY, r.scale);
            GLES30.glUseProgram(lineProgram);
            GLES30.glUniform1fv(u(lineProgram, "u_w"), 16, w, 0);
            GLES30.glUniform4fv(u(lineProgram, "u_col"), 16, col, 0);
            GLES30.glUniform2f(u(lineProgram, "u_view"), width, height);
            GLES30.glUniformMatrix4fv(u(lineProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glUniform1f(u(lineProgram, "u_alpha"), 1);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, r.lineBuf);
            for (int i = 0; i < 3; i++) GLES30.glEnableVertexAttribArray(i);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 24, 0);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 24, 8);
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 24, 16);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, r.indexBuf);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, r.index.length, GLES30.GL_UNSIGNED_INT, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
            for (int i = 0; i < 3; i++) GLES30.glDisableVertexAttribArray(i);
            GLES30.glUseProgram(jointProgram);
            GLES30.glUniform1fv(u(jointProgram, "u_w"), 16, w, 0);
            GLES30.glUniform4fv(u(jointProgram, "u_col"), 16, col, 0);
            GLES30.glUniform2f(u(jointProgram, "u_view"), width, height);
            GLES30.glUniformMatrix4fv(u(jointProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glUniform1f(u(jointProgram, "u_alpha"), 1);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, r.jointBuf);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, r.joints.length / 3);
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        private void releaseRoute(RouteMesh r) {
            if (r != null && r.lineBuf != 0) GLES30.glDeleteBuffers(3, new int[]{r.lineBuf, r.indexBuf, r.jointBuf}, 0);
        }

        /** The route from fromAlong metres to the end as float quads: slot 0 for the whole (grey) route, else
         *  1 clear, 2 slow, 3 stopped by traffic section. */
        private RouteMesh routeMesh(List<GeoMath.Point> pts, double fromAlong, List<RouteIntelligence.TrafficSection> traffic, boolean whole) {
            RouteMesh r = new RouteMesh();
            r.scale = 1 << 24;
            GeoMath.Point o = pts.get(0);
            r.originX = NativeMapProjection.x(o.lon); r.originY = NativeMapProjection.y(o.lat);
            FloatList lines = new FloatList(), joints = new FloatList();
            IntListG index = new IntListG();
            double run = 0; float px = 0, py = 0; boolean have = false;
            for (int i = 0; i < pts.size(); i++) {
                GeoMath.Point p = pts.get(i);
                double seg = i == 0 ? 0 : GeoMath.distance(pts.get(i - 1), p);
                double segStart = run; run += seg;
                if (!whole && run < fromAlong) continue;
                if (!whole && !have && i > 0) {
                    // start part-way along this segment, at the car
                    GeoMath.Point a = pts.get(i - 1); double t = seg <= 0 ? 0 : Math.max(0, (fromAlong - segStart) / seg);
                    double lat = a.lat + (p.lat - a.lat) * t, lon = a.lon + (p.lon - a.lon) * t;
                    px = (float) ((NativeMapProjection.x(lon) - r.originX) * r.scale); py = (float) ((NativeMapProjection.y(lat) - r.originY) * r.scale);
                    have = true;
                }
                float x = (float) ((NativeMapProjection.x(p.lon) - r.originX) * r.scale), y = (float) ((NativeMapProjection.y(p.lat) - r.originY) * r.scale);
                int slot = whole ? 0 : trafficSlot(traffic, (segStart + run) / 2);
                if (have && (x != px || y != py)) {
                    int base = lines.n / 6;
                    lines.add(px, py, x, y, 1, slot); lines.add(px, py, x, y, -1, slot);
                    lines.add(x, y, px, py, 1, slot); lines.add(x, y, px, py, -1, slot);
                    index.add(base); index.add(base + 1); index.add(base + 2); index.add(base); index.add(base + 2); index.add(base + 3);
                    joints.add(x, y, slot);
                }
                px = x; py = y; have = true;
            }
            r.lines = lines.done(); r.joints = joints.done(); r.index = index.done();
            return r;
        }

        private int trafficSlot(List<RouteIntelligence.TrafficSection> traffic, double along) {
            if (traffic != null) for (RouteIntelligence.TrafficSection t : traffic)
                if (along >= t.start && along <= t.end) {
                    String c = t.category == null ? "" : t.category.toUpperCase(java.util.Locale.ROOT);
                    return c.contains("JAM") || c.contains("STATIONARY") || c.contains("CLOSED") || t.delaySeconds >= 180 ? 3 : 2;
                }
            return 1;
        }

        // ----- names: a texture per name (drawn once), a quad per name per frame

        private boolean drawLabels(NativeMapView.GpuFrame f) {
            List<NativeBaseRenderer.Placed> labels = f.labels;
            labelsDrawn = labels == null ? 0 : labels.size();
            if (labels == null || labels.isEmpty()) return false;
            boolean fading = false;
            GLES30.glUseProgram(labelProgram);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);   // bitmaps are premultiplied
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glUniform1i(u(labelProgram, "u_tex"), 0);
            boolean night = nightAmount >= .5f;
            for (NativeBaseRenderer.Placed p : labels) {
                int[] tex = texture(p, night, f.density);
                if (tex == null) continue;
                if (p.alpha < 1) fading = true;
                float hw = tex[1] / 2f, hh = tex[2] / 2f, cx = p.x, cy = p.y - p.size / 2f + f.density;
                double r = Math.toRadians(p.angle); float cs = (float) Math.cos(r), sn = (float) Math.sin(r);
                float[][] corners = {{-hw, -hh, 0, 0}, {hw, -hh, 1, 0}, {hw, hh, 1, 1}, {-hw, hh, 0, 1}};
                quad.position(0);
                for (float[] k : corners) {
                    float x = cx + k[0] * cs - k[1] * sn, y = cy + k[0] * sn + k[1] * cs;
                    quad.put(2f * x / width - 1f).put(1f - 2f * y / height).put(k[2]).put(k[3]);
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
                GLES30.glUniform1f(u(labelProgram, "u_alpha"), p.alpha);
                GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1);
                quad.position(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad);
                quad.position(2); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, 4);
                GLES30.glDisableVertexAttribArray(0); GLES30.glDisableVertexAttribArray(1);
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            while (textures.size() > maxTextures) {
                Iterator<Map.Entry<String, int[]>> it = textures.entrySet().iterator();
                GLES30.glDeleteTextures(1, new int[]{it.next().getValue()[0]}, 0); it.remove();
            }
            texturesHeld = textures.size();
            return fading;
        }

        /** {textureId, width, height}: the name drawn once, with a halo, or as a road-number shield. */
        private int[] texture(NativeBaseRenderer.Placed p, boolean night, float density) {
            String key = p.style + "|" + Math.round(p.size) + "|" + night + "|" + p.text;
            int[] t = textures.get(key);
            if (t != null) return t;
            text.setTextSize(p.size); halo.setTextSize(p.size); halo.setStrokeWidth(3 * density);
            float w = text.measureText(p.text);
            int pad = Math.round((p.style >= 2 ? 5 : 3) * density);
            int bw = Math.max(2, Math.round(w) + 2 * pad), bh = Math.max(2, Math.round(p.size + 8 * density));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float base = bh / 2f + p.size * .36f;
            if (p.style >= 2) {
                int bg = p.style == 2 ? 0xff2a64b7 : p.style == 3 ? 0xff1f6b3a : 0xffffffff, fg = p.style == 2 ? 0xffffffff : p.style == 3 ? 0xffffd34d : 0xff1d1d1d;
                fill.setStyle(Paint.Style.FILL); fill.setColor(bg);
                RectF r = new RectF(1, 2 * density, bw - 1, bh - 2 * density);
                c.drawRoundRect(r, 3 * density, 3 * density, fill);
                if (p.style == 4) { fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(density); fill.setColor(0xff1d1d1d); c.drawRoundRect(r, 3 * density, 3 * density, fill); }
                text.setColor(fg); c.drawText(p.text, pad, base, text);
            } else {
                halo.setColor(night ? 0xf2142737 : 0xf2f8faf7); c.drawText(p.text, pad, base, halo);
                text.setColor(p.style == 0 ? (night ? 0xffeef4f8 : 0xff1f2d38) : (night ? 0xffc9d6de : 0xff4a5862));
                c.drawText(p.text, pad, base, text);
            }
            int[] id = new int[1];
            GLES30.glGenTextures(1, id, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
            b.recycle();
            t = new int[]{id[0], bw, bh};
            textures.put(key, t);
            return t;
        }
    }

    private static final class FloatList {
        float[] a = new float[1024]; int n;
        void add(float... v) { if (n + v.length > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + v.length)); for (float x : v) a[n++] = x; }
        float[] done() { return java.util.Arrays.copyOf(a, n); }
    }

    private static final class IntListG {
        int[] a = new int[1024]; int n;
        void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] done() { return java.util.Arrays.copyOf(a, n); }
    }

    private static float[] rgba(int c) {
        return new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, ((c >>> 24) & 255) / 255f};
    }

    // ---------------------------------------------------------------- shaders

    private static final String AREA_VS = "#version 300 es\n"
            + "layout(location=0) in vec3 a;\n"
            + "uniform mat4 u_m; uniform vec4 u_area[3]; uniform float u_alpha;\n"
            + "out vec4 v_c;\n"
            + "void main(){ gl_Position=u_m*vec4(a.xy,0.0,1.0); vec4 c=u_area[int(a.z)]; v_c=vec4(c.rgb,c.a*u_alpha); }\n";

    /** Quads pushed out by half the width plus one pixel; the fragment shader fades that pixel (anti-aliasing). */
    private static final String LINE_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 p; layout(location=1) in vec2 o; layout(location=2) in vec2 sk;\n"
            + "uniform mat4 u_m; uniform vec2 u_view; uniform float u_w[16]; uniform vec4 u_col[16]; uniform float u_alpha;\n"
            + "out vec4 v_c; out float v_edge; out float v_hw;\n"
            + "void main(){\n"
            + "  int slot=int(sk.y); float hw=u_w[slot]*0.5;\n"
            + "  vec4 a=u_m*vec4(p,0.0,1.0); vec4 b=u_m*vec4(o,0.0,1.0);\n"
            + "  v_c=u_col[slot]; v_c.a*=u_alpha; v_edge=sk.x; v_hw=hw;\n"
            + "  if(hw<=0.0||a.w<0.05||b.w<0.05){gl_Position=vec4(2.0,2.0,2.0,1.0);return;}\n"
            + "  vec2 half_=u_view*0.5; vec2 sa=a.xy/a.w*half_; vec2 sb=b.xy/b.w*half_;\n"
            + "  vec2 d=sa-sb; float len=length(d); d=len<1e-4?vec2(1.0,0.0):d/len;\n"
            + "  vec2 n=vec2(-d.y,d.x);\n"
            + "  sa+=(n*sk.x+d)*(hw+1.0);\n"
            + "  gl_Position=vec4(sa/half_*a.w,a.z,a.w);\n"
            + "}\n";

    private static final String LINE_FS = "#version 300 es\nprecision mediump float;\n"
            + "in vec4 v_c; in float v_edge; in float v_hw; out vec4 colour;\n"
            + "void main(){ float dist=abs(v_edge)*(v_hw+1.0); float k=clamp(v_hw+0.5-dist,0.0,1.0); colour=vec4(v_c.rgb,v_c.a*k); }\n";

    private static final String JOINT_VS = "#version 300 es\n"
            + "layout(location=0) in vec3 a;\n"
            + "uniform mat4 u_m; uniform vec2 u_view; uniform float u_w[16]; uniform vec4 u_col[16]; uniform float u_alpha;\n"
            + "out vec4 v_c; out float v_size;\n"
            + "void main(){ int slot=int(a.z); vec4 q=u_m*vec4(a.xy,0.0,1.0); v_c=u_col[slot]; v_c.a*=u_alpha; v_size=u_w[slot]+2.0;\n"
            + "  if(u_w[slot]<=0.0||q.w<0.05){gl_Position=vec4(2.0,2.0,2.0,1.0);gl_PointSize=1.0;return;}\n"
            + "  gl_Position=q; gl_PointSize=v_size; }\n";

    private static final String JOINT_FS = "#version 300 es\nprecision mediump float;\n"
            + "in vec4 v_c; in float v_size; out vec4 colour;\n"
            + "void main(){ float r=length(gl_PointCoord-vec2(0.5))*v_size; float k=clamp(v_size*0.5-0.5-r+0.5,0.0,1.0); if(k<=0.0) discard; colour=vec4(v_c.rgb,v_c.a*k); }\n";

    /** Raised buildings: height lifts the vertex up the screen, scaled by distance like the classic renderer. */
    private static final String BUILDING_VS = "#version 300 es\n"
            + "layout(location=0) in vec4 a;\n"
            + "uniform mat4 u_m; uniform float u_lift; uniform vec4 u_col;\n"
            + "out vec4 v_c;\n"
            + "void main(){ vec4 q=u_m*vec4(a.xy,0.0,1.0); q.y+=a.z*u_lift; gl_Position=q; float s=a.w/100.0; v_c=vec4(u_col.rgb*s,1.0); }\n";

    private static final String FILL_FS = "#version 300 es\nprecision mediump float;\n"
            + "in vec4 v_c; out vec4 colour; void main(){ colour=v_c; }\n";

    private static final String LABEL_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 p; layout(location=1) in vec2 uv;\n"
            + "out vec2 v_uv; void main(){ gl_Position=vec4(p,0.0,1.0); v_uv=uv; }\n";

    private static final String LABEL_FS = "#version 300 es\nprecision mediump float;\n"
            + "uniform sampler2D u_tex; uniform float u_alpha; in vec2 v_uv; out vec4 colour;\n"
            + "void main(){ colour=texture(u_tex,v_uv)*u_alpha; }\n";

    private static int shader(int type, String source) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, source);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("GPU map shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private static int program(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs));
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs));
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("GPU map program: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    /** RGBA8 with a depth buffer (raised buildings); 4x multisampling on real phones for area edges. */
    private static final class Chooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES3_BIT = 0x40;
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            boolean msaa = !android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.HARDWARE.contains("ranchu")
                    && !android.os.Build.HARDWARE.contains("goldfish");
            int[] common = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT};
            List<int[]> attempts = new ArrayList<>();
            if (msaa) attempts.add(concat(common, new int[]{EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE}));
            attempts.add(concat(common, new int[]{EGL10.EGL_NONE}));
            for (int[] spec : attempts) {
                int[] count = new int[1];
                EGLConfig[] configs = new EGLConfig[1];
                if (egl.eglChooseConfig(display, spec, configs, 1, count) && count[0] > 0) return configs[0];
            }
            int[] count = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            egl.eglChooseConfig(display, new int[]{EGL10.EGL_NONE}, configs, 1, count);
            return configs[0];
        }
        private static int[] concat(int[] a, int[] b) { int[] c = new int[a.length + b.length]; System.arraycopy(a, 0, c, 0, a.length); System.arraycopy(b, 0, c, a.length, b.length); return c; }
    }
}
