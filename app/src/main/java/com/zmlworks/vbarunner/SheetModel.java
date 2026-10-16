package com.zmlworks.vbarunner;

import java.util.ArrayList;
import java.util.List;

/** A fixed-size grid of string cells (1-based rows/cols), thread-safe. Also CSV (RFC 4180) I/O. */
public final class SheetModel {
    public static final int ROWS = 100, COLS = 26;
    private final String[][] cells = new String[ROWS + 1][COLS + 1];

    public synchronized String get(int r, int c) {
        if (!in(r, c)) return "";
        String v = cells[r][c];
        return v == null ? "" : v;
    }

    public synchronized void set(int r, int c, String v) {
        if (in(r, c)) cells[r][c] = (v == null || v.isEmpty()) ? null : v;
    }

    public synchronized void clear() {
        for (String[] row : cells) java.util.Arrays.fill(row, null);
    }

    /** Last non-empty row of column c, or 0. */
    public synchronized int lastRow(int c) {
        for (int r = ROWS; r >= 1; r--) if (cells[r][c] != null) return r;
        return 0;
    }

    static boolean in(int r, int c) { return r >= 1 && r <= ROWS && c >= 1 && c <= COLS; }

    public static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        while (c > 0) { int m = (c - 1) % 26; sb.insert(0, (char) ('A' + m)); c = (c - 1 - m) / 26; }
        return sb.toString();
    }

    /** CSV of the used area (trailing empty rows/cols trimmed). */
    public synchronized String toCsv() {
        int maxR = 0, maxC = 0;
        for (int r = 1; r <= ROWS; r++)
            for (int c = 1; c <= COLS; c++)
                if (cells[r][c] != null) { maxR = Math.max(maxR, r); maxC = Math.max(maxC, c); }
        StringBuilder sb = new StringBuilder();
        for (int r = 1; r <= maxR; r++) {
            for (int c = 1; c <= maxC; c++) {
                if (c > 1) sb.append(',');
                sb.append(csvField(get(r, c)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String csvField(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** Replace contents from CSV; cells outside the grid are dropped. Returns number of rows read. */
    public synchronized int fromCsv(String csv) {
        clear();
        List<List<String>> rows = parseCsv(csv);
        for (int r = 0; r < rows.size() && r < ROWS; r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size() && c < COLS; c++) set(r + 1, c + 1, row.get(c));
        }
        return rows.size();
    }

    static List<List<String>> parseCsv(String s) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false, any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; } else q = false;
                } else f.append(ch);
            } else if (ch == '"') { q = true; any = true; }
            else if (ch == ',') { row.add(f.toString()); f.setLength(0); any = true; }
            else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0); out.add(row); row = new ArrayList<>(); any = false;
            } else { f.append(ch); any = true; }
        }
        if (any || f.length() > 0) { row.add(f.toString()); out.add(row); }
        return out;
    }
}
