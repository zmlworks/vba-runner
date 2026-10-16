package com.zmlworks.vbarunner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Design-time description of one form control (positions and sizes in dp). */
public final class ControlSpec {
    public enum Type {
        LABEL("Label", "Label"), BUTTON("Button", "Button"), TEXTBOX("TextBox", "Text"), COMBOBOX("ComboBox", "Combo"), GRID("Grid", "Grid");
        public final String vbName, prefix;
        Type(String vbName, String prefix) { this.vbName = vbName; this.prefix = prefix; }
        public static Type parse(String s) {
            for (Type t : values()) if (t.vbName.equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    public Type type;
    public String name;
    public int x, y, w, h;
    public String caption = "";            // Label / Button caption, TextBox initial text
    public final List<String> items = new ArrayList<>();   // ComboBox
    public int rows = 5, cols = 3;         // Grid
    public boolean multiline;              // TextBox
    public int fontSize = 16;              // sp

    public ControlSpec(Type type, String name) {
        this.type = type;
        this.name = name;
        switch (type) {
            case LABEL: w = 200; h = 40; caption = name; break;
            case BUTTON: w = 140; h = 48; caption = name; break;
            case TEXTBOX: w = 200; h = 48; break;
            case COMBOBOX: w = 200; h = 48; break;
            default: w = 320; h = 240; break;
        }
    }

    public ControlSpec copy() {
        ControlSpec c = new ControlSpec(type, name);
        c.x = x; c.y = y; c.w = w; c.h = h; c.caption = caption; c.items.addAll(items);
        c.rows = rows; c.cols = cols; c.multiline = multiline; c.fontSize = fontSize;
        return c;
    }

    /** Events the runtime can raise for this control type. */
    public String[] events() {
        switch (type) {
            case BUTTON: case LABEL: return new String[]{"Click"};
            case TEXTBOX: case COMBOBOX: return new String[]{"Change"};
            default: return new String[]{"Click"};
        }
    }

    /** Signature for the event stub, e.g. "Grid1_Click(Row, Col)". */
    public String eventSignature(String ev) {
        if (type == Type.GRID && ev.equals("Click")) return name + "_Click(Row, Col)";
        return name + "_" + ev + "()";
    }

    public static boolean validName(String n) {
        if (n == null || n.isEmpty() || n.length() > 40) return false;
        if (!Character.isLetter(n.charAt(0))) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        String l = n.toLowerCase(Locale.ROOT);
        return !(l.equals("form") || l.equals("storage") || l.equals("err") || l.equals("debug"));
    }
}
