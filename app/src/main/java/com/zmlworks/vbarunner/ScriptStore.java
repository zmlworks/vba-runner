package com.zmlworks.vbarunner;

import android.content.Context;
import android.content.SharedPreferences;

import com.zmlworks.vbarunner.core.ScriptDoc;
import com.zmlworks.vbarunner.core.ScriptStorage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Scripts live in files/scripts/&lt;id&gt;.bas (the plain-text ScriptDoc format); per-script Storage in files/storage/&lt;id&gt;.json. */
final class ScriptStore {
    static final class Entry {
        final String id;
        final String name;
        final long modified;
        final int controls;
        Entry(String id, String name, long modified, int controls) { this.id = id; this.name = name; this.modified = modified; this.controls = controls; }
    }

    private final Context ctx;
    private final File dir, storageDir;

    ScriptStore(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "scripts");
        storageDir = new File(ctx.getFilesDir(), "storage");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        storageDir.mkdirs();
    }

    List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".bas"));
        if (fs == null) return out;
        for (File f : fs) {
            try {
                ScriptDoc d = ScriptDoc.parse(read(f));
                String id = f.getName().substring(0, f.getName().length() - 4);
                out.add(new Entry(id, d.name.isEmpty() ? id : d.name, f.lastModified(), d.controls.size()));
            } catch (IOException ignored) {
                // unreadable file: skip
            }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    ScriptDoc load(String id) throws IOException { return ScriptDoc.parse(read(file(id))); }

    void save(String id, ScriptDoc d) throws IOException { write(file(id), d.serialize()); }

    String create(ScriptDoc d) throws IOException {
        String id = "s" + Long.toString(System.currentTimeMillis(), 36);
        while (file(id).exists()) id = id + "x";
        save(id, d);
        return id;
    }

    void delete(String id) {
        //noinspection ResultOfMethodCallIgnored
        file(id).delete();
        //noinspection ResultOfMethodCallIgnored
        storageFile(id).delete();
    }

    boolean exists(String id) { return id != null && file(id).exists(); }

    ScriptStorage storage(String id) {
        final File f = storageFile(id);
        return new ScriptStorage(new ScriptStorage.Backend() {
            @Override public String load() {
                try { return f.exists() ? read(f) : null; } catch (IOException e) { return null; }
            }
            @Override public void save(String json) {
                try { write(f, json); } catch (IOException e) { throw new RuntimeException(e); }
            }
        });
    }

    void clearStorage(String id) {
        //noinspection ResultOfMethodCallIgnored
        storageFile(id).delete();
    }

    /** Copy bundled samples (once, or again on request). Returns how many were added. */
    int installSamples(boolean force) {
        SharedPreferences p = ctx.getSharedPreferences("store", Context.MODE_PRIVATE);
        if (!force && p.getBoolean("samples_v2", false)) return 0;
        int n = 0;
        try {
            String[] names = ctx.getAssets().list("samples");
            if (names != null) {
                Arrays.sort(names);
                List<Entry> existing = list();
                for (String a : names) {
                    if (!a.endsWith(".bas")) continue;
                    ScriptDoc d = ScriptDoc.parse(readAsset("samples/" + a));
                    boolean dup = false;
                    for (Entry e : existing) if (e.name.equals(d.name)) dup = true;
                    if (dup) continue;
                    create(d);
                    n++;
                    try { Thread.sleep(2); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        } catch (IOException ignored) {
            // assets missing: nothing to install
        }
        p.edit().putBoolean("samples_v2", true).apply();
        return n;
    }

    String readAsset(String path) throws IOException {
        try (InputStream in = ctx.getAssets().open(path)) { return readAll(in); }
    }

    private File file(String id) { return new File(dir, safe(id) + ".bas"); }
    private File storageFile(String id) { return new File(storageDir, safe(id) + ".json"); }

    private static String safe(String id) { return id.replaceAll("[^A-Za-z0-9_-]", "_"); }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 4_000_000) throw new IOException("file too large");
        }
        String s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    /** Atomic write: temp file + rename. */
    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
        }
    }
}
