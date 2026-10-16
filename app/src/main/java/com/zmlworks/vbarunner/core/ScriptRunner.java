package com.zmlworks.vbarunner.core;

import com.zmlworks.vbarunner.vba.Interp;
import com.zmlworks.vbarunner.vba.Msg;
import com.zmlworks.vbarunner.vba.Parser;
import com.zmlworks.vbarunner.vba.VbaError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs one script on its own thread:
 * parse → module code → Form_Load (or Main) → wait for events (ButtonX_Click …) until End / Stop / error / stop().
 * MsgBox / InputBox block the script thread while the UI shows the dialog, like real VBA.
 * Every Ui callback is delivered through {@code uiExecutor} (the main thread on Android).
 */
public final class ScriptRunner {

    public enum State { RUNNING, WAITING, ENDED, ERROR, STOPPED }

    public interface Ui {
        void onPrint(List<String> lines);
        void onState(State s);
        /** Always shown to the user; never silent. */
        void onError(VbaError e);
        void showMsgBox(String prompt, int buttons, String title, Reply<Integer> reply);
        /** reply(null) = cancelled */
        void showInputBox(String prompt, String title, String def, Reply<String> reply);
    }

    public interface Reply<T> { void answer(T value); }

    private static final Object[] STOP_EVENT = new Object[0];

    private final ScriptDoc doc;
    private final Executor ui;
    private final Ui cb;
    private final Map<String, ControlState> controls = new LinkedHashMap<>();
    private final ScriptStorage storage;
    private final FormObject form;
    private final BlockingQueue<Object[]> events = new LinkedBlockingQueue<>();
    private final List<String> pendingLines = new ArrayList<>();
    private boolean flushScheduled;
    private volatile Interp interp;
    private volatile State state;
    private volatile Thread thread;
    private volatile boolean stopping;

    public ScriptRunner(ScriptDoc doc, ScriptStorage storage, FormObject form, Executor uiExecutor, Ui ui) {
        this.doc = doc;
        this.storage = storage;
        this.form = form;
        this.ui = uiExecutor;
        this.cb = ui;
        for (ControlSpec s : doc.controls) controls.put(s.name.toLowerCase(Locale.ROOT), new ControlState(s));
    }

    public List<ControlState> controls() { return new ArrayList<>(controls.values()); }
    public ControlState control(String name) { return controls.get(name.toLowerCase(Locale.ROOT)); }
    public State state() { return state; }

    public void start() {
        if (thread != null) throw new IllegalStateException("already started");
        setState(State.RUNNING);
        Thread t = new Thread(null, this::loop, "vba-script", 32L * 1024 * 1024);
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Queue an event such as ("Button1_Click") or ("Grid1_Click", row, col). Ignored if no such Sub. */
    public void post(String proc, Object... args) {
        Interp in = interp;
        if (in == null || stopping || state == State.ENDED || state == State.ERROR || state == State.STOPPED) return;
        if (!in.hasProc(proc)) return;
        Object[] e = new Object[args.length + 1];
        e[0] = proc;
        System.arraycopy(args, 0, e, 1, args.length);
        events.offer(e);
    }

    /** ■ button / leaving the screen. Safe to call from any thread, repeatedly. */
    public void stop() {
        if (stopping) return;
        stopping = true;
        Interp in = interp;
        if (in != null) in.requestStop();
        Thread t = thread;
        if (t != null) t.interrupt();
        events.offer(STOP_EVENT);
    }

    /** Test helper: wait until the script thread is idle-waiting or finished. */
    public boolean awaitSettled(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            State s = state;
            if ((s == State.WAITING && events.isEmpty()) || s == State.ENDED || s == State.ERROR || s == State.STOPPED) return true;
            Thread.sleep(5);
        }
        return false;
    }

    public boolean join(long ms) throws InterruptedException {
        Thread t = thread;
        if (t == null) return true;
        t.join(ms);
        return !t.isAlive();
    }

