package com.zmlworks.vbarunner.vba;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Syntax tree. Names are stored lower-cased (VBA is case-insensitive) plus the original spelling. */
final class Ast {
    private Ast() {}

    abstract static class Node { int line; }

    // ---------- expressions ----------
    abstract static class Expr extends Node { }
    static final class Lit extends Expr { final Object v; Lit(Object v) { this.v = v; } }
    static final class Name extends Expr { final String id, orig; Name(String orig) { this.orig = orig; this.id = orig.toLowerCase(java.util.Locale.ROOT); } }
    static final class Member extends Expr {
        final Expr obj; final String id, orig;
        Member(Expr obj, String orig) { this.obj = obj; this.orig = orig; this.id = orig.toLowerCase(java.util.Locale.ROOT); }
    }
    /** f(args) or a(i): decided at run time. A null element in args is an omitted argument. */
    static final class Call extends Expr { final Expr callee; final List<Expr> args; Call(Expr c, List<Expr> a) { callee = c; args = a; } }
    static final class Un extends Expr { final String op; final Expr e; Un(String op, Expr e) { this.op = op; this.e = e; } }
    static final class Bin extends Expr { final String op; final Expr a, b; Bin(String op, Expr a, Expr b) { this.op = op; this.a = a; this.b = b; } }

    // ---------- statements ----------
    abstract static class Stmt extends Node { }
    static final class DimVar {
        String id, orig;
        List<Expr[]> bounds;    // null = scalar; each entry {lower or null, upper}
        boolean dynamic;        // Dim a()
        String type;            // lower-case As type or null
    }
    static final class Dim extends Stmt { final List<DimVar> vars = new ArrayList<>(); boolean redim, preserve; }
    static final class Const extends Stmt { String id, orig; Expr value; }
    static final class Assign extends Stmt { Expr target, value; boolean isSet; }
    static final class CallS extends Stmt { Expr callee; List<Expr> args; }
    static final class Print extends Stmt { final List<Expr> items = new ArrayList<>(); final List<Character> seps = new ArrayList<>(); }
    static final class If extends Stmt { final List<Expr> conds = new ArrayList<>(); final List<List<Stmt>> blocks = new ArrayList<>(); List<Stmt> elseBlock; }
    static final class For extends Stmt { String var; Expr from, to, step; List<Stmt> body; }
    static final class ForEach extends Stmt { String var; Expr coll; List<Stmt> body; }
    static final class Do extends Stmt { Expr pre, post; boolean preUntil, postUntil; List<Stmt> body; }
    static final class CaseItem { String isOp; Expr a, b; }
    static final class CaseClause { final List<CaseItem> items = new ArrayList<>(); List<Stmt> body; }
    static final class Select extends Stmt { Expr subject; final List<CaseClause> cases = new ArrayList<>(); List<Stmt> elseBlock; }
    static final class Exit extends Stmt { String kind; Exit(String k) { kind = k; } }
    static final class End extends Stmt { }
    static final class OnError extends Stmt { boolean resumeNext; }

    static final class Param { String id, orig, type; boolean byRef = true, optional, array; Expr def; }
    static final class Proc {
        String id, orig, retType;
        boolean isFunction;
        int line;
        final List<Param> params = new ArrayList<>();
        List<Stmt> body;
    }
    static final class Program {
        final List<Stmt> module = new ArrayList<>();
        final Map<String, Proc> procs = new HashMap<>();
        boolean explicit;
    }
}
