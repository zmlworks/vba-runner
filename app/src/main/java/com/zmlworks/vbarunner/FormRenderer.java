package com.zmlworks.vbarunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.zmlworks.vbarunner.core.ControlSpec;
import com.zmlworks.vbarunner.core.ControlState;
import com.zmlworks.vbarunner.core.ScriptRunner;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds real Android views for the run screen from {@link ControlState}s (absolute dp layout) and keeps them in sync.
 * Script-thread changes are coalesced and applied on the main thread; user input is written to the state first and
 * then an event (Name_Click / Name_Change / Grid_Click(Row, Col)) is queued on the runner.
 */
final class FormRenderer implements ControlState.Listener {
    private final Activity act;
    private final FrameLayout area;
    private final ScriptRunner runner;
    private final Map<ControlState, View> views = new HashMap<>();
    private final Set<ControlState> dirty = new LinkedHashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean posted, updating;
    private final float density;

    FormRenderer(Activity a, FrameLayout area, ScriptRunner runner) {
        act = a;
        this.area = area;
        this.runner = runner;
        density = a.getResources().getDisplayMetrics().density;
    }

    void build(List<ControlState> states) {
        area.removeAllViews();
        views.clear();
        for (ControlState s : states) {
            View v = create(s);
            views.put(s, v);
            area.addView(v);
            apply(s, v);
            s.setListener(this);
        }
        updateAreaHeight();
    }

    void detach() {
        for (ControlState s : views.keySet()) s.setListener(null);
    }

    private View create(final ControlState s) {
        switch (s.type) {
            case BUTTON: {
                Button b = Ui.button(act, "", v -> runner.post(s.name + "_Click"));
                b.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
                b.setMinHeight(0);
                b.setMinimumHeight(0);
                return b;
            }
            case TEXTBOX: {
                final EditText e = new EditText(act);
                e.setBackgroundResource(R.drawable.field_bg);
                e.setTextColor(Ui.TEXT);
                e.setHintTextColor(Ui.DIM);
                e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
                e.setOnEditorActionListener((v, id, ev) -> {
                    if (id == EditorInfo.IME_ACTION_DONE) {
                        InputMethodManager im = (InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
                        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                        v.clearFocus();
                        return true;
                    }
                    return false;
                });
                e.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                    @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
                    @Override public void afterTextChanged(Editable ed) {
                        if (updating) return;
                        if (s.userSetText(ed.toString())) runner.post(s.name + "_Change");
                    }
                });
                return e;
            }
            case COMBOBOX: {
                TextView t = Ui.text(act, "", 16, Ui.TEXT);
                t.setBackgroundResource(R.drawable.btn_bg);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0);
                t.setSingleLine(true);
                t.setFocusable(true);
                t.setOnClickListener(v -> pick(s));
                return t;
            }
            case GRID: {
                GridControlView g = new GridControlView(act, s);
                g.setOnCell((r, c) -> runner.post(s.name + "_Click", (long) r, (long) c));
                return g;
            }
            default: {
                TextView t = Ui.text(act, "", 16, Ui.TEXT);
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                t.setOnClickListener(v -> runner.post(s.name + "_Click"));
                return t;
            }
        }
    }

    private void pick(final ControlState s) {
        final List<String> items = s.items();
        if (items.isEmpty()) {
            android.widget.Toast.makeText(act, R.string.combo_empty, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle(s.name)
                .setSingleChoiceItems(items.toArray(new String[0]), s.listIndex(), (d, which) -> {
                    d.dismiss();
                    if (s.userSelect(which)) runner.post(s.name + "_Change");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ sync
    @Override public void onControlChanged(ControlState c, String prop) {
        synchronized (dirty) {
            dirty.add(c);
            if (posted) return;
            posted = true;
        }
        main.post(this::flush);
    }

    private void flush() {
        ControlState[] cs;
        synchronized (dirty) {
            cs = dirty.toArray(new ControlState[0]);
            dirty.clear();
            posted = false;
        }
        for (ControlState c : cs) {
            View v = views.get(c);
            if (v != null) apply(c, v);
        }
        updateAreaHeight();
    }

    private void apply(ControlState s, View v) {
        int[] b = s.bounds();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(b[2]), px(b[3]));
        lp.leftMargin = px(b[0]);
        lp.topMargin = px(b[1]);
        v.setLayoutParams(lp);
        v.setVisibility(s.visible() ? View.VISIBLE : View.GONE);
        boolean en = s.enabled();
        v.setEnabled(en);
        v.setAlpha(en ? 1f : 0.45f);
        updating = true;
        try {
            if (v instanceof GridControlView) {
                ((GridControlView) v).updateMetrics();
                v.invalidate();
            } else if (s.type == ControlSpec.Type.TEXTBOX) {
                EditText e = (EditText) v;
                int type = InputType.TYPE_CLASS_TEXT | (s.multiline() ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0);
                if (e.getInputType() != type) {
                    e.setInputType(type);
                    e.setSingleLine(!s.multiline());
                    e.setGravity(s.multiline() ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
                    e.setImeOptions(s.multiline() ? EditorInfo.IME_ACTION_NONE : EditorInfo.IME_ACTION_DONE);
                }
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSize());
                String t = s.text();
                if (!t.contentEquals(e.getText())) {
                    e.setText(t);
                    e.setSelection(e.getText().length());
                }
            } else if (s.type == ControlSpec.Type.COMBOBOX) {
                TextView t = (TextView) v;
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSize());
                String cur = s.text();
                t.setText(act.getString(R.string.combo_display, cur.isEmpty() ? act.getString(R.string.combo_none) : cur));
            } else {
                TextView t = (TextView) v;
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSize());
                t.setText(s.text());
            }
        } finally {
            updating = false;
        }
    }

    private void updateAreaHeight() {
        int max = 0;
        for (ControlState s : views.keySet()) {
            if (!s.visible()) continue;
            int[] b = s.bounds();
            max = Math.max(max, b[1] + b[3]);
        }
        area.setMinimumHeight(px(max + 24));
    }

    private int px(int dp) { return Math.round(dp * density); }
}
