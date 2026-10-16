package com.zmlworks.vbarunner.vba;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/** Variant semantics: conversions, operators, comparison, Format. Values are null(Empty), Boolean, Long, Double, String, VDate, VArray, HostObject. */
public final class Values {
    private Values() {}

    // ---------------- conversions ----------------
    public static boolean isNumber(Object v) { return v instanceof Long || v instanceof Double; }

    /** Parse a string as a number like VBA does (trim, optional sign, decimals, exponent, &H). Returns null if not numeric. */
    public static Number parseNum(String s) {
        String x = s.trim();
        if (x.isEmpty()) return null;
        x = x.replace(",", "");
        if (x.length() > 2 && (x.startsWith("&H") || x.startsWith("&h"))) {
            try { return Long.parseLong(x.substring(2), 16); } catch (NumberFormatException e) { return null; }
        }
        x = toHalfWidthDigits(x);
        if (!x.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return null;
        if (x.matches("[+-]?\\d{1,18}")) return Long.parseLong(x.startsWith("+") ? x.substring(1) : x);
        return Double.parseDouble(x);
    }

    static String toHalfWidthDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') c = (char) ('0' + (c - '\uFF10'));
            else if (c == '\uFF0E') c = '.';
            else if (c == '\uFF0D' || c == '\u2212') c = '-';
            else if (c == '\uFF0B') c = '+';
            b.append(c);
        }
        return b.toString();
    }

    public static Object toNum(Object v) {
        if (v == null) return 0L;
        if (v instanceof Long || v instanceof Double) return v;
        if (v instanceof Boolean) return ((Boolean) v) ? -1L : 0L;
        if (v instanceof String) {
            Number n = parseNum((String) v);
            if (n == null) throw Msg.typeMismatch();
            return n instanceof Long ? (Object) n.longValue() : (Object) n.doubleValue();
        }
        if (v instanceof VDate) return ((VDate) v).serial;
        if (v instanceof Integer) return ((Integer) v).longValue();
        throw Msg.typeMismatch();
    }

    public static double toDbl(Object v) {
        Object n = toNum(v);
        return n instanceof Long ? (double) (Long) n : (Double) n;
    }

    /** CLng semantics: banker's rounding of fractional values. */
    public static long toLong(Object v) {
        Object n = toNum(v);
        if (n instanceof Long) return (Long) n;
        double d = (Double) n;
        if (Double.isNaN(d) || Math.abs(d) > 9.2e18) throw Msg.overflow();
        return (long) Math.rint(d);
    }

    public static int toInt(Object v) {
        long l = toLong(v);
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) throw Msg.overflow();
        return (int) l;
    }

    public static boolean toBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return false;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        return toDbl(v) != 0;
    }

    public static String toStr(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return ((Boolean) v) ? "True" : "False";
        if (v instanceof Long || v instanceof Integer) return v.toString();
        if (v instanceof Double) return fmtDouble((Double) v);
        if (v instanceof VDate) {
            VDate d = (VDate) v;
            double frac = d.serial - Math.floor(d.serial);
            if (Math.floor(d.serial) == 0 && frac != 0) return format(v, "h:nn:ss");
            if (frac == 0) return format(v, "yyyy/mm/dd");
            return format(v, "yyyy/mm/dd h:nn:ss");
        }
        if (v instanceof VArray) throw Msg.typeMismatch();
        if (v instanceof HostObject) {
            HostObject h = (HostObject) v;
            if (h.defaultProp() != null) return toStr(h.get(h.defaultProp()));
            throw Msg.typeMismatch();
        }
        return String.valueOf(v);
    }

    /** VBA prints doubles with up to 15 significant digits. */
    public static String fmtDouble(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return "0";
        BigDecimal b = new BigDecimal(d).round(new MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        double a = Math.abs(d);
        if (a >= 1e15 || a < 1e-4) {
            String m = b.unscaledValue().abs().toString();
            int exp = m.length() - 1 - b.scale();
            String mant = m.length() > 1 ? m.charAt(0) + "." + m.substring(1) : m;
            return (d < 0 ? "-" : "") + mant + "E" + (exp < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d", Math.abs(exp));
        }
        return b.toPlainString();
    }

    /** Unwrap a host object's default property when used as a value. */
    public static Object scalar(Object v) {
        if (v instanceof HostObject) {
            HostObject h = (HostObject) v;
            if (h.defaultProp() != null) return h.get(h.defaultProp());
        }
        return v;
    }

    public static String typeName(Object v) {
        if (v == null) return "Empty";
        if (v instanceof Boolean) return "Boolean";
        if (v instanceof Long) { long l = (Long) v; return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? "Long" : "LongLong"; }
        if (v instanceof Double) return "Double";
        if (v instanceof String) return "String";
        if (v instanceof VDate) return "Date";
        if (v instanceof VArray) return "Variant()";
        if (v instanceof HostObject) return ((HostObject) v).typeName();
        return "Object";
    }

    // ---------------- operators ----------------
    private static Object numResult(double d) { return d; }

    public static Object add(Object a, Object b) {
        a = scalar(a); b = scalar(b);
        if (a instanceof String && b instanceof String) return (String) a + b;
        if (a instanceof VDate || b instanceof VDate) return new VDate(toDbl(a) + toDbl(b));
        Object x = toNum(a), y = toNum(b);
        if (x instanceof Long && y instanceof Long) {
            try { return Math.addExact((Long) x, (Long) y); } catch (ArithmeticException e) { return (double) (Long) x + (Long) y; }
        }
        return numResult(toDbl(x) + toDbl(y));
    }

    public static Object sub(Object a, Object b) {
        a = scalar(a); b = scalar(b);
        if (a instanceof VDate && b instanceof VDate) return toDbl(a) - toDbl(b);
        if (a instanceof VDate) return new VDate(toDbl(a) - toDbl(b));
        Object x = toNum(a), y = toNum(b);
        if (x instanceof Long && y instanceof Long) {
            try { return Math.subtractExact((Long) x, (Long) y); } catch (ArithmeticException e) { return (double) (Long) x - (Long) y; }
        }
        return numResult(toDbl(x) - toDbl(y));
    }

    public static Object mul(Object a, Object b) {
        Object x = toNum(scalar(a)), y = toNum(scalar(b));
        if (x instanceof Long && y instanceof Long) {
            try { return Math.multiplyExact((Long) x, (Long) y); } catch (ArithmeticException e) { return (double) (Long) x * (Long) y; }
        }
        return numResult(toDbl(x) * toDbl(y));
    }

    public static Object div(Object a, Object b) {
        double y = toDbl(scalar(b));
        if (y == 0) throw Msg.divZero();
        return toDbl(scalar(a)) / y;
    }

    public static Object idiv(Object a, Object b) {
        long y = toLong(scalar(b));
        if (y == 0) throw Msg.divZero();
        return toLong(scalar(a)) / y;
    }

    public static Object mod(Object a, Object b) {
        long y = toLong(scalar(b));
        if (y == 0) throw Msg.divZero();
        return toLong(scalar(a)) % y;
    }

    public static Object pow(Object a, Object b) { return Math.pow(toDbl(scalar(a)), toDbl(scalar(b))); }

    public static Object neg(Object a) {
        Object x = toNum(scalar(a));
        if (x instanceof Long) return (Long) x == Long.MIN_VALUE ? (Object) (-(double) (Long) x) : (Object) (-(Long) x);
        return -(Double) x;
    }

    public static Object concat(Object a, Object b) { return toStr(scalar(a)) + toStr(scalar(b)); }

    public static Object not(Object a) {
        a = scalar(a);
        if (a instanceof Boolean) return !(Boolean) a;
        if (a instanceof String && (((String) a).equalsIgnoreCase("true") || ((String) a).equalsIgnoreCase("false"))) return !toBool(a);
        return ~toLong(a);
    }

    public static Object logic(String op, Object a, Object b) {
        a = scalar(a); b = scalar(b);
        boolean bools = isBoolish(a) && isBoolish(b);
        if (bools) {
            boolean x = toBool(a), y = toBool(b);
            switch (op) { case "and": return x && y; case "or": return x || y; default: return x ^ y; }
        }
        long x = toLong(a), y = toLong(b);
        switch (op) { case "and": return x & y; case "or": return x | y; default: return x ^ y; }
    }

    private static boolean isBoolish(Object v) {
        return v instanceof Boolean || v == null
                || v instanceof String && (((String) v).equalsIgnoreCase("true") || ((String) v).equalsIgnoreCase("false"));
    }

    /** Three-way comparison with VBA variant rules. Numeric strings compare numerically against numbers. */
    public static int compare(Object a, Object b) {
        a = scalar(a); b = scalar(b);
        if (a instanceof VArray || b instanceof VArray) throw Msg.typeMismatch();
        if (a instanceof HostObject || b instanceof HostObject) throw Msg.typeMismatch();
        if (a == null && b == null) return 0;
        if (a instanceof String && b instanceof String) return Integer.signum(((String) a).compareTo((String) b));
        if (a == null) a = b instanceof String ? "" : (Object) 0L;
        if (b == null) b = a instanceof String ? "" : (Object) 0L;
        if (a instanceof String && b instanceof String) return Integer.signum(((String) a).compareTo((String) b));
        if (a instanceof String || b instanceof String) {
            String s = (String) (a instanceof String ? a : b);
            Number n = parseNum(s);
            if (n == null) {
                // a non-numeric string is greater than any number (VBA variant rule)
                return a instanceof String ? 1 : -1;
            }
        }
        double x = toDbl(a), y = toDbl(b);
        return Double.compare(x, y) == 0 || x == y ? 0 : (x < y ? -1 : 1);
    }

    public static boolean like(String s, String pattern) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*': re.append(".*"); break;
                case '?': re.append('.'); break;
                case '#': re.append("[0-9]"); break;
                case '[': {
                    int j = pattern.indexOf(']', i + 1);
                    if (j < 0) throw Msg.badCall("Like");
                    String body = pattern.substring(i + 1, j);
                    re.append('[');
                    if (body.startsWith("!")) { re.append('^'); body = body.substring(1); }
                    for (char d : body.toCharArray()) {
                        if ("\\[]^&".indexOf(d) >= 0) re.append('\\');
                        re.append(d);
                    }
                    re.append(']');
                    i = j;
                    break;
                }
                default: re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.DOTALL).matcher(s).matches();
    }

    // ---------------- Format ----------------
    public static String format(Object v, String fmt) {
        v = scalar(v);
        if (fmt == null || fmt.isEmpty()) return toStr(v);
        String lf = fmt.toLowerCase(Locale.ROOT);
        switch (lf) {
            case "general number": return toStr(toNum(v));
            case "currency": return format(v, "#,##0");
            case "fixed": return format(v, "0.00");
            case "standard": return format(v, "#,##0.00");
            case "percent": return format(v, "0.00%");
            case "yes/no": return toBool(v) ? "Yes" : "No";
            case "true/false": return toBool(v) ? "True" : "False";
            case "short date": return format(toDate(v), "yyyy/mm/dd");
            case "long date": return format(toDate(v), "yyyy年m月d日");
            case "short time": return format(toDate(v), "hh:nn");
            case "long time": return format(toDate(v), "h:nn:ss");
            case "general date": return toStr(toDate(v));
            default: break;
        }
        if (v instanceof VDate || looksLikeDateFormat(lf)) {
            VDate d = v instanceof VDate ? (VDate) v : toDate(v);
            return formatDate(d.toLocal(), fmt);
        }
        if (v instanceof String && parseNum((String) v) == null) return (String) v;
        if (v == null) v = 0L;
        double x = toDbl(v);
        String[] sections = fmt.split(";", -1);
        String sec = sections[0];
        if (x < 0 && sections.length > 1 && !sections[1].isEmpty()) { sec = sections[1]; x = -x; }
        else if (x == 0 && sections.length > 2 && !sections[2].isEmpty()) sec = sections[2];
        return formatNumber(x, sec);
    }

    private static boolean looksLikeDateFormat(String lf) {
        String stripped = lf.replaceAll("\"[^\"]*\"", "");
        return stripped.matches(".*(yy|dd|hh|nn|ss|m/|/m|aaa|ddd).*") && !stripped.contains("0") && !stripped.contains("#");
    }

    private static String formatNumber(double x, String sec) {
        // split into prefix literal, numeric pattern, suffix literal
        int start = -1, end = -1;
        boolean inQ = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            if (c == '"') { inQ = !inQ; continue; }
            if (!inQ && (c == '0' || c == '#' || c == ',' || c == '.' || c == '%')) {
                if (start < 0) start = i;
                end = i + 1;
            }
        }
        if (start < 0) return literal(sec);
        String prefix = literal(sec.substring(0, start)), suffix = literal(sec.substring(end)), pat = sec.substring(start, end);
        boolean pct = pat.contains("%");
        pat = pat.replace("%", "");
        if (pct) x *= 100;
        int dot = pat.indexOf('.');
        String ip = dot >= 0 ? pat.substring(0, dot) : pat, fpPat = dot >= 0 ? pat.substring(dot + 1) : "";
        int minDec = 0, maxDec = 0;
        for (char c : fpPat.toCharArray()) { if (c == '0') { minDec++; maxDec++; } else if (c == '#') maxDec++; }
        boolean group = ip.contains(",") && ip.indexOf(',') < ip.length() - 1;
        int scaleCommas = 0;   // trailing commas divide by 1000
        while (ip.endsWith(",")) { scaleCommas++; ip = ip.substring(0, ip.length() - 1); }
        for (int i = 0; i < scaleCommas; i++) x /= 1000;
        int minInt = 0;
        for (char c : ip.toCharArray()) if (c == '0') minInt++;
        BigDecimal bd = new BigDecimal(Math.abs(x)).setScale(maxDec, RoundingMode.HALF_UP);
        boolean neg = x < 0 && bd.signum() != 0;
        String s = bd.toPlainString();
        String intPart = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        String frac = s.contains(".") ? s.substring(s.indexOf('.') + 1) : "";
        while (frac.length() > minDec && frac.endsWith("0")) frac = frac.substring(0, frac.length() - 1);
        if (intPart.equals("0") && minInt == 0) intPart = "";
        while (intPart.length() < minInt) intPart = "0" + intPart;
        if (group && intPart.length() > 3) {
            StringBuilder g = new StringBuilder();
            int k = intPart.length() % 3;
            if (k > 0) g.append(intPart, 0, k);
            for (int i = k; i < intPart.length(); i += 3) { if (g.length() > 0) g.append(','); g.append(intPart, i, i + 3); }
            intPart = g.toString();
        }
        String num = intPart + (dot >= 0 && (!frac.isEmpty() || minDec > 0) ? "." + frac : (dot >= 0 && maxDec > 0 && frac.isEmpty() ? "." : ""));
        if (num.endsWith(".")) num = num.substring(0, num.length() - 1);
        if (num.isEmpty()) num = "0";
        return (neg ? "-" : "") + prefix + num + (pct ? "%" : "") + suffix;
    }

    private static String literal(String s) {
        StringBuilder b = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { inQ = !inQ; continue; }
            if (!inQ && c == '\\' && i + 1 < s.length()) { b.append(s.charAt(++i)); continue; }
            b.append(c);
        }
        return b.toString();
    }

    private static final String[] WEEK_JA = {"日", "月", "火", "水", "木", "金", "土"};
    private static final String[] WEEK_EN = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    static String formatDate(LocalDateTime t, String fmt) {
        StringBuilder b = new StringBuilder();
        int i = 0, n = fmt.length();
        boolean lastWasHour = false;
        while (i < n) {
            char c = fmt.charAt(i);
            if (c == '"') {
                int j = fmt.indexOf('"', i + 1);
                if (j < 0) j = n;
                b.append(fmt, i + 1, j);
                i = j + 1;
                continue;
            }
            char lc = Character.toLowerCase(c);
            int run = 1;
            while (i + run < n && Character.toLowerCase(fmt.charAt(i + run)) == lc) run++;
            int dow = t.getDayOfWeek().getValue() % 7;
            switch (lc) {
                case 'y':
                    if (run >= 4) b.append(t.getYear());
                    else if (run >= 2) b.append(String.format(Locale.ROOT, "%02d", t.getYear() % 100));
                    else b.append(t.getDayOfYear());
                    break;
                case 'm':
                    if (lastWasHour && run <= 2) b.append(run == 2 ? String.format(Locale.ROOT, "%02d", t.getMinute()) : t.getMinute());
                    else if (run >= 4) b.append(t.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
                    else if (run == 3) b.append(t.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH));
                    else b.append(run == 2 ? String.format(Locale.ROOT, "%02d", t.getMonthValue()) : t.getMonthValue());
                    break;
                case 'd':
                    if (run >= 4) b.append(t.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
                    else if (run == 3) b.append(WEEK_EN[dow]);
                    else b.append(run == 2 ? String.format(Locale.ROOT, "%02d", t.getDayOfMonth()) : t.getDayOfMonth());
                    break;
                case 'a':
                    if (run >= 3) { b.append(WEEK_JA[dow]); if (run >= 4) b.append("曜日"); }
                    else b.append(fmt, i, i + run);
                    break;
                case 'h': b.append(run >= 2 ? String.format(Locale.ROOT, "%02d", t.getHour()) : t.getHour()); break;
                case 'n': b.append(run >= 2 ? String.format(Locale.ROOT, "%02d", t.getMinute()) : t.getMinute()); break;
                case 's': b.append(run >= 2 ? String.format(Locale.ROOT, "%02d", t.getSecond()) : t.getSecond()); break;
                default: b.append(fmt, i, i + run);
            }
            if (lc == 'h') lastWasHour = true;
            else if (lc != ':' && lc != ' ') lastWasHour = false;
            i += run;
        }
        return b.toString();
    }

    public static VDate toDate(Object v) {
        v = scalar(v);
        if (v instanceof VDate) return (VDate) v;
        if (v instanceof String) {
            VDate d = parseDate((String) v);
            if (d == null) throw Msg.typeMismatch();
            return d;
        }
        return new VDate(toDbl(v));
    }

    static VDate parseDate(String s) {
        String x = toHalfWidthDigits(s.trim()).replace('年', '/').replace('月', '/').replace("日", "").replace('-', '/');
        java.util.regex.Matcher m = Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?)?$").matcher(x);
        try {
            if (m.matches()) {
                LocalDateTime t = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        m.group(4) == null ? 0 : Integer.parseInt(m.group(4)), m.group(5) == null ? 0 : Integer.parseInt(m.group(5)),
                        m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
                return VDate.of(t);
            }
            m = Pattern.compile("^(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?$").matcher(x);
            if (m.matches()) {
                double sec = Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + (m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
                return new VDate(sec / 86400.0);
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }
}
