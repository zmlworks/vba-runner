package com.zmlworks.vbarunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.vbarunner.core.FormObject;
import com.zmlworks.vbarunner.core.ScriptDoc;
import com.zmlworks.vbarunner.core.ScriptRunner;
import com.zmlworks.vbarunner.vba.Msg;
import com.zmlworks.vbarunner.vba.VbaError;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run screen: fixed top bar (← name ✎ ■ ⋮); everything below belongs to the script's form. Runs immediately. */
public final class RunActivity extends Activity {
    static final String EXTRA_ID = "id";
    private static final int MAX_LOG_CHARS = 60_000;

    private ScriptStore store;
    private String id;
    private ScriptDoc doc;
    private ScriptRunner runner;
    private FormRenderer renderer;
    private TextView title, stopBtn, banner, logView;
    private LinearLayout logPane;
    private ScrollView logScroll;
    private FrameLayout area;
    private ScrollView areaScroll;
    private final SpannableStringBuilder log = new SpannableStringBuilder();
    private boolean destroyed;
    private AlertDialog openDialog;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Msg.japanese = Ui.japanese();
        store = new ScriptStore(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(Ui.BG);
        stopBtn = Ui.barButton(this, "■", getString(R.string.stop), v -> onStopOrRerun());
        title = Ui.topBar(this, root, true,
                Ui.barButton(this, "✎", getString(R.string.edit), v -> openEditor(0)),
                stopBtn,
                Ui.barButton(this, "⋮", getString(R.string.menu), this::showMenu));

        banner = Ui.text(this, "", 15, Ui.TEXT);
        banner.setBackgroundColor(Ui.ACCENT);
        banner.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> restart());
        root.addView(banner, Ui.matchWrap());

        areaScroll = new ScrollView(this);
        areaScroll.setFillViewport(true);
        area = new FrameLayout(this);
        area.setPadding(0, 0, 0, Ui.dp(this, 8));
        areaScroll.addView(area, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(areaScroll, Ui.fill());

        logPane = Ui.vertical(this);
        logPane.setBackgroundColor(Ui.BG2);
        LinearLayout logBar = new LinearLayout(this);
        logBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = Ui.text(this, getString(R.string.immediate), 13, Ui.DIM);
        lt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        logBar.addView(lt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        logBar.addView(Ui.barButton(this, getString(R.string.clear), getString(R.string.clear), v -> { log.clear(); logView.setText(""); }));
        logBar.addView(Ui.barButton(this, "×", getString(R.string.close), v -> logPane.setVisibility(View.GONE)));
        logPane.addView(logBar, Ui.matchWrap());
        logScroll = new ScrollView(this);
        logView = Ui.text(this, "", 13, Ui.TEXT);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        logScroll.addView(logView);
        logPane.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));
        logPane.setVisibility(View.GONE);
        root.addView(logPane, Ui.matchWrap());

        setContentView(root);
        Ui.edgeToEdge(this, root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        restart();
    }

