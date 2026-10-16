package com.zmlworks.vbarunner.vba;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tree-walking interpreter. One instance = one loaded script; module variables persist between event calls. */
public final class Interp {

    /** UI services. Calls arrive on the script thread and may block (MsgBox / InputBox). */
    public interface Host {
        int msgBox(String prompt, int buttons, String title) throws InterruptedException;
        /** @return the entered text, or null when cancelled */
        String inputBox(String prompt, String title, String def) throws InterruptedException;
        void print(String line);
        default void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
    }

    /** End / Stop / user pressed ■. */
    public static final class Halt extends RuntimeException {
        public final boolean byUser;
        Halt(boolean byUser) { super(null, null, false, false); this.byUser = byUser; }
    }

    private static final class ExitSignal extends RuntimeException {
        final String kind;
        ExitSignal(String kind) { super(null, null, false, false); this.kind = kind; }
    }

    static final class Frame {
        final Map<String, Ref> vars;
        final Ast.Proc proc;
        boolean resumeNext;
        Frame(Map<String, Ref> vars, Ast.Proc proc) { this.vars = vars; this.proc = proc; }
    }

    public static final int MAX_DEPTH = 800;

    final Ast.Program prog;
    final Host host;
    final Map<String, Ref> globals = new HashMap<>();
    private final Map<String, HostObject> objects = new LinkedHashMap<>();
    final ErrObject err = new ErrObject();
    private volatile boolean stopRequested;
    private int depth;
    private Frame frame;
    private final StringBuilder pendingPrint = new StringBuilder();
    java.util.Random rnd = new java.util.Random();
    final long startNanos = System.nanoTime();

    public Interp(Ast.Program prog, Host host) {
        this.prog = prog;
        this.host = host;
        objects.put("err", err);
    }

    public static Interp compile(String src, Host host) { return new Interp(Parser.parse(src), host); }

    /** Register a named object (control, Storage, Form). */
    public void addObject(String name, HostObject o) { objects.put(name.toLowerCase(Locale.ROOT), o); }

    public boolean hasProc(String name) { return prog.procs.containsKey(name.toLowerCase(Locale.ROOT)); }

    public List<String> procNames() {
        List<String> out = new ArrayList<>();
        for (Ast.Proc p : prog.procs.values()) out.add(p.orig);
        return out;
    }

    public void requestStop() { stopRequested = true; }
    public boolean stopRequested() { return stopRequested; }

    /** Execute module-level statements (global Dim/Const and loose code). */
    public void runModule() {
        frame = new Frame(globals, null);
        try {
            execBlock(prog.module);
        } catch (ExitSignal e) {
            // Exit For/Do outside loops is rejected by the parser
        } finally {
            flushPrint();
        }
    }

    /** Call a Sub/Function by name with plain values (event handlers). Returns its result. */
    public Object call(String name, Object... args) {
        Ast.Proc p = prog.procs.get(name.toLowerCase(Locale.ROOT));
        if (p == null) throw Msg.noSub(name);
        Frame saved = frame;
        if (frame == null) frame = new Frame(globals, null);
        try {
            Ref[] refs = new Ref[args.length];
            for (int i = 0; i < args.length; i++) refs[i] = new Ref.Cell(args[i]);
            return invoke(p, refs);
        } finally {
            frame = saved;
            flushPrint();
        }
    }

    /** Number of required parameters of a procedure, or -1 if it does not exist. */
    public int requiredParams(String name) {
        Ast.Proc p = prog.procs.get(name.toLowerCase(Locale.ROOT));
        if (p == null) return -1;
        int n = 0;
        for (Ast.Param pa : p.params) if (!pa.optional) n++;
        return n;
    }

    // ------------------------------------------------------------------ statements
    void checkStop() {
        if (stopRequested || Thread.currentThread().isInterrupted()) throw new Halt(true);
    }

