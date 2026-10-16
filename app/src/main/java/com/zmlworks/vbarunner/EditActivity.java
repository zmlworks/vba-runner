package com.zmlworks.vbarunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.vbarunner.core.ControlSpec;
import com.zmlworks.vbarunner.core.ScriptDoc;
import com.zmlworks.vbarunner.vba.Msg;
import com.zmlworks.vbarunner.vba.Parser;
import com.zmlworks.vbarunner.vba.VbaError;

import java.io.IOException;

/** New / edit screen with two tabs: 画面作成 (form designer) and コード. Saves on leave. */
public final class EditActivity extends Activity implements DesignSurface.Listener {
    static final String EXTRA_ID = "id", EXTRA_LINE = "line", EXTRA_TAB = "tab";

    private ScriptStore store;
    private String id;
    private ScriptDoc doc;
    private TextView title, tabDesign, tabCode, selInfo, parseStatus;
    private LinearLayout designPage, codePage, selBar;
    private DesignSurface surface;
    private CodeEditor editor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable parseCheck = this::checkSyntax;
    private int errorLine;
    private boolean dirty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Msg.japanese = Ui.japanese();
        store = new ScriptStore(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        try {
            doc = store.load(id);
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, getString(R.string.io_error, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(Ui.BG);
        title = Ui.topBar(this, root, true,
                Ui.barButton(this, "▶", getString(R.string.run), v -> run()),
                Ui.barButton(this, "⋮", getString(R.string.menu), this::showMenu));
        title.setOnClickListener(v -> rename());
        updateTitle();

        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(Ui.BG2);
        tabDesign = tab(getString(R.string.tab_design), v -> showTab(false));
        tabCode = tab(getString(R.string.tab_code), v -> showTab(true));
        tabs.addView(tabDesign, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        tabs.addView(tabCode, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        root.addView(tabs, Ui.matchWrap());

        buildDesignPage();
        buildCodePage();
        root.addView(designPage, Ui.fill());
        root.addView(codePage, Ui.fill());

        setContentView(root);
        Ui.edgeToEdge(this, root);

        int line = getIntent().getIntExtra(EXTRA_LINE, 0);
        boolean code = line > 0 || "code".equals(getIntent().getStringExtra(EXTRA_TAB))
                || (b != null && b.getBoolean("codeTab"));
        showTab(code);
        if (line > 0) {
            final int l = line;
            editor.post(() -> { editor.goToLine(l); editor.setErrorLine(l); });
        }
        checkSyntax();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("codeTab", codePage.getVisibility() == View.VISIBLE);
    }

    private TextView tab(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bar_btn_bg);
        t.setOnClickListener(l);
        return t;
    }

    private void showTab(boolean code) {
        designPage.setVisibility(code ? View.GONE : View.VISIBLE);
        codePage.setVisibility(code ? View.VISIBLE : View.GONE);
        tabDesign.setTextColor(code ? Ui.DIM : Ui.HI);
        tabCode.setTextColor(code ? Ui.HI : Ui.DIM);
        tabDesign.setBackgroundColor(code ? Ui.BG2 : Ui.BG);
        tabCode.setBackgroundColor(code ? Ui.BG : Ui.BG2);
        if (!code) {
            syncCodeFromEditor();
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && editor != null) im.hideSoftInputFromWindow(editor.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ designer
    private void buildDesignPage() {
        designPage = Ui.vertical(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout add = new LinearLayout(this);
        add.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        addBtn(add, R.string.add_label, ControlSpec.Type.LABEL);
        addBtn(add, R.string.add_button, ControlSpec.Type.BUTTON);
        addBtn(add, R.string.add_textbox, ControlSpec.Type.TEXTBOX);
        addBtn(add, R.string.add_combobox, ControlSpec.Type.COMBOBOX);
        addBtn(add, R.string.add_grid, ControlSpec.Type.GRID);
        hs.addView(add);
        designPage.addView(hs, Ui.matchWrap());

        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setBackgroundColor(Ui.BG2);
        selBar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        selInfo = Ui.text(this, "", 13, Ui.TEXT);
        selInfo.setSingleLine(true);
        selInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selBar.addView(selInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        selBar.addView(Ui.barButton(this, getString(R.string.props), getString(R.string.props), v -> editProps(surface.selected())));
        selBar.addView(Ui.barButton(this, getString(R.string.event), getString(R.string.event), v -> writeEvent(surface.selected())));
        selBar.addView(Ui.barButton(this, "🗑", getString(R.string.delete), v -> deleteSelected()));
        designPage.addView(selBar, Ui.matchWrap());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        surface = new DesignSurface(this);
        surface.setControls(doc.controls);
        surface.setListener(this);
        sv.addView(surface, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        designPage.addView(sv, Ui.fill());
        onSelect(null);
    }

    private void addBtn(LinearLayout row, int label, ControlSpec.Type t) {
        android.widget.Button b = Ui.button(this, getString(label), v -> addControl(t));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        row.addView(b, lp);
    }

    private void addControl(ControlSpec.Type t) {
        ControlSpec c = new ControlSpec(t, doc.nextName(t));
        int width = surface.widthDp();
        int bottom = 0;
        for (ControlSpec o : doc.controls) bottom = Math.max(bottom, o.y + o.h);
        c.x = 16;
        c.y = doc.controls.isEmpty() ? 16 : bottom + 12;
        c.y = Math.round(c.y / 4f) * 4;
        c.w = Math.min(c.w, width - 32);
        if (t == ControlSpec.Type.GRID) c.w = width - 32;
        if (t == ControlSpec.Type.COMBOBOX) { c.items.add(getString(R.string.sample_item, 1)); c.items.add(getString(R.string.sample_item, 2)); }
        doc.controls.add(c);
        dirty = true;
        surface.setControls(doc.controls);
        surface.select(c);
    }

    @Override public void onSelect(ControlSpec c) {
        if (c == null) {
            selInfo.setText(doc.controls.isEmpty() ? R.string.design_hint_empty : R.string.design_hint);
            for (int i = 1; i < selBar.getChildCount(); i++) selBar.getChildAt(i).setVisibility(View.GONE);
        } else {
            for (int i = 1; i < selBar.getChildCount(); i++) selBar.getChildAt(i).setVisibility(View.VISIBLE);
            onMoved(c);
        }
    }

    @Override public void onMoved(ControlSpec c) {
        dirty = true;
        selInfo.setText(getString(R.string.sel_info, c.name, c.x, c.y, c.w, c.h));
    }

    @Override public void onTapSelected(ControlSpec c) { editProps(c); }

    private void deleteSelected() {
        final ControlSpec c = surface.selected();
        if (c == null) return;
        new AlertDialog.Builder(this).setMessage(getString(R.string.delete_control_confirm, c.name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    doc.controls.remove(c);
                    dirty = true;
                    surface.select(null);
                    surface.setControls(doc.controls);
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private EditText field(LinearLayout form, int label, String value, int inputType) {
        TextView l = Ui.text(this, getString(label), 13, Ui.DIM);
        l.setPadding(0, Ui.dp(this, 8), 0, 0);
        form.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextColor(Ui.TEXT);
        form.addView(e, Ui.matchWrap());
        return e;
    }

    private void editProps(final ControlSpec c) {
        if (c == null) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout form = Ui.vertical(this);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        sv.addView(form);
        final EditText name = field(form, R.string.p_name, c.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText caption = null, items = null, rows = null, cols = null;
        CheckBox multi = null;
        switch (c.type) {
            case LABEL: case BUTTON:
                caption = field(form, R.string.p_caption, c.caption, InputType.TYPE_CLASS_TEXT);
                break;
            case TEXTBOX:
                caption = field(form, R.string.p_text, c.caption, InputType.TYPE_CLASS_TEXT);
                multi = new CheckBox(this);
                multi.setText(R.string.p_multiline);
                multi.setChecked(c.multiline);
                form.addView(multi);
                break;
            case COMBOBOX:
                items = field(form, R.string.p_items, String.join("\n", c.items), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                items.setMinLines(3);
                caption = field(form, R.string.p_combo_initial, c.caption, InputType.TYPE_CLASS_TEXT);
                break;
            case GRID:
                rows = field(form, R.string.p_rows, String.valueOf(c.rows), InputType.TYPE_CLASS_NUMBER);
                cols = field(form, R.string.p_cols, String.valueOf(c.cols), InputType.TYPE_CLASS_NUMBER);
                break;
        }
        final EditText font = field(form, R.string.p_font, String.valueOf(c.fontSize), InputType.TYPE_CLASS_NUMBER);
        final EditText geo = field(form, R.string.p_geometry, c.x + ", " + c.y + ", " + c.w + ", " + c.h, InputType.TYPE_CLASS_TEXT);
        TextView help = Ui.text(this, getString(R.string.p_code_hint, propHint(c)), 12, Ui.DIM);
        help.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        help.setTextIsSelectable(true);
        form.addView(help);
        final EditText fCaption = caption, fItems = items, fRows = rows, fCols = cols;
        final CheckBox fMulti = multi;
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(c.type.vbName + " " + c.name)
                .setView(sv)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nn = name.getText().toString().trim();
            if (!ControlSpec.validName(nn)) { name.setError(getString(R.string.bad_name)); return; }
            ControlSpec other = doc.find(nn);
            if (other != null && other != c) { name.setError(getString(R.string.dup_name)); return; }
            if (!nn.equals(c.name)) {
                syncCodeFromEditor();
                String renamed = ScriptDoc.renameIdentifier(doc.code, c.name, nn);
                if (!renamed.equals(doc.code)) {
                    doc.code = renamed;
                    editor.setText(renamed);
                    Toast.makeText(this, getString(R.string.renamed_in_code, c.name, nn), Toast.LENGTH_SHORT).show();
                }
                c.name = nn;
            }
            if (fCaption != null) c.caption = fCaption.getText().toString().replace("\n", " ");
            if (fMulti != null) c.multiline = fMulti.isChecked();
            if (fItems != null) {
                c.items.clear();
                for (String s : fItems.getText().toString().split("\n")) {
                    String t = s.trim().replace("|", "/");
                    if (!t.isEmpty()) c.items.add(t);
                }
                if (!c.caption.isEmpty() && !c.items.contains(c.caption)) c.caption = "";
            }
            if (fRows != null) c.rows = clampInt(fRows.getText().toString(), 1, 500, c.rows);
            if (fCols != null) c.cols = clampInt(fCols.getText().toString(), 1, 50, c.cols);
            c.fontSize = clampInt(font.getText().toString(), 8, 64, c.fontSize);
            String[] g = geo.getText().toString().split("[,\\s]+");
            if (g.length == 4) {
                c.x = clampInt(g[0], 0, 2000, c.x);
                c.y = clampInt(g[1], 0, 20000, c.y);
                c.w = clampInt(g[2], DesignSurface.MIN, 2000, c.w);
                c.h = clampInt(g[3], DesignSurface.MIN, 5000, c.h);
            }
            dirty = true;
            surface.setControls(doc.controls);
            onSelect(c);
            dlg.dismiss();
        }));
        dlg.show();
    }

    private String propHint(ControlSpec c) {
        switch (c.type) {
            case LABEL: return c.name + ".Caption";
            case BUTTON: return c.name + ".Caption, " + c.name + "_Click";
            case TEXTBOX: return c.name + ".Value / .Text, " + c.name + "_Change";
            case COMBOBOX: return c.name + ".Text, .ListIndex, .AddItem, " + c.name + "_Change";
            default: return c.name + ".Cells(r, c), .Rows, .Cols, .Clear, " + c.name + "_Click(Row, Col)";
        }
    }

    private static int clampInt(String s, int lo, int hi, int def) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim()))); } catch (NumberFormatException e) { return def; }
    }

    /** Insert (or jump to) the event Sub for the selected control and switch to the code tab. */
    private void writeEvent(ControlSpec c) {
        if (c == null) return;
        syncCodeFromEditor();
        String ev = c.events()[0];
        String sub = c.name + "_" + ev;
        String code = doc.code;
        if (!ScriptDoc.hasSub(code, sub)) {
            StringBuilder b = new StringBuilder(code);
            if (b.length() > 0 && b.charAt(b.length() - 1) != '\n') b.append('\n');
            if (b.length() > 0) b.append('\n');
            b.append("Sub ").append(c.eventSignature(ev)).append("\n    \nEnd Sub\n");
            code = b.toString();
            doc.code = code;
            editor.setText(code);
            dirty = true;
        }
        final int line = ScriptDoc.subLine(code, sub) + 2;
        showTab(true);
        editor.post(() -> {
            editor.goToLine(line);
            int pos = editor.getSelectionStart();
            String t = editor.getText().toString();
            int end = t.indexOf('\n', pos);
            editor.setSelection(end < 0 ? t.length() : end);
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(editor, 0);
        });
    }

    // ------------------------------------------------------------------ code tab
    private void buildCodePage() {
        codePage = Ui.vertical(this);
        editor = new CodeEditor(this, null);
        editor.setTextSize(14);
        editor.setTextColor(Ui.TEXT);
        editor.setBackgroundColor(Ui.BG);
        editor.setText(doc.code);
        editor.setHint(R.string.code_hint);
        editor.setHintTextColor(Ui.DIM);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                dirty = true;
                handler.removeCallbacks(parseCheck);
                handler.postDelayed(parseCheck, 600);
            }
        });
        codePage.addView(editor, Ui.fill());

        parseStatus = Ui.text(this, "", 13, Ui.DIM);
        parseStatus.setBackgroundColor(Ui.BG2);
        parseStatus.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        parseStatus.setOnClickListener(v -> { if (errorLine > 0) editor.goToLine(errorLine); });
        codePage.addView(parseStatus, Ui.matchWrap());

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(Ui.BG2);
        LinearLayout keys = new LinearLayout(this);
        String[] ks = {"⇥", "\"", "(", ")", "=", "&", ".", ",", "'", "_", ":", "<", ">", "+", "-", "*", "/"};
        for (final String k : ks) {
            TextView t = Ui.barButton(this, k, k.equals("⇥") ? "Tab" : k, v -> editor.insertAtCursor(k.equals("⇥") ? "    " : k));
            t.setTextSize(17);
            keys.addView(t);
        }
        String[] snippets = {"If", "For", "Do", "Sub", "MsgBox", "Dim"};
        for (final String s : snippets) {
            TextView t = Ui.barButton(this, s, s, v -> insertSnippet(s));
            t.setTextSize(14);
            t.setTextColor(Ui.HI);
            keys.addView(t);
        }
        hs.addView(keys);
        codePage.addView(hs, Ui.matchWrap());
    }

    private void insertSnippet(String s) {
        String ind = editor.currentIndent();
        switch (s) {
            case "If": editor.insertTemplate("If ", " Then\n" + ind + "    \n" + ind + "End If"); break;
            case "For": editor.insertTemplate("For i = 1 To ", "\n" + ind + "    \n" + ind + "Next i"); break;
            case "Do": editor.insertTemplate("Do While ", "\n" + ind + "    \n" + ind + "Loop"); break;
            case "Sub": editor.insertTemplate("Sub ", "()\n" + ind + "    \n" + ind + "End Sub"); break;
            case "MsgBox": editor.insertTemplate("MsgBox \"", "\""); break;
            default: editor.insertTemplate("Dim ", " As Long"); break;
        }
    }

    private void checkSyntax() {
        if (editor == null) return;
        String code = editor.getText().toString();
        try {
            Parser.parse(code);
            errorLine = 0;
            editor.setErrorLine(0);
            parseStatus.setTextColor(Ui.DIM);
            parseStatus.setText(code.trim().isEmpty() ? getString(R.string.parse_empty) : getString(R.string.parse_ok));
        } catch (VbaError e) {
            errorLine = e.line;
            editor.setErrorLine(e.line);
            parseStatus.setTextColor(Ui.ERR);
            parseStatus.setText("✗ " + e.display());
        }
    }

    private void syncCodeFromEditor() {
        if (editor != null) doc.code = editor.getText().toString().replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------ save / actions
    private void updateTitle() { title.setText(doc.name.isEmpty() ? getString(R.string.untitled) : doc.name); }

    private boolean save() {
        syncCodeFromEditor();
        try {
            store.save(id, doc);
            dirty = false;
            return true;
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.io_error, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void run() {
        if (!save()) return;
        startActivity(new Intent(this, RunActivity.class).putExtra(RunActivity.EXTRA_ID, id));
        finish();
    }

    private void rename() {
        final EditText e = new EditText(this);
        e.setText(doc.name);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        LinearLayout wrap = Ui.vertical(this);
        wrap.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        wrap.addView(e, Ui.matchWrap());
        new AlertDialog.Builder(this).setTitle(R.string.rename).setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) { doc.name = n; dirty = true; updateTitle(); save(); }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.save);
        m.getMenu().add(0, 2, 0, R.string.rename);
        m.getMenu().add(0, 3, 0, R.string.share);
        m.getMenu().add(0, 4, 0, R.string.help);
        m.getMenu().add(0, 5, 0, R.string.copy_ai);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: if (save()) Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show(); return true;
                case 2: rename(); return true;
                case 3: save(); MainActivity.share(this, doc); return true;
                case 4: startActivity(new Intent(this, HelpActivity.class)); return true;
                case 5: HelpActivity.copyAiGuide(this, doc); return true;
                default: return false;
            }
        });
        m.show();
    }

    @Override protected void onPause() {
        super.onPause();
        if (dirty) save();
    }

    @Override public void onBackPressed() {
        save();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(parseCheck);
        super.onDestroy();
    }
}
