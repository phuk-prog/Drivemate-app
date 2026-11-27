package com.drivemate.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Full-screen settings: nine groups, a search box, and a hidden Developer group
 * (tap the title seven times). Each row shows the setting and its current value;
 * the list refreshes after every change and when a sub-dialog closes.
 */
final class SettingsScreen {
    private SettingsScreen() { }

    private static final int DARK = Color.rgb(11, 34, 51), CARD = Color.rgb(20, 52, 70),
            CYAN = Color.rgb(0, 209, 232), SUB = Color.rgb(176, 205, 218);

    static void show(Activity a, Supplier<String[]> labels, IntConsumer run, SharedPreferences prefs) {
        Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar);
        float density = a.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);
        root.setPadding(px(density, 16), px(density, 14), px(density, 16), 0);

        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(a, "Settings", 24, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(a);
        close.setText("Done");
        close.setAllCaps(false);
        close.setTextColor(DARK);
        close.setBackground(round(CYAN, density, 12));
        close.setOnClickListener(v -> d.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(px(density, 84), px(density, 44)));
        root.addView(header);

        EditText search = new EditText(a);
        search.setHint("Search settings");
        search.setHintTextColor(SUB);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setBackground(round(CARD, density, 12));
        search.setPadding(px(density, 14), px(density, 10), px(density, 14), px(density, 10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, px(density, 12), 0, px(density, 6));
        root.addView(search, sp);

        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, px(density, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            String[] l = labels.get();
            boolean developer = prefs.getBoolean("developer_settings", false);
            String q = search.getText().toString();
            for (SettingsGroups.Group g : SettingsGroups.GROUPS) {
                if (g.developer && !developer) continue;
                List<Integer> items = SettingsGroups.matching(g, l, q);
                if (items.isEmpty()) continue;
                TextView h = text(a, g.title.toUpperCase(java.util.Locale.UK), 12, CYAN, true);
                h.setLetterSpacing(0.08f);
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
                hp.setMargins(px(density, 4), px(density, 18), 0, px(density, 6));
                list.addView(h, hp);
                LinearLayout card = new LinearLayout(a);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(round(CARD, density, 14));
                for (int n = 0; n < items.size(); n++) {
                    int index = items.get(n);
                    String[] parts = SettingsGroups.split(l[index]);
                    LinearLayout row = new LinearLayout(a);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(px(density, 56));
                    row.setPadding(px(density, 14), px(density, 8), px(density, 14), px(density, 8));
                    TypedValue ripple = new TypedValue();
                    a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                    row.setForeground(a.getDrawable(ripple.resourceId));
                    TextView name = text(a, parts[0], 16, Color.WHITE, false);
                    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView value = text(a, parts[1].isEmpty() ? "›" : parts[1] + "  ›", 14, SUB, false);
                    value.setGravity(Gravity.END);
                    value.setMaxWidth(px(density, 170));
                    row.addView(value);
                    row.setContentDescription(l[index]);
                    row.setOnClickListener(v -> {
                        if (SettingsGroups.closesScreen(index)) { d.dismiss(); run.accept(index); return; }
                        run.accept(index);
                        rebuild[0].run();
                    });
                    card.addView(row, new LinearLayout.LayoutParams(-1, -2));
                    if (n + 1 < items.size()) {
                        View line = new View(a);
                        line.setBackgroundColor(Color.rgb(36, 72, 92));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
                        lp.setMargins(px(density, 14), 0, 0, 0);
                        card.addView(line, lp);
                    }
                }
                list.addView(card, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { rebuild[0].run(); }
        });
        int[] taps = {0};
        title.setOnClickListener(v -> {
            if (++taps[0] < SettingsGroups.DEVELOPER_TAPS) return;
            taps[0] = 0;
            boolean on = !prefs.getBoolean("developer_settings", false);
            prefs.edit().putBoolean("developer_settings", on).apply();
            Toast.makeText(a, on ? "Developer settings shown" : "Developer settings hidden", Toast.LENGTH_SHORT).show();
            rebuild[0].run();
        });
        rebuild[0].run();
        d.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        // Sub-dialogs (voice mode, tolerance, ...) change values: refresh when focus returns.
        root.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> { if (focused) rebuild[0].run(); });
        d.show();
    }

    private static int px(float density, float dp) { return Math.round(dp * density); }

    private static GradientDrawable round(int color, float density, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius * density);
        return g;
    }

    private static TextView text(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
