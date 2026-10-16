package com.zmlworks.vbarunner.core;

import com.zmlworks.vbarunner.vba.Builtins;
import com.zmlworks.vbarunner.vba.HostObject;
import com.zmlworks.vbarunner.vba.Msg;
import com.zmlworks.vbarunner.vba.VArray;
import com.zmlworks.vbarunner.vba.Values;
import com.zmlworks.vbarunner.vba.VbaError;

import java.util.ArrayList;
import java.util.List;

/**
 * Run-time state of one control, shared by the script thread (via {@link HostObject}) and the UI thread.
 * All state access is synchronized; the listener is told which property changed so the UI can refresh.
 */
public final class ControlState implements HostObject {
    public interface Listener { void onControlChanged(ControlState c, String prop); }

    public final ControlSpec.Type type;
    public final String name;
    private String text;
    private final List<String> items = new ArrayList<>();
    private int listIndex = -1;
    private int rows, cols;
    private Object[][] cells;
    private boolean visible = true, enabled = true, multiline;
    private int x, y, w, h, fontSize;
    private volatile Listener listener;

    public ControlState(ControlSpec s) {
        type = s.type;
        name = s.name;
        text = s.caption == null ? "" : s.caption;
        items.addAll(s.items);
        if (type == ControlSpec.Type.COMBOBOX) {
            int k = items.indexOf(text);
            listIndex = k >= 0 ? k : (items.isEmpty() ? -1 : 0);
            text = listIndex >= 0 ? items.get(listIndex) : "";
        }
        rows = s.rows; cols = s.cols;
        cells = new Object[rows][cols];
        multiline = s.multiline;
        x = s.x; y = s.y; w = s.w; h = s.h; fontSize = s.fontSize;
    }

    public void setListener(Listener l) { listener = l; }

    private void fire(String prop) {
        Listener l = listener;
        if (l != null) l.onControlChanged(this, prop);
    }

    // ------------------------------------------------------------------ UI-side accessors
    public synchronized String text() { return text; }
    public synchronized List<String> items() { return new ArrayList<>(items); }
    public synchronized int listIndex() { return listIndex; }
    public synchronized boolean visible() { return visible; }
    public synchronized boolean enabled() { return enabled; }
    public synchronized boolean multiline() { return multiline; }
    public synchronized int[] bounds() { return new int[]{x, y, w, h}; }
    public synchronized int fontSize() { return fontSize; }
    public synchronized int rows() { return rows; }
    public synchronized int cols() { return cols; }
    /** Display text of a cell (0-based). */
    public synchronized String cellText(int r, int c) {
        if (r < 0 || c < 0 || r >= rows || c >= cols) return "";
        Object v = cells[r][c];
        try { return Values.toStr(v); } catch (VbaError e) { return "?"; }
    }

    /** The user typed into a TextBox (UI thread). Returns true if the value changed. */
    public boolean userSetText(String t) {
        synchronized (this) {
            if (t.equals(text)) return false;
            text = t;
        }
        return true;
    }

    /** The user picked an item in a ComboBox (UI thread). */
    public boolean userSelect(int index) {
        synchronized (this) {
            if (index < 0 || index >= items.size() || index == listIndex) return false;
            listIndex = index;
            text = items.get(index);
        }
        fire("text");
        return true;
    }

    // ------------------------------------------------------------------ HostObject
    @Override public String typeName() { return type.vbName; }

    @Override public String defaultProp() {
        switch (type) {
            case LABEL: case BUTTON: return "caption";
            case TEXTBOX: case COMBOBOX: return "text";
            default: return null;
        }
    }

