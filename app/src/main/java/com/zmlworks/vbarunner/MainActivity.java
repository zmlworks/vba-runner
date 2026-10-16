package com.zmlworks.vbarunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements ScriptHost.Listener {
    private static final int REQ_EXPORT_JS = 1, REQ_IMPORT_JS = 2, REQ_EXPORT_CSV = 3, REQ_IMPORT_CSV = 4;
    private static final int IMM_MAX_CHARS = 64_000;

    private final SheetModel sheet = new SheetModel();
    private ScriptHost host;
    private CodeEditor editor;
    private SheetView sheetView;
    private TextView title, imm, cellAddr;
    private EditText cellEdit;
    private ScrollView immScroll;
    private Button runBtn, stopBtn;
    private View[] panes;
    private TextView[] tabs;
    private String scriptName;   // null = untitled
    private String savedText = "";
    private long startedAt;
    private final SpannableStringBuilder immText = new SpannableStringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        title = findViewById(R.id.title);
        editor = findViewById(R.id.editor);
        sheetView = findViewById(R.id.sheet);
        imm = findViewById(R.id.imm);
        immScroll = findViewById(R.id.imm_scroll);
        cellAddr = findViewById(R.id.cell_addr);
        cellEdit = findViewById(R.id.cell_edit);
        runBtn = findViewById(R.id.run);
        stopBtn = findViewById(R.id.stop);

        panes = new View[]{findViewById(R.id.pane_code), findViewById(R.id.pane_sheet), findViewById(R.id.pane_form)};
        tabs = new TextView[]{findViewById(R.id.tab_code), findViewById(R.id.tab_sheet), findViewById(R.id.tab_form)};
        for (int i = 0; i < tabs.length; i++) { final int k = i; tabs[i].setOnClickListener(v -> showTab(k)); }
        showTab(0);

        host = new ScriptHost(this, sheet, findViewById(R.id.web_holder), findViewById(R.id.form), readAsset("vba.js"), this);
        runBtn.setOnClickListener(v -> runScript());
        stopBtn.setOnClickListener(v -> host.stop(true));
        findViewById(R.id.menu).setOnClickListener(this::showMenu);
        findViewById(R.id.imm_clear).setOnClickListener(v -> { immText.clear(); imm.setText(""); });

        sheetView.setModel(sheet);
        sheetView.setOnSelect((r, c) -> {
            cellAddr.setText(SheetModel.colName(c) + r);
            cellEdit.setText(sheet.get(r, c));
        });
        sheetView.select(1, 1);
        cellEdit.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { commitCell(); return true; }
            return false;
        });
        findViewById(R.id.cell_ok).setOnClickListener(v -> commitCell());

        buildKeys();
        restoreAutosave();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) { setScript(null, t.toString()); savedText = ""; showTab(0); }
        }
    }

    @Override protected void onPause() { super.onPause(); autosave(); }
    @Override protected void onDestroy() { host.stop(false); super.onDestroy(); }

    // ---------------- UI ----------------
    private void showTab(int k) {
        for (int i = 0; i < panes.length; i++) {
            panes[i].setVisibility(i == k ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == k ? Ui.HI : Ui.DIM);
            tabs[i].setBackgroundColor(i == k ? Ui.PANEL : 0);
        }
    }

    private void buildKeys() {
        LinearLayout keys = findViewById(R.id.keys);
        String[] ks = {"⇥", "(", ")", "{", "}", "[", "]", "\"", "'", ";", "=", ".", ",", "+", "-", "*", "/", "<", ">", "!", "&", "|", "Cells(", ".Value", "Range(\"", "Debug.Print(", "MsgBox("};
        for (String k : ks) {
            TextView t = new TextView(this);
            t.setText(k);
            t.setTextColor(Ui.TEXT);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            int p = Ui.dp(this, 10);
            t.setPadding(p, p, p, p);
            t.setOnClickListener(v -> editor.insertAtCursor(k.equals("⇥") ? "  " : k));
            keys.addView(t);
        }
    }

    private void commitCell() {
        sheet.set(sheetView.selRow(), sheetView.selCol(), cellEdit.getText().toString());
        int r = sheetView.selRow();
        if (r < SheetModel.ROWS) sheetView.select(r + 1, sheetView.selCol()); else sheetView.invalidate();
    }

    private void updateTitle() {
        String n = scriptName == null ? getString(R.string.untitled) : scriptName;
        title.setText((isDirty() ? "● " : "") + n);
    }

    private boolean isDirty() { return !editor.getText().toString().equals(savedText); }

    private void setScript(String name, String text) {
        scriptName = name;
        editor.setText(text);
        savedText = text;
        updateTitle();
    }

    // ---------------- running ----------------
    private void runScript() {
        autosave();
        host.run(editor.getText().toString());
    }

    @Override public void onBegin() {
        startedAt = SystemClock.elapsedRealtime();
        runBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        stopBtn.setBackgroundColor(Ui.ACCENT);
    }

    @Override public void onDone(String error) {
        runBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        stopBtn.setBackgroundResource(R.drawable.btn_bg);
        sheetView.invalidate();
        if (error == null || error.isEmpty()) {
            appendImm(getString(R.string.done_ok, (int) (SystemClock.elapsedRealtime() - startedAt)), Ui.DIM);
        } else {
            appendImm(getString(R.string.error_prefix, error), 0xFFFF6B6B);
            Matcher m = Pattern.compile("行 (\\d+)").matcher(error);
            if (m.find()) { showTab(0); editor.goToLine(Integer.parseInt(m.group(1))); }
        }
    }

    @Override public void onPrint(String line, boolean error) { appendImm(line, error ? 0xFFFF6B6B : Ui.TEXT); }

    @Override public void onSheetChanged() {
        sheetView.invalidate();
        cellEdit.setText(sheet.get(sheetView.selRow(), sheetView.selCol()));
    }

    @Override public void onShowForm() { showTab(2); }

    private void appendImm(String s, int color) {
        int st = immText.length();
        immText.append(s).append('\n');
        immText.setSpan(new ForegroundColorSpan(color), st, immText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (immText.length() > IMM_MAX_CHARS) immText.delete(0, immText.length() - IMM_MAX_CHARS * 3 / 4);
        imm.setText(immText);
        immScroll.post(() -> immScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------- storage (app-private) ----------------
    private File dir(String name) {
        File d = new File(getFilesDir(), name);
        if (!d.isDirectory() && !d.mkdirs()) toast(getString(R.string.io_error, d.getPath()));
        return d;
    }

    private static String safeName(String n) {
        n = n.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return n.isEmpty() ? "noname" : n;
    }

    private static void writeFile(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new IOException("rename " + f);
    }

    private static String readFile(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) { return readAll(in); } catch (IOException e) { throw new RuntimeException(e); }
    }

    private void autosave() {
        try {
            File d = dir("state");
            writeFile(new File(d, "editor.js"), editor.getText().toString());
            writeFile(new File(d, "sheet.csv"), sheet.toCsv());
            getSharedPreferences("state", MODE_PRIVATE).edit()
                    .putString("name", scriptName).putString("saved", savedText).apply();
        } catch (IOException e) { toast(getString(R.string.io_error, e.getMessage())); }
    }

    private void restoreAutosave() {
        File d = dir("state"), js = new File(d, "editor.js");
        try {
            if (js.exists()) {
                scriptName = getSharedPreferences("state", MODE_PRIVATE).getString("name", null);
                editor.setText(readFile(js));
                savedText = getSharedPreferences("state", MODE_PRIVATE).getString("saved", "");
                File csv = new File(d, "sheet.csv");
                if (csv.exists()) sheet.fromCsv(readFile(csv));
            } else {
                setScript(null, readAsset("samples/01_kuku.js"));
                savedText = "";
            }
        } catch (IOException e) { toast(getString(R.string.io_error, e.getMessage())); }
        editor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { updateTitle(); }
        });
        updateTitle();
        sheetView.invalidate();
    }

    private void saveScript(String name) {
        try {
            name = safeName(name);
            writeFile(new File(dir("scripts"), name + ".js"), editor.getText().toString());
            scriptName = name;
            savedText = editor.getText().toString();
            updateTitle();
            toast(getString(R.string.saved, name));
        } catch (IOException e) { toast(getString(R.string.io_error, e.getMessage())); }
    }

    private void askName(String def, java.util.function.Consumer<String> then) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(def);
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, 0, p, 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(R.string.name_prompt).setView(box)
                .setPositiveButton("OK", (d, w) -> then.accept(et.getText().toString()))
                .setNegativeButton(R.string.btn_cancel, null).show();
    }

    private String[] listNames(String dirName, String ext) {
        String[] fs = dir(dirName).list((d, n) -> n.endsWith(ext));
        if (fs == null) fs = new String[0];
        Arrays.sort(fs, String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < fs.length; i++) fs[i] = fs[i].substring(0, fs[i].length() - ext.length());
        return fs;
    }

    private void pick(String dirName, String ext, java.util.function.Consumer<String> then) {
        String[] names = listNames(dirName, ext);
        if (names.length == 0) { toast(getString(R.string.nothing_saved)); return; }
        new AlertDialog.Builder(this).setItems(names, (d, w) -> then.accept(names[w])).show();
    }

    private void confirmDiscard(Runnable r) {
        if (!isDirty()) { r.run(); return; }
        new AlertDialog.Builder(this).setMessage(R.string.confirm_discard)
                .setPositiveButton(R.string.btn_yes, (d, w) -> r.run()).setNegativeButton(R.string.btn_no, null).show();
    }

    // ---------------- menu ----------------
    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        int[] items = {R.string.m_new, R.string.m_open, R.string.m_save, R.string.m_save_as, R.string.m_samples,
                R.string.m_export, R.string.m_import, R.string.m_share,
                R.string.m_sheet_save, R.string.m_sheet_open, R.string.m_sheet_export, R.string.m_sheet_import,
                R.string.m_sheet_share, R.string.m_sheet_clear, R.string.m_delete, R.string.m_help};
        for (int id : items) m.add(Menu.NONE, id, Menu.NONE, id);
        pm.setOnMenuItemClickListener(it -> { onMenu(it.getItemId()); return true; });
        pm.show();
    }

    private void onMenu(int id) {
        if (id == R.string.m_new) confirmDiscard(() -> setScript(null, "function Main() {\n  \n}\n"));
        else if (id == R.string.m_open) pick("scripts", ".js", n -> confirmDiscard(() -> {
            try { setScript(n, readFile(new File(dir("scripts"), n + ".js"))); showTab(0); }
            catch (IOException e) { toast(getString(R.string.io_error, e.getMessage())); }
        }));
        else if (id == R.string.m_save) { if (scriptName == null) askName("", this::saveScript); else saveScript(scriptName); }
        else if (id == R.string.m_save_as) askName(scriptName == null ? "" : scriptName, this::saveScript);
        else if (id == R.string.m_samples) {
            try {
                String[] s = getAssets().list("samples");
                if (s == null) return;
                Arrays.sort(s);
                new AlertDialog.Builder(this).setItems(s, (d, w) -> confirmDiscard(() -> {
                    setScript(null, readAsset("samples/" + s[w]));
                    savedText = "";
                    updateTitle();
                    showTab(0);
                })).show();
            } catch (IOException e) { toast(e.getMessage()); }
        }
        else if (id == R.string.m_export) createDoc((scriptName == null ? "macro" : scriptName) + ".js", "text/javascript", REQ_EXPORT_JS);
        else if (id == R.string.m_import) openDoc(REQ_IMPORT_JS);
        else if (id == R.string.m_share) shareText(editor.getText().toString(), scriptName == null ? "macro.js" : scriptName + ".js");
        else if (id == R.string.m_sheet_save) askName("", n -> {
            try { writeFile(new File(dir("sheets"), safeName(n) + ".csv"), sheet.toCsv()); toast(getString(R.string.saved, safeName(n))); }
            catch (IOException e) { toast(getString(R.string.io_error, e.getMessage())); }
        });
        else if (id == R.string.m_sheet_open) pick("sheets", ".csv", n -> {
            try { sheet.fromCsv(readFile(new File(dir("sheets"), n + ".csv"))); onSheetChanged(); showTab(1); }
            catch (IOException e) { toast(getString(R.string.io_error, e.getMessage())); }
        });
        else if (id == R.string.m_sheet_export) createDoc("sheet.csv", "text/csv", REQ_EXPORT_CSV);
        else if (id == R.string.m_sheet_import) openDoc(REQ_IMPORT_CSV);
        else if (id == R.string.m_sheet_share) shareText(sheet.toCsv(), "sheet.csv");
        else if (id == R.string.m_sheet_clear) { sheet.clear(); onSheetChanged(); }
        else if (id == R.string.m_delete) deleteSaved();
        else if (id == R.string.m_help) {
            TextView tv = new TextView(this);
            tv.setText(R.string.help_text);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(12);
            tv.setTextIsSelectable(true);
            int p = Ui.dp(this, 16);
            tv.setPadding(p, p, p, p);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this).setTitle(R.string.help_title).setView(sv).setPositiveButton("OK", null).show();
        }
    }

    private void deleteSaved() {
        String[] js = listNames("scripts", ".js"), csv = listNames("sheets", ".csv");
        String[] all = new String[js.length + csv.length];
        for (int i = 0; i < js.length; i++) all[i] = js[i] + ".js";
        for (int i = 0; i < csv.length; i++) all[js.length + i] = csv[i] + ".csv";
        if (all.length == 0) { toast(getString(R.string.nothing_saved)); return; }
        new AlertDialog.Builder(this).setTitle(R.string.m_delete).setItems(all, (d, w) -> {
            File f = new File(dir(all[w].endsWith(".js") ? "scripts" : "sheets"), all[w]);
            if (f.delete()) toast(getString(R.string.deleted, all[w]));
        }).show();
    }

    // ---------------- SAF / share sheet ----------------
    private void createDoc(String name, String mime, int req) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, req);
    }

    private void openDoc(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, req);
    }

    private void shareText(String text, String subject) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject);
        startActivity(Intent.createChooser(i, subject));
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (req == REQ_EXPORT_JS || req == REQ_EXPORT_CSV) {
                String s = req == REQ_EXPORT_JS ? editor.getText().toString() : sheet.toCsv();
                try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
                    if (o == null) throw new IOException(u.toString());
                    o.write(s.getBytes(StandardCharsets.UTF_8));
                }
                toast(getString(R.string.saved, u.getLastPathSegment()));
            } else {
                String s;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) throw new IOException(u.toString());
                    s = readAll(in);
                }
                if (s.startsWith("\uFEFF")) s = s.substring(1);  // Excel-style BOM
                if (req == REQ_IMPORT_JS) { final String t = s; confirmDiscard(() -> { setScript(null, t); savedText = ""; updateTitle(); showTab(0); }); }
                else { sheet.fromCsv(s); onSheetChanged(); showTab(1); }
                toast(getString(R.string.imported));
            }
        } catch (IOException | SecurityException e) { toast(getString(R.string.io_error, e.getMessage())); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
