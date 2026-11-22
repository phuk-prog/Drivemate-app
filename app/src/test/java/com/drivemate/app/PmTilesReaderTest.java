package com.drivemate.app;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class PmTilesReaderTest {
    private static byte[] tinyArchive() {
        byte[] result = new byte[127 + 5 + 5];
        byte[] magic = "PMTiles".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, result, 0, 7);
        result[7] = 3;
        ByteBuffer b = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(8, 127);
        b.putLong(16, 5);
        b.putLong(24, 132);
        b.putLong(32, 0);
        b.putLong(40, 132);
        b.putLong(48, 0);
        b.putLong(56, 132);
        b.putLong(64, 5);
        result[97] = 1; // directory uncompressed
        result[98] = 1; // MVT uncompressed
        result[99] = 1; // MVT archive
        result[100] = 0;
        result[101] = 0;
        result[127] = 1; // 1 root entry
        result[128] = 0; // tile ID 0
        result[129] = 1; // 1 tile
        result[130] = 5; // length 5
        result[131] = 1; // offset + 1
        System.arraycopy(new byte[]{8, 1, 18, 1, 0}, 0, result, 132, 5);
        return result;
    }

    @Test public void readsValidatedHeaderAndTileWithoutNetwork() throws Exception {
        byte[] bytes = tinyArchive();
        PmTilesReader.Source source = new PmTilesReader.Source() {
            public long size() { return bytes.length; }
            public byte[] read(long offset, int length) {
                return java.util.Arrays.copyOfRange(bytes, (int) offset, (int) offset + length);
            }
        };
        PmTilesReader reader = PmTilesReader.open(source);
        assertEquals(0, reader.header().minZoom);
        assertArrayEquals(new byte[]{8, 1, 18, 1, 0}, reader.tile(0, 0, 0));
    }

    @Test public void rejectsInvalidMagic() {
        byte[] bytes = tinyArchive();
        bytes[0] = 0;
        PmTilesReader.Source source = new PmTilesReader.Source() {
            public long size() { return bytes.length; }
            public byte[] read(long offset, int len) {
                return java.util.Arrays.copyOfRange(bytes,(int)offset,(int)offset+len);
            }
        };
        try {
            PmTilesReader.open(source);
            fail("Must fail closed on invalid map");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PMTiles"));
        }
    }

    @Test public void tileIdsAtZoomOneAreUnique() {
        Set<Long> ids = new HashSet<>();
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                ids.add(PmTilesReader.tileId(1, x, y));
        assertEquals(4, ids.size());
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(4L));
    }
}
