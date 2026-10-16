package com.zmlworks.vbarunner.vba;

final class Token {
    enum K { IDENT, NUM, STR, OP, NL, COLON, EOF }
    final K k;
    final String s;      // identifier / operator text (identifiers as written)
    final Object v;      // NUM: Long or Double, STR: String
    final int line;

    Token(K k, String s, Object v, int line) { this.k = k; this.s = s; this.v = v; this.line = line; }

    boolean is(String word) { return k == K.IDENT && s.equalsIgnoreCase(word); }
    boolean op(String o) { return k == K.OP && s.equals(o); }
    boolean end() { return k == K.NL || k == K.COLON || k == K.EOF; }

    @Override public String toString() {
        switch (k) {
            case NL: return Msg.t("改行", "end of line");
            case EOF: return Msg.t("ファイルの終わり", "end of file");
            case STR: return "\"" + v + "\"";
            case NUM: return String.valueOf(v);
            default: return s;
        }
    }
}
