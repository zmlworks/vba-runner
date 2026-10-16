package com.zmlworks.vbarunner.vba;

import java.util.ArrayList;
import java.util.List;

/** Tokenizer: case-insensitive identifiers, "" escapes, ' and Rem comments, " _" line continuation, ':' separator. */
final class Lexer {
    private Lexer() {}

    static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = src.length(), line = 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && src.charAt(i + 1) == '\n') i++;
                i++;
                nl(out, line);
                line++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\u3000' || c == '\uFEFF') { i++; continue; }
            if (c == '\'' || c == '\u2019') { while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') i++; continue; }
            if (c == '_' && (i == 0 || isSpace(src.charAt(i - 1)))) {
                int j = i + 1;
                while (j < n && (src.charAt(j) == ' ' || src.charAt(j) == '\t')) j++;
                if (j >= n || src.charAt(j) == '\r' || src.charAt(j) == '\n') {   // line continuation
                    if (j < n && src.charAt(j) == '\r' && j + 1 < n && src.charAt(j + 1) == '\n') j++;
                    i = j + 1;
                    line++;
                    continue;
                }
            }
            if (c == '"' || c == '\u201C' || c == '\u201D') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\n' || d == '\r') break;
                    if (d == '"' || d == '\u201C' || d == '\u201D') {
                        if (j + 1 < n && src.charAt(j + 1) == '"') { sb.append('"'); j += 2; continue; }
                        closed = true; j++; break;
                    }
                    sb.append(d); j++;
                }
                if (!closed) throw VbaError.syntax(Msg.t("文字列が閉じられていません (\" が必要です)", "Unterminated string (missing \")"), line);
                out.add(new Token(Token.K.STR, null, sb.toString(), line));
                i = j;
                continue;
            }
            boolean prevValue = !out.isEmpty() && (out.get(out.size() - 1).k == Token.K.IDENT
                    || out.get(out.size() - 1).k == Token.K.NUM || out.get(out.size() - 1).op(")"));
            if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(src.charAt(i + 1)) && !prevValue)) {
                int j = i;
                while (j < n && isDigit(src.charAt(j))) j++;
                boolean dbl = false;
                if (j < n && src.charAt(j) == '.' && j + 1 < n && isDigit(src.charAt(j + 1))) {
                    dbl = true; j++;
                    while (j < n && isDigit(src.charAt(j))) j++;
                } else if (j < n && src.charAt(j) == '.' && (j + 1 >= n || !Character.isLetter(src.charAt(j + 1)))) {
                    dbl = true; j++;   // "1."
                }
                if (j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < n && (src.charAt(k) == '+' || src.charAt(k) == '-')) k++;
                    if (k < n && isDigit(src.charAt(k))) {
                        dbl = true; j = k;
                        while (j < n && isDigit(src.charAt(j))) j++;
                    }
                }
                String t = src.substring(i, j);
                Object v;
                if (dbl) v = Double.parseDouble(t);
                else { try { v = Long.parseLong(t); } catch (NumberFormatException e) { v = Double.parseDouble(t); } }
                if (j < n && (src.charAt(j) == '#' || src.charAt(j) == '!' || src.charAt(j) == '@')) j++; // type suffix
                out.add(new Token(Token.K.NUM, t, v, line));
                i = j;
                continue;
            }
            if (c == '&' && i + 1 < n && (src.charAt(i + 1) == 'H' || src.charAt(i + 1) == 'h')
                    && i + 2 < n && Character.digit(src.charAt(i + 2), 16) >= 0) {
                int j = i + 2;
                while (j < n && Character.digit(src.charAt(j), 16) >= 0) j++;
                out.add(new Token(Token.K.NUM, src.substring(i, j), Long.parseLong(src.substring(i + 2, j), 16), line));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) j++;
                String id = src.substring(i, j);
                if (j < n && src.charAt(j) == '$') j++;   // Left$ etc.
                boolean stmtStart = out.isEmpty() || out.get(out.size() - 1).k == Token.K.NL || out.get(out.size() - 1).k == Token.K.COLON;
                if (stmtStart && id.equalsIgnoreCase("rem")) {
                    while (j < n && src.charAt(j) != '\n' && src.charAt(j) != '\r') j++;
                    i = j;
                    continue;
                }
                out.add(new Token(Token.K.IDENT, id, null, line));
                i = j;
                continue;
            }
            if (c == ':' && i + 1 < n && src.charAt(i + 1) == '=') { out.add(new Token(Token.K.OP, ":=", null, line)); i += 2; continue; }
            if (c == ':') { out.add(new Token(Token.K.COLON, ":", null, line)); i++; continue; }
            if (i + 1 < n) {
                String two = src.substring(i, i + 2);
                if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("=<") || two.equals("=>")) {
                    String norm = two.equals("=<") ? "<=" : two.equals("=>") ? ">=" : two;
                    out.add(new Token(Token.K.OP, norm, null, line));
                    i += 2;
                    continue;
                }
            }
            if ("=<>+-*/\\^&(),;.".indexOf(c) >= 0) { out.add(new Token(Token.K.OP, String.valueOf(c), null, line)); i++; continue; }
            String hint = (c >= '\uFF01' && c <= '\uFF5E') ? Msg.t(" (全角文字です。半角で入力してください)", " (full-width character; use half-width)") : "";
            throw VbaError.syntax(Msg.t("不正な文字 '", "Invalid character '") + c + "'" + hint, line);
        }
        nl(out, line);
        out.add(new Token(Token.K.EOF, null, null, line));
        return out;
    }

    private static void nl(List<Token> out, int line) {
        if (!out.isEmpty() && out.get(out.size() - 1).k != Token.K.NL) out.add(new Token(Token.K.NL, null, null, line));
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isSpace(char c) { return c == ' ' || c == '\t'; }
}