    private void restart() {
        stopRunner();
        dismissDialog();
        try {
            doc = store.load(id);
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, getString(R.string.io_error, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String name = doc.name.isEmpty() ? getString(R.string.untitled) : doc.name;
        title.setText(name);
        banner.setVisibility(View.GONE);
        FormObject form = new FormObject(name, cap -> runOnUiThread(() -> { if (!destroyed) title.setText(cap); }));
        int wDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int hDp = Math.round(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
        form.setSize(wDp, hDp);
        Session session = new Session();
        runner = new ScriptRunner(doc, store.storage(id), form, this::runOnUiThread, session);
        session.mine = runner;
        renderer = new FormRenderer(this, area, runner);
        renderer.build(runner.controls());
        boolean codeOnly = doc.controls.isEmpty();
        // code-only script: the immediate pane is the main output and fills the screen
        areaScroll.setVisibility(codeOnly ? View.GONE : View.VISIBLE);
        logPane.setLayoutParams(codeOnly ? Ui.fill() : Ui.matchWrap());
        logScroll.setLayoutParams(codeOnly ? Ui.fill()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));
        logPane.setVisibility(codeOnly ? View.VISIBLE : View.GONE);
        runner.start();
    }

    private void stopRunner() {
        if (runner != null) runner.stop();
        if (renderer != null) renderer.detach();
    }

    private void onStopOrRerun() {
        ScriptRunner.State s = runner == null ? null : runner.state();
        if (s == ScriptRunner.State.RUNNING || s == ScriptRunner.State.WAITING) {
            runner.stop();
        } else {
            restart();
        }
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.rerun);
        m.getMenu().add(0, 2, 0, logPane.getVisibility() == View.VISIBLE ? R.string.hide_immediate : R.string.show_immediate);
        m.getMenu().add(0, 3, 0, R.string.edit);
        m.getMenu().add(0, 4, 0, R.string.clear_storage);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: restart(); return true;
                case 2: logPane.setVisibility(logPane.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); return true;
                case 3: openEditor(0); return true;
                case 4:
                    new AlertDialog.Builder(this).setMessage(R.string.clear_storage_confirm)
                            .setPositiveButton(R.string.delete, (d, w) -> { stopRunner(); store.clearStorage(id); restart(); })
                            .setNegativeButton(R.string.cancel, null).show();
                    return true;
                default: return false;
            }
        });
        m.show();
    }

    private void openEditor(int line) {
        stopRunner();
        Intent i = new Intent(this, EditActivity.class).putExtra(EditActivity.EXTRA_ID, id);
        if (line > 0) i.putExtra(EditActivity.EXTRA_LINE, line);
        startActivity(i);
        finish();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopRunner();
        dismissDialog();
        super.onDestroy();
    }

    private void dismissDialog() {
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        openDialog = null;
    }

    /** Callbacks of one runner; ignored once that runner has been replaced by a restart. */
    private final class Session implements ScriptRunner.Ui {
        ScriptRunner mine;
        private boolean current() { return !destroyed && mine == runner; }
        @Override public void onPrint(List<String> lines) { if (current()) print(lines); }
        @Override public void onState(ScriptRunner.State s) { if (current()) state(s); }
        @Override public void onError(VbaError e) { if (current()) error(e); }
        @Override public void showMsgBox(String p, int b, String t, ScriptRunner.Reply<Integer> r) {
            if (current()) msgBox(p, b, t, r); else r.answer(2);
        }
        @Override public void showInputBox(String p, String t, String d, ScriptRunner.Reply<String> r) {
            if (current()) inputBox(p, t, d, r); else r.answer(null);
        }
    }

    // ------------------------------------------------------------------ runner callbacks (main thread)
    private void print(List<String> lines) {
        if (destroyed) return;
        for (String l : lines) log.append(l).append('\n');
        trimLog();
        logView.setText(log);
        if (logPane.getVisibility() != View.VISIBLE) logPane.setVisibility(View.VISIBLE);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void trimLog() {
        if (log.length() > MAX_LOG_CHARS) {
            int cut = log.length() - MAX_LOG_CHARS;
            int nl = log.toString().indexOf('\n', cut);
            log.delete(0, nl > 0 ? nl + 1 : cut);
        }
    }

    private void state(ScriptRunner.State s) {
        if (destroyed) return;
        boolean active = s == ScriptRunner.State.RUNNING || s == ScriptRunner.State.WAITING;
        stopBtn.setText(active ? "■" : "▶");
        stopBtn.setContentDescription(getString(active ? R.string.stop : R.string.rerun));
        stopBtn.setTextColor(s == ScriptRunner.State.RUNNING ? Ui.HI : Ui.TEXT);
        switch (s) {
            case ENDED: showBanner(getString(R.string.banner_ended)); break;
            case STOPPED: showBanner(getString(R.string.banner_stopped)); dismissDialog(); break;
            case ERROR: showBanner(getString(R.string.banner_error)); break;
            default: banner.setVisibility(View.GONE); break;
        }
    }

    private void showBanner(String text) {
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
    }

    private void error(VbaError e) {
        if (destroyed) return;
        String msg = e.display();
        String src = sourceLine(e.line);
        int start = log.length();
        log.append("✗ ").append(msg).append('\n');
        if (src != null) log.append("    ").append(src.trim()).append('\n');
        log.setSpan(new ForegroundColorSpan(Ui.ERR), start, log.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        trimLog();
        logView.setText(log);
        logPane.setVisibility(View.VISIBLE);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        dismissDialog();
        AlertDialog.Builder bd = new AlertDialog.Builder(this)
                .setTitle(e.syntax ? R.string.syntax_error_title : R.string.runtime_error_title)
                .setMessage(msg + (src != null ? "\n\n» " + src.trim() : ""))
                .setPositiveButton(R.string.close, null);
        if (e.line > 0) bd.setNeutralButton(R.string.open_code_at_line, (d, w) -> openEditor(e.line));
        openDialog = bd.show();
    }

    private String sourceLine(int line) {
        if (line <= 0 || doc == null) return null;
        String[] ls = doc.code.split("\n", -1);
        return line <= ls.length ? ls[line - 1] : null;
    }

    private void msgBox(String prompt, int buttons, String t, ScriptRunner.Reply<Integer> reply) {
        final AtomicBoolean done = new AtomicBoolean();
        final ScriptRunner.Reply<Integer> once = v -> { if (done.compareAndSet(false, true)) reply.answer(v); };
        if (destroyed || isFinishing()) { once.answer(2); return; }
        int kind = buttons & 7, icon = buttons & 0x70;
        String prefix = icon == 16 ? "⛔ " : icon == 32 ? "❓ " : icon == 48 ? "⚠ " : icon == 64 ? "ℹ " : "";
        String ttl = prefix + (t == null || t.isEmpty() ? title.getText().toString() : t);
        AlertDialog.Builder bd = new AlertDialog.Builder(this).setTitle(ttl).setMessage(prompt);
        int cancelValue;
        switch (kind) {
            case 1: bd.setPositiveButton(R.string.ok, (d, w) -> once.answer(1)).setNegativeButton(R.string.cancel, (d, w) -> once.answer(2)); cancelValue = 2; break;
            case 2:
                bd.setNegativeButton(R.string.btn_abort, (d, w) -> once.answer(3)).setNeutralButton(R.string.btn_retry, (d, w) -> once.answer(4))
                        .setPositiveButton(R.string.btn_ignore, (d, w) -> once.answer(5));
                cancelValue = -1;
                break;
            case 3:
                bd.setPositiveButton(R.string.btn_yes, (d, w) -> once.answer(6)).setNegativeButton(R.string.btn_no, (d, w) -> once.answer(7))
                        .setNeutralButton(R.string.cancel, (d, w) -> once.answer(2));
                cancelValue = 2;
                break;
            case 4: bd.setPositiveButton(R.string.btn_yes, (d, w) -> once.answer(6)).setNegativeButton(R.string.btn_no, (d, w) -> once.answer(7)); cancelValue = -1; break;
            case 5: bd.setPositiveButton(R.string.btn_retry, (d, w) -> once.answer(4)).setNegativeButton(R.string.cancel, (d, w) -> once.answer(2)); cancelValue = 2; break;
            default: bd.setPositiveButton(R.string.ok, (d, w) -> once.answer(1)); cancelValue = 1; break;
        }
        bd.setCancelable(cancelValue > 0);
        final int cv = cancelValue;
        bd.setOnCancelListener(d -> once.answer(cv > 0 ? cv : 2));
        // if the dialog disappears for any other reason (activity finishing), unblock the script
        bd.setOnDismissListener(d -> { if (!done.get()) once.answer(cv > 0 ? cv : 2); });
        dismissDialog();
        openDialog = bd.show();
    }

    private void inputBox(String prompt, String t, String def, ScriptRunner.Reply<String> reply) {
        final AtomicBoolean done = new AtomicBoolean();
        final ScriptRunner.Reply<String> once = v -> { if (done.compareAndSet(false, true)) reply.answer(v); };
        if (destroyed || isFinishing()) { once.answer(null); return; }
        final EditText e = new EditText(this);
        e.setText(def);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        wrap.addView(e);
        AlertDialog.Builder bd = new AlertDialog.Builder(this)
                .setTitle(t == null || t.isEmpty() ? title.getText() : t)
                .setMessage(prompt)
                .setView(wrap)
                .setPositiveButton(R.string.ok, (d, w) -> once.answer(e.getText().toString()))
                .setNegativeButton(R.string.cancel, (d, w) -> once.answer(null))
                .setOnCancelListener(d -> once.answer(null))
                .setOnDismissListener(d -> once.answer(null));
        dismissDialog();
        openDialog = bd.create();
        if (openDialog.getWindow() != null)
            openDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        openDialog.show();
        e.requestFocus();
    }
}
