package com.zmlworks.vbarunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.vbarunner.core.ScriptDoc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Launch screen: list of scripts (tap = run, long-press = edit/rename/duplicate/export/delete) + new/import. */
public final class MainActivity extends Activity {
    private static final int REQ_IMPORT = 1, REQ_EXPORT = 2;

    private ScriptStore store;
    private LinearLayout list;
    private String exportId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ScriptStore(this);
        if (b != null) exportId = b.getString("exportId");
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(Ui.BG);
        TextView title = Ui.topBar(this, root, false,
                Ui.barButton(this, "＋", getString(R.string.new_script), v -> newScript()),
                Ui.barButton(this, "?", getString(R.string.help), v -> startActivity(new Intent(this, HelpActivity.class))),
                Ui.barButton(this, "⋮", getString(R.string.menu), this::showMenu));
        title.setText(R.string.app_name);
        ScrollView sv = new ScrollView(this);
        list = Ui.vertical(this);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        sv.addView(list);
        root.addView(sv, Ui.fill());
        setContentView(root);
        Ui.edgeToEdge(this, root);
        store.installSamples(false);
        handleShare(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("exportId", exportId);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleShare(i);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<ScriptStore.Entry> es = store.list();
        if (es.isEmpty()) {
            TextView t = Ui.text(this, getString(R.string.empty_list), 15, Ui.DIM);
            t.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), 0);
            list.addView(t);
            return;
        }
        java.text.DateFormat df = DateFormat.getMediumDateFormat(this), tf = DateFormat.getTimeFormat(this);
        for (final ScriptStore.Entry e : es) {
            LinearLayout row = Ui.vertical(this);
            row.setBackgroundResource(R.drawable.bar_btn_bg);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            row.setMinimumHeight(Ui.dp(this, 60));
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(this, "▶  " + e.name, 17, Ui.TEXT);
            row.addView(name);
            java.util.Date d = new java.util.Date(e.modified);
            String sub = (e.controls > 0 ? getString(R.string.n_controls, e.controls) : getString(R.string.code_only))
                    + " · " + df.format(d) + " " + tf.format(d);
            row.addView(Ui.text(this, sub, 12, Ui.DIM));
            row.setOnClickListener(v -> startActivity(new Intent(this, RunActivity.class).putExtra(RunActivity.EXTRA_ID, e.id)));
            row.setOnLongClickListener(v -> { actions(e); return true; });
            list.addView(row, Ui.matchWrap());
            View sep = new View(this);
            sep.setBackgroundColor(Ui.GRID);
            list.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));
        }
        TextView hint = Ui.text(this, getString(R.string.list_hint), 12, Ui.DIM);
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        list.addView(hint);
    }

    private void actions(final ScriptStore.Entry e) {
        String[] items = {getString(R.string.edit), getString(R.string.rename), getString(R.string.duplicate),
                getString(R.string.export_file), getString(R.string.share), getString(R.string.delete)};
        new AlertDialog.Builder(this).setTitle(e.name).setItems(items, (d, w) -> {
            switch (w) {
                case 0: startActivity(new Intent(this, EditActivity.class).putExtra(EditActivity.EXTRA_ID, e.id)); break;
                case 1: rename(e); break;
                case 2: duplicate(e); break;
                case 3: exportFile(e); break;
                case 4: try { share(this, store.load(e.id)); } catch (IOException ex) { ioError(ex); } break;
                default:
                    new AlertDialog.Builder(this).setMessage(getString(R.string.delete_confirm, e.name))
                            .setPositiveButton(R.string.delete, (d2, w2) -> { store.delete(e.id); refresh(); })
                            .setNegativeButton(R.string.cancel, null).show();
                    break;
            }
        }).show();
    }

    private void ioError(Exception ex) {
        Toast.makeText(this, getString(R.string.io_error, String.valueOf(ex.getMessage())), Toast.LENGTH_LONG).show();
    }

    private interface NameCallback { void onName(String n); }

    private void askName(int titleRes, String initial, NameCallback cb) {
        final EditText e = new EditText(this);
        e.setText(initial);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        LinearLayout wrap = Ui.vertical(this);
        wrap.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        wrap.addView(e, Ui.matchWrap());
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(titleRes).setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String n = e.getText().toString().trim();
                    cb.onName(n.isEmpty() ? initial : n);
                })
                .setNegativeButton(R.string.cancel, null).create();
        if (dlg.getWindow() != null) dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
        e.requestFocus();
    }

    private void newScript() {
        askName(R.string.new_script, getString(R.string.new_script_default), n -> {
            ScriptDoc d = new ScriptDoc();
            d.name = n;
            d.code = getString(R.string.new_code_template);
            try {
                String id = store.create(d);
                startActivity(new Intent(this, EditActivity.class).putExtra(EditActivity.EXTRA_ID, id));
            } catch (IOException ex) { ioError(ex); }
        });
    }

    private void rename(final ScriptStore.Entry e) {
        askName(R.string.rename, e.name, n -> {
            try {
                ScriptDoc d = store.load(e.id);
                d.name = n;
                store.save(e.id, d);
                refresh();
            } catch (IOException ex) { ioError(ex); }
        });
    }

    private void duplicate(ScriptStore.Entry e) {
        try {
            ScriptDoc d = store.load(e.id);
            d.name = getString(R.string.copy_of, d.name);
            store.create(d);
            refresh();
        } catch (IOException ex) { ioError(ex); }
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.paste_clipboard);
        m.getMenu().add(0, 2, 0, R.string.import_file);
        m.getMenu().add(0, 3, 0, R.string.add_samples);
        m.getMenu().add(0, 4, 0, R.string.copy_ai);
        m.getMenu().add(0, 5, 0, R.string.help);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: pasteClipboard(); return true;
                case 2:
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream"});
                    try { startActivityForResult(i, REQ_IMPORT); } catch (android.content.ActivityNotFoundException ex) { ioError(ex); }
                    return true;
                case 3: {
                    int n = store.installSamples(true);
                    Toast.makeText(this, getString(R.string.samples_added, n), Toast.LENGTH_SHORT).show();
                    refresh();
                    return true;
                }
                case 4: HelpActivity.copyAiGuide(this, null); return true;
                case 5: startActivity(new Intent(this, HelpActivity.class)); return true;
                default: return false;
            }
        });
        m.show();
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence t = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0)
            t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (t == null || t.toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        importText(t.toString(), null);
    }

    private void importText(String text, String fallbackName) {
        ScriptDoc d = ScriptDoc.fromPasted(text);
        if (d.code.trim().isEmpty() && d.controls.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_import, Toast.LENGTH_SHORT).show();
            return;
        }
        if (d.name.isEmpty()) d.name = fallbackName != null ? fallbackName : getString(R.string.imported_name);
        try {
            String id = store.create(d);
            refresh();
            Toast.makeText(this, getString(R.string.imported, d.name), Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, EditActivity.class).putExtra(EditActivity.EXTRA_ID, id).putExtra(EditActivity.EXTRA_TAB, "code"));
        } catch (IOException ex) { ioError(ex); }
    }

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t != null && !t.trim().isEmpty()) importText(t, null);
        else {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) importUri(u);
        }
        setIntent(new Intent(this, MainActivity.class));
    }

    private void exportFile(ScriptStore.Entry e) {
        exportId = e.id;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, e.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".bas");
        try { startActivityForResult(i, REQ_EXPORT); } catch (android.content.ActivityNotFoundException ex) { ioError(ex); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_IMPORT) importUri(u);
        else if (req == REQ_EXPORT && exportId != null) {
            try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
                if (o == null) throw new IOException("openOutputStream");
                o.write(store.load(exportId).serialize().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show();
            } catch (IOException | RuntimeException ex) { ioError(ex); }
        }
    }

    private void importUri(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // name is optional
        }
        if (name != null && name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("openInputStream");
            importText(ScriptStore.readAll(in), name);
        } catch (IOException | RuntimeException ex) { ioError(ex); }
    }

    static void share(Activity a, ScriptDoc d) {
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, d.name)
                .putExtra(Intent.EXTRA_TEXT, d.serialize());
        a.startActivity(Intent.createChooser(s, a.getString(R.string.share)));
    }

    static void copyText(Activity a, String label, String text) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }
}