    @Override public Object get(String p) {
        synchronized (this) {
            switch (p) {
                case "name": return name;
                case "visible": return visible;
                case "enabled": return enabled;
                case "left": return (long) x;
                case "top": return (long) y;
                case "width": return (long) w;
                case "height": return (long) h;
                case "fontsize": return (long) fontSize;
                default: break;
            }
            switch (type) {
                case LABEL: case BUTTON:
                    if (p.equals("caption") || p.equals("text") || p.equals("value")) return text;
                    break;
                case TEXTBOX:
                    if (p.equals("text") || p.equals("value")) return text;
                    if (p.equals("multiline")) return multiline;
                    break;
                case COMBOBOX:
                    switch (p) {
                        case "text": case "value": return text;
                        case "listindex": return (long) listIndex;
                        case "listcount": return (long) items.size();
                        case "list": return listArray();
                        default: break;
                    }
                    break;
                case GRID:
                    if (p.equals("rows")) return (long) rows;
                    if (p.equals("cols")) return (long) cols;
                    break;
            }
        }
        if (isMethod(p)) return call(p, new Object[0]);
        throw noMember(p);
    }

    private VArray listArray() {
        if (items.isEmpty()) return new VArray(new int[]{0}, new int[]{-1});
        return VArray.of(items.toArray());
    }

    private boolean isMethod(String p) {
        return p.equals("clear") || p.equals("additem") || p.equals("removeitem") || p.equals("setfocus");
    }

    @Override public void set(String p, Object v) {
        String prop;
        synchronized (this) {
            switch (p) {
                case "visible": visible = Values.toBool(v); prop = "visible"; break;
                case "enabled": enabled = Values.toBool(v); prop = "enabled"; break;
                case "left": x = Values.toInt(v); prop = "bounds"; break;
                case "top": y = Values.toInt(v); prop = "bounds"; break;
                case "width": w = Math.max(0, Values.toInt(v)); prop = "bounds"; break;
                case "height": h = Math.max(0, Values.toInt(v)); prop = "bounds"; break;
                case "fontsize": fontSize = Math.max(6, Math.min(96, Values.toInt(v))); prop = "font"; break;
                default: prop = setTyped(p, v); break;
            }
        }
        fire(prop);
    }

    private String setTyped(String p, Object v) {
        switch (type) {
            case LABEL: case BUTTON:
                if (p.equals("caption") || p.equals("text") || p.equals("value")) { text = Values.toStr(Values.scalar(v)); return "text"; }
                break;
            case TEXTBOX:
                if (p.equals("text") || p.equals("value")) { text = Values.toStr(Values.scalar(v)); return "text"; }
                break;
            case COMBOBOX:
                if (p.equals("text") || p.equals("value")) {
                    String s = Values.toStr(Values.scalar(v));
                    int k = items.indexOf(s);
                    if (k < 0 && !s.isEmpty()) throw badValue(name + "." + "Text = \"" + s + "\" " + Msg.t("(リストにない値)", "(not in the list)"));
                    listIndex = k;
                    text = k >= 0 ? s : "";
                    return "text";
                }
                if (p.equals("listindex")) {
                    int k = Values.toInt(v);
                    if (k < -1 || k >= items.size()) throw badValue(name + ".ListIndex = " + k);
                    listIndex = k;
                    text = k >= 0 ? items.get(k) : "";
                    return "text";
                }
                break;
            case GRID:
                if (p.equals("rows") || p.equals("cols")) {
                    int n = Values.toInt(v);
                    int max = p.equals("rows") ? 500 : 50;
                    if (n < 1 || n > max) throw badValue(name + "." + (p.equals("rows") ? "Rows" : "Cols") + " = " + n + " (1-" + max + ")");
                    resizeGrid(p.equals("rows") ? n : rows, p.equals("cols") ? n : cols);
                    return "grid";
                }
                break;
        }
        if (get0(p)) throw VbaError.run(383, Msg.t("読み取り専用のプロパティです: ", "Property is read-only: ") + name + "." + p);
        throw noMember(p);
    }

    private boolean get0(String p) {
        return p.equals("name") || p.equals("listcount") || p.equals("list") || p.equals("multiline");
    }

    private void resizeGrid(int nr, int nc) {
        Object[][] n = new Object[nr][nc];
        for (int r = 0; r < Math.min(rows, nr); r++) System.arraycopy(cells[r], 0, n[r], 0, Math.min(cols, nc));
        cells = n;
        rows = nr;
        cols = nc;
    }

    private static VbaError badValue(String what) {
        return VbaError.run(380, Msg.t("プロパティの値が不正です: ", "Invalid property value: ") + what);
    }

