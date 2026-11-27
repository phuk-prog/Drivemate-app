package com.drivemate.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Serves tiles from installed full-detail packs. tile() returns null when no pack covers the tile. */
public final class PackTiles implements java.io.Closeable, PackSearch.Tiles {
    private static final class Open {
        final PmTilesReader reader; final RandomAccessFile file;
        Open(PmTilesReader r, RandomAccessFile f) { reader = r; file = f; }
    }

    private final List<Open> open = new ArrayList<>();

    public synchronized void add(File pmtiles) throws IOException {
        final RandomAccessFile f = new RandomAccessFile(pmtiles, "r");
        try {
            PmTilesReader r = PmTilesReader.open(new PmTilesReader.Source() {
                public long size() throws RuntimeException { try { return f.length(); } catch (IOException e) { throw new IllegalStateException(e); } }
                public byte[] read(long offset, int length) throws IOException {
                    byte[] b = new byte[length];
                    synchronized (f) { f.seek(offset); f.readFully(b); }
                    return b;
                }
            });
            open.add(new Open(r, f));
        } catch (IOException | RuntimeException e) {
            f.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    public synchronized boolean isEmpty() { return open.isEmpty(); }

    /** Tile bytes, empty array if a covering pack has no such tile, null if no pack covers the tile. */
    public byte[] tile(int z, int x, int y) throws IOException {
        List<Open> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(open); }
        for (Open o : snapshot) {
            PmTilesReader.Header h = o.reader.header();
            if (z < h.minZoom || z > h.maxZoom || !covers(h, z, x, y)) continue;
            byte[] t = o.reader.tile(z, x, y);
            if (t != null && t.length > 0) return t;
        }
        return null;
    }

    static boolean covers(PmTilesReader.Header h, int z, int x, int y) {
        double n = Math.pow(2, z);
        double lon = (x + .5) / n * 360 - 180;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + .5) / n))));
        return lon >= h.minLon && lon <= h.maxLon && lat >= h.minLat && lat <= h.maxLat;
    }

    @Override public synchronized void close() {
        for (Open o : open) try { o.file.close(); } catch (IOException ignored) { }
        open.clear();
    }
}
