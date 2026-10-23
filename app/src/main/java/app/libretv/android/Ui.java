package app.libretv.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xff0c1320, PANEL = 0xff141f2e, TILE = 0xff213248;
    static final int TEXT = 0xffe7eef4, MUTED = 0xff98aabe, ACCENT = 0xff00b9df, GOLD = 0xffffcd57;
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static String plain(String value) { return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim(); }
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static void pad(View view, int size) { int d = dp(view.getContext(), size); view.setPadding(d, d, d, d); }
    static GradientDrawable shape(int color, int radius, Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius)); return d;
    }
    static TextView text(Context c, String value, int sp, int color) {
        TextView t = new TextView(c); t.setText(plain(value)); t.setTextSize(sp); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); return t;
    }
    static TextView title(Context c, String value) {
        TextView t = text(c, value, 27, TEXT); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t;
    }
    static Button button(Context c, String label, Runnable action) {
        Button b = new Button(c); b.setText(label); b.setTextColor(TEXT); b.setTextSize(14); b.setAllCaps(false);
        b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x448bcad9), shape(TILE, 12, c), null));
        b.setOnClickListener(v -> action.run()); return b;
    }
    static EditText input(Context c, String hint) {
        EditText e = new EditText(c); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setTextSize(16);
        e.setSingleLine(true); e.setHint(hint); e.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        e.setBackground(shape(PANEL, 12, c)); e.setMinimumHeight(dp(c, 52)); return e;
    }
    static TextView brand(Context c, int size) {
        TextView text = text(c, "LibreTV", size, ACCENT);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.getPaint().setShader(new android.graphics.LinearGradient(0, 0, dp(c, size * 3.2f), 0,
            new int[]{ACCENT, 0xff999acb, 0xfffc3a7b}, null, android.graphics.Shader.TileMode.CLAMP));
        return text;
    }
    static Button chip(Context c, String label, boolean active, Runnable action) {
        Button button = button(c, label, action);
        if (active) { button.setBackgroundTintList(ColorStateList.valueOf(ACCENT)); button.setTextColor(BG); }
        return button;
    }
    static void gap(LinearLayout l, int height) { l.addView(new View(l.getContext()), new LinearLayout.LayoutParams(1, dp(l.getContext(), height))); }
    static void insets(Activity activity, View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        activity.setContentView(root); root.requestApplyInsets();
    }
}
