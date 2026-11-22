package com.drivemate.app;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SearchQuery {
    private SearchQuery() {}
    private static final Pattern UK_POSTCODE =
            Pattern.compile("^(GIR0AA|(?:[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}))$");
    public static String clean(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll("\\s+", " ");
        String compact = value.replace(" ", "").toUpperCase(Locale.UK);
        if (UK_POSTCODE.matcher(compact).matches()) {
            return compact.substring(0, compact.length() - 3) + " "
                    + compact.substring(compact.length() - 3);
        }
        return value;
    }
}
