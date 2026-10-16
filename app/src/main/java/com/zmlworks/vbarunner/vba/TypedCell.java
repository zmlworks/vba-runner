package com.zmlworks.vbarunner.vba;

/** Variable declared with "As <type>": assignments are converted like VBA does. */
final class TypedCell implements Ref {
    Object v;
    final String type;

    TypedCell(Object v, String type) { this.v = v; this.type = type; }

    @Override public Object get() { return v; }
    @Override public void set(Object nv) { v = coerce(nv, type); }

    static Object initial(String type) {
        if (type == null) return null;
        switch (type) {
            case "integer": case "long": case "byte": case "longlong": case "longptr": return 0L;
            case "single": case "double": case "currency": case "decimal": return 0.0;
            case "string": return "";
            case "boolean": return Boolean.FALSE;
            case "date": return new VDate(0);
            default: return null;
        }
    }

    static Object coerce(Object v, String type) {
        if (type == null || v == Builtins.MISSING) return v;
        switch (type) {
            case "integer": {
                long l = Values.toLong(Values.scalar(v));
                if (l < -32768 || l > 32767) throw Msg.overflow();
                return l;
            }
            case "byte": {
                long l = Values.toLong(Values.scalar(v));
                if (l < 0 || l > 255) throw Msg.overflow();
                return l;
            }
            case "long": {
                long l = Values.toLong(Values.scalar(v));
                if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) throw Msg.overflow();
                return l;
            }
            case "longlong": case "longptr": return Values.toLong(Values.scalar(v));
            case "single": case "double": case "currency": case "decimal": return Values.toDbl(Values.scalar(v));
            case "string": return Values.toStr(Values.scalar(v));
            case "boolean": return Values.toBool(Values.scalar(v));
            case "date": return Values.toDate(v);
            default: return v;
        }
    }
}
