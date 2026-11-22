package com.drivemate.app;

import org.junit.Test;
import static org.junit.Assert.*;
public class SearchQueryTest {
    @Test public void normalizesUkPostcodes() {
        assertEquals("SK3 0AA", SearchQuery.clean(" sk30aa "));
        assertEquals("SW1A 1AA", SearchQuery.clean("sw1a  1aa"));
        assertEquals("M1 1AE", SearchQuery.clean("m1 1ae"));
    }
    @Test public void keepsNormalDestinationWords() {
        assertEquals("Bestway Stockport", SearchQuery.clean("  Bestway   Stockport  "));
    }
}
