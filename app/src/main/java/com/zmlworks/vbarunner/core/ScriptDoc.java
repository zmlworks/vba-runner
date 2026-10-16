package com.zmlworks.vbarunner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A script = name + form layout + VBA code, stored as ONE plain text file:
 * <pre>
 * '@form name="足し算"
 * '@TextBox Text1 x=16 y=16 w=150 h=48
 * '@Button Button1 x=16 y=80 w=150 h=48 caption="計算"
 * Sub Button1_Click() ... End Sub
 * </pre>
 * Layout lines are VBA comments, so the whole file is still valid code and can be pasted from an AI chat.
 * {@link #code} excludes the layout lines; error line numbers refer to it (= the コード tab).
 */
public final class ScriptDoc {
    public String name = "";
    public String code = "";
    public final List<ControlSpec> controls = new ArrayList<>();

    public static final String MARK = "'@";

    public ControlSpec find(String ctlName) {
        for (ControlSpec c : controls) if (c.name.equalsIgnoreCase(ctlName)) return c;
        return null;
    }

    /** Next free name such as Button3. */
    public String nextName(ControlSpec.Type t) {
        for (int i = 1; ; i++) if (find(t.prefix + i) == null) return t.prefix + i;
    }

    // ------------------------------------------------------------------ parse
    /**
     * Import text pasted from a chat/AI answer: if it contains ``` fenced blocks, use the block that looks like a
     * script (has '@ layout lines or Sub/Function), otherwise the whole text.
     */
    public static ScriptDoc fromPasted(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : lines) {
            if (l.trim().startsWith("```")) {
                if (cur == null) cur = new StringBuilder();
                else { blocks.add(cur.toString()); cur = null; }
                continue;
            }
            if (cur != null) cur.append(l).append('\n');
        }
        if (cur != null) blocks.add(cur.toString());   // unterminated fence
        if (blocks.isEmpty()) return parse(text);
        for (String b : blocks) if (b.contains(MARK)) return parse(b);
        for (String b : blocks) {
            String low = b.toLowerCase(Locale.ROOT);
            if (low.contains("sub ") || low.contains("function ")) return parse(b);
        }
        return parse(blocks.get(0));
    }

    public static ScriptDoc parse(String text) {
        ScriptDoc d = new ScriptDoc();
        StringBuilder code = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean header = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith(MARK)) {
                parseLayout(d, line.substring(MARK.length()));
                continue;
            }
            if (header && line.isEmpty()) continue;   // blank lines between header and code
            header = false;
            code.append(raw).append('\n');
        }
        String c = code.toString();
        while (c.endsWith("\n\n")) c = c.substring(0, c.length() - 1);
        d.code = c.equals("\n") ? "" : c;
        return d;
    }

    private static void parseLayout(ScriptDoc d, String s) {
        List<String> tok = tokens(s);
        if (tok.isEmpty()) return;
        String kind = tok.get(0);
        Map<String, String> a = new LinkedHashMap<>();
        String name = null;
        for (int i = 1; i < tok.size(); i++) {
            String t = tok.get(i);
            int eq = t.indexOf('=');
            if (eq > 0) a.put(t.substring(0, eq).toLowerCase(Locale.ROOT), unquote(t.substring(eq + 1)));
            else if (name == null) name = t;
        }
        if (kind.equalsIgnoreCase("form")) {
            if (a.containsKey("name")) d.name = a.get("name");
            return;
        }
        ControlSpec.Type type = ControlSpec.Type.parse(kind);
        if (type == null || name == null || !ControlSpec.validName(name) || d.find(name) != null) return;   // ignore unknown lines
        ControlSpec c = new ControlSpec(type, name);
        c.x = num(a, "x", 0); c.y = num(a, "y", 0); c.w = Math.max(16, num(a, "w", c.w)); c.h = Math.max(16, num(a, "h", c.h));
        if (a.containsKey("caption")) c.caption = a.get("caption");
        else if (a.containsKey("text")) c.caption = a.get("text");
        else if (type == ControlSpec.Type.TEXTBOX || type == ControlSpec.Type.COMBOBOX) c.caption = "";
        if (a.containsKey("items")) for (String it : a.get("items").split("\\|", -1)) if (!it.isEmpty()) c.items.add(it);
        c.rows = clamp(num(a, "rows", c.rows), 1, 500);
        c.cols = clamp(num(a, "cols", c.cols), 1, 50);
        c.multiline = "true".equalsIgnoreCase(a.get("multiline"));
        c.fontSize = clamp(num(a, "font", c.fontSize), 8, 64);
        d.controls.add(c);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int num(Map<String, String> a, String k, int def) {
        String v = a.get(k);
        if (v == null) return def;
        try { return (int) Math.round(Double.parseDouble(v.trim())); } catch (NumberFormatException e) { return def; }
    }

    /** Split on spaces, keeping "quoted ""strings""" together (also in key="value"). */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cur.append("\"\""); i++; continue; }
                    q = false;
                }
                cur.append(c);
            } else if (c == ' ' || c == '\t') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                if (c == '"') q = true;
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length() - 1).replace("\"\"", "\"");
        return v;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"").replace("\r", "").replace("\n", " ") + "\"";
    }

    // ------------------------------------------------------------------ serialize
    public String layoutText() {
        StringBuilder b = new StringBuilder();
        b.append(MARK).append("form name=").append(quote(name)).append('\n');
        for (ControlSpec c : controls) {
            b.append(MARK).append(c.type.vbName).append(' ').append(c.name)
                    .append(" x=").append(c.x).append(" y=").append(c.y).append(" w=").append(c.w).append(" h=").append(c.h);
            switch (c.type) {
                case LABEL: case BUTTON: b.append(" caption=").append(quote(c.caption)); break;
                case TEXTBOX:
                    if (!c.caption.isEmpty()) b.append(" text=").append(quote(c.caption));
                    if (c.multiline) b.append(" multiline=true");
                    break;
                case COMBOBOX:
                    b.append(" items=").append(quote(String.join("|", c.items)));
                    if (!c.caption.isEmpty()) b.append(" text=").append(quote(c.caption));
                    break;
                case GRID: b.append(" rows=").append(c.rows).append(" cols=").append(c.cols); break;
            }
            if (c.fontSize != 16) b.append(" font=").append(c.fontSize);
            b.append('\n');
        }
        return b.toString();
    }

    public String serialize() {
        String c = code;
        if (!c.isEmpty() && !c.endsWith("\n")) c += "\n";
        return layoutText() + "\n" + c;
    }

    public ScriptDoc copy() { return parse(serialize()); }

    // ------------------------------------------------------------------ code helpers
    /** Rename identifier occurrences (whole word, case-insensitive, outside strings and comments). */
    public static String renameIdentifier(String code, String from, String to) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && code.charAt(j) != '\n') {
                    if (code.charAt(j) == '"') { if (j + 1 < n && code.charAt(j + 1) == '"') { j += 2; continue; } j++; break; }
                    j++;
                }
                out.append(code, i, j);
                i = j;
            } else if (c == '\'') {
                int j = code.indexOf('\n', i);
                if (j < 0) j = n;
                out.append(code, i, j);
                i = j;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(code.charAt(j)) || code.charAt(j) == '_')) j++;
                String word = code.substring(i, j);
                boolean afterDot = i > 0 && code.charAt(i - 1) == '.';
                if (!afterDot && word.equalsIgnoreCase(from)) out.append(to);
                else if (!afterDot && word.toLowerCase(Locale.ROOT).startsWith(from.toLowerCase(Locale.ROOT) + "_"))
                    out.append(to).append(word.substring(from.length()));   // Button1_Click -> OkButton_Click
                else out.append(word);
                i = j;
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    /** True if the code already declares Sub name(. */
    public static boolean hasSub(String code, String subName) {
        for (String l : code.split("\n")) {
            String t = l.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("private ")) t = t.substring(8).trim();
            if (t.startsWith("public ")) t = t.substring(7).trim();
            if (t.startsWith("sub ")) {
                String rest = t.substring(4).trim();
                int p = rest.indexOf('(');
                String nm = (p >= 0 ? rest.substring(0, p) : rest).trim();
                if (nm.equalsIgnoreCase(subName)) return true;
            }
        }
        return false;
    }

    /** Line (0-based) of "Sub name", or -1. */
    public static int subLine(String code, String subName) {
        String[] ls = code.split("\n", -1);
        for (int i = 0; i < ls.length; i++) if (hasSub(ls[i], subName)) return i;
        return -1;
    }
}
