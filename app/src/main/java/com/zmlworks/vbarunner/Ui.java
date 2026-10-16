package com.zmlworks.vbarunner;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers shared by activities: palette, edge-to-edge handling for targetSdk 35, view factories. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF002218, TEXT = 0xFFC6CFC6, ACCENT = 0xFF3D0105, HI = 0xFF00A2E8,
            PANEL = 0xFF0A3226, GRID = 0xFF1E4A3C, DIM = 0xFF7F8F86, BG2 = 0xFF00140E, ERR = 0xFFFF8A80;

    /** Android 15 forces edge-to-edge; we opt in on every version and pad the root by the bar/IME insets. */
    @SuppressWarnings("deprecation")
    static void edgeToEdge(Activity a, View root) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                il = i.left; it = i.top; ir = i.right; ib = i.bottom;
            } else {
                il = in.getSystemWindowInsetLeft(); it = in.getSystemWindowInsetTop();
                ir = in.getSystemWindowInsetRight(); ib = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l + il, t + it, r + ir, b + ib);
            return in;
        });
        root.requestApplyInsets();
    }

    static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Context c, CharSequence label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** Flat icon-like button for top bars (min 48dp touch target). */
    static TextView barButton(Context c, CharSequence glyph, CharSequence description, View.OnClickListener l) {
        TextView b = text(c, glyph, 20, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(c, 48));
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        b.setContentDescription(description);
        b.setBackgroundResource(R.drawable.bar_btn_bg);
        b.setOnClickListener(l);
        b.setFocusable(true);
        return b;
    }

    /** Top bar: [←] title [actions…]. Returns the title view. */
    static TextView topBar(Activity a, LinearLayout root, boolean back, View... actions) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG2);
        bar.setMinimumHeight(dp(a, 52));
        if (back) bar.addView(barButton(a, "←", a.getString(R.string.back), v -> a.onBackPressed()));
        TextView title = text(a, "", 18, TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(a, back ? 4 : 16), 0, dp(a, 8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (View v : actions) bar.addView(v);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return title;
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    static boolean japanese() {
        return "ja".equals(java.util.Locale.getDefault().getLanguage());
    }
}