    private void execBlock(List<Ast.Stmt> body) {
        for (int i = 0; i < body.size(); i++) {
            Ast.Stmt s = body.get(i);
            checkStop();
            try {
                exec(s);
            } catch (VbaError e) {
                if (e.line == 0) { e.line = s.line; e.proc = frame.proc != null ? frame.proc.orig : null; }
                if (frame.resumeNext && !e.syntax) { err.set(e); continue; }
                throw e;
            } catch (StackOverflowError so) {
                VbaError e = VbaError.run(28, Msg.t("スタック領域が不足しています (再帰が深すぎます)。", "Out of stack space (recursion too deep)."));
                e.line = s.line;
                throw e;
            } catch (ArithmeticException | IllegalArgumentException | IndexOutOfBoundsException | java.time.DateTimeException ex) {
                VbaError e = VbaError.run(5, Msg.t("プロシージャの呼び出し、または引数が不正です。", "Invalid procedure call or argument.") + " (" + ex.getMessage() + ")");
                e.line = s.line;
                e.proc = frame.proc != null ? frame.proc.orig : null;
                if (frame.resumeNext) { err.set(e); continue; }
                throw e;
            }
        }
    }

    private void exec(Ast.Stmt s) {
        if (s instanceof Ast.Assign) { assign((Ast.Assign) s); return; }
        if (s instanceof Ast.CallS) { callStmt((Ast.CallS) s); return; }
        if (s instanceof Ast.If) {
            Ast.If f = (Ast.If) s;
            for (int i = 0; i < f.conds.size(); i++) {
                if (Values.toBool(eval(f.conds.get(i)))) { execBlock(f.blocks.get(i)); return; }
            }
            if (f.elseBlock != null) execBlock(f.elseBlock);
            return;
        }
        if (s instanceof Ast.For) { execFor((Ast.For) s); return; }
        if (s instanceof Ast.ForEach) { execForEach((Ast.ForEach) s); return; }
        if (s instanceof Ast.Do) { execDo((Ast.Do) s); return; }
        if (s instanceof Ast.Select) { execSelect((Ast.Select) s); return; }
        if (s instanceof Ast.Print) { execPrint((Ast.Print) s); return; }
        if (s instanceof Ast.Dim) { execDim((Ast.Dim) s); return; }
        if (s instanceof Ast.Const) {
            Ast.Const c = (Ast.Const) s;
            if (!frame.vars.containsKey(c.id)) frame.vars.put(c.id, new Ref.Cell(eval(c.value), true));
            return;
        }
        if (s instanceof Ast.Exit) throw new ExitSignal(((Ast.Exit) s).kind);
        if (s instanceof Ast.End) { flushPrint(); throw new Halt(false); }
        if (s instanceof Ast.OnError) { frame.resumeNext = ((Ast.OnError) s).resumeNext; err.clear(); return; }
        throw VbaError.run(0, "internal: " + s.getClass().getSimpleName());
    }

    private void execDim(Ast.Dim d) {
        for (Ast.DimVar v : d.vars) {
            Ref existing = frame.vars.get(v.id);
            if (d.redim) {
                int[][] b = bounds(v.bounds);
                Ref r = existing != null ? existing : lookupVar(v.id);
                if (r == null) { r = new TypedCell(null, v.type); frame.vars.put(v.id, r); }
                Object cur = r.get();
                VArray na;
                if (d.preserve && cur instanceof VArray) na = ((VArray) cur).resize(b[0], b[1], true);
                else na = new VArray(b[0], b[1]);
                if (!d.preserve || !(cur instanceof VArray)) fillDefault(na, v.type);
                else fillMissingDefault(na, v.type);
                setRaw(r, na);
                continue;
            }
            if (existing != null) continue;   // declarations are hoisted in VBA: re-running Dim keeps the value
            if (v.bounds != null) {
                int[][] b = bounds(v.bounds);
                VArray a = new VArray(b[0], b[1]);
                fillDefault(a, v.type);
                frame.vars.put(v.id, new Ref.Cell(a));
            } else if (v.dynamic) {
                frame.vars.put(v.id, new Ref.Cell(new VArray(new int[]{0}, new int[]{-1})));
            } else {
                frame.vars.put(v.id, new TypedCell(TypedCell.initial(v.type), v.type));
            }
        }
    }

