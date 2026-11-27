package com.drivemate.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Resumable, verified pack download. Writes id.pmtiles.part, resumes with a Range request, follows at most 6
 * redirects itself (HTTPS only), verifies size and SHA-256, then renames atomically. Pure JVM so it is unit-tested.
 */
public final class MapPackDownloader {
    public interface Progress { boolean update(long done, long total); } // return false to cancel

    private final boolean allowInsecure;

    public MapPackDownloader() { this(false); }
    MapPackDownloader(boolean allowInsecureForTests) { allowInsecure = allowInsecureForTests; }

    public static final class Cancelled extends IOException { Cancelled() { super("Download cancelled"); } }

    public void download(MapPackCatalog.Pack pack, File target, Progress progress) throws IOException {
        File part = new File(target.getPath() + ".part");
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create map folder");
        if (dir != null && dir.getUsableSpace() < pack.bytes - Math.min(part.length(), pack.bytes) + 50_000_000L)
            throw new IOException("Not enough free storage for " + pack.name);
        IOException last = null;
        for (String url : pack.urls) {
            try {
                fetch(url, pack, part, progress);
                verify(part, pack);
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace old map");
                if (!part.renameTo(target)) throw new IOException("Cannot finalise map");
                return;
            } catch (Cancelled c) {
                throw c;
            } catch (IOException e) {
                last = e;
                if (e instanceof BadPack) part.delete();
            }
        }
        throw last == null ? new IOException("No download source") : last;
    }

    static final class BadPack extends IOException { BadPack(String m) { super(m); } }

    private void fetch(String start, MapPackCatalog.Pack pack, File part, Progress progress) throws IOException {
        long have = part.length();
        if (have > pack.bytes) { part.delete(); have = 0; }
        if (have == pack.bytes) return;
        HttpURLConnection c = open(start, have);
        try {
            int code = c.getResponseCode();
            if (have > 0 && code == 200) { have = 0; part.delete(); } // server ignored Range: restart
            else if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                out.seek(have);
                byte[] buf = new byte[64 * 1024];
                long done = have;
                int n;
                while ((n = in.read(buf)) > 0) {
                    done += n;
                    if (done > pack.bytes) throw new BadPack("Map larger than expected");
                    out.write(buf, 0, n);
                    if (progress != null && !progress.update(done, pack.bytes)) throw new Cancelled();
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String start, long from) throws IOException {
        String url = start;
        for (int hop = 0; hop <= 6; hop++) {
            if (!allowInsecure && !url.startsWith("https://")) throw new IOException("Refusing non-HTTPS map source");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    /** Small text download (catalog) through the same redirect and HTTPS rules; capped at 1 MB. */
    public String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url, 0);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1_000_000) throw new IOException("Catalog too large");
                }
                return out.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    static void verify(File file, MapPackCatalog.Pack pack) throws IOException {
        if (file.length() != pack.bytes) throw new IOException("Incomplete download " + file.length() + "/" + pack.bytes);
        try (InputStream in = new java.io.FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b));
            if (!s.toString().equals(pack.sha256)) throw new BadPack("Map checksum mismatch");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
