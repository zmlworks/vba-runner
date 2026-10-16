package com.zmlworks.vbarunner.core;

import com.zmlworks.vbarunner.vba.Builtins;
import com.zmlworks.vbarunner.vba.HostObject;
import com.zmlworks.vbarunner.vba.Msg;
import com.zmlworks.vbarunner.vba.VArray;
import com.zmlworks.vbarunner.vba.VDate;
import com.zmlworks.vbarunner.vba.Values;
import com.zmlworks.vbarunner.vba.VbaError;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-script key/value store exposed as the "Storage" object. Every Set/Remove/Clear is written through to the
 * backend immediately (so a crash or swipe-away keeps the data). Values keep their type: number, Boolean, string.
 */
public final class ScriptStorage implements HostObject {
    public interface Backend {
        String load();
        void save(String json);
    }

    public static final int MAX_KEYS = 1000;
    public static final int MAX_TOTAL_CHARS = 1_000_000;

    private final Backend backend;
    private final Map<String, Object> map = new LinkedHashMap<>();

    public ScriptStorage(Backend backend) {
        this.backend = backend;
        String s = backend.load();
        if (s != null && !s.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(s);
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.get(k);
                    if (v instanceof Integer) v = ((Integer) v).longValue();
                    else if (v instanceof Number && !(v instanceof Long) && !(v instanceof Double)) v = ((Number) v).doubleValue();
                    else if (v == JSONObject.NULL) v = null;
                    map.put(k, v);
                }
            } catch (JSONException e) {
                // corrupt file: start empty rather than failing the script
            }
        }
    }

    public synchronized Map<String, Object> snapshot() { return new LinkedHashMap<>(map); }

    private void persist() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : map.entrySet()) o.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
        } catch (JSONException e) {
            throw VbaError.run(5, Msg.t("保存できない値です。", "Value cannot be stored."));
        }
        String json = o.toString();
        if (json.length() > MAX_TOTAL_CHARS) throw VbaError.run(7, Msg.t("Storage の容量 (1MB) を超えました。", "Storage limit (1 MB) exceeded."));
        backend.save(json);
    }

    @Override public String typeName() { return "Storage"; }

    @Override public Object get(String p) {
        switch (p) {
            case "count": synchronized (this) { return (long) map.size(); }
            case "keys": case "clear": return call(p, new Object[0]);
            default: throw noMember(p);
        }
    }

    @Override public void set(String p, Object v) { throw noMember(p); }

    private static boolean has(Object[] a, int i) { return i < a.length && a[i] != Builtins.MISSING; }

    private static String key(Object[] a, String fn) {
        if (!has(a, 0)) throw Msg.argCountPublic("Storage." + fn);
        String k = Values.toStr(Values.scalar(a[0]));
        if (k.isEmpty()) throw VbaError.run(5, Msg.t("キーが空です: Storage.", "Empty key: Storage.") + fn);
        return k;
    }

    @Override public synchronized Object call(String m, Object[] a) {
        switch (m) {
            case "get": {
                String k = key(a, "Get");
                if (map.containsKey(k)) return map.get(k);
                return has(a, 1) ? Values.scalar(a[1]) : null;
            }
            case "set": {
                String k = key(a, "Set");
                if (a.length < 2 || a[1] == Builtins.MISSING) throw Msg.argCountPublic("Storage.Set");
                Object v = Values.scalar(a[1]);
                if (v instanceof VArray || v instanceof HostObject)
                    throw VbaError.run(13, Msg.t("Storage には数値・文字列・True/False だけ保存できます (配列は Join で文字列に)", "Storage only holds numbers, strings and True/False (Join arrays into a string)"));
                if (v instanceof VDate) v = Values.toStr(v);
                if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) v = Values.toStr(v);
                if (!map.containsKey(k) && map.size() >= MAX_KEYS) throw VbaError.run(7, Msg.t("Storage のキーは 1000 個までです。", "Storage holds at most 1000 keys."));
                map.put(k, v);
                persist();
                return null;
            }
            case "exists": return map.containsKey(key(a, "Exists"));
            case "remove": {
                map.remove(key(a, "Remove"));
                persist();
                return null;
            }
            case "clear": map.clear(); persist(); return null;
            case "keys": {
                List<Object> ks = new ArrayList<>(map.keySet());
                return ks.isEmpty() ? new VArray(new int[]{0}, new int[]{-1}) : VArray.of(ks.toArray());
            }
            case "count": return (long) map.size();
            default: throw noMember(m);
        }
    }
}
