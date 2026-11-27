package com.drivemate.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parsed map-packs.json (schema 1) published by Drivemate-map. Pure Java; invalid entries are dropped, never guessed. */
public final class MapPackCatalog {
    public static final class Pack {
        public final String id, name, version, sha256;
        public final double west, south, east, north;
        public final int minZoom, maxZoom;
        public final long bytes;
        public final boolean bundled;
        public final List<String> urls;

        Pack(String id, String name, String version, String sha256, double[] box, int minZoom, int maxZoom,
             long bytes, boolean bundled, List<String> urls) {
            this.id = id; this.name = name; this.version = version; this.sha256 = sha256;
            west = box[0]; south = box[1]; east = box[2]; north = box[3];
            this.minZoom = minZoom; this.maxZoom = maxZoom; this.bytes = bytes; this.bundled = bundled;
            this.urls = Collections.unmodifiableList(urls);
        }

        public boolean covers(double lat, double lon) {
            return lat >= south && lat <= north && lon >= west && lon <= east;
        }

        public boolean servesZoom(int z) { return z >= minZoom && z <= maxZoom; }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    static final long MIN_BYTES = 10_000L, MAX_BYTES = 1_950_000_000L;

    public final String version;
    public final List<Pack> packs;

    private MapPackCatalog(String version, List<Pack> packs) {
        this.version = version;
        this.packs = Collections.unmodifiableList(packs);
    }

    public static MapPackCatalog parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schema", 0) != 1) throw new JSONException("Unsupported map catalog schema");
        if (!"pmtiles-v3".equals(root.optString("format")) || !"mvt".equals(root.optString("tile_type")))
            throw new JSONException("Unsupported map catalog format");
        JSONArray arr = root.getJSONArray("packs");
        List<Pack> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Pack p = pack(arr.optJSONObject(i));
            if (p != null) out.add(p);
        }
        return new MapPackCatalog(root.optString("version", ""), out);
    }

    private static Pack pack(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.getString("id");
            String sha = o.getString("sha256");
            long bytes = o.getLong("bytes");
            JSONArray b = o.getJSONArray("bbox");
            if (!ID.matcher(id).matches() || !SHA.matcher(sha).matches() || bytes < MIN_BYTES || bytes > MAX_BYTES || b.length() != 4) return null;
            double[] box = {b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
            if (box[0] >= box[2] || box[1] >= box[3] || box[0] < -180 || box[2] > 180 || box[1] < -90 || box[3] > 90) return null;
            int minZ = o.optInt("minzoom", 0), maxZ = o.optInt("maxzoom", 14);
            if (minZ < 0 || maxZ < minZ || maxZ > 22) return null;
            JSONArray u = o.getJSONArray("urls");
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < u.length(); i++) {
                String s = u.optString(i, "");
                if (s.startsWith("https://")) urls.add(s);
            }
            if (urls.isEmpty()) return null;
            return new Pack(id, o.optString("name", id), o.optString("version", ""), sha, box, minZ, maxZ, bytes,
                    o.optBoolean("bundled", false), urls);
        } catch (JSONException e) {
            return null;
        }
    }

    public Pack find(String id) {
        for (Pack p : packs) if (p.id.equals(id)) return p;
        return null;
    }

    /** Downloadable full-detail packs containing the point, smallest first. */
    public List<Pack> forPoint(double lat, double lon) {
        List<Pack> out = new ArrayList<>();
        for (Pack p : packs) if (!p.bundled && p.covers(lat, lon)) out.add(p);
        Collections.sort(out, (a, b) -> Long.compare(a.bytes, b.bytes));
        return out;
    }

    /** True when the catalog offers a different version than the installed one. */
    public static boolean needsUpdate(Pack offered, String installedVersion) {
        return offered != null && installedVersion != null && !installedVersion.isEmpty()
                && !offered.version.equals(installedVersion);
    }
}