    private static void setRaw(Ref r, Object v) {
        if (r instanceof TypedCell) ((TypedCell) r).v = v; else r.set(v);
    }

    private static void fillDefault(VArray a, String type) {
        a.elemType = type;
        Object init = TypedCell.initial(type);
        if (init != null) java.util.Arrays.fill(a.data, init);
    }

    private static void fillMissingDefault(VArray a, String type) {
        a.elemType = type;
        Object init = TypedCell.initial(type);
        if (init == null) return;
        for (int i = 0; i < a.data.length; i++) if (a.data[i] == null) a.data[i] = init;
    }

    private int[][] bounds(List<Ast.Expr[]> bs) {
        int[] lo = new int[bs.size()], hi = new int[bs.size()];
        for (int i = 0; i < bs.size(); i++) {
            lo[i] = bs.get(i)[0] == null ? 0 : Values.toInt(eval(bs.get(i)[0]));
            hi[i] = Values.toInt(eval(bs.get(i)[1]));
            if (hi[i] < lo[i]) throw Msg.subscript();
        }
        return new int[][]{lo, hi};
    }

    private void execFor(Ast.For f) {
        Object from = Values.toNum(Values.scalar(eval(f.from))), to = Values.toNum(Values.scalar(eval(f.to)));
        Object step = f.step == null ? (Object) 1L : Values.toNum(Values.scalar(eval(f.step)));
        Ref var = refForWrite(f.var, f.var);
        boolean integral = from instanceof Long && to instanceof Long && step instanceof Long;
        var.set(from);
        double st = Values.toDbl(step), end = Values.toDbl(to);
        try {
            while (true) {
                double cur = Values.toDbl(var.get());
                if (st >= 0 ? cur > end : cur < end) break;
                if (st == 0) checkStop();
                execBlock(f.body);
                Object v = var.get();
                var.set(integral && v instanceof Long ? Values.add(v, step) : (Object) (Values.toDbl(v) + st));
            }
        } catch (ExitSignal e) {
            if (!e.kind.equals("for")) throw e;
        }
    }

    private void execForEach(Ast.ForEach f) {
        Object c = Values.scalar(eval(f.coll));
        if (!(c instanceof VArray)) throw VbaError.run(92, Msg.t("For Each には配列が必要です。", "For Each requires an array."));
        VArray a = (VArray) c;
        Ref var = refForWrite(f.var, f.var);
        try {
            for (int i = 0; i < a.length(); i++) {
                var.set(a.at(i));
                execBlock(f.body);
            }
        } catch (ExitSignal e) {
            if (!e.kind.equals("for")) throw e;
        }
    }

    private void execDo(Ast.Do d) {
        try {
            while (true) {
                if (d.pre != null && Values.toBool(eval(d.pre)) == d.preUntil) break;
                execBlock(d.body);
                checkStop();
                if (d.post != null && Values.toBool(eval(d.post)) == d.postUntil) break;
            }
        } catch (ExitSignal e) {
            if (!e.kind.equals("do")) throw e;
        }
    }

    private void execSelect(Ast.Select s) {
        Object subj = Values.scalar(eval(s.subject));
        for (Ast.CaseClause cc : s.cases) {
            for (Ast.CaseItem it : cc.items) {
                boolean hit;
                if (it.isOp != null) hit = cmpOp(it.isOp, subj, eval(it.a));
                else if (it.b != null) hit = Values.compare(subj, eval(it.a)) >= 0 && Values.compare(subj, eval(it.b)) <= 0;
                else hit = Values.compare(subj, eval(it.a)) == 0;
                if (hit) { execBlock(cc.body); return; }
            }
        }
        if (s.elseBlock != null) execBlock(s.elseBlock);
    }

