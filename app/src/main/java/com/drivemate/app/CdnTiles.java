package com.drivemate.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/**
 * Free public vector tiles (OpenFreeMap, OpenMapTiles schema) served from a CDN, with a disk cache.
 * Far faster than range reads from a release file. tile() returns tile bytes, an empty array when the CDN says the
 * tile does not exist, or null when it cannot be reached (caller falls back to the offline sources).
 */
final class CdnTiles {
    static final String TILEJSON = "https://tiles.openfreemap.org/planet";
    static final String ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap";
    static final long MAX_CACHE_BYTES = 200L * 1024 * 1024;
    private static final int MAX_TILE_BYTES = 8 * 1024 * 1024;

    private final File dir;
    private volatile String template;     // https://.../planet/<version>/{z}/{x}/{y}.pbf
    private volatile long failedAtMs;
    private volatile int consecutiveFailures;
    private long cachedBytes = -1;

    CdnTiles(Context c) { dir = new File(c.getCacheDir(), "cdn-tiles"); }

    private File templateFile() { return new File(dir, "template.txt"); }

    private static String versionOf(String tpl) {
        return tpl.replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
    }

    /** Resolves the current tile URL template; remembers the last good one for offline use. */
    private String template() {
        String t = template;
        if (t != null) return t;
        synchronized (this) {
            if (template != null) return template;
            try {
                String body = fetchString(TILEJSON, 1_000_000);
                String found = new JSONObject(body).getJSONArray("tiles").getString(0);
                if (found.startsWith("https://") && found.contains("{z}") && found.contains("{x}") && found.contains("{y}")) {
                    template = found;
                    dir.mkdirs();
                    Files.write(templateFile().toPath(), found.getBytes("UTF-8"));
                    return found;
                }
            } catch (Exception ignored) { }
            try {
                if (templateFile().isFile()) template = new String(Files.readAllBytes(templateFile().toPath()), "UTF-8").trim();
            } catch (IOException ignored) { }
            return template;
        }
    }

    /** After repeated network failures stop trying for 30 s so offline use is not slowed by timeouts. */
    private boolean coolingDown() {
        return consecutiveFailures >= 3 && System.currentTimeMillis() - failedAtMs < 30_000;
    }

    byte[] tile(int z, int x, int y) {
        String tpl = template();
        if (tpl == null) return null;
        File f = new File(new File(dir, versionOf(tpl)), z + "-" + x + "-" + y + ".mvt");
        try {
            if (f.isFile()) {
                f.setLastModified(System.currentTimeMillis());
                return Files.readAllBytes(f.toPath());
            }
        } catch (IOException ignored) { }
        if (coolingDown()) return null;
        try {
            String url = tpl.replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "DriveMate-Android/2.1");
            try {
                int code = c.getResponseCode();
                if (code == 404 || code == 204) { consecutiveFailures = 0; return new byte[0]; }
                if (code != 200) throw new IOException("HTTP " + code);
                byte[] data = readAll(c.getInputStream());
                if (data.length >= 2 && (data[0] & 255) == 0x1f && (data[1] & 255) == 0x8b)
                    data = readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(data)));
                consecutiveFailures = 0;
                store(f, data);
                return data;
            } finally {
                // Not disconnected on success: the stream was drained so the connection is reused (keep-alive).
            }
        } catch (IOException e) {
            failedAtMs = System.currentTimeMillis();
            consecutiveFailures++;
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_TILE_BYTES) throw new IOException("Tile too large");
            }
            return out.toByteArray();
        }
    }

    private static String fetchString(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "DriveMate-Android/2.1");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        byte[] b = readAll(c.getInputStream());
        if (b.length > max) throw new IOException("Too large");
        return new String(b, "UTF-8");
    }

    private synchronized void store(File f, byte[] data) {
        try {
            File parent = f.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            File tmp = new File(parent, f.getName() + ".tmp");
            Files.write(tmp.toPath(), data);
            if (!tmp.renameTo(f)) { tmp.delete(); return; }
            if (cachedBytes < 0) cachedBytes = sizeOf(dir);
            cachedBytes += data.length;
            if (cachedBytes > MAX_CACHE_BYTES) trim();
        } catch (IOException ignored) { }
    }

    private static long sizeOf(File d) {
        long t = 0;
        File[] all = d.listFiles();
        if (all != null) for (File x : all) t += x.isDirectory() ? sizeOf(x) : x.length();
        return t;
    }

    /** Deletes the least recently used quarter of the cache. */
    private void trim() {
        java.util.List<File> files = new java.util.ArrayList<>();
        collect(dir, files);
        Collections_sort(files);
        long target = MAX_CACHE_BYTES * 3 / 4, size = 0;
        for (File x : files) size += x.length();
        for (File x : files) {
            if (size <= target) break;
            size -= x.length();
            x.delete();
        }
        cachedBytes = size;
    }

    private static void collect(File d, java.util.List<File> out) {
        File[] all = d.listFiles();
        if (all == null) return;
        for (File x : all) {
            if (x.isDirectory()) collect(x, out);
            else if (x.getName().endsWith(".mvt")) out.add(x);
        }
    }

    private static void Collections_sort(java.util.List<File> files) {
        File[] a = files.toArray(new File[0]);
        Arrays.sort(a, (p, q) -> Long.compare(p.lastModified(), q.lastModified()));
        files.clear();
        files.addAll(Arrays.asList(a));
    }
}
