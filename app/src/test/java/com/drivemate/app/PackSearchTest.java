package com.drivemate.app;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PackSearchTest {
    private static byte[] v(long n) throws Exception { ByteArrayOutputStream b = new ByteArrayOutputStream(); while ((n & ~127L) != 0) { b.write((int) (n & 127) | 128); n >>>= 7; } b.write((int) n); return b.toByteArray(); }
    private static byte[] cat(byte[]... all) throws Exception { ByteArrayOutputStream b = new ByteArrayOutputStream(); for (byte[] a : all) b.write(a); return b.toByteArray(); }
    private static byte[] field(int n, byte[] d) throws Exception { return cat(v(n * 8 + 2), v(d.length), d); }

    /** One layer with one point feature: tags name=<name>, plus an optional second tag. */
    private static byte[] tile(String layer, String name, String k2, String v2, int px, int py) throws Exception {
        byte[] geometry = cat(v(9), v((long) px << 1), v((long) py << 1));
        byte[] tags = k2 == null ? cat(v(0), v(0)) : cat(v(0), v(0), v(1), v(1));
        byte[] feature = cat(field(2, tags), v(24), v(1), field(4, geometry));
        byte[] layerBytes = cat(field(1, layer.getBytes("UTF-8")), field(2, feature), field(3, "name".getBytes("UTF-8")),
            k2 == null ? new byte[0] : field(3, k2.getBytes("UTF-8")),
            field(4, field(1, name.getBytes("UTF-8"))), k2 == null ? new byte[0] : field(4, field(1, v2.getBytes("UTF-8"))),
            v(40), v(4096), v(120), v(2));
        return field(3, layerBytes);
    }

    private static int[] tileOf(GeoMath.Point p) {
        double n = 1 << 14, r = Math.toRadians(p.lat);
        return new int[]{(int) Math.floor((p.lon + 180) / 360 * n), (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n)};
    }

    @Test public void findsAShopByPartialNameNearTheDriver() throws Exception {
        GeoMath.Point stockport = new GeoMath.Point(53.404, -2.17);
        int[] t = tileOf(stockport);
        byte[] data = tile("pois", "Bestway Wholesale", "shop", "wholesale", 2048, 2048);
        List<TomTomApi.Destination> r = PackSearch.search((z, x, y) -> x == t[0] && y == t[1] ? data : null,
            "bestway", stockport, new int[]{2, 5});
        assertEquals(1, r.size());
        assertEquals("Bestway Wholesale", r.get(0).title);
        assertTrue(GeoMath.distance(stockport, r.get(0).point) < 2000);
        assertTrue(r.get(0).address.contains("wholesale"));
    }

    @Test public void streetsMatchEveryWordAndRejectOthers() throws Exception {
        GeoMath.Point p = new GeoMath.Point(53.404, -2.17);
        int[] t = tileOf(p);
        byte[] data = tile("street_labels", "Menai Grove", null, null, 100, 200);
        PackSearch.Tiles tiles = (z, x, y) -> x == t[0] && y == t[1] ? data : null;
        assertEquals(1, PackSearch.search(tiles, "menai grove", p, new int[]{1}).size());
        assertEquals(1, PackSearch.search(tiles, "Grove Menai", p, new int[]{1}).size());
        assertTrue(PackSearch.search(tiles, "menai road", p, new int[]{1}).isEmpty());
        assertTrue(PackSearch.search(tiles, "x", p, new int[]{1}).isEmpty());
    }

    @Test public void layersWithoutNamesAreIgnoredAndNoFixMeansNoResults() throws Exception {
        GeoMath.Point p = new GeoMath.Point(53.404, -2.17);
        int[] t = tileOf(p);
        byte[] data = tile("buildings", "Bestway", null, null, 10, 10);
        assertTrue(PackSearch.search((z, x, y) -> x == t[0] && y == t[1] ? data : null, "bestway", p, new int[]{1}).isEmpty());
        assertTrue(PackSearch.search((z, x, y) -> data, "bestway", null, new int[]{1}).isEmpty());
    }

    @Test public void normalizeAndMatchRanking() {
        assertEquals("cafe nero", PackSearch.normalize("Café  Nero!"));
        assertEquals(3, PackSearch.match(new String[]{"tesco"}, "tesco", "tesco"), 0);
        assertEquals(2, PackSearch.match(new String[]{"tesco"}, "tesco", "tesco express"), 0);
        assertEquals(1, PackSearch.match(new String[]{"express"}, "express", "tesco express"), 0);
        assertEquals(0, PackSearch.match(new String[]{"asda"}, "asda", "tesco express"), 0);
    }
}