    private void execPrint(Ast.Print p) {
        StringBuilder b = pendingPrint;
        boolean newline = true;
        for (int i = 0; i < p.items.size(); i++) {
            Ast.Expr e = p.items.get(i);
            if (e != null) b.append(Values.toStr(Values.scalar(eval(e))));
            char sep = i < p.seps.size() ? p.seps.get(i) : ' ';
            if (sep == ',') { int col = b.length() - b.lastIndexOf("\n") - 1; int pad = 14 - (col % 14); for (int k = 0; k < pad; k++) b.append(' '); }
            newline = sep == ' ';
        }
        if (p.items.isEmpty()) newline = true;
        if (newline) {
            host.print(b.toString());
            b.setLength(0);
        }
    }

    void flushPrint() {
        if (pendingPrint.length() > 0) { host.print(pendingPrint.toString()); pendingPrint.setLength(0); }
    }

    private void assign(Ast.Assign a) {
        Object v = eval(a.value);
        if (!a.isSet) {
            if (v instanceof HostObject && ((HostObject) v).defaultProp() != null) v = Values.scalar(v);
            if (v instanceof VArray) v = ((VArray) v).copy();
        }
        Ast.Expr t = a.target;
        if (t instanceof Ast.Name) {
            Ast.Name n = (Ast.Name) t;
            Ref r = lookupVar(n.id);
            if (r == null) {
                HostObject o = objects.get(n.id);
                if (o != null && o.defaultProp() != null && !a.isSet) { o.set(o.defaultProp(), v); return; }
                if (o != null || prog.procs.containsKey(n.id) || Builtins.constant(n.id) != Builtins.NOT_FOUND)
                    throw VbaError.run(0, Msg.t("この名前には代入できません: ", "Cannot assign to: ") + n.orig);
                r = declareImplicit(n.id, n.orig);
            }
            r.set(v);
            return;
        }
        if (t instanceof Ast.Member) {
            Ast.Member m = (Ast.Member) t;
            Object o = eval(m.obj);
            if (!(o instanceof HostObject)) throw Msg.needObject(exprName(m.obj));
            ((HostObject) o).set(m.id, v);
            return;
        }
        if (t instanceof Ast.Call) {
            Ast.Call c = (Ast.Call) t;
            if (c.callee instanceof Ast.Member) {
                Ast.Member m = (Ast.Member) c.callee;
                Object o = eval(m.obj);
                if (!(o instanceof HostObject)) throw Msg.needObject(exprName(m.obj));
                ((HostObject) o).setIndexed(m.id, evalArgs(c.args), v);
                return;
            }
            if (c.callee instanceof Ast.Name) {
                Ast.Name n = (Ast.Name) c.callee;
                Ref r = lookupVar(n.id);
                if (r == null) {
                    if (prog.explicit) throw Msg.noVar(n.orig);
                    throw VbaError.run(9, Msg.t("配列が宣言されていません: ", "Array not declared: ") + n.orig + Msg.t(" (先に Dim " + n.orig + "(10) などで宣言してください)", " (declare it first, e.g. Dim " + n.orig + "(10))"));
                }
                Object arr = r.get();
                if (!(arr instanceof VArray)) throw VbaError.run(13, Msg.t("配列ではありません: ", "Not an array: ") + n.orig);
                VArray va = (VArray) arr;
                va.set(TypedCell.coerce(v, va.elemType), evalArgs(c.args));
                return;
            }
        }
        throw VbaError.run(0, Msg.t("代入できない式です。", "Invalid assignment target."));
    }

    private Ref declareImplicit(String id, String orig) {
        if (prog.explicit) throw Msg.noVar(orig);
        Ref.Cell c = new Ref.Cell(null);
        frame.vars.put(id, c);
        return c;
    }

    private Ref lookupVar(String id) {
        Ref r = frame.vars.get(id);
        if (r == null && frame.vars != globals) r = globals.get(id);
        return r;
    }

    private Ref refForWrite(String id, String orig) {
        Ref r = lookupVar(id);
        return r != null ? r : declareImplicit(id, orig);
    }

