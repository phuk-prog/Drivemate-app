package com.drivemate.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App-private persistent map cache, backed by DriveMate Map PMTiles v3.
 *
 * Local HTTP is bound to 127.0.0.1 ONLY. It serves a MapLibre vector tile
 * source and fetches missing MVT bytes by HTTPS Range from a pinned public
 * DriveMate Map release. A bounded cache is reused on subsequent journeys.
 * The remote archive is NOT downloaded in full, and the routing graph is
 * completely independent of this map-display cache.
 */
public final class DriveTileServer implements AutoCloseable {
    public static final String MAP_RELEASE = "tiles-20261122T0943Z";
    public static final String SOURCE = "https://github.com/phuk-prog/Drivemate-map/releases/download/"
            + MAP_RELEASE + "/united-kingdom.pmtiles";
    public static final long SOURCE_SIZE = 1_871_405_796L;
    public static final int MAX_ZOOM = 14;
    public static final long MAX_TILE_CACHE_BYTES = 180L * 1024 * 1024;
    private static final int MAX_TILE_BYTES = 12 * 1024 * 1024;
    private final File base;
    private final File tiles;
    private final File ranges;
    private final Context context;
    private final ServerSocket socket;
    private final ExecutorService connections = Executors.newFixedThreadPool(4);
    private final ExecutorService prefetcher = Executors.newSingleThreadExecutor();
    private final RangeSource source;
    private volatile PmTilesReader archive;
    private volatile boolean closed;
    private volatile long prefetchAt;
    private volatile int lastPrefetchX = -1;
    private volatile int lastPrefetchY = -1;
    private volatile Runnable onFirstTile;
    private final AtomicInteger tileHits = new AtomicInteger();
    private final AtomicInteger cachedHits = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public DriveTileServer(Context app) throws IOException {
        context = app.getApplicationContext();
        base = new File(context.getFilesDir(), "drivemate-map/" + MAP_RELEASE);
        tiles = new File(base, "tiles");
        ranges = new File(base, "ranges");
        if ((!tiles.isDirectory() && !tiles.mkdirs())
                || (!ranges.isDirectory() && !ranges.mkdirs()))
            throw new IOException("Cannot initialise local DriveMate map cache");
        source = new RangeSource(ranges);
        socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(this::accept, "drivemate-local-map");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return socket.getLocalPort(); }
    public String tilesUrl() {
        return "http://127.0.0.1:" + port() + "/tiles/{z}/{x}/{y}.pbf";
    }
    public int downloadedTiles() { return tileHits.get(); }
    public int localHits() { return cachedHits.get(); }
    public int errors() { return failures.get(); }
    public void setOnFirstTile(Runnable callback) { onFirstTile = callback; }

