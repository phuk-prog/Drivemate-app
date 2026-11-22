package com.drivemate.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Read-only PMTiles v3 archive reader (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
 *
 * Own implementation with no third-party code. Only what the Drivemate map
 * packs use is supported: uncompressed or gzip directories and tiles, and a
 * clustered or unclustered archive with at most one leaf-directory level.
 * Everything else, including brotli or zstd, a bad magic, out-of-range offsets,
 * truncated directories or non-increasing tile IDs, is rejected with an
 * IOException rather than guessed. Tile bytes are returned exactly as stored
 * after decompression; the caller decides whether they are trusted.
 */
public final class PmTilesReader {
    /** Random-access bytes; the Android app backs this with a RandomAccessFile. */
    public interface Source {
        long size();
        byte[] read(long offset, int length) throws IOException;
    }

    public static final int HEADER_BYTES = 127;
    public static final int COMPRESSION_NONE = 1;
    public static final int COMPRESSION_GZIP = 2;
    public static final int TILE_TYPE_MVT = 1;

    static final int MAX_DIRECTORY_BYTES = 4 * 1024 * 1024;
    static final int MAX_DIRECTORY_ENTRIES = 1 << 20;
    static final int MAX_TILE_BYTES = 4 * 1024 * 1024;
    static final int MAX_INFLATED_BYTES = 16 * 1024 * 1024;
    static final int MAX_ZOOM = 30;

    /** Parsed header fields that the app needs; offsets are absolute file positions. */
    public static final class Header {
        public final long rootOffset, rootLength, metadataOffset, metadataLength;
        public final long leafOffset, leafLength, tileDataOffset, tileDataLength;
        public final int internalCompression, tileCompression, tileType, minZoom, maxZoom;
        public final double minLon, minLat, maxLon, maxLat;

        Header(ByteBuffer b) {
            rootOffset = b.getLong(8);
            rootLength = b.getLong(16);
            metadataOffset = b.getLong(24);
            metadataLength = b.getLong(32);
            leafOffset = b.getLong(40);
            leafLength = b.getLong(48);
            tileDataOffset = b.getLong(56);
            tileDataLength = b.getLong(64);
            internalCompression = b.get(97) & 0xFF;
            tileCompression = b.get(98) & 0xFF;
            tileType = b.get(99) & 0xFF;
            minZoom = b.get(100) & 0xFF;
            maxZoom = b.get(101) & 0xFF;
            minLon = b.getInt(102) / 1e7;
            minLat = b.getInt(106) / 1e7;
            maxLon = b.getInt(110) / 1e7;
            maxLat = b.getInt(114) / 1e7;
        }
    }

    static final class Entry {
        final long tileId, offset, length;
        final int runLength;
        Entry(long tileId, long offset, long length, int runLength) {
            this.tileId = tileId; this.offset = offset; this.length = length; this.runLength = runLength;
        }
    }

    private final Source source;
    private final Header header;
    private final Entry[] root;

    private PmTilesReader(Source source, Header header, Entry[] root) {
        this.source = source; this.header = header; this.root = root;
    }

    /** Opens and validates the header and root directory. Throws on any inconsistency. */
    public static PmTilesReader open(Source source) throws IOException {
        if (source.size() < HEADER_BYTES) throw new IOException("PMTiles header truncated");
        byte[] raw = source.read(0, HEADER_BYTES);
        String magic = new String(raw, 0, 7, StandardCharsets.US_ASCII);
        if (!"PMTiles".equals(magic) || raw[7] != 3) throw new IOException("Not a PMTiles v3 archive");
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Header h = new Header(b);
        if (h.internalCompression != COMPRESSION_NONE && h.internalCompression != COMPRESSION_GZIP)
            throw new IOException("Unsupported directory compression " + h.internalCompression);
        if (h.tileCompression != COMPRESSION_NONE && h.tileCompression != COMPRESSION_GZIP)
            throw new IOException("Unsupported tile compression " + h.tileCompression);
        if (h.minZoom > h.maxZoom || h.maxZoom > MAX_ZOOM) throw new IOException("Invalid zoom range");
        requireSection(source, h.rootOffset, h.rootLength, MAX_DIRECTORY_BYTES, "root directory");
        requireSection(source, h.leafOffset, h.leafLength, Integer.MAX_VALUE, "leaf directories");
        requireSection(source, h.tileDataOffset, h.tileDataLength, Long.MAX_VALUE, "tile data");
        requireSection(source, h.metadataOffset, h.metadataLength, MAX_DIRECTORY_BYTES, "metadata");
        Entry[] root = readDirectory(inflate(source.read(h.rootOffset, (int) h.rootLength),
                h.internalCompression));
        return new PmTilesReader(source, h, root);
    }

    public Header header() { return header; }

    /**
     * The tile for z/x/y, decompressed as stored, or {@code null} when the archive
     * has no such tile. Coordinates outside the z-level grid are an error.
     */
    public byte[] tile(int z, int x, int y) throws IOException {
        if (z < 0 || z > MAX_ZOOM) throw new IOException("Zoom out of range");
        long n = 1L << z;
        if (x < 0 || y < 0 || x >= n || y >= n) throw new IOException("Tile coordinate out of range");
        long id = tileId(z, x, y);
        Entry e = find(root, id);
        if (e == null) return null;
        if (e.runLength == 0) {
            // A leaf pointer: the leaf directory is located relative to the leaf section.
            if (e.length <= 0 || e.length > MAX_DIRECTORY_BYTES) throw new IOException("Invalid leaf pointer");
            long at = header.leafOffset + e.offset;
            requireSection(source, at, e.length, MAX_DIRECTORY_BYTES, "leaf directory");
            Entry[] leaf = readDirectory(inflate(source.read(at, (int) e.length), header.internalCompression));
            e = find(leaf, id);
            if (e == null) return null;
            if (e.runLength == 0) throw new IOException("Nested leaf directories are not supported");
        }
        if (id >= e.tileId + e.runLength) return null;
        if (e.length <= 0 || e.length > MAX_TILE_BYTES) throw new IOException("Invalid tile length");
        long at = header.tileDataOffset + e.offset;
        requireSection(source, at, e.length, MAX_TILE_BYTES, "tile");
        return inflate(source.read(at, (int) e.length), header.tileCompression);
    }

    /** PMTiles Hilbert tile ID: levels below z are counted first, then the curve index. */
    public static long tileId(int z, int x, int y) {
        long acc = ((1L << (2 * z)) - 1) / 3; // sum of 4^i for i < z
        long n = 1L << z;
        long d = 0;
        long xx = x, yy = y;
        for (long s = n / 2; s > 0; s /= 2) {
            long rx = (xx & s) > 0 ? 1 : 0;
            long ry = (yy & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) { xx = n - 1 - xx; yy = n - 1 - yy; }
                long t = xx; xx = yy; yy = t;
            }
        }
        return acc + d;
    }

    static Entry find(Entry[] entries, long id) {
        int lo = 0, hi = entries.length - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (entries[mid].tileId <= id) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return best < 0 ? null : entries[best];
    }

    static Entry[] readDirectory(byte[] raw) throws IOException {
        Cursor c = new Cursor(raw);
        long count = c.varint();
        if (count < 0 || count > MAX_DIRECTORY_ENTRIES) throw new IOException("Directory entry count invalid");
        int n = (int) count;
        long[] ids = new long[n];
        int[] runs = new int[n];
        long[] lengths = new long[n];
        long[] offsets = new long[n];
        long last = 0;
        for (int i = 0; i < n; i++) {
            last += c.varint();
            ids[i] = last;
            if (i > 0 && ids[i] <= ids[i - 1]) throw new IOException("Tile IDs must strictly increase");
        }
        for (int i = 0; i < n; i++) {
            long r = c.varint();
            if (r < 0 || r > Integer.MAX_VALUE) throw new IOException("Run length invalid");
            runs[i] = (int) r;
        }
        for (int i = 0; i < n; i++) lengths[i] = c.varint();
        for (int i = 0; i < n; i++) {
            long v = c.varint();
            if (i > 0 && v == 0) offsets[i] = offsets[i - 1] + lengths[i - 1];
            else if (v == 0 && i == 0) throw new IOException("First offset must be explicit");
            else offsets[i] = v - 1;
        }
        if (c.remaining() != 0) throw new IOException("Trailing bytes in directory");
        Entry[] out = new Entry[n];
        for (int i = 0; i < n; i++) out[i] = new Entry(ids[i], offsets[i], lengths[i], runs[i]);
        return out;
    }

    static void requireSection(Source s, long offset, long length, long max, String what) throws IOException {
        if (offset < 0 || length < 0 || length > max) throw new IOException(what + " size invalid");
        if (offset > s.size() || length > s.size() - offset) throw new IOException(what + " lies outside the file");
    }

    static byte[] inflate(byte[] raw, int compression) throws IOException {
        if (compression == COMPRESSION_NONE) return raw;
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (out.size() + r > MAX_INFLATED_BYTES) throw new IOException("Inflated data too large");
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    /** Minimal unsigned LEB128 reader. Overlong or truncated values fail closed. */
    static final class Cursor {
        private final byte[] data;
        private int pos;
        Cursor(byte[] data) { this.data = data; }
        int remaining() { return data.length - pos; }
        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= data.length) throw new IOException("Varint truncated");
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("Varint too long");
        }
    }
}
