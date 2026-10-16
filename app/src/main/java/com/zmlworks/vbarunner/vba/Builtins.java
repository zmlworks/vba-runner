package com.zmlworks.vbarunner.vba;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Built-in functions and constants. Names are lower-case. */
public final class Builtins {
    private Builtins() {}

    public static final Object NOT_FOUND = new Object() { @Override public String toString() { return "<not found>"; } };
    public static final Object MISSING = new Object() { @Override public String toString() { return "<missing>"; } };

    private static final Map<String, Object> CONSTS = new HashMap<>();
    static {
        CONSTS.put("vbcrlf", "\r\n"); CONSTS.put("vbnewline", "\n"); CONSTS.put("vbcr", "\r"); CONSTS.put("vblf", "\n");
        CONSTS.put("vbtab", "\t"); CONSTS.put("vbnullstring", ""); CONSTS.put("vbnullchar", "\0");
        String[] mb = {"vbokonly", "vbokcancel", "vbabortretryignore", "vbyesnocancel", "vbyesno", "vbretrycancel"};
        for (int i = 0; i < mb.length; i++) CONSTS.put(mb[i], (long) i);
        CONSTS.put("vbcritical", 16L); CONSTS.put("vbquestion", 32L); CONSTS.put("vbexclamation", 48L); CONSTS.put("vbinformation", 64L);
        CONSTS.put("vbdefaultbutton1", 0L); CONSTS.put("vbdefaultbutton2", 256L); CONSTS.put("vbdefaultbutton3", 512L);
        String[] res = {"vbok", "vbcancel", "vbabort", "vbretry", "vbignore", "vbyes", "vbno"};
        for (int i = 0; i < res.length; i++) CONSTS.put(res[i], (long) (i + 1));
        String[] wd = {"vbsunday", "vbmonday", "vbtuesday", "vbwednesday", "vbthursday", "vbfriday", "vbsaturday"};
        for (int i = 0; i < wd.length; i++) CONSTS.put(wd[i], (long) (i + 1));
        CONSTS.put("vbbinarycompare", 0L); CONSTS.put("vbtextcompare", 1L);
    }

