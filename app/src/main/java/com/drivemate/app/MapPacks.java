package com.drivemate.app;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed full-detail map areas on device storage (files/map-packs). Free hosting: Drivemate-map GitHub Releases. */
final class MapPacks {
    static final String CATALOG_URL = "https://github.com/phuk-prog/Drivemate-map/releases/download/map-packs-latest/map-packs.json";
    /** Bumped whenever a pack is installed or removed so tile sources reopen their files. */
    static final AtomicInteger GENERATION = new AtomicInteger();

    private final File dir;
    MapPacks(Context c) { dir = new File(c.getFilesDir(), "map-packs"); }

    File file(String id) { return new File(dir, id + ".pmtiles"); }
    private File versionFile(String id) { return new File(dir, id + ".version"); }
    File catalogCache() { return new File(dir, "catalog.json"); }

    boolean installed(String id) { return file(id).isFile(); }

    String installedVersion(String id) {
        try { return new String(Files.readAllBytes(versionFile(id).toPath()), StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    List<File> installedFiles() {
        List<File> out = new ArrayList<>();
        File[] all = dir.listFiles((d, n) -> n.endsWith(".pmtiles"));
        if (all != null) for (File f : all) out.add(f);
        return out;
    }

    long installedBytes() { long t = 0; for (File f : installedFiles()) t += f.length(); return t; }

    void install(MapPackCatalog.Pack pack, MapPackDownloader.Progress progress) throws IOException {
        new MapPackDownloader().download(pack, file(pack.id), progress);
        Files.write(versionFile(pack.id).toPath(), pack.version.getBytes(StandardCharsets.UTF_8));
        GENERATION.incrementAndGet();
    }

    void remove(String id) {
        file(id).delete();
        versionFile(id).delete();
        GENERATION.incrementAndGet();
    }

    /** Fetches a fresh catalog and caches it; falls back to the cached copy when offline. */
    MapPackCatalog catalog() throws Exception {
        try {
            String text = new MapPackDownloader().fetchText(CATALOG_URL);
            MapPackCatalog c = MapPackCatalog.parse(text);
            if (!dir.isDirectory()) dir.mkdirs();
            Files.write(catalogCache().toPath(), text.getBytes(StandardCharsets.UTF_8));
            return c;
        } catch (IOException offline) {
            if (!catalogCache().isFile()) throw offline;
            return MapPackCatalog.parse(new String(Files.readAllBytes(catalogCache().toPath()), StandardCharsets.UTF_8));
        }
    }
}