    private void callStmt(Ast.CallS c) {
        if (c.callee instanceof Ast.Name) {
            Ast.Name n = (Ast.Name) c.callee;
            Ast.Proc p = prog.procs.get(n.id);
            if (p != null) { invoke(p, argRefs(p, c.args)); return; }
            Ref r = lookupVar(n.id);
            if (r != null) throw VbaError.run(0, Msg.t("変数は呼び出せません: ", "A variable cannot be called: ") + n.orig);
            HostObject o = objects.get(n.id);
            if (o != null) throw VbaError.run(0, Msg.t("メソッドを指定してください (例: " + n.orig + ".Caption = ...)", "Specify a member (e.g. " + n.orig + ".Caption = ...)"));
            Object res = Builtins.call(this, n.id, evalArgs(c.args));
            if (res == Builtins.NOT_FOUND) throw Msg.noSub(n.orig);
            return;
        }
        if (c.callee instanceof Ast.Member) {
            Ast.Member m = (Ast.Member) c.callee;
            Object o = eval(m.obj);
            if (!(o instanceof HostObject)) throw Msg.needObject(exprName(m.obj));
            ((HostObject) o).call(m.id, evalArgs(c.args));
            return;
        }
        throw VbaError.run(0, Msg.t("呼び出せない式です。", "Expression cannot be called."));
    }

    // ------------------------------------------------------------------ procedures
    private Ref[] argRefs(Ast.Proc p, List<Ast.Expr> args) {
        if (args.size() > p.params.size()) throw Msg.argCount(p.orig);
        Ref[] refs = new Ref[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Ast.Expr e = args.get(i);
            if (e == null) continue;
            Ast.Param pa = p.params.get(i);
            Ref r = pa.byRef ? refOf(e) : null;
            refs[i] = r != null ? r : new Ref.Cell(eval(e));
        }
        return refs;
    }

    /** ByRef target for a variable or array element; null if the expression is not addressable. */
    private Ref refOf(Ast.Expr e) {
        if (e instanceof Ast.Name) {
            Ast.Name n = (Ast.Name) e;
            Ref r = lookupVar(n.id);
            if (r != null) return r;
            if (objects.containsKey(n.id) || prog.procs.containsKey(n.id) || Builtins.isName(n.id)) return null;
            return declareImplicit(n.id, n.orig);
        }
        if (e instanceof Ast.Call && ((Ast.Call) e).callee instanceof Ast.Name) {
            Ast.Name n = (Ast.Name) ((Ast.Call) e).callee;
            Ref r = lookupVar(n.id);
            if (r != null && r.get() instanceof VArray) {
                final VArray a = (VArray) r.get();
                final Object[] idx = evalArgs(((Ast.Call) e).args);
                final int k = a.index(idx);
                return new Ref() {
                    @Override public Object get() { return a.data[k]; }
                    @Override public void set(Object v) { a.data[k] = TypedCell.coerce(v, a.elemType); }
                };
            }
        }
        return null;
    }

    Object invoke(Ast.Proc p, Ref[] args) {
        if (args.length > p.params.size()) throw Msg.argCount(p.orig);
        if (++depth > MAX_DEPTH) {
            depth--;
            throw VbaError.run(28, Msg.t("スタック領域が不足しています (再帰が深すぎます)。", "Out of stack space (recursion too deep)."));
        }
        Frame saved = frame;
        Map<String, Ref> vars = new HashMap<>();
        Frame fr = new Frame(vars, p);
        try {
            for (int i = 0; i < p.params.size(); i++) {
                Ast.Param pa = p.params.get(i);
                Ref a = i < args.length ? args[i] : null;
                if (a == null) {
                    if (!pa.optional) throw Msg.argCount(p.orig);
                    frame = fr;   // defaults may use earlier params
                    Object def = pa.def != null ? eval(pa.def) : (pa.type != null ? TypedCell.initial(pa.type) : Builtins.MISSING);
                    vars.put(pa.id, new TypedCell(def == Builtins.MISSING ? def : TypedCell.coerce(def, pa.type), pa.type));
                    frame = saved;
                } else if (pa.byRef) {
                    vars.put(pa.id, a);
                } else {
                    Object v = a.get();
                    if (v instanceof VArray) v = ((VArray) v).copy();
                    vars.put(pa.id, new TypedCell(TypedCell.coerce(Values.scalar(v), pa.type), pa.type));
                }
            }
            TypedCell ret = null;
            if (p.isFunction) { ret = new TypedCell(TypedCell.initial(p.retType), p.retType); vars.put(p.id, ret); }
            frame = fr;
            try {
                execBlock(p.body);
            } catch (ExitSignal e) {
                if (!e.kind.equals("sub") && !e.kind.equals("function")) throw e;
            }
            return ret != null ? ret.get() : null;
        } finally {
            frame = saved;
            depth--;
        }
    }