    private static boolean has(Object[] a, int i) { return i < a.length && a[i] != Builtins.MISSING; }

    @Override public Object call(String m, Object[] a) {
        String prop = null;
        Object result = null;
        synchronized (this) {
            switch (type) {
                case COMBOBOX:
                    switch (m) {
                        case "additem": {
                            if (!has(a, 0)) throw Msg.argCountPublic(name + ".AddItem");
                            String it = Values.toStr(Values.scalar(a[0]));
                            if (has(a, 1)) {
                                int at = Values.toInt(a[1]);
                                if (at < 0 || at > items.size()) throw badValue(name + ".AddItem index " + at);
                                items.add(at, it);
                                if (listIndex >= at) listIndex++;
                            } else items.add(it);
                            prop = "items";
                            break;
                        }
                        case "removeitem": {
                            if (!has(a, 0)) throw Msg.argCountPublic(name + ".RemoveItem");
                            int k = Values.toInt(a[0]);
                            if (k < 0 || k >= items.size()) throw badValue(name + ".RemoveItem " + k);
                            items.remove(k);
                            if (listIndex == k) { listIndex = -1; text = ""; } else if (listIndex > k) listIndex--;
                            prop = "items";
                            break;
                        }
                        case "clear": items.clear(); listIndex = -1; text = ""; prop = "items"; break;
                        case "list": {
                            if (!has(a, 0)) return listArray();
                            int k = Values.toInt(a[0]);
                            if (k < 0 || k >= items.size()) throw badValue(name + ".List(" + k + ")");
                            return items.get(k);
                        }
                        default: break;
                    }
                    break;
                case GRID:
                    switch (m) {
                        case "cells": {
                            int[] rc = cellIndex(a);
                            return cells[rc[0]][rc[1]];
                        }
                        case "clear":
                            cells = new Object[rows][cols];
                            prop = "grid";
                            break;
                        default: break;
                    }
                    break;
                case TEXTBOX:
                    if (m.equals("clear")) { text = ""; prop = "text"; }
                    else if (m.equals("setfocus")) prop = "focus";
                    break;
                default:
                    if (m.equals("setfocus")) prop = "focus";
                    break;
            }
        }
        if (prop != null) { fire(prop); return result; }
        if (a.length == 0 && !isMethod(m)) return get(m);
        throw noMember(m);
    }

    private int[] cellIndex(Object[] a) {
        if (a.length != 2 || !has(a, 0) || !has(a, 1)) throw Msg.argCountPublic(name + ".Cells(row, col)");
        int r = Values.toInt(a[0]), c = Values.toInt(a[1]);
        if (r < 1 || r > rows || c < 1 || c > cols)
            throw VbaError.run(9, Msg.t("セルの位置が範囲外です: ", "Cell out of range: ") + name + ".Cells(" + r + ", " + c + ") "
                    + Msg.t("(Rows=" + rows + ", Cols=" + cols + ")", "(Rows=" + rows + ", Cols=" + cols + ")"));
        return new int[]{r - 1, c - 1};
    }

    @Override public void setIndexed(String p, Object[] a, Object v) {
        synchronized (this) {
            if (type == ControlSpec.Type.GRID && p.equals("cells")) {
                int[] rc = cellIndex(a);
                Object val = Values.scalar(v);
                if (val instanceof VArray) throw VbaError.run(13, Msg.t("セルに配列は入れられません。", "Cannot put an array into a cell."));
                cells[rc[0]][rc[1]] = val;
            } else if (type == ControlSpec.Type.COMBOBOX && p.equals("list")) {
                if (a.length != 1) throw Msg.argCountPublic(name + ".List(i)");
                int k = Values.toInt(a[0]);
                if (k < 0 || k >= items.size()) throw badValue(name + ".List(" + k + ")");
                items.set(k, Values.toStr(Values.scalar(v)));
                if (k == listIndex) text = items.get(k);
            } else throw noMember(p);
        }
        fire(type == ControlSpec.Type.GRID ? "grid" : "items");
    }
}
