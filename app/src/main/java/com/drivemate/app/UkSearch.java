package com.drivemate.app;

import java.util.Locale;

/** Normalize plain UK full postcodes without modifying other place searches. */
public final class UkSearch {
    private UkSearch() { }

    public static String normalize(String query) {
        String text = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        String compact = text.replace(" ", "").toUpperCase(Locale.UK);
        if (compact.matches("(GIR0AA|[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2})")) {
            return compact.substring(0, compact.length() - 3)
                    + " " + compact.substring(compact.length() - 3);
        }
        return text;
    }
}
