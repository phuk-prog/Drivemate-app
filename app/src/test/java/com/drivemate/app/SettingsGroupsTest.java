package com.drivemate.app;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class SettingsGroupsTest {
    @Test public void everySettingIsInExactlyOneGroup() {
        int[] seen = new int[SettingsGroups.TOTAL];
        for (SettingsGroups.Group g : SettingsGroups.GROUPS) for (int i : g.items) seen[i]++;
        for (int i = 0; i < seen.length; i++) assertEquals("setting " + i, 1, seen[i]);
    }

    @Test public void nineEverydayGroupsAndAHiddenDeveloperGroup() {
        int everyday = 0, developer = 0;
        for (SettingsGroups.Group g : SettingsGroups.GROUPS) if (g.developer) developer++; else everyday++;
        assertEquals(9, everyday);
        assertEquals(1, developer);
    }

    @Test public void splitsLabelAndValue() {
        assertArrayEquals(new String[]{"Voice volume", "80%"}, SettingsGroups.split("Voice volume · 80%"));
        assertArrayEquals(new String[]{"Set or change Home", ""}, SettingsGroups.split("Set or change Home"));
        assertArrayEquals(new String[]{"Map · DriveMate native", "verified"}, SettingsGroups.split("Map · DriveMate native · verified"));
    }

    @Test public void searchMatchesLabelsAndGroupTitles() {
        String[] labels = new String[SettingsGroups.TOTAL];
        Arrays.fill(labels, "Other");
        labels[3] = "Voice volume · 80%";
        SettingsGroups.Group voice = SettingsGroups.GROUPS[0];
        assertEquals(Collections.singletonList(3), SettingsGroups.matching(voice, labels, "VOLUME"));
        assertEquals(voice.items.length, SettingsGroups.matching(voice, labels, "voice and").size());
        assertEquals(voice.items.length, SettingsGroups.matching(voice, labels, "").size());
        assertTrue(SettingsGroups.closesScreen(21));
        assertFalse(SettingsGroups.closesScreen(3));
    }
}