    // ------------------------------------------------------------------ script thread
    private void loop() {
        try {
            Interp in;
            try {
                in = new Interp(Parser.parse(doc.code), host);
            } catch (VbaError e) {
                fail(e);
                return;
            }
            for (ControlState c : controls.values()) in.addObject(c.name, c);
            in.addObject("Storage", storage);
            in.addObject("Form", form);
            interp = in;
            if (stopping) { in.requestStop(); }
            in.runModule();
            if (in.hasProc("Form_Load")) callChecked(in, "Form_Load");
            else if (in.hasProc("Main")) callChecked(in, "Main");
            if (!hasEventHandlers(in)) { finish(State.ENDED); return; }
            while (true) {
                flush();
                setState(State.WAITING);
                Object[] e = events.poll(Long.MAX_VALUE, TimeUnit.DAYS);
                if (e == STOP_EVENT || stopping) break;
                setState(State.RUNNING);
                Object[] args = new Object[e.length - 1];
                System.arraycopy(e, 1, args, 0, args.length);
                callChecked(in, (String) e[0], args);
            }
            finish(State.STOPPED);
        } catch (Interp.Halt h) {
            finish(h.byUser || stopping ? State.STOPPED : State.ENDED);
        } catch (VbaError e) {
            if (stopping) finish(State.STOPPED); else fail(e);
        } catch (InterruptedException e) {
            finish(State.STOPPED);
        } catch (Throwable t) {
            VbaError e = VbaError.run(51, Msg.t("内部エラー: ", "Internal error: ") + t);
            fail(e);
        }
    }

    private void callChecked(Interp in, String name, Object... args) {
        int req = in.requiredParams(name);
        if (req > args.length) {
            throw VbaError.run(450, Msg.t("イベント " + name + " の引数が多すぎます (" + args.length + " 個まで)",
                    "Event " + name + " has too many parameters (max " + args.length + ")"));
        }
        in.call(name, args);
    }

    private boolean hasEventHandlers(Interp in) {
        for (String p : in.procNames()) {
            int us = p.indexOf('_');
            if (us <= 0) continue;
            String ctl = p.substring(0, us).toLowerCase(Locale.ROOT);
            if (controls.containsKey(ctl)) return true;
        }
        return false;
    }

    private void fail(VbaError e) {
        flush();
        state = State.ERROR;
        ui.execute(() -> { cb.onError(e); cb.onState(State.ERROR); });
    }

    private void finish(State s) {
        flush();
        setState(s);
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        ui.execute(() -> cb.onState(s));
    }

    private void flush() {
        final List<String> lines;
        synchronized (pendingLines) {
            if (pendingLines.isEmpty()) return;
            lines = new ArrayList<>(pendingLines);
            pendingLines.clear();
            flushScheduled = false;
        }
        ui.execute(() -> cb.onPrint(lines));
    }

    private final Interp.Host host = new Interp.Host() {
        @Override public int msgBox(String prompt, int buttons, String title) throws InterruptedException {
            flush();
            final BlockingQueue<Integer> q = new ArrayBlockingQueue<>(1);
            ui.execute(() -> cb.showMsgBox(prompt, buttons, title, v -> q.offer(v == null ? 2 : v)));
            return q.take();
        }

        @Override public String inputBox(String prompt, String title, String def) throws InterruptedException {
            flush();
            final BlockingQueue<Object[]> q = new ArrayBlockingQueue<>(1);
            ui.execute(() -> cb.showInputBox(prompt, title, def, v -> q.offer(new Object[]{v})));
            return (String) q.take()[0];
        }

        @Override public void print(String line) {
            boolean schedule;
            synchronized (pendingLines) {
                pendingLines.add(line);
                if (pendingLines.size() > 5000) pendingLines.remove(0);
                schedule = !flushScheduled;
                flushScheduled = true;
            }
            if (schedule) ui.execute(ScriptRunner.this::flushFromUi);
        }

        @Override public void sleep(long ms) throws InterruptedException {
            flush();
            Thread.sleep(ms);
        }
    };

    private void flushFromUi() {
        final List<String> lines;
        synchronized (pendingLines) {
            lines = new ArrayList<>(pendingLines);
            pendingLines.clear();
            flushScheduled = false;
        }
        if (!lines.isEmpty()) cb.onPrint(lines);
    }
}
