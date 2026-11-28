package com.drivemate.app;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 * DriveMate's own GPU map engine (beta): draws the base map (areas and roads) with OpenGL ES 3.
 *
 * Each tile is tessellated once on a background thread ({@link GlTessellator}) and uploaded to GPU buffers once.
 * After that a frame is only a camera matrix per tile: panning, zooming and tilting never touch the geometry again.
 * The projection is the same pinhole model as {@link NativeMapProjection}, so the classic view drawn on top
 * (names, route, markers, car) lines up exactly. Line widths are applied on screen by the vertex shader.
 */
final class GlBaseView extends GLSurfaceView implements NativeMapView.GpuBase {
    private static final String TAG = "DriveMateGPU";
    private static final int MAX_MESHES = 220;

    /** One frame's inputs, captured on the UI thread. */
    private static final class Frame {
        NativeMapProjection.Camera camera;
        List<List<NativeMapScene.Shape>> groups;
        double zoom; boolean night; float density;
    }

    private static final class Gpu {
        final GlTessellator.Mesh mesh;
        int areaBuf, lineBuf, indexBuf, jointBuf;
        Gpu(GlTessellator.Mesh mesh) { this.mesh = mesh; }
    }

    private volatile Frame next;
    private volatile int segmentsDrawn, meshesReady, meshesPending;
    private volatile long gpuFrameNanos;
    private final ExecutorService tessellator = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "dm-gpu-tessellate"); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    /** Finished meshes waiting for upload on the GL thread. */
    private final Map<Object, GlTessellator.Mesh[]> ready = new IdentityHashMap<>();
    /** Groups being tessellated (identity of the shape list). */
    private final Set<Object> pending = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object lock = new Object();

    /** OpenGL ES 3 is required; checked before the engine is created so an old phone never reaches the GL thread. */
    static boolean supported(Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    GlBaseView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(new MultisampleChooser());
        setPreserveEGLContextOnPause(true);
        setRenderer(new Renderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    // ---------------------------------------------------------------- NativeMapView.GpuBase

    @Override public void frame(NativeMapProjection.Camera camera, List<NativeMapScene> scenes, double zoom, boolean night) {
        Frame f = new Frame();
        f.camera = camera; f.zoom = zoom; f.night = night; f.density = getResources().getDisplayMetrics().density;
        List<List<NativeMapScene.Shape>> groups = new ArrayList<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (NativeMapScene s : scenes) {
            if (s == null) continue;
            List<List<NativeMapScene.Shape>> g = s.groups;
            if (g == null) { if (seen.add(s.shapes)) groups.add(s.shapes); }
            else for (List<NativeMapScene.Shape> one : g) if (seen.add(one)) groups.add(one);
        }
        f.groups = groups;
        next = f;
        requestRender();
    }

    @Override public int roadsDrawn() { return segmentsDrawn; }

    @Override public String stats() {
        return String.format(java.util.Locale.UK, "GPU · tiles %d ready, %d preparing · %,d road segments · %.1f ms",
                meshesReady, meshesPending, segmentsDrawn, gpuFrameNanos / 1e6);
    }

    void dispose() { tessellator.shutdownNow(); }

    private volatile boolean failed;
    private Runnable onFailure;
    private volatile long framesDrawn;
    long framesDrawn() { return framesDrawn; }
    /** Called on the UI thread if the GPU cannot draw (no ES3, driver error): the owner switches back to the classic renderer. */
    void setOnFailure(Runnable r) { onFailure = r; }
    private void fail(Throwable e) {
        if (failed) return;
        failed = true;
        Log.e(TAG, "DriveMate GPU engine stopped; using the classic renderer", e);
        post(() -> { setVisibility(GONE); if (onFailure != null) onFailure.run(); });
    }

    // ---------------------------------------------------------------- renderer

    private final class Renderer implements GLSurfaceView.Renderer {
        private final LinkedHashMap<Object, Gpu[]> meshes = new LinkedHashMap<>(64, .75f, true);
        private int areaProgram, lineProgram, jointProgram;
        private int width, height;
        private final float[] matrix = new float[16];

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            try { create(); } catch (Throwable e) { fail(e); }
        }

        private void create() {
            meshes.clear();
            areaProgram = program(AREA_VS, FILL_FS);
            lineProgram = program(LINE_VS, FILL_FS);
            jointProgram = program(JOINT_VS, JOINT_FS);
        }

        @Override public void onSurfaceChanged(GL10 unused, int w, int h) { width = w; height = h; GLES30.glViewport(0, 0, w, h); }

        @Override public void onDrawFrame(GL10 unused) {
            if (failed) return;
            try { draw(); framesDrawn++; } catch (Throwable e) { fail(e); }
        }

        private void draw() {
            long start = System.nanoTime();
            Frame f = next;
            int bg = f != null && f.night ? 0xff132634 : 0xffeef1e9;
            GLES30.glClearColor(((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT | GLES30.GL_STENCIL_BUFFER_BIT);
            if (f == null || f.camera == null || width == 0) return;
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            uploadFinished();
            List<Gpu[]> visible = new ArrayList<>();
            int waiting = 0;
            for (List<NativeMapScene.Shape> g : f.groups) {
                Gpu[] m = meshes.get(g);
                if (m == null) { request(g); waiting++; continue; }
                if (onScreen(f.camera, m[0].mesh)) visible.add(m);
            }
            meshesPending = waiting;
            int segments = 0;
            // 1 areas, 2 road casings, 3 road fills (joints round off corners), 4 bridges on top.
            GLES30.glUseProgram(areaProgram);
            setAreaColours(f);
            for (Gpu[] m : visible) drawAreas(f, m[0]);
            for (int layer = 0; layer < 2; layer++) {
                for (int pass = 0; pass < 2; pass++) {
                    boolean casing = pass == 0;
                    for (Gpu[] m : visible) segments += drawLines(f, m[layer], casing);
                }
            }
            segmentsDrawn = segments / 2;
            meshesReady = meshes.size();
            evict(f);
            gpuFrameNanos = System.nanoTime() - start;
        }

        // ----- mesh lifecycle

        private void request(List<NativeMapScene.Shape> group) {
            synchronized (lock) { if (!pending.add(group)) return; }
            tessellator.execute(() -> {
                GlTessellator.Mesh[] built;
                try {
                    built = new GlTessellator.Mesh[]{GlTessellator.build(group, false), GlTessellator.build(group, true)};
                } catch (Throwable e) {
                    Log.w(TAG, "tile tessellation failed", e);
                    built = new GlTessellator.Mesh[]{new GlTessellator.Mesh(), new GlTessellator.Mesh()};
                }
                synchronized (lock) { ready.put(group, built); pending.remove(group); }
                requestRender();
            });
        }

        private void uploadFinished() {
            List<Map.Entry<Object, GlTessellator.Mesh[]>> done;
            synchronized (lock) { done = new ArrayList<>(ready.entrySet()); ready.clear(); }
            for (Map.Entry<Object, GlTessellator.Mesh[]> e : done) {
                Gpu[] g = {upload(e.getValue()[0]), upload(e.getValue()[1])};
                meshes.put(e.getKey(), g);
            }
        }

        private void evict(Frame f) {
            if (meshes.size() <= MAX_MESHES) return;
            Set<Object> inUse = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            inUse.addAll(f.groups);
            java.util.Iterator<Map.Entry<Object, Gpu[]>> it = meshes.entrySet().iterator();
            while (meshes.size() > MAX_MESHES && it.hasNext()) {
                Map.Entry<Object, Gpu[]> e = it.next();
                if (inUse.contains(e.getKey())) continue;
                for (Gpu g : e.getValue()) release(g);
                it.remove();
            }
        }

        private Gpu upload(GlTessellator.Mesh m) {
            Gpu g = new Gpu(m);
            int[] ids = new int[4];
            GLES30.glGenBuffers(4, ids, 0);
            g.areaBuf = ids[0]; g.lineBuf = ids[1]; g.indexBuf = ids[2]; g.jointBuf = ids[3];
            shorts(GLES30.GL_ARRAY_BUFFER, g.areaBuf, m.areas);
            shorts(GLES30.GL_ARRAY_BUFFER, g.lineBuf, m.lines);
            shorts(GLES30.GL_ARRAY_BUFFER, g.jointBuf, m.joints);
            IntBuffer ib = ByteBuffer.allocateDirect(Math.max(4, m.lineIndex.length * 4)).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(m.lineIndex).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.indexBuf);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.lineIndex.length * 4, ib, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
            return g;
        }

        private void shorts(int target, int id, short[] data) {
            ShortBuffer sb = ByteBuffer.allocateDirect(Math.max(2, data.length * 2)).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(data).position(0);
            GLES30.glBindBuffer(target, id);
            GLES30.glBufferData(target, data.length * 2, sb, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(target, 0);
        }

        private void release(Gpu g) {
            GLES30.glDeleteBuffers(4, new int[]{g.areaBuf, g.lineBuf, g.indexBuf, g.jointBuf}, 0);
        }

        // ----- projection: identical to NativeMapProjection.Camera.screen, as a matrix per mesh

        private boolean onScreen(NativeMapProjection.Camera c, GlTessellator.Mesh m) {
            if (!(m.w <= m.e)) return false;
            float[] p = new float[2];
            float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
            for (int i = 0; i < 4; i++) {
                c.screen((i & 1) == 0 ? m.w : m.e, (i & 2) == 0 ? m.s : m.n, p);
                if (!Float.isFinite(p[0]) || !Float.isFinite(p[1])) return true;   // crosses the horizon: draw it
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            return maxX >= -50 && minX <= width + 50 && maxY >= -50 && minY <= height + 50;
        }

        private void matrix(NativeMapProjection.Camera c, GlTessellator.Mesh m) {
            double a = c.scale / m.scale, bx = (m.originX - c.cx) * c.scale, by = (m.originY - c.cy) * c.scale;
            double Lu = a * c.cos, Lv = a * c.sin, L0 = c.cos * bx + c.sin * by;
            double Fu = -a * c.sin, Fv = a * c.cos, F0 = -c.sin * bx + c.cos * by;
            double t = c.tiltSin / c.focal;
            double Wu = -t * Fu, Wv = -t * Fv, W0 = 1 - t * F0;
            double Xu = c.anchorX * Wu + Lu, Xv = c.anchorX * Wv + Lv, X0 = c.anchorX * W0 + L0;
            double Yu = c.anchorY * Wu + c.pitch * Fu, Yv = c.anchorY * Wv + c.pitch * Fv, Y0 = c.anchorY * W0 + c.pitch * F0;
            double kx = 2.0 / width, ky = 2.0 / height;
            // rows: clip.x = kx*X - w, clip.y = w - ky*Y, clip.z = w - 0.1, clip.w = w ; input (u, v, 0, 1)
            double[] rx = {kx * Xu - Wu, kx * Xv - Wv, kx * X0 - W0};
            double[] ry = {Wu - ky * Yu, Wv - ky * Yv, W0 - ky * Y0};
            double[] rz = {Wu, Wv, W0 - 0.1};
            double[] rw = {Wu, Wv, W0};
            // column-major 4x4: column 0 = u coefficients, column 1 = v, column 2 = z (unused), column 3 = constants
            matrix[0] = (float) rx[0]; matrix[1] = (float) ry[0]; matrix[2] = (float) rz[0]; matrix[3] = (float) rw[0];
            matrix[4] = (float) rx[1]; matrix[5] = (float) ry[1]; matrix[6] = (float) rz[1]; matrix[7] = (float) rw[1];
            matrix[8] = 0; matrix[9] = 0; matrix[10] = 0; matrix[11] = 0;
            matrix[12] = (float) rx[2]; matrix[13] = (float) ry[2]; matrix[14] = (float) rz[2]; matrix[15] = (float) rw[2];
        }

        // ----- drawing

        private void setAreaColours(Frame f) {
            int loc = GLES30.glGetUniformLocation(areaProgram, "u_area");
            int[] c = f.night ? new int[]{0xff243c37, 0xff254d6b, 0xff334654} : new int[]{0xffdce9ce, 0xffa6d5e9, 0xffd7cbb9};
            float[] v = new float[12];
            for (int i = 0; i < 3; i++) rgba(c[i], v, i * 4);
            GLES30.glUniform4fv(loc, 3, v, 0);
        }

        private void drawAreas(Frame f, Gpu g) {
            if (g.mesh.areas.length == 0) return;
            matrix(f.camera, g.mesh);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(areaProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.areaBuf);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_SHORT, false, 6, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, g.mesh.areas.length / 3);
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        /** Width (px) and colour for every line slot, for the casing pass or the fill pass. */
        private void lineStyle(Frame f, int program, boolean casing) {
            float[] widths = new float[16], colours = new float[64];
            float px = f.density;
            for (int slot = 0; slot < 16; slot++) {
                int kind = slot & 7; boolean tunnel = slot >= GlTessellator.LINE_TUNNEL;
                float w; int colour;
                if (slot == 1 && !tunnel) { w = casing ? 0 : 2 * px; colour = f.night ? 0xff376989 : 0xff8bc6e2; }
                else if (slot == 2 && !tunnel) { w = casing ? 0 : 1.2f * px; colour = f.night ? 0xff687782 : 0xff969c9e; }
                else if (kind >= NativeMapScene.PATH && kind <= NativeMapScene.MOTORWAY) {
                    float base = MapLayers.roadWidth(kind, f.zoom) * px;
                    if (casing) {
                        w = MapLayers.casing(kind, f.zoom) ? base + 1.7f * px : 0;
                        colour = f.night ? 0xff10202d : 0xffb1b8b9;
                    } else {
                        w = base;
                        colour = tunnel ? (f.night ? 0xff465461 : 0xffd6d8d6)
                                : kind == NativeMapScene.MOTORWAY ? (f.night ? 0xffa18b68 : 0xfff1cd9d)
                                : kind == NativeMapScene.MAIN ? (f.night ? 0xff667178 : 0xffffe7a7)
                                : !MapLayers.casing(kind, f.zoom) ? (f.night ? 0xff50616d : 0xffc9ced0)
                                : (f.night ? 0xff50616d : 0xffffffff);
                    }
                } else { w = 0; colour = 0; }
                widths[slot] = w;
                rgba(colour, colours, slot * 4);
            }
            GLES30.glUniform1fv(GLES30.glGetUniformLocation(program, "u_w"), 16, widths, 0);
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(program, "u_col"), 16, colours, 0);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "u_view"), width, height);
        }

        private int drawLines(Frame f, Gpu g, boolean casing) {
            GlTessellator.Mesh m = g.mesh;
            if (m.lineIndex.length == 0) return 0;
            matrix(f.camera, m);
            // segments
            GLES30.glUseProgram(lineProgram);
            lineStyle(f, lineProgram, casing);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.lineBuf);
            GLES30.glEnableVertexAttribArray(0); GLES30.glEnableVertexAttribArray(1); GLES30.glEnableVertexAttribArray(2);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_SHORT, false, 12, 0);
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_SHORT, false, 12, 4);
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_SHORT, false, 12, 8);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.indexBuf);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.lineIndex.length, GLES30.GL_UNSIGNED_INT, 0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLES30.glDisableVertexAttribArray(1); GLES30.glDisableVertexAttribArray(2);
            // round joins and caps
            GLES30.glUseProgram(jointProgram);
            lineStyle(f, jointProgram, casing);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(jointProgram, "u_m"), 1, false, matrix, 0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.jointBuf);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_SHORT, false, 6, 0);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, m.joints.length / 3);
            GLES30.glDisableVertexAttribArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            GLES30.glUseProgram(areaProgram);
            return m.segmentCount();
        }
    }

    private static void rgba(int c, float[] out, int at) {
        out[at] = ((c >> 16) & 255) / 255f; out[at + 1] = ((c >> 8) & 255) / 255f; out[at + 2] = (c & 255) / 255f; out[at + 3] = ((c >>> 24) & 255) / 255f;
    }

    // ---------------------------------------------------------------- shaders

    private static final String AREA_VS = "#version 300 es\n"
            + "layout(location=0) in vec3 a;\n"
            + "uniform mat4 u_m; uniform vec4 u_area[3];\n"
            + "out vec4 v_c;\n"
            + "void main(){ gl_Position=u_m*vec4(a.xy,0.0,1.0); v_c=u_area[int(a.z)]; }\n";

    private static final String LINE_VS = "#version 300 es\n"
            + "layout(location=0) in vec2 p; layout(location=1) in vec2 o; layout(location=2) in vec2 sk;\n"
            + "uniform mat4 u_m; uniform vec2 u_view; uniform float u_w[16]; uniform vec4 u_col[16];\n"
            + "out vec4 v_c;\n"
            + "void main(){\n"
            + "  int slot=int(sk.y); float hw=u_w[slot]*0.5;\n"
            + "  vec4 a=u_m*vec4(p,0.0,1.0); vec4 b=u_m*vec4(o,0.0,1.0);\n"
            + "  if(hw<=0.0||a.w<0.05||b.w<0.05){gl_Position=vec4(2.0,2.0,2.0,1.0);v_c=vec4(0.0);return;}\n"
            + "  vec2 half_=u_view*0.5; vec2 sa=a.xy/a.w*half_; vec2 sb=b.xy/b.w*half_;\n"
            + "  vec2 d=sa-sb; float len=length(d); d=len<1e-4?vec2(1.0,0.0):d/len;\n"
            + "  vec2 n=vec2(-d.y,d.x);\n"
            + "  sa+=(n*sk.x+d)*hw;\n"
            + "  gl_Position=vec4(sa/half_*a.w,a.z,a.w); v_c=u_col[slot];\n"
            + "}\n";

    private static final String JOINT_VS = "#version 300 es\n"
            + "layout(location=0) in vec3 a;\n"
            + "uniform mat4 u_m; uniform vec2 u_view; uniform float u_w[16]; uniform vec4 u_col[16];\n"
            + "out vec4 v_c;\n"
            + "void main(){ int slot=int(a.z); vec4 q=u_m*vec4(a.xy,0.0,1.0);\n"
            + "  if(u_w[slot]<=0.0||q.w<0.05){gl_Position=vec4(2.0,2.0,2.0,1.0);gl_PointSize=1.0;v_c=vec4(0.0);return;}\n"
            + "  gl_Position=q; gl_PointSize=u_w[slot]; v_c=u_col[slot]; }\n";

    private static final String FILL_FS = "#version 300 es\nprecision mediump float;\n"
            + "in vec4 v_c; out vec4 colour; void main(){ colour=v_c; }\n";

    private static final String JOINT_FS = "#version 300 es\nprecision mediump float;\n"
            + "in vec4 v_c; out vec4 colour;\n"
            + "void main(){ vec2 c=gl_PointCoord-vec2(0.5); if(dot(c,c)>0.25) discard; colour=v_c; }\n";

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

    /** 4× multisampling for smooth road edges, falling back to a plain RGBA surface. */
    private static final class MultisampleChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES3_BIT = 0x40;
        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            // Multisampling is only used where the platform says it is safe (not emulators/software GL):
            // a config the window surface cannot use would stall the GL thread.
            boolean msaa = !android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.HARDWARE.contains("ranchu")
                    && !android.os.Build.HARDWARE.contains("goldfish");
            int[][] attempts = msaa ? new int[][]{
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                            EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE},
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT, EGL10.EGL_NONE},
            } : new int[][]{
                    {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT, EGL10.EGL_NONE},
            };
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
    }
}