    // ------------------------------------------------------------------ expressions
    Object[] evalArgs(List<Ast.Expr> args) {
        Object[] out = new Object[args.size()];
        for (int i = 0; i < out.length; i++) out[i] = args.get(i) == null ? Builtins.MISSING : eval(args.get(i));
        return out;
    }

    static String exprName(Ast.Expr e) {
        if (e instanceof Ast.Name) return ((Ast.Name) e).orig;
        if (e instanceof Ast.Member) return exprName(((Ast.Member) e).obj) + "." + ((Ast.Member) e).orig;
        if (e instanceof Ast.Call) return exprName(((Ast.Call) e).callee) + "(...)";
        return "?";
    }

    Object eval(Ast.Expr e) {
        if (e instanceof Ast.Lit) return ((Ast.Lit) e).v;
        if (e instanceof Ast.Name) return readName((Ast.Name) e);
        if (e instanceof Ast.Bin) return bin((Ast.Bin) e);
        if (e instanceof Ast.Call) return evalCall((Ast.Call) e);
        if (e instanceof Ast.Member) {
            Ast.Member m = (Ast.Member) e;
            Object o = eval(m.obj);
            if (!(o instanceof HostObject)) throw Msg.needObject(exprName(m.obj));
            return ((HostObject) o).get(m.id);
        }
        if (e instanceof Ast.Un) {
            Ast.Un u = (Ast.Un) e;
            Object v = eval(u.e);
            return u.op.equals("-") ? Values.neg(v) : Values.not(v);
        }
        throw VbaError.run(0, "internal expr");
    }

    private Object readName(Ast.Name n) {
        Ref r = lookupVar(n.id);
        if (r != null) {
            Object v = r.get();
            if (v == Builtins.MISSING) return null;
            return v;
        }
        HostObject o = objects.get(n.id);
        if (o != null) return o;
        Object c = Builtins.constant(n.id);
        if (c != Builtins.NOT_FOUND) return c;
        Ast.Proc p = prog.procs.get(n.id);
        if (p != null) return invoke(p, new Ref[0]);
        Object b = Builtins.call(this, n.id, new Object[0]);
        if (b != Builtins.NOT_FOUND) return b;
        if (prog.explicit) throw Msg.noVar(n.orig);
        return null;   // undeclared variable reads as Empty
    }

    private Object evalCall(Ast.Call c) {
        if (c.callee instanceof Ast.Name) {
            Ast.Name n = (Ast.Name) c.callee;
            Ref r = lookupVar(n.id);
            Ast.Proc p = prog.procs.get(n.id);
            if (r != null && (r.get() instanceof VArray || p == null)) {
                Object v = r.get();
                if (v instanceof VArray) return ((VArray) v).get(evalArgs(c.args));
                if (v instanceof String && c.args.size() == 1) throw VbaError.run(13, Msg.t("配列ではありません: ", "Not an array: ") + n.orig);
                throw VbaError.run(13, Msg.t("配列ではありません: ", "Not an array: ") + n.orig);
            }
            if (p != null) return invoke(p, argRefs(p, c.args));
            if (n.id.equals("ismissing") && c.args.size() == 1 && c.args.get(0) instanceof Ast.Name) {
                Ref mr = lookupVar(((Ast.Name) c.args.get(0)).id);
                return mr != null && mr.get() == Builtins.MISSING;
            }
            Object b = Builtins.call(this, n.id, evalArgs(c.args));
            if (b != Builtins.NOT_FOUND) return b;
            if (objects.containsKey(n.id)) throw Msg.noMember(n.orig, "()");
            throw Msg.noSub(n.orig);
        }
        if (c.callee instanceof Ast.Member) {
            Ast.Member m = (Ast.Member) c.callee;
            Object o = eval(m.obj);
            if (!(o instanceof HostObject)) throw Msg.needObject(exprName(m.obj));
            return ((HostObject) o).call(m.id, evalArgs(c.args));
        }
        Object v = eval(c.callee);   // e.g. Split(s, ",")(0)
        if (v instanceof VArray) return ((VArray) v).get(evalArgs(c.args));
        throw VbaError.run(13, Msg.t("配列ではありません。", "Not an array."));
    }