    private void accept() {
        while (!closed) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(25000);
                connections.execute(() -> handle(client));
            } catch (Exception error) {
                if (!closed) failures.incrementAndGet();
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client;
             BufferedInputStream input = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(c.getOutputStream())) {
            String request = readLine(input);
            if (request == null || !request.startsWith("GET /tiles/")) {
                respond(out, 404, null);
                return;
            }
            // Limit request headers to avoid a peer exhausting the socket.
            int chars = request.length();
            for (String line; (line = readLine(input)) != null && !line.isEmpty();) {
                chars += line.length();
                if (chars > 8192) throw new IOException("Headers too large");
            }
            String path = request.split(" ", 3)[1];
            String[] part = path.split("/");
            if (part.length != 5 || !part[4].endsWith(".pbf")) {
                respond(out, 404, null);
                return;
            }
            int z = Integer.parseInt(part[2]);
            int x = Integer.parseInt(part[3]);
            int y = Integer.parseInt(part[4].substring(0, part[4].length() - 4));
            if (z < 0 || z > MAX_ZOOM || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
                respond(out, 404, null);
                return;
            }
            try {
                byte[] tile = fetchTile(z, x, y);
                respond(out, tile == null ? 404 : 200, tile);
            } catch (IOException e) {
                failures.incrementAndGet();
                respond(out, 503, null);
            }
        } catch (Exception ignored) {
            failures.incrementAndGet();
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.US_ASCII.name());
    }

    private static void respond(OutputStream out, int code, byte[] bytes) throws IOException {
        String label = code == 200 ? "OK" : code == 404 ? "Not Found" : "Service Unavailable";
        byte[] payload = bytes == null ? new byte[0] : bytes;
        String headers = "HTTP/1.1 " + code + " " + label + "\r\n"
                + "Content-Type: application/vnd.mapbox-vector-tile\r\n"
                + "Cache-Control: max-age=86400\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + payload.length + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private synchronized PmTilesReader reader() throws IOException {
        if (archive == null) {
            archive = PmTilesReader.open(source);
            PmTilesReader.Header h = archive.header();
            if (h.tileType != PmTilesReader.TILE_TYPE_MVT || h.maxZoom != MAX_ZOOM)
                throw new IOException("DriveMate Map archive has an unexpected tile format");
        }
        return archive;
    }

    private byte[] fetchTile(int z, int x, int y) throws IOException {
        File path = path(z, x, y);
        if (path.exists() && path.length() > 0 && path.length() < MAX_TILE_BYTES) {
            cachedHits.incrementAndGet();
            path.setLastModified(System.currentTimeMillis());
            Runnable callback = onFirstTile;
            if (callback != null) {
                onFirstTile = null;
                callback.run();
            }
            return readBytes(path, MAX_TILE_BYTES);
        }
        byte[] tile = reader().tile(z, x, y);
        if (tile == null) return null;
        if (tile.length > MAX_TILE_BYTES) throw new IOException("Map tile too large");
        storeAtomically(path, tile);
        tileHits.incrementAndGet();
        Runnable callback = onFirstTile;
        if (callback != null) {
            onFirstTile = null;
            callback.run();
        }
        if (tileHits.get() % 15 == 0) trim();
        return tile;
    }

    private File path(int z, int x, int y) {
        // A flat directory avoids a costly mkdir per tile request.
        return new File(tiles, z + "-" + x + "-" + y + ".mvt");
    }

    static byte[] readBytes(File file, int max) throws IOException {
        if (!file.exists() || file.length() > max || file.length() < 0)
            throw new IOException("Cache entry missing or invalid");
        byte[] content = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int at = 0;
            while (at < content.length) {
                int n = in.read(content, at, content.length - at);
                if (n < 0) throw new IOException("Cached map tile truncated");
                at += n;
            }
        }
        return content;
    }

    static void storeAtomically(File path, byte[] bytes) throws IOException {
        File tmp = new File(path.getParentFile(), path.getName() + ".tmp-"
                + Thread.currentThread().getId());
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        }
        if (!tmp.renameTo(path)) {
            tmp.delete();
            if (!path.isFile()) throw new IOException("Map cache write failed");
        }
    }

    /** Bounded local cache: remove least-recently-used tiles first. */
    public synchronized void trim() {
        File[] files = tiles.listFiles((dir, name) -> name.endsWith(".mvt"));
        if (files == null) return;
        long used = 0;
        for (File file : files) used += file.length();
        if (used <= MAX_TILE_CACHE_BYTES) return;
        java.util.Arrays.sort(files, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));
        long target = MAX_TILE_CACHE_BYTES * 8 / 10;
        for (File file : files) {
            if (used <= target) break;
            long bytes = file.length();
            if (file.delete()) used -= bytes;
        }
    }

    /**
     * Nearby map tiles are prefetched on unmetered Wi-Fi only.
     * GPS is not uploaded as a location request; tile xyz requests indirectly
     * reveal the visited area to the public DriveMate Map release host.
     */
    public void prefetchAround(double lat, double lon) {
        if (closed || !unmetered()) return;
        int x = tileX(lon, 13);
        int y = tileY(lat, 13);
        long now = SystemClock.elapsedRealtime();
        if (x == lastPrefetchX && y == lastPrefetchY && now - prefetchAt < 120000)
            return;
        prefetchAt = now;
        lastPrefetchX = x;
        lastPrefetchY = y;
        prefetcher.execute(() -> {
            // Prioritise z14 streets immediately near the phone, then fan out
            // to approximately 20–30 km across depending on latitude.
            for (int z = 14; z >= 11 && !closed; z--) {
                int radius = z == 14 ? 7 : z == 13 ? 4 : z == 12 ? 2 : 1;
                int cx = tileX(lon, z), cy = tileY(lat, z);
                for (int ring = 0; ring <= radius && !closed && unmetered(); ring++) {
                    for (int dy = -ring; dy <= ring && !closed; dy++) {
                        for (int dx = -ring; dx <= ring && !closed; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) continue;
                            int xx = cx + dx, yy = cy + dy;
                            if (xx < 0 || yy < 0 || xx >= (1 << z) || yy >= (1 << z))
                                continue;
                            try { fetchTile(z, xx, yy); }
                            catch (IOException ignored) {
                                // Retain previous tiles; stop when network disappears.
                                if (!unmetered()) return;
                            }
                        }
                    }
                }
            }
            trim();
        });
    }

    public static int tileX(double lon, int z) {
        return Math.max(0, Math.min((1 << z) - 1,
                (int) Math.floor((lon + 180) / 360 * (1 << z))));
    }

    public static int tileY(double lat, int z) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double radians = Math.toRadians(clamped);
        return Math.max(0, Math.min((1 << z) - 1, (int) Math.floor(
                (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI)
                        / 2 * (1 << z))));
    }

    private boolean unmetered() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try { return cm != null && cm.getActiveNetworkInfo() != null
                && cm.getActiveNetworkInfo().isConnected() && !cm.isActiveNetworkMetered(); }
        catch (SecurityException ignored) { return false; }
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
        connections.shutdownNow();
        prefetcher.shutdownNow();
    }

    /**
     * Read-only HTTP-range source. Index blocks and compressed chunks are
     * cached by exact offset/length, so an app restart can read prior tiles.
     * Fail closed if the server ignores Range; never download 1.9GB by mistake.
     */
    private static final class RangeSource implements PmTilesReader.Source {
        final File directory;
        private long dataStarts;
        RangeSource(File directory) { this.directory = directory; }
        @Override public long size() { return SOURCE_SIZE; }

        @Override public synchronized byte[] read(long offset, int length) throws IOException {
            if (offset < 0 || length <= 0 || length > 4 * 1024 * 1024
                    || offset > SOURCE_SIZE - length)
                throw new IOException("PMTiles range outside verified release bounds");
            File local = new File(directory, offset + "-" + length + ".part");
            if (local.isFile() && local.length() == length) {
                byte[] cached = readBytes(local, 4 * 1024 * 1024);
                if (offset == 0 && cached.length >= 127) {
                    dataStarts = java.nio.ByteBuffer.wrap(cached)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(56);
                }
                return cached;
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(SOURCE).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(18000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Range",
                    "bytes=" + offset + "-" + (offset + length - 1));
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "DriveMateMap/1.4");
            try {
                int response = connection.getResponseCode();
                if (response != 206)
                    throw new IOException("DriveMate Map host did not honor byte ranges: HTTP "
                            + response);
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange == null || !contentRange.startsWith("bytes "
                        + offset + "-" + (offset + length - 1) + "/"))
                    throw new IOException("DriveMate Map range response did not match request");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(length);
                try (InputStream in = connection.getInputStream()) {
                    byte[] bytes = new byte[8192];
                    int count;
                    while ((count = in.read(bytes)) != -1) {
                        if (buffer.size() + count > length)
                            throw new IOException("Server returned too much map data");
                        buffer.write(bytes, 0, count);
                    }
                }
                if (buffer.size() != length)
                    throw new IOException("Server returned an incomplete map range");
                byte[] bytes = buffer.toByteArray();
                if (offset == 0 && bytes.length >= 127) {
                    dataStarts = java.nio.ByteBuffer.wrap(bytes)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(56);
                }
                // Do not double-cache every road tile's compressed bytes:
                // the decompressed tile cache already has an LRU size cap.
                if (offset == 0 || (dataStarts > 0 && offset < dataStarts))
                    storeAtomically(local, bytes);
                return bytes;
            } finally { connection.disconnect(); }
        }
    }
}
