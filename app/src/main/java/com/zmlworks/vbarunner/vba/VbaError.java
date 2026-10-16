package com.zmlworks.vbarunner.vba;

/** A compile (syntax) or run-time error with a VBA-style error number and 1-based source line. */
public class VbaError extends RuntimeException {
    public final int number;
    public int line;
    public final boolean syntax;
    /** Procedure in which the error happened (null = module level / unknown). */
    public String proc;

    public VbaError(int number, String message, int line, boolean syntax) {
        super(message, null, false, false);
        this.number = number;
        this.line = line;
        this.syntax = syntax;
    }

    public static VbaError syntax(String msg, int line) { return new VbaError(0, msg, line, true); }
    public static VbaError run(int number, String msg) { return new VbaError(number, msg, 0, false); }

    /** e.g. "行 12: 型が一致しません。 (13)" */
    public String display() {
        String where = line > 0 ? Msg.t("行 " + line, "Line " + line) + (proc != null ? " (" + proc + ")" : "") + ": " : "";
        String kind = syntax ? Msg.t("構文エラー: ", "Syntax error: ") : "";
        return where + kind + getMessage() + (number > 0 ? " (" + number + ")" : "");
    }
}