    static boolean cmpOp(String op, Object a, Object b) {
        int c = Values.compare(a, b);
        switch (op) {
            case "=": return c == 0;
            case "<>": return c != 0;
            case "<": return c < 0;
            case ">": return c > 0;
            case "<=": return c <= 0;
            default: return c >= 0;
        }
    }

    private Object bin(Ast.Bin b) {
        Object x = eval(b.a), y = eval(b.b);
        switch (b.op) {
            case "+": return Values.add(x, y);
            case "-": return Values.sub(x, y);
            case "*": return Values.mul(x, y);
            case "/": return Values.div(x, y);
            case "\\": return Values.idiv(x, y);
            case "mod": return Values.mod(x, y);
            case "^": return Values.pow(x, y);
            case "&": return Values.concat(x, y);
            case "and": case "or": case "xor": return Values.logic(b.op, x, y);
            case "like": return Values.like(Values.toStr(Values.scalar(x)), Values.toStr(Values.scalar(y)));
            case "is": return x == y || (x == null && y == null);
            default: return cmpOp(b.op, x, y);
        }
    }

    // ------------------------------------------------------------------ helpers for builtins
    void doSleep(long ms) {
        flushPrint();
        try { host.sleep(Math.max(0, ms)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Halt(true); }
        checkStop();
    }

    int doMsgBox(String prompt, int buttons, String title) {
        flushPrint();
        try { return host.msgBox(prompt, buttons, title); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Halt(true); }
        finally { checkStop(); }
    }

    String doInputBox(String prompt, String title, String def) {
        flushPrint();
        try {
            String s = host.inputBox(prompt, title, def);
            checkStop();
            return s == null ? "" : s;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Halt(true); }
    }

    /** VBA Err object. */
    final class ErrObject implements HostObject {
        long number;
        String description = "";
        int line;

        void set(VbaError e) { number = e.number == 0 ? 1000 : e.number; description = e.getMessage(); line = e.line; }
        void clear() { number = 0; description = ""; line = 0; }

        @Override public String typeName() { return "ErrObject"; }
        @Override public String defaultProp() { return "number"; }
        @Override public Object get(String p) {
            switch (p) {
                case "number": return number;
                case "description": return description;
                case "line": case "erl": return (long) line;
                case "source": return "VBA";
                case "clear": clear(); return null;
                default: throw noMember(p);
            }
        }
        @Override public void set(String p, Object v) {
            switch (p) {
                case "number": number = Values.toLong(v); break;
                case "description": description = Values.toStr(v); break;
                default: throw noMember(p);
            }
        }
        @Override public Object call(String m, Object[] a) {
            switch (m) {
                case "clear": clear(); return null;
                case "raise": {
                    if (a.length < 1 || a[0] == Builtins.MISSING) throw Msg.argCount("Err.Raise");
                    int num = Values.toInt(a[0]);
                    String desc = a.length > 2 && a[2] != Builtins.MISSING ? Values.toStr(a[2])
                            : Msg.t("アプリケーション定義またはオブジェクト定義のエラーです。", "Application-defined or object-defined error.");
                    throw VbaError.run(num, desc);
                }
                default: throw noMember(m);
            }
        }
    }
}
