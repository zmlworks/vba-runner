package com.zmlworks.vbarunner.core;

import com.zmlworks.vbarunner.vba.HostObject;
import com.zmlworks.vbarunner.vba.Values;

/** "Form" object: Form.Caption (top-bar title), Form.Width / Form.Height (visible area in dp, read-only). */
public final class FormObject implements HostObject {
    public interface Listener { void onCaption(String caption); }

    private volatile String caption;
    private volatile int width = 360, height = 640;
    private final Listener listener;

    public FormObject(String caption, Listener listener) { this.caption = caption; this.listener = listener; }

    public void setSize(int wDp, int hDp) { width = wDp; height = hDp; }
    public String caption() { return caption; }

    @Override public String typeName() { return "Form"; }
    @Override public String defaultProp() { return "caption"; }

    @Override public Object get(String p) {
        switch (p) {
            case "caption": return caption;
            case "width": return (long) width;
            case "height": return (long) height;
            default: throw noMember(p);
        }
    }

    @Override public void set(String p, Object v) {
        if (!p.equals("caption")) throw noMember(p);
        caption = Values.toStr(Values.scalar(v));
        if (listener != null) listener.onCaption(caption);
    }

    @Override public Object call(String m, Object[] a) {
        if (a.length == 0) return get(m);
        throw noMember(m);
    }
}
