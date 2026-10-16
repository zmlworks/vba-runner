package com.zmlworks.vbarunner.vba;

/** A storage location (variable, array element, property). Used for ByRef arguments. */
public interface Ref {
    Object get();
    void set(Object v);

    final class Cell implements Ref {
        public Object v;
        final boolean constant;
        Cell(Object v) { this(v, false); }
        Cell(Object v, boolean constant) { this.v = v; this.constant = constant; }
        @Override public Object get() { return v; }
        @Override public void set(Object nv) {
            if (constant) throw VbaError.run(0, Msg.t("定数には代入できません。", "Cannot assign to a constant."));
            v = nv;
        }
    }
}
