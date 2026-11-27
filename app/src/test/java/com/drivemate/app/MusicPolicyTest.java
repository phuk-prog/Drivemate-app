package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class MusicPolicyTest {
    @Test public void prefersPlayingSpotify() {
        List<String> p = Arrays.asList("com.google.android.youtube", "com.spotify.music");
        assertEquals(1, MusicPolicy.choose(p, Arrays.asList(true, true)));
        assertEquals(0, MusicPolicy.choose(p, Arrays.asList(true, false)));
        assertEquals(1, MusicPolicy.choose(p, Arrays.asList(false, false)));
        assertEquals(-1, MusicPolicy.choose(Collections.emptyList(), Collections.emptyList()));
    }

    @Test public void lineAndTurnHiding() {
        assertEquals("Song · Artist", MusicPolicy.line(" Song ", "Artist"));
        assertEquals("Artist", MusicPolicy.line(null, "Artist"));
        assertEquals("", MusicPolicy.line(null, null));
        assertFalse(MusicPolicy.showWhileDriving(true, 100));
        assertTrue(MusicPolicy.showWhileDriving(true, 400));
        assertTrue(MusicPolicy.showWhileDriving(true, -1));
        assertFalse(MusicPolicy.showWhileDriving(false, 400));
    }
}
