package com.zmlworks.vbarunner.vba;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recursive-descent parser for the supported VBA subset (see HELP / README). */
public final class Parser {
    private final List<Token> t;
    private int p;
    private final Deque<String> blocks = new ArrayDeque<>();   // "for", "do", "sub", "function" — validates Exit
    private Ast.Proc proc;

    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "if", "then", "else", "elseif", "end", "for", "to", "step", "next", "do", "loop", "while", "until", "wend",
            "select", "case", "sub", "function", "dim", "as", "and", "or", "not", "xor", "mod", "is", "like", "exit",
            "call", "set", "let", "const", "redim", "preserve", "each", "in", "byval", "byref", "optional", "option",
            "on", "error", "resume", "goto", "gosub", "new", "public", "private", "global", "static", "with", "endif"));

    private Parser(List<Token> t) { this.t = t; }

    public static Ast.Program parse(String src) {
        Parser ps = new Parser(Lexer.lex(src));
        return ps.program();
    }

    // ---------------- token helpers ----------------
    private Token peek() { return t.get(p); }
    private Token peek(int k) { return t.get(Math.min(p + k, t.size() - 1)); }
    private Token next() { Token x = t.get(p); if (p < t.size() - 1) p++; return x; }
    private boolean kw(String w) { return peek().is(w); }
    private boolean acceptKw(String w) { if (kw(w)) { p++; return true; } return false; }
    private boolean acceptOp(String o) { if (peek().op(o)) { p++; return true; } return false; }
    private int line() { return peek().line; }

    private VbaError err(String ja, String en) { return VbaError.syntax(Msg.t(ja, en), line()); }
    private VbaError unexpected() {
        return err("予期しない " + describe(peek()), "Unexpected " + describe(peek()));
    }
    private static String describe(Token x) { return x.k == Token.K.IDENT || x.k == Token.K.OP ? "'" + x.s + "'" : x.toString(); }

    private void expectKw(String w) {
        if (!acceptKw(w)) throw err("'" + w + "' が必要です (" + describe(peek()) + " があります)", "Expected '" + w + "' but found " + describe(peek()));
    }
    private void expectOp(String o) {
        if (!acceptOp(o)) throw err("'" + o + "' が必要です (" + describe(peek()) + " があります)", "Expected '" + o + "' but found " + describe(peek()));
    }
    private String ident() {
        Token x = peek();
        if (x.k != Token.K.IDENT || RESERVED.contains(x.s.toLowerCase(Locale.ROOT)))
            throw err("名前が必要です (" + describe(x) + " があります)", "Expected a name but found " + describe(x));
        p++;
        return x.s;
    }
    private void endStmt() {
        if (peek().k == Token.K.NL || peek().k == Token.K.COLON) { p++; return; }
        if (peek().k == Token.K.EOF) return;
        throw err("ステートメントの終わりが必要です (" + describe(peek()) + " があります)", "Expected end of statement but found " + describe(peek()));
    }
    private void skipSeparators() { while (peek().k == Token.K.NL || peek().k == Token.K.COLON) p++; }

    /** Does the statement at the cursor start with these words? ("end","if") */
    private boolean startsWith(String... words) {
        for (int i = 0; i < words.length; i++) if (!peek(i).is(words[i])) return false;
        return true;
    }

    // ---------------- program ----------------
    private Ast.Program program() {
        Ast.Program prog = new Ast.Program();
        while (true) {
            skipSeparators();
            if (peek().k == Token.K.EOF) break;
            if (kw("option")) {
                next();
                if (acceptKw("explicit")) prog.explicit = true;
                else while (!peek().end()) next();   // Option Base / Compare: accepted, ignored
                endStmt();
                continue;
            }
            int save = p;
            boolean modifier = acceptKw("public") || acceptKw("private") || acceptKw("global") || acceptKw("static");
            if (kw("sub") || kw("function")) {
                Ast.Proc pr = procedure();
                if (prog.procs.containsKey(pr.id))
                    throw VbaError.syntax(Msg.t("名前が重複しています: ", "Duplicate procedure: ") + pr.orig, pr.line);
                prog.procs.put(pr.id, pr);
                continue;
            }
            if (modifier && !kw("const")) { acceptKw("dim"); prog.module.add(dimRest(false, line())); continue; }
            p = save;
            prog.module.add(statement());
        }
        return prog;
    }

    private Ast.Proc procedure() {
        Ast.Proc pr = new Ast.Proc();
        pr.line = line();
        pr.isFunction = next().is("function");
        pr.orig = ident();
        pr.id = pr.orig.toLowerCase(Locale.ROOT);
        if (acceptOp("(")) {
            if (!acceptOp(")")) {
                do {
                    Ast.Param pa = new Ast.Param();
                    if (acceptKw("optional")) pa.optional = true;
                    if (acceptKw("byval")) pa.byRef = false; else acceptKw("byref");
                    pa.orig = ident();
                    pa.id = pa.orig.toLowerCase(Locale.ROOT);
                    if (acceptOp("(")) { expectOp(")"); pa.array = true; }
                    pa.type = skipAsType();
                    if (acceptOp("=")) { pa.def = expr(); pa.optional = true; }
                    pr.params.add(pa);
                } while (acceptOp(","));
                expectOp(")");
            }
        }
        pr.retType = skipAsType();
        endStmt();
        proc = pr;
        blocks.push(pr.isFunction ? "function" : "sub");
        pr.body = block(pr.isFunction ? new String[][]{{"end", "function"}} : new String[][]{{"end", "sub"}}, pr.line,
                pr.isFunction ? "End Function" : "End Sub");
        blocks.pop();
        next(); next();   // End Sub / End Function
        endStmt();
        proc = null;
        return pr;
    }

    private String skipAsType() {
        if (acceptKw("as")) {
            acceptKw("new");
            String ty = ident().toLowerCase(Locale.ROOT);
            while (acceptOp(".")) ty = next().s.toLowerCase(Locale.ROOT);
            if (acceptOp("*")) next();   // String * 10
            return ty;
        }
        return null;
    }

    /** Parse statements until one of the enders starts a statement. */
    private List<Ast.Stmt> block(String[][] enders, int openLine, String closer) {
        List<Ast.Stmt> out = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (peek().k == Token.K.EOF)
                throw VbaError.syntax(Msg.t(closer + " がありません (" + openLine + " 行目のブロック)",
                        "Missing " + closer + " (block opened at line " + openLine + ")"), openLine);
            for (String[] e : enders) if (startsWith(e)) return out;
            if ((startsWith("end", "sub") || startsWith("end", "function") || kw("sub") || kw("function")))
                throw VbaError.syntax(Msg.t(closer + " がありません (" + openLine + " 行目のブロック)",
                        "Missing " + closer + " (block opened at line " + openLine + ")"), openLine);
            out.add(statement());
        }
    }

    // ---------------- statements ----------------
    private Ast.Stmt statement() {
        int ln = line();
        Ast.Stmt s = statementNoEnd(false);
        s.line = ln;
        return s;
    }

    private Ast.Stmt statementNoEnd(boolean inlineIf) {
        int ln = line();
        Token x = peek();
        if (x.k != Token.K.IDENT) throw unexpected();
        String w = x.s.toLowerCase(Locale.ROOT);
        Ast.Stmt s;
        switch (w) {
            case "dim": case "static": next(); s = dimRest(false, ln); return s;
            case "redim": next(); s = dimRest(true, ln); return s;
            case "const": next(); s = constRest(ln); return s;
            case "if": next(); return ifRest(ln);
            case "for": next(); return forRest(ln);
            case "do": next(); return doRest(ln);
            case "while": next(); return whileRest(ln);
            case "select": next(); return selectRest(ln);
            case "exit": {
                next();
                String k = ident0();
                if (!Arrays.asList("sub", "function", "for", "do").contains(k)) throw err("Exit の後には Sub / Function / For / Do", "Exit must be followed by Sub/Function/For/Do");
                if (!blocks.contains(k)) throw err("Exit " + cap(k) + " が " + cap(k) + " の外にあります", "Exit " + cap(k) + " outside of " + cap(k));
                s = new Ast.Exit(k);
                s.line = ln;
                if (!inlineIf) endStmt();
                return s;
            }
            case "end":
                if (peek(1).end() || inlineIf && peek(1).is("else")) { next(); s = new Ast.End(); s.line = ln; if (!inlineIf) endStmt(); return s; }
                throw unexpected();
            case "stop": next(); s = new Ast.End(); s.line = ln; if (!inlineIf) endStmt(); return s;
            case "on": {
                next(); expectKw("error");
                Ast.OnError oe = new Ast.OnError();
                if (acceptKw("resume")) { expectKw("next"); oe.resumeNext = true; }
                else if (acceptKw("goto")) {
                    Token z = next();
                    if (!(z.k == Token.K.NUM && ((Number) z.v).longValue() == 0))
                        throw VbaError.syntax(Msg.t("On Error GoTo ラベル はサポートしていません (Resume Next / GoTo 0 のみ)", "On Error GoTo <label> is not supported (use Resume Next / GoTo 0)"), ln);
                } else throw unexpected();
                oe.line = ln;
                if (!inlineIf) endStmt();
                return oe;
            }
            case "goto": case "gosub": case "with":
                throw err(cap(w) + " はサポートしていません", cap(w) + " is not supported");
            case "call": {
                next();
                Ast.Expr e = postfix(primaryName());
                Ast.CallS c = new Ast.CallS();
                if (e instanceof Ast.Call) { c.callee = ((Ast.Call) e).callee; c.args = ((Ast.Call) e).args; } else { c.callee = e; c.args = new ArrayList<>(); }
                c.line = ln;
                if (!inlineIf) endStmt();
                return c;
            }
            case "set": case "let": {
                boolean isSet = next().is("set");
                s = simple(ln, inlineIf);
                if (!(s instanceof Ast.Assign)) throw err("Set / Let の後には代入が必要です", "Set / Let must be followed by an assignment");
                ((Ast.Assign) s).isSet = isSet;
                if (!inlineIf) endStmt();
                return s;
            }
            default: break;
        }
        s = simple(ln, inlineIf);
        if (!inlineIf) endStmt();
        return s;
    }

    private String ident0() { Token x = next(); if (x.k != Token.K.IDENT) throw unexpected(); return x.s.toLowerCase(Locale.ROOT); }
    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** assignment, call (with or without parentheses), Debug.Print */
    private Ast.Stmt simple(int ln, boolean inlineIf) {
        Ast.Expr head = primaryName();
        while (peek().op(".")) {
            next();
            Token m = next();
            if (m.k != Token.K.IDENT) throw VbaError.syntax(Msg.t("'.' の後に名前が必要です", "Expected a name after '.'"), m.line);
            Ast.Member mm = new Ast.Member(head, m.s);
            mm.line = m.line;
            head = mm;
        }
        if (isDebugPrint(head)) return printRest(ln);
        int afterHead = p;
        Ast.Expr first = postfix(head);
        if (acceptOp("=")) {
            Ast.Assign a = new Ast.Assign();
            a.target = first;
            a.value = expr();
            a.line = ln;
            return a;
        }
        Ast.CallS c = new Ast.CallS();
        c.line = ln;
        if (peek().end() || (inlineIf && kw("else"))) {
            if (first instanceof Ast.Call) { c.callee = ((Ast.Call) first).callee; c.args = ((Ast.Call) first).args; }
            else { c.callee = first; c.args = new ArrayList<>(); }
            return c;
        }
        // "Foo 1, 2" / "MsgBox (1) + 2" / "Foo (1), 2": arguments without call parentheses
        if (first instanceof Ast.Call) {
            Ast.Call fc = (Ast.Call) first;
            if (fc.callee instanceof Ast.Call) throw unexpected();
            p = afterHead;
            c.callee = fc.callee;
        } else c.callee = first;
        c.args = argList(false);
        if (!(peek().end() || (inlineIf && kw("else")))) throw unexpected();
        return c;
    }

    private static boolean isDebugPrint(Ast.Expr e) {
        return e instanceof Ast.Member && ((Ast.Member) e).id.equals("print")
                && ((Ast.Member) e).obj instanceof Ast.Name && ((Ast.Name) ((Ast.Member) e).obj).id.equals("debug");
    }

    private Ast.Print printRest(int ln) {
        Ast.Print pr = new Ast.Print();
        pr.line = ln;
        while (!peek().end() && !kw("else")) {
            if (peek().op(";") || peek().op(",")) { pr.seps.add(next().s.charAt(0)); pr.items.add(null); continue; }
            pr.items.add(expr());
            if (peek().op(";") || peek().op(",")) pr.seps.add(next().s.charAt(0)); else pr.seps.add(' ');
        }
        return pr;
    }

    private Ast.Dim dimRest(boolean redim, int ln) {
        Ast.Dim d = new Ast.Dim();
        d.line = ln;
        d.redim = redim;
        if (redim) d.preserve = acceptKw("preserve");
        do {
            Ast.DimVar v = new Ast.DimVar();
            v.orig = ident();
            v.id = v.orig.toLowerCase(Locale.ROOT);
            if (acceptOp("(")) {
                if (acceptOp(")")) v.dynamic = true;
                else {
                    v.bounds = new ArrayList<>();
                    do {
                        Ast.Expr a = expr(), lo = null;
                        if (acceptKw("to")) { lo = a; a = expr(); }
                        v.bounds.add(new Ast.Expr[]{lo, a});
                    } while (acceptOp(","));
                    expectOp(")");
                }
            }
            if (redim && v.bounds == null) throw err("ReDim には配列のサイズが必要です", "ReDim needs array bounds");
            v.type = skipAsType();
            d.vars.add(v);
        } while (acceptOp(","));
        endStmt();
        return d;
    }

    private Ast.Stmt constRest(int ln) {
        // Const a = 1, b = 2  -> first one returned, others appended via a small block
        List<Ast.Const> cs = new ArrayList<>();
        do {
            Ast.Const c = new Ast.Const();
            c.line = ln;
            c.orig = ident();
            c.id = c.orig.toLowerCase(Locale.ROOT);
            skipAsType();
            expectOp("=");
            c.value = expr();
            cs.add(c);
        } while (acceptOp(","));
        endStmt();
        if (cs.size() == 1) return cs.get(0);
        Ast.If wrap = new Ast.If();   // "If True Then <consts>" keeps the AST simple
        wrap.line = ln;
        Ast.Lit tr = new Ast.Lit(Boolean.TRUE);
        wrap.conds.add(tr);
        wrap.blocks.add(new ArrayList<>(cs));
        return wrap;
    }

    private Ast.Stmt ifRest(int ln) {
        Ast.If s = new Ast.If();
        s.line = ln;
        s.conds.add(expr());
        expectKw("then");
        if (!(peek().k == Token.K.NL || peek().k == Token.K.EOF)) {
            // single-line If: If c Then a : b Else c : d
            s.blocks.add(inlineStatements());
            if (acceptKw("else")) s.elseBlock = inlineStatements();
            if (peek().k == Token.K.NL) next();
            return s;
        }
        endStmt();
        s.blocks.add(block(new String[][]{{"elseif"}, {"else"}, {"end", "if"}, {"endif"}}, ln, "End If"));
        while (true) {
            if (acceptKw("elseif")) {
                s.conds.add(expr());
                expectKw("then");
                endStmt();
                s.blocks.add(block(new String[][]{{"elseif"}, {"else"}, {"end", "if"}, {"endif"}}, ln, "End If"));
            } else if (kw("else")) {
                next();
                if (kw("if")) throw err("Else If ではなく ElseIf と書いてください", "Use ElseIf instead of Else If");
                endStmt();
                s.elseBlock = block(new String[][]{{"end", "if"}, {"endif"}}, ln, "End If");
            } else break;
        }
        if (!acceptKw("endif")) { expectKw("end"); expectKw("if"); }
        endStmt();
        return s;
    }

    private List<Ast.Stmt> inlineStatements() {
        List<Ast.Stmt> out = new ArrayList<>();
        while (true) {
            int ln = line();
            Ast.Stmt st;
            if (kw("if")) { next(); st = inlineIf(ln); }
            else st = statementNoEnd(true);
            st.line = ln;
            out.add(st);
            if (peek().k == Token.K.COLON) { next(); if (peek().end() || kw("else")) break; continue; }
            break;
        }
        return out;
    }

    private Ast.Stmt inlineIf(int ln) {
        Ast.If s = new Ast.If();
        s.line = ln;
        s.conds.add(expr());
        expectKw("then");
        s.blocks.add(inlineStatements());
        if (acceptKw("else")) s.elseBlock = inlineStatements();
        return s;
    }

    private Ast.Stmt forRest(int ln) {
        blocks.push("for");
        try {
            if (acceptKw("each")) {
                Ast.ForEach f = new Ast.ForEach();
                f.line = ln;
                f.var = ident().toLowerCase(Locale.ROOT);
                expectKw("in");
                f.coll = expr();
                endStmt();
                f.body = block(new String[][]{{"next"}}, ln, "Next");
                next();
                if (!peek().end()) ident();
                endStmt();
                return f;
            }
            Ast.For f = new Ast.For();
            f.line = ln;
            String orig = ident();
            f.var = orig.toLowerCase(Locale.ROOT);
            expectOp("=");
            f.from = expr();
            expectKw("to");
            f.to = expr();
            if (acceptKw("step")) f.step = expr();
            endStmt();
            f.body = block(new String[][]{{"next"}}, ln, "Next");
            next();
            if (!peek().end()) {
                String nv = ident();
                if (!nv.equalsIgnoreCase(orig)) throw err("Next の変数が For と違います (" + orig + ")", "Next variable does not match For (" + orig + ")");
                if (acceptOp(",")) throw err("Next i, j の形はサポートしていません", "Next i, j is not supported");
            }
            endStmt();
            return f;
        } finally { blocks.pop(); }
    }

    private Ast.Stmt doRest(int ln) {
        Ast.Do d = new Ast.Do();
        d.line = ln;
        if (acceptKw("while")) d.pre = expr();
        else if (acceptKw("until")) { d.pre = expr(); d.preUntil = true; }
        endStmt();
        blocks.push("do");
        d.body = block(new String[][]{{"loop"}}, ln, "Loop");
        blocks.pop();
        next();
        if (acceptKw("while")) d.post = expr();
        else if (acceptKw("until")) { d.post = expr(); d.postUntil = true; }
        if (d.pre != null && d.post != null) throw err("Do と Loop の両方に条件があります", "Condition on both Do and Loop");
        endStmt();
        return d;
    }

    private Ast.Stmt whileRest(int ln) {
        Ast.Do d = new Ast.Do();
        d.line = ln;
        d.pre = expr();
        endStmt();
        d.body = block(new String[][]{{"wend"}}, ln, "Wend");
        next();
        endStmt();
        return d;
    }

    private Ast.Stmt selectRest(int ln) {
        expectKw("case");
        Ast.Select s = new Ast.Select();
        s.line = ln;
        s.subject = expr();
        endStmt();
        skipSeparators();
        while (true) {
            skipSeparators();
            if (startsWith("end", "select")) { next(); next(); endStmt(); return s; }
            if (!kw("case")) {
                if (peek().k == Token.K.EOF) throw VbaError.syntax(Msg.t("End Select がありません (" + ln + " 行目)", "Missing End Select (line " + ln + ")"), ln);
                throw err("Case が必要です", "Expected Case");
            }
            next();
            if (acceptKw("else")) {
                if (peek().k == Token.K.COLON) next(); else endStmt();
                s.elseBlock = block(new String[][]{{"end", "select"}}, ln, "End Select");
                continue;
            }
            Ast.CaseClause cc = new Ast.CaseClause();
            do {
                Ast.CaseItem it = new Ast.CaseItem();
                if (acceptKw("is")) {
                    Token o = next();
                    if (o.k != Token.K.OP || !Arrays.asList("=", "<>", "<", ">", "<=", ">=").contains(o.s)) throw unexpected();
                    it.isOp = o.s;
                    it.a = concat();
                } else {
                    it.a = expr();
                    if (acceptKw("to")) it.b = expr();
                }
                cc.items.add(it);
            } while (acceptOp(","));
            if (peek().k == Token.K.COLON) next(); else endStmt();
            cc.body = block(new String[][]{{"case"}, {"end", "select"}}, ln, "End Select");
            s.cases.add(cc);
        }
    }

    // ---------------- expressions ----------------
    Ast.Expr expr() { return xor(); }

    private Ast.Expr bin(String op, Ast.Expr a, Ast.Expr b, int ln) { Ast.Bin x = new Ast.Bin(op, a, b); x.line = ln; return x; }

    private Ast.Expr xor() {
        Ast.Expr a = or();
        while (kw("xor")) { int ln = next().line; a = bin("xor", a, or(), ln); }
        return a;
    }
    private Ast.Expr or() {
        Ast.Expr a = and();
        while (kw("or")) { int ln = next().line; a = bin("or", a, and(), ln); }
        return a;
    }
    private Ast.Expr and() {
        Ast.Expr a = not();
        while (kw("and")) { int ln = next().line; a = bin("and", a, not(), ln); }
        return a;
    }
    private Ast.Expr not() {
        if (kw("not")) { int ln = next().line; Ast.Un u = new Ast.Un("not", not()); u.line = ln; return u; }
        return cmp();
    }
    private Ast.Expr cmp() {
        Ast.Expr a = concat();
        while (true) {
            Token x = peek();
            String op = null;
            if (x.k == Token.K.OP && Arrays.asList("=", "<>", "<", ">", "<=", ">=").contains(x.s)) op = x.s;
            else if (x.is("like")) op = "like";
            else if (x.is("is")) op = "is";
            if (op == null) return a;
            next();
            a = bin(op, a, concat(), x.line);
        }
    }
    private Ast.Expr concat() {
        Ast.Expr a = add();
        while (peek().op("&")) { int ln = next().line; a = bin("&", a, add(), ln); }
        return a;
    }
    private Ast.Expr add() {
        Ast.Expr a = mod();
        while (peek().op("+") || peek().op("-")) { Token o = next(); a = bin(o.s, a, mod(), o.line); }
        return a;
    }
    private Ast.Expr mod() {
        Ast.Expr a = idiv();
        while (kw("mod")) { int ln = next().line; a = bin("mod", a, idiv(), ln); }
        return a;
    }
    private Ast.Expr idiv() {
        Ast.Expr a = mul();
        while (peek().op("\\")) { int ln = next().line; a = bin("\\", a, mul(), ln); }
        return a;
    }
    private Ast.Expr mul() {
        Ast.Expr a = unary();
        while (peek().op("*") || peek().op("/")) { Token o = next(); a = bin(o.s, a, unary(), o.line); }
        return a;
    }
    private Ast.Expr unary() {
        if (peek().op("-")) { int ln = next().line; Ast.Un u = new Ast.Un("-", unary()); u.line = ln; return u; }
        if (peek().op("+")) { next(); return unary(); }
        return pow();
    }
    private Ast.Expr pow() {
        Ast.Expr a = postfix(primary());
        while (peek().op("^")) { int ln = next().line; a = bin("^", a, powOperand(), ln); }
        return a;
    }
    private Ast.Expr powOperand() {
        if (peek().op("-")) { int ln = next().line; Ast.Un u = new Ast.Un("-", powOperand()); u.line = ln; return u; }
        return postfix(primary());
    }

    private Ast.Expr primary() {
        Token x = peek();
        int ln = x.line;
        Ast.Expr e;
        if (x.k == Token.K.NUM || x.k == Token.K.STR) { next(); e = new Ast.Lit(x.v); }
        else if (x.op("(")) { next(); e = expr(); expectOp(")"); return e; }
        else if (x.is("true")) { next(); e = new Ast.Lit(Boolean.TRUE); }
        else if (x.is("false")) { next(); e = new Ast.Lit(Boolean.FALSE); }
        else if (x.is("empty") || x.is("nothing") || x.is("null")) { next(); e = new Ast.Lit(null); }
        else if (x.is("new")) throw err("New はサポートしていません", "New is not supported");
        else return primaryName();
        e.line = ln;
        return e;
    }

    private Ast.Expr primaryName() {
        Token x = peek();
        if (x.k != Token.K.IDENT) throw unexpected();
        if (RESERVED.contains(x.s.toLowerCase(Locale.ROOT))) throw unexpected();
        next();
        Ast.Name n = new Ast.Name(x.s);
        n.line = x.line;
        return n;
    }

    private Ast.Expr postfix(Ast.Expr e) {
        while (true) {
            if (peek().op(".")) {
                next();
                Token m = next();
                if (m.k != Token.K.IDENT) throw VbaError.syntax(Msg.t("'.' の後に名前が必要です", "Expected a name after '.'"), m.line);
                Ast.Member mm = new Ast.Member(e, m.s);
                mm.line = m.line;
                e = mm;
            } else if (peek().op("(")) {
                int ln = next().line;
                List<Ast.Expr> args = argList(true);
                expectOp(")");
                Ast.Call c = new Ast.Call(e, args);
                c.line = ln;
                e = c;
            } else return e;
        }
    }

    /** Comma-separated arguments; empty positions (MsgBox "a", , "t") become null. */
    private List<Ast.Expr> argList(boolean inParens) {
        List<Ast.Expr> args = new ArrayList<>();
        if (inParens && peek().op(")")) return args;
        while (true) {
            if (peek().op(",")) { args.add(null); next(); continue; }
            if (inParens ? peek().op(")") : peek().end() || kw("else")) { if (!args.isEmpty()) args.add(null); break; }
            if (peek().k == Token.K.IDENT && peek(1).op(":="))
                throw err("名前付き引数 (:=) はサポートしていません", "Named arguments (:=) are not supported");
            args.add(expr());
            if (!acceptOp(",")) break;
            if (inParens ? peek().op(")") : peek().end()) { args.add(null); break; }
        }
        return args;
    }
}
