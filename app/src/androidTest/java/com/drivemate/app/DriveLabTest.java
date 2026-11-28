package com.drivemate.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Drive lab: scripted drives through the real app (real guidance UI, real map engine), one GPS fix per second,
 * with automatic driver-facing checks on every second and frames saved for a human/AI review.
 *
 * Checks (any failure fails the test, with the second and screen text it happened on):
 *  - provider wording on screen ("Try to …"), or a U-turn where the scenario has none
 *  - distance to the turn going up while the same instruction is shown
 *  - arrival time jumping more than 3 minutes between seconds
 *  - speed-limit signs left on the map behind the car
 *  - the app not answering for more than a second (freeze), including a zoom-out over the whole region
 *  - an empty instruction while navigating
 * Output: files/drive-lab/<scenario>/results.json and frame-XXXX.jpg (printed into the CI log by the script).
 */
@RunWith(AndroidJUnit4.class)
public class DriveLabTest {
    private static final String TAG = "DriveLab";

    @Test public void stockportOfflineDrive() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        TomTomApi.Route route = offlineRoute(ins.getTargetContext(), new double[][]{
                {53.3940, -2.2120, 53.4010, -2.1880}, {53.400, -2.154, 53.410, -2.171}, {53.405, -2.165, 53.410, -2.171}});
        drive(ins, "stockport-offline", route, false, false);
    }

    /** The same drive on the other engines, so DriveMate's own GPU map is checked against MapLibre and classic. */
    @Test public void stockportOfflineDriveMapLibre() throws Exception { sameDriveOn(MapEngines.MAPLIBRE, "stockport-maplibre"); }
    @Test public void stockportOfflineDriveClassic() throws Exception { sameDriveOn(MapEngines.CLASSIC, "stockport-classic"); }

    private static void sameDriveOn(String engine, String name) throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        DmUnifiedZoomTest.engine(ins.getTargetContext(), engine);
        try {
            TomTomApi.Route route = offlineRoute(ins.getTargetContext(), new double[][]{
                    {53.3940, -2.2120, 53.4010, -2.1880}, {53.400, -2.154, 53.410, -2.171}, {53.405, -2.165, 53.410, -2.171}});
            drive(ins, name, route, false, false);
        } finally { DmUnifiedZoomTest.engine(ins.getTargetContext(), MapEngines.DEFAULT); }
    }

    @Test public void missedTurnReroutes() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        TomTomApi.Route route = offlineRoute(ins.getTargetContext(), new double[][]{
                {53.400, -2.154, 53.410, -2.171}, {53.405, -2.165, 53.410, -2.171}, {53.3940, -2.2120, 53.4010, -2.1880}});
        drive(ins, "missed-turn", route, true, false);
    }

    /** Ulverston/Aldi report: the provider's fastest route turns into Daltongate and U-turns; a slightly slower
     *  route without the U-turn must be chosen, and nothing on screen may use the provider's wording. */
    @Test public void needlessUTurnIsAvoided() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        JSONObject response = new JSONObject().put("routes", new JSONArray().put(uTurnRoute()).put(directRoute()));
        TomTomApi.Route route = TomTomApi.parseRoute(response);
        for (TomTomApi.Instruction i : route.instructions) assertTrue("U-turn route was chosen", !IntelligentGuidance.uTurn(i));
        drive(ins, "uturn-avoided", route, false, false);
    }

    /** When the only route has a U-turn, it is shown in DriveMate's words. */
    @Test public void unavoidableUTurnIsWordedPlainly() throws Exception {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        TomTomApi.Route route = TomTomApi.parseRoute(new JSONObject().put("routes", new JSONArray().put(uTurnRoute())));
        drive(ins, "uturn-only", route, false, true);
    }

    // ---------------------------------------------------------------- scenarios

    private static TomTomApi.Route offlineRoute(Context app, double[][] pairs) throws Exception {
        NativeRegionalRouter router = new NativeRegionalRouter(app);
        Exception last = null;
        for (double[] p : pairs) {
            try {
                TomTomApi.Route r = router.preview(new GeoMath.Point(p[0], p[1]), new GeoMath.Point(p[2], p[3]));
                if (r != null && r.lengthMetres >= 600 && r.instructions.size() >= 3) return r;
            } catch (Exception e) { last = e; }
        }
        throw new AssertionError("No offline drive route in coverage", last);
    }

    private static JSONArray line(double[][] pts) throws Exception {
        JSONArray out = new JSONArray();
        for (double[] p : pts) out.put(new JSONObject().put("latitude", p[0]).put("longitude", p[1]));
        return out;
    }

    private static JSONObject step(double offset, String maneuver, String message, String street) throws Exception {
        return new JSONObject().put("routeOffsetInMeters", offset).put("maneuver", maneuver).put("message", message).put("street", street);
    }

    /** Ulverston Road north, left into Daltongate, U-turn, back, right into the store. */
    private static JSONObject uTurnRoute() throws Exception {
        double[][] pts = {{54.1900, -3.0950}, {54.1918, -3.0951}, {54.1936, -3.0952}, {54.1937, -3.0966}, {54.19372, -3.09665},
                {54.1937, -3.0966}, {54.1936, -3.0952}, {54.1937, -3.0940}, {54.1939, -3.0928}};
        JSONArray steps = new JSONArray()
                .put(step(0, "DEPART", "Leave from Ulverston Road", "Ulverston Road"))
                .put(step(400, "TURN_LEFT", "Turn left onto Daltongate", "Daltongate"))
                .put(step(490, "TRY_MAKE_UTURN", "Try to make a U-turn at Daltongate", "Daltongate"))
                .put(step(580, "TURN_LEFT", "Turn left onto Ulverston Road", "Ulverston Road"))
                .put(step(740, "ARRIVE", "You have arrived at your destination", ""));
        return new JSONObject().put("summary", new JSONObject().put("lengthInMeters", 740).put("travelTimeInSeconds", 110))
                .put("legs", new JSONArray().put(new JSONObject().put("points", line(pts))))
                .put("guidance", new JSONObject().put("instructions", steps))
                .put("sections", new JSONArray().put(new JSONObject().put("sectionType", "SPEED_LIMIT").put("startPointIndex", 0)
                        .put("endPointIndex", 2).put("maxSpeedLimitInKmh", 48)).put(new JSONObject().put("sectionType", "SPEED_LIMIT")
                        .put("startPointIndex", 2).put("endPointIndex", 8).put("maxSpeedLimitInKmh", 64)));
    }

    private static JSONObject directRoute() throws Exception {
        double[][] pts = {{54.1900, -3.0950}, {54.1918, -3.0951}, {54.1936, -3.0952}, {54.1937, -3.0940}, {54.1939, -3.0928}};
        JSONArray steps = new JSONArray()
                .put(step(0, "DEPART", "Leave from Ulverston Road", "Ulverston Road"))
                .put(step(400, "TURN_RIGHT", "Turn right onto the store access road", "Store access road"))
                .put(step(560, "ARRIVE", "You have arrived at your destination", ""));
        return new JSONObject().put("summary", new JSONObject().put("lengthInMeters", 560).put("travelTimeInSeconds", 130))
                .put("legs", new JSONArray().put(new JSONObject().put("points", line(pts))))
                .put("guidance", new JSONObject().put("instructions", steps));
    }

    // ---------------------------------------------------------------- the drive

    private static Field field(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }

    private static String text(MainActivity a, String name) {
        try { Object v = field(name).get(a); return v instanceof TextView ? ((TextView) v).getText().toString() : String.valueOf(v); }
        catch (Exception e) { return ""; }
    }

    private static Location fix(GeoMath.Point p, float speed, float bearing) {
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(p.lat); loc.setLongitude(p.lon); loc.setAccuracy(5); loc.setSpeed(speed); loc.setBearing(bearing);
        loc.setTime(System.currentTimeMillis()); loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return loc;
    }

    private static GeoMath.Point move(GeoMath.Point p, double bearingDeg, double metres) {
        double r = Math.toRadians(bearingDeg), dLat = metres * Math.cos(r) / 111320.0;
        double dLon = metres * Math.sin(r) / (111320.0 * Math.cos(Math.toRadians(p.lat)));
        return new GeoMath.Point(p.lat + dLat, p.lon + dLon);
    }

    private static double project(List<GeoMath.Point> path, GeoMath.Point p) {
        double best = Double.POSITIVE_INFINITY, at = 0, run = 0;
        for (int i = 1; i < path.size(); i++) {
            GeoMath.Point a = path.get(i - 1), b = path.get(i);
            double seg = GeoMath.distance(a, b);
            for (int k = 0; k <= 10; k++) {
                GeoMath.Point q = new GeoMath.Point(a.lat + (b.lat - a.lat) * k / 10.0, a.lon + (b.lon - a.lon) * k / 10.0);
                double d = GeoMath.distance(q, p);
                if (d < best) { best = d; at = run + seg * k / 10.0; }
            }
            run += seg;
        }
        return at;
    }

    /** "400 yd", "½ mi", "0.3 mi", "1.2 km", "250 m" → metres; NaN when not a distance. */
    static double metres(String s) {
        Matcher m = Pattern.compile("([0-9]*[.]?[0-9]+|½|¼|¾)\\s*(yd|mi|km|m|ft)\\b").matcher(s.replace("1½", "1.5"));
        if (!m.find()) return Double.NaN;
        String n = m.group(1);
        double v = "½".equals(n) ? .5 : "¼".equals(n) ? .25 : "¾".equals(n) ? .75 : Double.parseDouble(n);
        switch (m.group(2)) { case "yd": return v * .9144; case "mi": return v * 1609.344; case "km": return v * 1000; case "ft": return v * .3048; default: return v; }
    }

    /** "1:26 pm" / "13:26" → minutes since midnight; -1 when not a clock time. */
    static int clock(String s) {
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(am|pm)?", Pattern.CASE_INSENSITIVE).matcher(s);
        if (!m.find()) return -1;
        int h = Integer.parseInt(m.group(1)) % 12 + ("pm".equalsIgnoreCase(m.group(3)) ? 12 : 0);
        if (m.group(3) == null) h = Integer.parseInt(m.group(1));
        return h * 60 + Integer.parseInt(m.group(2));
    }

    private static long mainThreadDelayMs(Instrumentation ins) {
        long t = SystemClock.uptimeMillis();
        ins.runOnMainSync(() -> { });
        return SystemClock.uptimeMillis() - t;
    }

    private static void drive(Instrumentation ins, String name, TomTomApi.Route planned, boolean missTurn, boolean uTurnAllowed) throws Exception {
        Context app = ins.getTargetContext();
        File out = new File(app.getExternalFilesDir(null), "drive-lab/" + name);
        assertTrue(out.isDirectory() || out.mkdirs());
        MainActivity a = (MainActivity) ins.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONArray seconds = new JSONArray();
        List<String> problems = new ArrayList<>();
        try {
            SystemClock.sleep(4000);
            List<GeoMath.Point> path = planned.points;
            GeoMath.Point start = path.get(0);
            ins.runOnMainSync(() -> {
                try {
                    Location first = fix(start, 0, (float) GeoMath.bearingDegrees(path.get(0), path.get(1)));
                    field("currentLocation").set(a, first);
                    field("destination").set(a, new TomTomApi.Destination("Drive lab: " + name, "Test drive", path.get(path.size() - 1)));
                    field("lastRouteRequest").setLong(a, SystemClock.elapsedRealtime());
                    Method install = MainActivity.class.getDeclaredMethod("installCalculatedRoute", TomTomApi.Route.class, boolean.class);
                    install.setAccessible(true); install.invoke(a, planned, true);
                    Method startNav = MainActivity.class.getDeclaredMethod("startNavigation");
                    startNav.setAccessible(true); startNav.invoke(a);
                    LocationManager lm = (LocationManager) field("locationManager").get(a);
                    if (lm != null) lm.removeUpdates(a);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            List<Double> turns = new ArrayList<>();
            for (TomTomApi.Instruction i : planned.instructions) turns.add(i.routeOffsetMetres);
            double missAt = Double.NaN;
            if (missTurn) for (double t : turns) if (t > 250 && t < planned.lengthMetres - 150) { missAt = t; break; }
            double along = 0, strayed = 0;
            List<GeoMath.Point> following = path;
            Object startRoute = field("route").get(a);
            boolean rerouted = false;
            String lastTitle = "", lastInstruction = "";
            double minTurnDistance = Double.POSITIVE_INFINITY;
            int lastEta = -1, frames = 0;
            long began = SystemClock.elapsedRealtime();
            for (int s = 0; s < 420; s++) {
                long wait = began + 1000L * s - SystemClock.elapsedRealtime();
                if (wait > 0) SystemClock.sleep(wait);
                // Speed: 13 m/s (~30 mph), easing to 7 m/s within 60 m of a turn.
                double speed = 13;
                for (double t : turns) if (Math.abs(t - along) < 60) speed = 7;
                GeoMath.Point here;
                float bearing;
                if (!rerouted && Double.isFinite(missAt) && along >= missAt) {
                    // Missed the turn: carry straight on past the junction.
                    GeoMath.Point a0 = GeoMath.pointAtDistance(following, Math.max(0, missAt - 20)), a1 = GeoMath.pointAtDistance(following, missAt);
                    double b = GeoMath.bearingDegrees(a0, a1);
                    strayed += speed;
                    here = move(a1, b, strayed);
                    bearing = (float) b;
                } else {
                    along = Math.min(along + speed, GeoMath.length(following));
                    here = GeoMath.pointAtDistance(following, along);
                    bearing = (float) GeoMath.bearingDegrees(GeoMath.pointAtDistance(following, Math.max(0, along - 10)), here);
                }
                final Location loc = fix(here, (float) speed, bearing);
                long t0 = SystemClock.uptimeMillis();
                ins.runOnMainSync(() -> {
                    try { field("currentLocation").set(a, loc); } catch (Exception ignored) { }
                    a.onLocationChanged(loc);
                });
                long handled = SystemClock.uptimeMillis() - t0, idle = mainThreadDelayMs(ins);
                boolean active = field("active").getBoolean(a);
                Object currentRoute = field("route").get(a);
                if (missTurn && !rerouted && currentRoute != null && currentRoute != startRoute) {
                    rerouted = true;
                    following = ((TomTomApi.Route) currentRoute).points;
                    along = project(following, here);
                    turns.clear();
                    for (TomTomApi.Instruction i : ((TomTomApi.Route) currentRoute).instructions) turns.add(i.routeOffsetMetres);
                    Log.i(TAG, name + " rerouted after " + (int) strayed + " m");
                }
                String title = text(a, "topTurnTitle"), instruction = text(a, "topInstructionText"), distance = text(a, "topDistanceText"),
                        then = text(a, "topNextTurnText"), eta = text(a, "tripEtaValue"), road = text(a, "currentRoadPill"),
                        next = text(a, "nextInstruction");
                java.util.concurrent.atomic.AtomicReference<double[]> limits = new java.util.concurrent.atomic.AtomicReference<>(new double[0]);
                ins.runOnMainSync(() -> { try { limits.set(((DriveMap) field("vectorMap").get(a)).limitMarkerOffsets()); } catch (Exception ignored) { } });
                double progress = project(rerouted ? following : path, here);
                String screen = title + " | " + instruction + " | " + distance + " | " + then;
                // ---- checks
                if (screen.contains("Try to")) problems.add(s + "s provider wording: " + screen);
                if (!uTurnAllowed && screen.toLowerCase(Locale.UK).contains("u-turn")) problems.add(s + "s unexpected U-turn: " + screen);
                if (active && s > 5 && title.trim().isEmpty() && instruction.trim().isEmpty()) problems.add(s + "s no instruction while navigating");
                double d = metres(distance);
                if (!title.equals(lastTitle) || !instruction.equals(lastInstruction)) { minTurnDistance = Double.POSITIVE_INFINITY; lastTitle = title; lastInstruction = instruction;
                    frames += frame(ins, out, s); }
                else if (Double.isFinite(d)) {
                    if (d > minTurnDistance + 60 && !(missTurn && !rerouted && strayed > 0)) problems.add(s + "s distance went up " + (int) minTurnDistance + "→" + (int) d + " m: " + screen);
                    minTurnDistance = Math.min(minTurnDistance, d);
                }
                if (Double.isFinite(d) && minTurnDistance == Double.POSITIVE_INFINITY) minTurnDistance = d;
                int e = clock(eta);
                if (e >= 0 && lastEta >= 0 && Math.abs(e - lastEta) > 3 && !(missTurn && rerouted)) problems.add(s + "s arrival time jumped " + lastEta + "→" + e);
                if (e >= 0) lastEta = e;
                if (!(missTurn && !rerouted && strayed > 0))
                    for (double off : limits.get()) if (off < progress - 15) problems.add(s + "s speed sign behind the car at " + (int) off + " m (car at " + (int) progress + " m)");
                if (handled > 1000 || idle > 1000) problems.add(s + "s app unresponsive " + Math.max(handled, idle) + " ms");
                if (s % 15 == 0) frames += frame(ins, out, s);
                seconds.put(new JSONObject().put("s", s).put("active", active).put("title", title).put("instruction", instruction)
                        .put("distance", distance).put("then", then).put("next", next).put("eta", eta).put("road", road)
                        .put("progress", Math.round(progress)).put("handledMs", handled).put("idleMs", idle).put("rerouted", rerouted));
                if (!active && s > 10) break;
                if (!missTurn || rerouted) if (along >= GeoMath.length(following) - 1 && s > 10) {
                    SystemClock.sleep(3000); frames += frame(ins, out, s + 3); break;
                }
                if (missTurn && !rerouted && strayed > 600) { problems.add(s + "s no reroute after " + (int) strayed + " m off route"); break; }
            }
            if (missTurn && !rerouted) problems.add("missed turn never rerouted");
            // Zoom out over the whole region and back: the 2.3.0 freeze.
            long worst = 0;
            GeoMath.Point mid = GeoMath.pointAtDistance(path, planned.lengthMetres / 2);
            for (double z = 17; z >= 7; z -= .5) {
                final double zoom = z;
                long t0 = SystemClock.uptimeMillis();
                ins.runOnMainSync(() -> { try { ((DriveMap) field("vectorMap").get(a)).setBrowseCamera(mid, zoom, 0, 0, 0); } catch (Exception ignored) { } });
                SystemClock.sleep(300);
                worst = Math.max(worst, Math.max(SystemClock.uptimeMillis() - t0 - 300, mainThreadDelayMs(ins)));
            }
            frames += frame(ins, out, 999);
            if (worst > 1000) problems.add("zoom-out: app unresponsive " + worst + " ms");
            JSONObject summary = new JSONObject().put("scenario", name).put("seconds", seconds.length()).put("frames", frames)
                    .put("problems", new JSONArray(problems)).put("zoomOutWorstMs", worst);
            try (FileOutputStream f = new FileOutputStream(new File(out, "results.json"))) {
                f.write(new JSONObject().put("summary", summary).put("timeline", seconds).toString(1).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "SUMMARY " + summary);
        } finally {
            ins.runOnMainSync(a::finish);
        }
        assertTrue(name + ": " + problems.size() + " driver-facing problems: " + problems.subList(0, Math.min(8, problems.size())), problems.isEmpty());
    }

    private static int frame(Instrumentation ins, File dir, int second) {
        Bitmap shot = ins.getUiAutomation().takeScreenshot();
        if (shot == null) return 0;
        try {
            Bitmap soft = shot.copy(Bitmap.Config.ARGB_8888, false);
            int w = 420, h = Math.round(soft.getHeight() * 420f / soft.getWidth());
            Bitmap small = Bitmap.createScaledBitmap(soft, w, h, true);
            try (FileOutputStream f = new FileOutputStream(new File(dir, String.format(Locale.ROOT, "frame-%04d.jpg", second)))) {
                small.compress(Bitmap.CompressFormat.JPEG, 62, f);
            }
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "frame", e);
            return 0;
        } finally { shot.recycle(); }
    }
}
