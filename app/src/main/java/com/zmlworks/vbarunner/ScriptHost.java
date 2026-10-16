package com.zmlworks.vbarunner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs user scripts in a throw-away, sandboxed WebView (no network permission in the app,
 * file/content access off, all sub-resource loads answered with an empty response).
 * One WebView per run = a fresh global scope each time, like resetting a VBA project.
 */
final class ScriptHost {
    interface Listener {
        void onBegin();
        void onDone(String error);    // "" = success
        void onPrint(String line, boolean error);
        void onSheetChanged();
        void onShowForm();
    }

    private final Activity act;
    private final SheetModel sheet;
    private final ViewGroup webHolder;
    private final LinearLayout form;
    private final Listener l;
    private final String html;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView wv;
    private int generation;
    private boolean running;
    private boolean sheetDirty;

    ScriptHost(Activity act, SheetModel sheet, ViewGroup webHolder, LinearLayout form, String runtimeJs, Listener l) {
        this.act = act; this.sheet = sheet; this.webHolder = webHolder; this.form = form; this.l = l;
        this.html = "<!doctype html><html><head><meta charset='utf-8'></head><body><script>"
                + runtimeJs.replace("</script", "<\\/script") + "</script></body></html>";
    }

    boolean isRunning() { return running; }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void run(final String code) {
        stop(false);
        final int gen = ++generation;
        final boolean[] started = {false};
        form.removeAllViews();
        wv = new WebView(act);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setBlockNetworkImage(true);
        s.setGeolocationEnabled(false);
        s.setDomStorageEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (gen == generation && v == wv && !started[0]) started[0] = true; else return;
                v.evaluateJavascript("__run(" + JSONObject.quote(code) + ")", null);
            }
        });
        wv.setWebChromeClient(new Chrome());
        wv.addJavascriptInterface(new Bridge(gen), "__host");
        webHolder.addView(wv, new ViewGroup.LayoutParams(1, 1));
        running = true;
        l.onBegin();
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /** Kill the current script (and its form callbacks). */
    void stop(boolean report) {
        generation++;
        if (wv != null) {
            webHolder.removeView(wv);
            wv.destroy();
            wv = null;
        }
        if (running && report) l.onDone(act.getString(R.string.stopped));
        running = false;
    }

    // ---- native dialogs answering window.prompt() ----
    private final class Chrome extends WebChromeClient {
        @Override public boolean onJsPrompt(WebView view, String url, String message, String def, JsPromptResult res) {
            if (message == null || !message.startsWith("@@VBA@@")) return false;
            try {
                JSONObject o = new JSONObject(message.substring(7));
                if ("input".equals(o.optString("t"))) inputBox(o, res); else msgBox(o, res);
            } catch (JSONException e) {
                res.cancel();
            }
            return true;
        }

        @Override public boolean onJsAlert(WebView view, String url, String message, JsResult res) {
            new AlertDialog.Builder(act).setMessage(message).setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> res.confirm()).show();
            return true;
        }

        @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult res) {
            new AlertDialog.Builder(act).setMessage(message).setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> res.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> res.cancel()).show();
            return true;
        }
    }

    private String title(JSONObject o) {
        String t = o.optString("title");
        return t.isEmpty() ? act.getString(R.string.app_name) : t;
    }

    private void msgBox(JSONObject o, final JsPromptResult res) {
        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle(title(o)).setMessage(o.optString("m")).setCancelable(false);
        // VBA button sets -> return codes (vbOK=1, vbCancel=2, vbAbort=3, vbRetry=4, vbIgnore=5, vbYes=6, vbNo=7)
        switch (o.optInt("b")) {
            case 1: b.setPositiveButton("OK", (d, w) -> res.confirm("1")).setNegativeButton(R.string.btn_cancel, (d, w) -> res.confirm("2")); break;
            case 2: b.setPositiveButton(R.string.btn_abort, (d, w) -> res.confirm("3")).setNeutralButton(R.string.btn_retry, (d, w) -> res.confirm("4"))
                    .setNegativeButton(R.string.btn_ignore, (d, w) -> res.confirm("5")); break;
            case 3: b.setPositiveButton(R.string.btn_yes, (d, w) -> res.confirm("6")).setNegativeButton(R.string.btn_no, (d, w) -> res.confirm("7"))
                    .setNeutralButton(R.string.btn_cancel, (d, w) -> res.confirm("2")); break;
            case 4: b.setPositiveButton(R.string.btn_yes, (d, w) -> res.confirm("6")).setNegativeButton(R.string.btn_no, (d, w) -> res.confirm("7")); break;
            case 5: b.setPositiveButton(R.string.btn_retry, (d, w) -> res.confirm("4")).setNegativeButton(R.string.btn_cancel, (d, w) -> res.confirm("2")); break;
            default: b.setPositiveButton("OK", (d, w) -> res.confirm("1"));
        }
        b.show();
    }

    private void inputBox(JSONObject o, final JsPromptResult res) {
        final EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(o.optString("d"));
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(act);
        int p = Ui.dp(act, 20);
        box.setPadding(p, 0, p, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act).setTitle(title(o)).setMessage(o.optString("m")).setView(box).setCancelable(false)
                .setPositiveButton("OK", (d, w) -> res.confirm(et.getText().toString()))
                .setNegativeButton(R.string.btn_cancel, (d, w) -> res.cancel())
                .show();
        et.requestFocus();
    }

    // ---- the JS bridge; methods run on WebView's JavaBridge thread ----
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, String> formValues = new ConcurrentHashMap<>();
    private final Map<Integer, TextView> formViews = new ConcurrentHashMap<>();

    private final class Bridge {
        final int gen;
        Bridge(int gen) { this.gen = gen; }
        boolean alive() { return gen == generation; }
        void post(Runnable r) { ui.post(() -> { if (alive()) r.run(); }); }

        @JavascriptInterface public int rows() { return SheetModel.ROWS; }
        @JavascriptInterface public int cols() { return SheetModel.COLS; }
        @JavascriptInterface public String get(int r, int c) { return sheet.get(r, c); }
        @JavascriptInterface public void set(int r, int c, String v) { if (alive()) { sheet.set(r, c, v); dirty(); } }
        @JavascriptInterface public int lastRow(int c) { return sheet.lastRow(c); }
        @JavascriptInterface public void clear() { if (alive()) { sheet.clear(); dirty(); } }

        @JavascriptInterface public String getBlock(int r1, int c1, int r2, int c2) {
            JSONArray out = new JSONArray();
            for (int r = r1; r <= r2; r++) {
                JSONArray row = new JSONArray();
                for (int c = c1; c <= c2; c++) row.put(sheet.get(r, c));
                out.put(row);
            }
            return out.toString();
        }

        @JavascriptInterface public void setBlock(int r1, int c1, String json) {
            if (!alive()) return;
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONArray row = a.getJSONArray(i);
                    for (int j = 0; j < row.length(); j++) sheet.set(r1 + i, c1 + j, row.getString(j));
                }
            } catch (JSONException ignored) { }
            dirty();
        }

        @JavascriptInterface public void print(String s) { post(() -> l.onPrint(s, false)); }
        @JavascriptInterface public void error(String s) { post(() -> l.onPrint(s, true)); }
        @JavascriptInterface public void begin() { }
        @JavascriptInterface public void done(String err) {
            post(() -> { running = false; flushSheet(); l.onDone(err); });
        }

        @JavascriptInterface public void formClear() {
            post(() -> { form.removeAllViews(); formViews.clear(); formValues.clear(); });
        }
        @JavascriptInterface public void formShow() { post(l::onShowForm); }

        @JavascriptInterface public int formAddLabel(String text) {
            final int id = ids.incrementAndGet();
            formValues.put(id, text);
            post(() -> {
                TextView tv = new TextView(act);
                tv.setText(text);
                tv.setTextSize(16);
                tv.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4));
                add(id, tv, tv);
            });
            return id;
        }

        @JavascriptInterface public int formAddTextBox(String name, String value) {
            final int id = ids.incrementAndGet();
            formValues.put(id, value);
            post(() -> {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView lab = new TextView(act);
                lab.setText(name);
                lab.setMinWidth(Ui.dp(act, 72));
                EditText et = new EditText(act);
                et.setSingleLine(true);
                et.setText(value);
                et.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void afterTextChanged(Editable e) { formValues.put(id, e.toString()); }
                });
                row.addView(lab);
                row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                add(id, row, et);
            });
            return id;
        }

        @JavascriptInterface public int formAddButton(String caption) {
            final int id = ids.incrementAndGet();
            formValues.put(id, caption);
            post(() -> {
                Button b = new Button(act);
                b.setText(caption);
                b.setOnClickListener(v -> {
                    if (alive() && wv != null) wv.evaluateJavascript("__fire(" + id + ")", null);
                });
                add(id, b, b);
            });
            return id;
        }

        @JavascriptInterface public String formGet(int id) {
            String v = formValues.get(id);
            return v == null ? "" : v;
        }

        @JavascriptInterface public void formSet(int id, String v) {
            formValues.put(id, v);
            post(() -> { TextView tv = formViews.get(id); if (tv != null) tv.setText(v); });
        }

        private void add(int id, android.view.View outer, TextView valueView) {
            formViews.put(id, valueView);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(act, 4);
            form.addView(outer, lp);
        }
    }

    // coalesce sheet repaints (scripts may write thousands of cells)
    private void dirty() {
        synchronized (this) {
            if (sheetDirty) return;
            sheetDirty = true;
        }
        ui.postDelayed(this::flushSheet, 60);
    }

    private void flushSheet() {
        synchronized (this) { sheetDirty = false; }
        l.onSheetChanged();
    }
}