    /** Functions callable without parentheses / arguments as a plain name. */
    private static final Set<String> ZERO_ARG = new HashSet<>(Arrays.asList("now", "date", "time", "timer", "rnd", "randomize", "doevents", "beep"));

    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "msgbox", "inputbox", "len", "left", "right", "mid", "instr", "instrrev", "replace", "trim", "ltrim", "rtrim",
            "ucase", "lcase", "split", "join", "strreverse", "string", "space", "chr", "chrw", "asc", "ascw", "format",
            "cstr", "str", "val", "strcomp", "hex", "oct", "abs", "int", "fix", "round", "sqr", "sgn", "exp", "log", "sin", "cos",
            "tan", "atn", "cint", "clng", "clnglng", "cdbl", "csng", "ccur", "cbool", "cdate", "cvar", "cbyte", "isnumeric", "isempty", "isarray",
            "isdate", "ismissing", "isnull", "isobject", "typename", "vartype", "array", "ubound", "lbound", "iif", "sleep", "year", "month", "day",
            "hour", "minute", "second", "weekday", "weekdayname", "monthname", "dateserial", "timeserial", "dateadd", "datediff",
            "datevalue", "timevalue", "rgb", "choose", "switch", "filter", "max", "min", "nz"));
    static { NAMES.addAll(ZERO_ARG); }

    public static boolean isName(String id) { return NAMES.contains(id) || CONSTS.containsKey(id); }

    public static Object constant(String id) {
        Object v = CONSTS.get(id);
        return v != null ? v : (CONSTS.containsKey(id) ? null : NOT_FOUND);
    }

    // ---------------------------------------------------------------- helpers
    private static boolean has(Object[] a, int i) { return i < a.length && a[i] != MISSING; }
    private static Object arg(Object[] a, int i) { return has(a, i) ? Values.scalar(a[i]) : null; }
    private static String s(Object[] a, int i) { return Values.toStr(arg(a, i)); }
    private static long l(Object[] a, int i) { return Values.toLong(arg(a, i)); }
    private static double d(Object[] a, int i) { return Values.toDbl(arg(a, i)); }

    private static void need(String name, Object[] a, int min, int max) {
        int n = a.length;
        while (n > 0 && a[n - 1] == MISSING) n--;
        for (int i = 0; i < Math.min(min, a.length); i++)
            if (a[i] == MISSING) throw Msg.argCount(name);
        if (n < min || n > max) throw Msg.argCount(name);
    }

    private static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 9e15) return (long) v;
        return v;
    }

    private static LocalDateTime dt(Object v) { return Values.toDate(v).toLocal(); }

    private static long cint(Object v, long min, long max) {
        long r = Values.toLong(Values.scalar(v));
        if (r < min || r > max) throw Msg.overflow();
        return r;
    }

    // ---------------------------------------------------------------- dispatch
    public static Object call(Interp in, String name, Object[] a) {
        switch (name) {
            // ---- dialogs / host
            case "msgbox": {
                need("MsgBox", a, 1, 5);
                String title = has(a, 2) ? s(a, 2) : "";
                return (long) in.doMsgBox(s(a, 0), has(a, 1) ? (int) l(a, 1) : 0, title);
            }
            case "inputbox": need("InputBox", a, 1, 5); return in.doInputBox(s(a, 0), has(a, 1) ? s(a, 1) : "", has(a, 2) ? s(a, 2) : "");
            case "sleep": need("Sleep", a, 1, 1); in.doSleep(l(a, 0)); return null;
            case "doevents": need("DoEvents", a, 0, 0); in.checkStop(); return 0L;
            case "beep": return null;
            case "randomize": if (has(a, 0)) in.rnd = new java.util.Random(Double.doubleToLongBits(d(a, 0))); else in.rnd = new java.util.Random(); return null;
            case "rnd": return in.rnd.nextDouble();

            // ---- strings
            case "len": need("Len", a, 1, 1); return (long) s(a, 0).length();
            case "left": { need("Left", a, 2, 2); String x = s(a, 0); long n = nonNeg(l(a, 1), "Left"); return x.substring(0, (int) Math.min(n, x.length())); }
            case "right": { need("Right", a, 2, 2); String x = s(a, 0); long n = nonNeg(l(a, 1), "Right"); return x.substring((int) Math.max(0, x.length() - n)); }
            case "mid": {
                need("Mid", a, 2, 3);
                String x = s(a, 0);
                long st = l(a, 1);
                if (st < 1) throw Msg.badCall("Mid");
                if (st > x.length()) return "";
                long n = has(a, 2) ? nonNeg(l(a, 2), "Mid") : x.length();
                return x.substring((int) st - 1, (int) Math.min(x.length(), st - 1 + n));
            }
            case "instr": {
                need("InStr", a, 2, 4);
                long start = 1;
                String hay, needle;
                boolean text = false;
                if (has(a, 2) || (a.length >= 3)) {
                    start = l(a, 0); hay = s(a, 1); needle = s(a, 2);
                    text = has(a, 3) && l(a, 3) == 1;
                } else { hay = s(a, 0); needle = s(a, 1); }
                if (start < 1) throw Msg.badCall("InStr");
                if (text) { hay = hay.toLowerCase(Locale.ROOT); needle = needle.toLowerCase(Locale.ROOT); }
                if (start > hay.length() + 1) return 0L;
                return (long) (hay.indexOf(needle, (int) start - 1) + 1);
            }
            case "instrrev": {
                need("InStrRev", a, 2, 4);
                String hay = s(a, 0), needle = s(a, 1);
                long start = has(a, 2) ? l(a, 2) : -1;
                if (start == 0 || start < -1) throw Msg.badCall("InStrRev");
                int from = start == -1 ? hay.length() : (int) Math.min(hay.length(), start) - needle.length();
                if (from < 0) return 0L;
                return (long) (hay.lastIndexOf(needle, from) + 1);
            }
            case "replace": {
                need("Replace", a, 3, 6);
                String f = s(a, 1);
                if (f.isEmpty()) return s(a, 0);
                return s(a, 0).replace(f, s(a, 2));
            }
            case "trim": need("Trim", a, 1, 1); return s(a, 0).replaceAll("^[ \u3000]+|[ \u3000]+$", "");
            case "ltrim": need("LTrim", a, 1, 1); return s(a, 0).replaceAll("^[ \u3000]+", "");
            case "rtrim": need("RTrim", a, 1, 1); return s(a, 0).replaceAll("[ \u3000]+$", "");
            case "ucase": need("UCase", a, 1, 1); return s(a, 0).toUpperCase(Locale.ROOT);
            case "lcase": need("LCase", a, 1, 1); return s(a, 0).toLowerCase(Locale.ROOT);
            case "strreverse": need("StrReverse", a, 1, 1); return new StringBuilder(s(a, 0)).reverse().toString();
            case "string": {
                need("String", a, 2, 2);
                Object c = arg(a, 1);
                String ch = c instanceof String ? ((String) c).substring(0, Math.min(1, ((String) c).length())) : String.valueOf((char) Values.toLong(c));
                if (ch.isEmpty()) throw Msg.badCall("String");
                return repeat(ch, nonNeg(l(a, 0), "String"));
            }
            case "space": need("Space", a, 1, 1); return repeat(" ", nonNeg(l(a, 0), "Space"));
            case "chr": case "chrw": {
                need("Chr", a, 1, 1);
                long c = l(a, 0);
                if (c < 0 || c > 0x10FFFF) throw Msg.badCall("Chr");
                return new String(Character.toChars((int) c));
            }
            case "asc": case "ascw": {
                need("Asc", a, 1, 1);
                String x = s(a, 0);
                if (x.isEmpty()) throw Msg.badCall("Asc");
                return (long) x.codePointAt(0);
            }
            case "split": {
                need("Split", a, 1, 4);
                String x = s(a, 0), dl = has(a, 1) ? s(a, 1) : " ";
                if (x.isEmpty()) return new VArray(new int[]{0}, new int[]{-1});
                if (dl.isEmpty()) return VArray.of((Object) x);
                long limit = has(a, 2) ? l(a, 2) : -1;
                java.util.List<Object> parts = new java.util.ArrayList<>();
                int pos = 0;
                while (true) {
                    if (limit > 0 && parts.size() == limit - 1) { parts.add(x.substring(pos)); break; }
                    int k = x.indexOf(dl, pos);
                    if (k < 0) { parts.add(x.substring(pos)); break; }
                    parts.add(x.substring(pos, k));
                    pos = k + dl.length();
                }
                return VArray.of(parts.toArray());
            }
            case "join": {
                need("Join", a, 1, 2);
                Object arr = arg(a, 0);
                if (!(arr instanceof VArray)) throw Msg.typeMismatch();
                String dl = has(a, 1) ? s(a, 1) : " ";
                StringBuilder b = new StringBuilder();
                VArray va = (VArray) arr;
                for (int i = 0; i < va.length(); i++) { if (i > 0) b.append(dl); b.append(Values.toStr(va.at(i))); }
                return b.toString();
            }
            case "filter": {
                need("Filter", a, 2, 3);
                Object arr = arg(a, 0);
                if (!(arr instanceof VArray)) throw Msg.typeMismatch();
                String m = s(a, 1);
                boolean include = !has(a, 2) || Values.toBool(arg(a, 2));
                java.util.List<Object> out = new java.util.ArrayList<>();
                VArray va = (VArray) arr;
                for (int i = 0; i < va.length(); i++) { String e = Values.toStr(va.at(i)); if (e.contains(m) == include) out.add(e); }
                return out.isEmpty() ? new VArray(new int[]{0}, new int[]{-1}) : VArray.of(out.toArray());
            }
            case "format": need("Format", a, 1, 2); return Values.format(arg(a, 0), has(a, 1) ? s(a, 1) : null);
            case "cstr": need("CStr", a, 1, 1); return s(a, 0);
            case "str": { need("Str", a, 1, 1); Object n = Values.toNum(arg(a, 0)); String r = Values.toStr(n); return Values.toDbl(n) >= 0 ? " " + r : r; }
            case "val": {
                need("Val", a, 1, 1);
                String x = Values.toHalfWidthDigits(s(a, 0)).replace(" ", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").matcher(x);
                if (!m.find()) return 0L;
                Number n = Values.parseNum(m.group());
                return n == null ? (Object) 0L : num(n.doubleValue());
            }
            case "strcomp": {
                need("StrComp", a, 2, 3);
                String x = s(a, 0), y = s(a, 1);
                if (has(a, 2) && l(a, 2) == 1) { x = x.toLowerCase(Locale.ROOT); y = y.toLowerCase(Locale.ROOT); }
                return (long) Integer.signum(x.compareTo(y));
            }
            case "hex": need("Hex", a, 1, 1); return Long.toHexString(l(a, 0)).toUpperCase(Locale.ROOT);
            case "oct": need("Oct", a, 1, 1); return Long.toOctalString(l(a, 0));

            // ---- math
            case "abs": { need("Abs", a, 1, 1); Object n = Values.toNum(arg(a, 0)); return n instanceof Long ? (Object) Math.abs((Long) n) : (Object) Math.abs((Double) n); }
            case "int": { need("Int", a, 1, 1); Object n = Values.toNum(arg(a, 0)); return n instanceof Long ? n : num(Math.floor((Double) n)); }
            case "fix": { need("Fix", a, 1, 1); Object n = Values.toNum(arg(a, 0)); return n instanceof Long ? n : num((Double) n < 0 ? Math.ceil((Double) n) : Math.floor((Double) n)); }
            case "round": {
                need("Round", a, 1, 2);
                Object n = Values.toNum(arg(a, 0));
                long digits = has(a, 1) ? l(a, 1) : 0;
                if (n instanceof Long && digits >= 0) return n;
                java.math.BigDecimal bd = new java.math.BigDecimal(Values.toStr(n)).setScale((int) digits, java.math.RoundingMode.HALF_EVEN);
                return digits <= 0 ? (Object) bd.longValue() : (Object) bd.doubleValue();
            }
            case "sqr": { need("Sqr", a, 1, 1); double x = d(a, 0); if (x < 0) throw Msg.badCall("Sqr"); return Math.sqrt(x); }
            case "sgn": need("Sgn", a, 1, 1); return (long) Math.signum(d(a, 0));
            case "exp": need("Exp", a, 1, 1); return Math.exp(d(a, 0));
            case "log": { need("Log", a, 1, 1); double x = d(a, 0); if (x <= 0) throw Msg.badCall("Log"); return Math.log(x); }
            case "sin": need("Sin", a, 1, 1); return Math.sin(d(a, 0));
            case "cos": need("Cos", a, 1, 1); return Math.cos(d(a, 0));
            case "tan": need("Tan", a, 1, 1); return Math.tan(d(a, 0));
            case "atn": need("Atn", a, 1, 1); return Math.atan(d(a, 0));
            case "max": case "min": {
                if (a.length == 0) throw Msg.argCount(name);
                Object best = null;
                for (Object x : a) {
                    if (x == MISSING) continue;
                    Object v = Values.scalar(x);
                    if (v instanceof VArray) { for (int i = 0; i < ((VArray) v).length(); i++) best = pick(name, best, ((VArray) v).at(i)); }
                    else best = pick(name, best, v);
                }
                return best;
            }

            // ---- conversion / inspection
            case "cint": need("CInt", a, 1, 1); return cint(arg(a, 0), -32768, 32767);
            case "clng": need("CLng", a, 1, 1); return cint(arg(a, 0), Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "clnglng": need("CLngLng", a, 1, 1); return Values.toLong(arg(a, 0));
            case "cbyte": need("CByte", a, 1, 1); return cint(arg(a, 0), 0, 255);
            case "cdbl": case "csng": case "ccur": need("CDbl", a, 1, 1); return d(a, 0);
            case "cbool": need("CBool", a, 1, 1); return Values.toBool(arg(a, 0));
            case "cdate": case "datevalue": case "timevalue": {
                need("CDate", a, 1, 1);
                VDate dd = Values.toDate(arg(a, 0));
                if (name.equals("datevalue")) return new VDate(Math.floor(dd.serial));
                if (name.equals("timevalue")) return new VDate(dd.serial - Math.floor(dd.serial));
                return dd;
            }
            case "cvar": need("CVar", a, 1, 1); return arg(a, 0);
            case "isnumeric": {
                need("IsNumeric", a, 1, 1);
                Object v = arg(a, 0);
                if (v instanceof Long || v instanceof Double) return true;
                if (v instanceof String) return Values.parseNum((String) v) != null;
                return v instanceof Boolean;
            }
            case "isempty": { need("IsEmpty", a, 1, 1); return arg(a, 0) == null; }
            case "isnull": return false;
            case "isobject": return has(a, 0) && a[0] instanceof HostObject;
            case "isarray": need("IsArray", a, 1, 1); return arg(a, 0) instanceof VArray;
            case "isdate": {
                need("IsDate", a, 1, 1);
                Object v = arg(a, 0);
                return v instanceof VDate || v instanceof String && Values.parseDate((String) v) != null;
            }
            case "ismissing": return !has(a, 0);
            case "typename": need("TypeName", a, 1, 1); return Values.typeName(a[0]);
            case "vartype": {
                Object v = has(a, 0) ? a[0] : null;
                if (v == null) return 0L;
                if (v instanceof Long) return 3L;
                if (v instanceof Double) return 5L;
                if (v instanceof VDate) return 7L;
                if (v instanceof String) return 8L;
                if (v instanceof Boolean) return 11L;
                if (v instanceof VArray) return 8204L;
                return 9L;
            }
            case "nz": return has(a, 0) && arg(a, 0) != null ? arg(a, 0) : (has(a, 1) ? arg(a, 1) : "");

            // ---- arrays
            case "array": {
                Object[] items = new Object[a.length];
                for (int i = 0; i < a.length; i++) items[i] = a[i] == MISSING ? null : a[i];
                return a.length == 0 ? new VArray(new int[]{0}, new int[]{-1}) : VArray.of(items);
            }
            case "ubound": case "lbound": {
                need(name.equals("ubound") ? "UBound" : "LBound", a, 1, 2);
                Object v = arg(a, 0);
                if (!(v instanceof VArray)) throw Msg.typeMismatch();
                int dim = has(a, 1) ? (int) l(a, 1) : 1;
                return (long) (name.equals("ubound") ? ((VArray) v).upper(dim) : ((VArray) v).lower(dim));
            }
            case "iif": need("IIf", a, 3, 3); return Values.toBool(arg(a, 0)) ? a[1] : a[2];
            case "choose": {
                if (a.length < 2) throw Msg.argCount("Choose");
                long i = l(a, 0);
                return i >= 1 && i < a.length ? arg(a, (int) i) : null;
            }
            case "switch": {
                for (int i = 0; i + 1 < a.length; i += 2) if (Values.toBool(arg(a, i))) return arg(a, i + 1);
                return null;
            }
            case "rgb": need("RGB", a, 3, 3); return (l(a, 0) & 255) | ((l(a, 1) & 255) << 8) | ((l(a, 2) & 255) << 16);

            // ---- date / time
            case "now": return VDate.of(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
            case "date": return VDate.of(LocalDate.now().atStartOfDay());
            case "time": return new VDate(LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toSecondOfDay() / 86400.0);
            case "timer": return LocalTime.now().toNanoOfDay() / 1e9;
            case "year": need("Year", a, 1, 1); return (long) dt(arg(a, 0)).getYear();
            case "month": need("Month", a, 1, 1); return (long) dt(arg(a, 0)).getMonthValue();
            case "day": need("Day", a, 1, 1); return (long) dt(arg(a, 0)).getDayOfMonth();
            case "hour": need("Hour", a, 1, 1); return (long) dt(arg(a, 0)).getHour();
            case "minute": need("Minute", a, 1, 1); return (long) dt(arg(a, 0)).getMinute();
            case "second": need("Second", a, 1, 1); return (long) dt(arg(a, 0)).getSecond();
            case "weekday": need("Weekday", a, 1, 2); return (long) (dt(arg(a, 0)).getDayOfWeek().getValue() % 7 + 1);
            case "weekdayname": {
                need("WeekdayName", a, 1, 2);
                long w = l(a, 0);
                if (w < 1 || w > 7) throw Msg.badCall("WeekdayName");
                String[] ja = {"日曜日", "月曜日", "火曜日", "水曜日", "木曜日", "金曜日", "土曜日"};
                String[] en = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
                String r = Msg.t(ja[(int) w - 1], en[(int) w - 1]);
                return has(a, 1) && Values.toBool(arg(a, 1)) ? (Msg.japanese ? r.substring(0, 1) : r.substring(0, 3)) : r;
            }
            case "monthname": {
                need("MonthName", a, 1, 2);
                long m = l(a, 0);
                if (m < 1 || m > 12) throw Msg.badCall("MonthName");
                return Msg.japanese ? m + "月" : java.time.Month.of((int) m).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
            }
            case "dateserial": {
                need("DateSerial", a, 3, 3);
                LocalDate base = LocalDate.of((int) l(a, 0), 1, 1).plusMonths(l(a, 1) - 1).plusDays(l(a, 2) - 1);
                return VDate.of(base.atStartOfDay());
            }
            case "timeserial": {
                need("TimeSerial", a, 3, 3);
                return new VDate((l(a, 0) * 3600 + l(a, 1) * 60 + l(a, 2)) / 86400.0);
            }
            case "dateadd": {
                need("DateAdd", a, 3, 3);
                LocalDateTime t = dt(arg(a, 2));
                long n = l(a, 1);
                switch (s(a, 0).toLowerCase(Locale.ROOT)) {
                    case "yyyy": t = t.plusYears(n); break;
                    case "q": t = t.plusMonths(3 * n); break;
                    case "m": t = t.plusMonths(n); break;
                    case "d": case "y": case "w": t = t.plusDays(n); break;
                    case "ww": t = t.plusWeeks(n); break;
                    case "h": t = t.plusHours(n); break;
                    case "n": t = t.plusMinutes(n); break;
                    case "s": t = t.plusSeconds(n); break;
                    default: throw Msg.badCall("DateAdd");
                }
                return VDate.of(t);
            }
            case "datediff": {
                need("DateDiff", a, 3, 5);
                LocalDateTime x = dt(arg(a, 1)), y = dt(arg(a, 2));
                switch (s(a, 0).toLowerCase(Locale.ROOT)) {
                    case "yyyy": return (long) (y.getYear() - x.getYear());
                    case "q": return (long) ((y.getYear() * 4 + (y.getMonthValue() - 1) / 3) - (x.getYear() * 4 + (x.getMonthValue() - 1) / 3));
                    case "m": return (long) ((y.getYear() * 12 + y.getMonthValue()) - (x.getYear() * 12 + x.getMonthValue()));
                    case "d": case "y": case "w": return ChronoUnit.DAYS.between(x.toLocalDate(), y.toLocalDate());
                    case "ww": return ChronoUnit.WEEKS.between(x.toLocalDate(), y.toLocalDate());
                    case "h": return ChronoUnit.HOURS.between(x.truncatedTo(ChronoUnit.HOURS), y.truncatedTo(ChronoUnit.HOURS));
                    case "n": return ChronoUnit.MINUTES.between(x.truncatedTo(ChronoUnit.MINUTES), y.truncatedTo(ChronoUnit.MINUTES));
                    case "s": return ChronoUnit.SECONDS.between(x, y);
                    default: throw Msg.badCall("DateDiff");
                }
            }
            default: return NOT_FOUND;
        }
    }

    private static Object pick(String which, Object best, Object v) {
        if (best == null) return v;
        int c = Values.compare(v, best);
        return which.equals("max") ? (c > 0 ? v : best) : (c < 0 ? v : best);
    }

    private static long nonNeg(long n, String fn) { if (n < 0) throw Msg.badCall(fn); return n; }

    private static String repeat(String s, long n) {
        if (n > 10_000_000) throw Msg.badCall("String");
        StringBuilder b = new StringBuilder();
        for (long i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    public static boolean zeroArg(String id) { return ZERO_ARG.contains(id); }
}
