package com.drivemate.app;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class MapPackTest {
    private static String sha(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String catalog(String url, long bytes, String sha) {
        return "{\"schema\":1,\"format\":\"pmtiles-v3\",\"tile_type\":\"mvt\",\"version\":\"v1\",\"packs\":["
            + "{\"id\":\"north-west-england\",\"name\":\"North West\",\"bbox\":[-3.7,52.9,-1.9,55.2],\"minzoom\":0,\"maxzoom\":14,\"bundled\":false,\"version\":\"v1\",\"bytes\":" + bytes + ",\"sha256\":\"" + sha + "\",\"urls\":[\"" + url + "\"]},"
            + "{\"id\":\"BAD ID\",\"name\":\"x\",\"bbox\":[0,0,1,1],\"bytes\":20000,\"sha256\":\"" + sha + "\",\"urls\":[\"https://x/y\"]},"
            + "{\"id\":\"uk-base\",\"name\":\"UK\",\"bbox\":[-8.7,49.8,2,60.9],\"maxzoom\":10,\"bundled\":true,\"version\":\"v1\",\"bytes\":50000,\"sha256\":\"" + sha + "\",\"urls\":[\"https://x/uk\"]}]}";
    }

    @Test public void catalogDropsInvalidAndFindsByPoint() throws Exception {
        String h = "a".repeat(64);
        MapPackCatalog c = MapPackCatalog.parse(catalog("https://x/nw.pmtiles", 20000, h));
        assertEquals(2, c.packs.size());
        assertEquals(1, c.forPoint(53.4, -2.2).size());
        assertEquals("north-west-england", c.forPoint(53.4, -2.2).get(0).id);
        assertTrue(c.forPoint(51.5, -0.1).isEmpty());
        assertTrue(MapPackCatalog.needsUpdate(c.find("uk-base"), "v0"));
        assertFalse(MapPackCatalog.needsUpdate(c.find("uk-base"), "v1"));
    }

    @Test public void catalogRejectsPlainHttpAndWrongSchema() throws Exception {
        String h = "a".repeat(64);
        assertTrue(MapPackCatalog.parse(catalog("http://x/nw", 20000, h)).find("north-west-england") == null);
        try { MapPackCatalog.parse("{\"schema\":2}"); fail(); } catch (JSONException expected) { }
    }

    @Test public void downloaderResumesVerifiesAndRejectsCorruption() throws Exception {
        byte[] data = new byte[40000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        final ServerSocket srv = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"));
        Thread server = new Thread(() -> {
            try {
                while (!srv.isClosed()) {
                    try (Socket so = srv.accept()) {
                        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream(), StandardCharsets.ISO_8859_1));
                        String line, range = null;
                        while ((line = in.readLine()) != null && !line.isEmpty())
                            if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
                        int from = range == null ? 0 : Integer.parseInt(range.replace("bytes=", "").replace("-", ""));
                        byte[] body = Arrays.copyOfRange(data, from, data.length);
                        OutputStream o = so.getOutputStream();
                        o.write(((range == null ? "HTTP/1.1 200 OK" : "HTTP/1.1 206 Partial Content") + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        o.write(body);
                        o.flush();
                    }
                }
            } catch (Exception ignored) { }
        });
        server.setDaemon(true);
        server.start();
        try {
            String url = "http://127.0.0.1:" + srv.getLocalPort() + "/p";
            MapPackCatalog.Pack p = MapPackCatalog.parse(catalog(url, data.length, sha(data)).replace("\"https://x/uk\"", "\"https://x/uk\"")).find("north-west-england");
            // http is filtered by the catalog, so build the pack through a permissive JSON round trip
            if (p == null) {
                String json = catalog("https://placeholder/p", data.length, sha(data));
                p = MapPackCatalog.parse(json).find("north-west-england");
            }
            File dir = Files.createTempDirectory("packs").toFile();
            File target = new File(dir, "nw.pmtiles");
            Files.write(new File(dir, "nw.pmtiles.part").toPath(), Arrays.copyOf(data, 15000));
            MapPackCatalog.Pack local = withUrl(p, url);
            new MapPackDownloader(true).download(local, target, null);
            assertArrayEquals(data, Files.readAllBytes(target.toPath()));
            assertFalse(new File(dir, "nw.pmtiles.part").exists());

            File bad = new File(dir, "bad.pmtiles");
            Files.write(new File(dir, "bad.pmtiles.part").toPath(), new byte[]{9, 9, 9});
            MapPackCatalog.Pack wrong = MapPackCatalog.parse(catalog("https://placeholder/p", data.length, "b".repeat(64))).find("north-west-england");
            try { new MapPackDownloader(true).download(withUrl(wrong, url), bad, null); fail(); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
            assertFalse(bad.exists());
        } finally { srv.close(); }
    }

    private static MapPackCatalog.Pack withUrl(MapPackCatalog.Pack p, String url) throws Exception {
        java.lang.reflect.Constructor<MapPackCatalog.Pack> k = MapPackCatalog.Pack.class.getDeclaredConstructors().length > 0
            ? (java.lang.reflect.Constructor<MapPackCatalog.Pack>) MapPackCatalog.Pack.class.getDeclaredConstructors()[0] : null;
        k.setAccessible(true);
        return k.newInstance(p.id, p.name, p.version, p.sha256, new double[]{p.west, p.south, p.east, p.north},
            p.minZoom, p.maxZoom, p.bytes, p.bundled, java.util.Collections.singletonList(url));
    }

    private static byte[] archive(double w, double s, double e, double n) {
        byte[] r = new byte[127 + 5 + 5];
        System.arraycopy("PMTiles".getBytes(StandardCharsets.US_ASCII), 0, r, 0, 7);
        r[7] = 3;
        ByteBuffer b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(8, 127); b.putLong(16, 5); b.putLong(24, 132); b.putLong(32, 0);
        b.putLong(40, 132); b.putLong(48, 0); b.putLong(56, 132); b.putLong(64, 5);
        r[97] = 1; r[98] = 1; r[99] = 1; r[100] = 0; r[101] = 14;
        b.putInt(102, (int) (w * 1e7)); b.putInt(106, (int) (s * 1e7)); b.putInt(110, (int) (e * 1e7)); b.putInt(114, (int) (n * 1e7));
        r[127] = 1; r[128] = 0; r[129] = 1; r[130] = 5; r[131] = 1;
        System.arraycopy(new byte[]{8, 1, 18, 1, 0}, 0, r, 132, 5);
        return r;
    }

    @Test public void packTilesServesCoveredTilesOnly() throws Exception {
        File f = File.createTempFile("pack", ".pmtiles");
        Files.write(f.toPath(), archive(-180, -85, 180, 85));
        PackTiles t = new PackTiles();
        assertTrue(t.isEmpty());
        t.add(f);
        assertArrayEquals(new byte[]{8, 1, 18, 1, 0}, t.tile(0, 0, 0));
        assertNull(t.tile(15, 0, 0)); // above pack maxzoom
        t.close();
        File small = File.createTempFile("small", ".pmtiles");
        Files.write(small.toPath(), archive(10, 10, 11, 11));
        PackTiles t2 = new PackTiles();
        t2.add(small);
        assertNull(t2.tile(0, 0, 0)); // tile centre outside the bbox
        t2.close();
    }
}
