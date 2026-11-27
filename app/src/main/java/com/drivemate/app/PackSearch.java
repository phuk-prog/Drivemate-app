package com.drivemate.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline name search over the vector tiles of installed map areas: places of interest, streets, towns and stops.
 * Pure Java (no Android). Reads only the name layers of nearby z14 tiles, widening the ring until enough matches
 * are found, so it works anywhere a map area is installed, with no network and no separate search index.
 */
final class PackSearch {
    interface Tiles { byte[] tile(int z, int x, int y) throws IOException; }

    static final int ZOOM = 14;
    private static final int MAX_RESULTS = 8;
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private PackSearch() { }

    static String normalize(String s) {
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.toLowerCase(Locale.UK).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** radiusTiles: ring sizes to try in order, e.g. {2, 5, 9}. Stops at the first ring with enough results. */
    static List<TomTomApi.Destination> search(Tiles tiles, String query, GeoMath.Point near, int[] radiusTiles) {
        String q = normalize(query);
        if (q.length() < 2 || near == null || tiles == null) return Collections.emptyList();
        String[] words = q.split(" ");
        double n = 1 << ZOOM;
        int cx = (int) Math.floor((near.lon + 180) / 360 * n);
        double latRad = Math.toRadians(near.lat);
        int cy = (int) Math.floor((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n);
        List<Hit> hits = new ArrayList<>();
        int done = -1;
        for (int radius : radiusTiles) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) <= done) continue; // already scanned in a smaller ring
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= (int) n || y >= (int) n) continue;
                    byte[] data;
                    try { data = tiles.tile(ZOOM, x, y); } catch (IOException e) { continue; }
                    if (data == null || data.length == 0) continue;
                    try { scan(data, x, y, words, q, near, hits); } catch (IOException | RuntimeException ignored) { }
                }
            }
            done = radius;
            if (distinct(hits) >= 5) break;
        }
        return rank(hits);
    }

    private static final class Hit {
        final String name, kind;
        final GeoMath.Point point;
        final double score, distance;
        Hit(String name, String kind, GeoMath.Point point, double score, double distance) {
            this.name = name; this.kind = kind; this.point = point; this.score = score; this.distance = distance;
        }
    }

    private static int distinct(List<Hit> hits) { return rank(hits).size(); }

    private static List<TomTomApi.Destination> rank(List<Hit> hits) {
        List<Hit> sorted = new ArrayList<>(hits);
        Collections.sort(sorted, (a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : Double.compare(a.distance, b.distance));
        List<TomTomApi.Destination> out = new ArrayList<>();
        List<Hit> kept = new ArrayList<>();
        for (Hit h : sorted) {
            boolean dup = false;
            for (Hit k : kept) {
                if (k.name.equalsIgnoreCase(h.name) && GeoMath.distance(k.point, h.point) < 400) { dup = true; break; }
            }
            if (dup) continue;
            kept.add(h);
            out.add(new TomTomApi.Destination(h.name, h.kind, h.point));
            if (out.size() >= MAX_RESULTS) break;
        }
        return out;
    }

    // ---- minimal MVT reader: names and the first vertex only ----
    private static final class Wire {
        final byte[] b; int p; final int end;
        Wire(byte[] b, int p, int end) { this.b = b; this.p = p; this.end = end; }
        boolean more() { return p < end; }
        long v() throws IOException {
            long n = 0; int s = 0;
            for (int i = 0; i < 10; i++) {
                if (p >= end) throw new IOException("truncated");
                int x = b[p++] & 255;
                n |= (long) (x & 127) << s; s += 7;
                if (x < 128) return n;
            }
            throw new IOException("varint");
        }
        Wire sub() throws IOException {
            long n = v();
            if (n < 0 || n > end - p) throw new IOException("field");
            Wire w = new Wire(b, p, p + (int) n); p += (int) n; return w;
        }
        String str() throws IOException { Wire w = sub(); return new String(b, w.p, w.end - w.p, java.nio.charset.StandardCharsets.UTF_8); }
        void skip(int type) throws IOException {
            switch (type) {
                case 0: v(); break;
                case 1: p += 8; break;
                case 2: sub(); break;
                case 5: p += 4; break;
                default: throw new IOException("wire");
            }
        }
    }

    private static boolean searchable(String layer) {
        return layer.equals("pois") || layer.equals("street_labels") || layer.equals("place_labels") || layer.equals("public_transport");
    }

    private static void scan(byte[] data, int tx, int ty, String[] words, String q, GeoMath.Point near, List<Hit> out) throws IOException {
        if (data.length > MAX_BYTES) return;
        Wire w = new Wire(data, 0, data.length);
        while (w.more()) {
            int k = (int) w.v();
            if ((k >> 3) == 3 && (k & 7) == 2) layer(w.sub(), tx, ty, words, q, near, out);
            else w.skip(k & 7);
        }
    }

    private static void layer(Wire w, int tx, int ty, String[] words, String q, GeoMath.Point near, List<Hit> out) throws IOException {
        String name = ""; int extent = 4096;
        List<String> keys = new ArrayList<>(), values = new ArrayList<>();
        List<Wire> features = new ArrayList<>();
        while (w.more()) {
            int k = (int) w.v();
            switch (k) {
                case 10: name = w.str(); break;
                case 18: features.add(w.sub()); break;
                case 26: keys.add(w.str()); break;
                case 34: values.add(value(w.sub())); break;
                case 40: extent = (int) w.v(); break;
                default: w.skip(k & 7);
            }
        }
        if (!searchable(name) || extent < 1) return;
        for (Wire f : features) {
            Wire tags = null, geometry = null;
            while (f.more()) {
                int k = (int) f.v();
                if (k == 18) tags = f.sub();
                else if (k == 34) geometry = f.sub();
                else f.skip(k & 7);
            }
            if (tags == null || geometry == null) continue;
            Map<String, String> t = new HashMap<>();
            while (tags.more()) {
                int a = (int) tags.v(), b = (int) tags.v();
                if (a < keys.size() && b < values.size()) t.put(keys.get(a), values.get(b));
            }
            String label = t.get("name");
            if (label == null || label.isEmpty() || label.length() > 100) continue;
            double score = match(words, q, normalize(label));
            if (score <= 0) continue;
            long cmd = geometry.v();
            if ((cmd & 7) != 1) continue;
            long x = zig(geometry.v()), y = zig(geometry.v());
            double scale = 1L << ZOOM;
            double lon = ((tx + x / (double) extent) / scale) * 360 - 180;
            double ny = (ty + y / (double) extent) / scale;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * ny))));
            GeoMath.Point p = new GeoMath.Point(lat, lon);
            out.add(new Hit(label, describe(name, t), p, score, GeoMath.distance(near, p)));
        }
    }

    private static String value(Wire w) throws IOException {
        String s = "";
        while (w.more()) {
            int k = (int) w.v();
            if (k == 10) s = w.str(); else w.skip(k & 7);
        }
        return s;
    }

    private static long zig(long n) { return (n >>> 1) ^ -(n & 1); }

    /** 3 = whole name matches, 2 = name starts with the query, 1 = every word found; 0 = no match. */
    static double match(String[] words, String q, String name) {
        if (name.equals(q)) return 3;
        if (name.startsWith(q)) return 2;
        String[] nameWords = name.split(" ");
        for (String w : words) {
            boolean found = false;
            for (String nw : nameWords) if (nw.startsWith(w)) { found = true; break; }
            if (!found) return 0;
        }
        return 1;
    }

    private static String describe(String layer, Map<String, String> t) {
        if (layer.equals("street_labels")) return "Street";
        if (layer.equals("place_labels")) return capital(t.getOrDefault("kind", "place"));
        if (layer.equals("public_transport")) return "Stop · " + t.getOrDefault("kind", "transport").replace('_', ' ');
        for (String key : new String[]{"shop", "amenity", "tourism", "leisure", "historic"}) {
            String v = t.get(key);
            if (v != null && !v.isEmpty()) return capital(key.equals("shop") ? "Shop · " + v : v).replace('_', ' ');
        }
        return "Place";
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
