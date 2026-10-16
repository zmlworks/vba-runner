package com.zmlworks.vbarunner.vba;

/**
 * Object exposed to scripts (form controls, Storage, Err, Form). Property and method names arrive lower-cased.
 * Implementations throw {@link VbaError} (e.g. via {@link #noMember}) for unknown members.
 */
public interface HostObject {
    String typeName();
    Object get(String prop);
    void set(String prop, Object value);
    /** Method call or indexed property read: Combo1.AddItem "a", Grid1.Cells(1, 2), Storage.Get("k"). */
    Object call(String method, Object[] args);
    /** Indexed property write: Grid1.Cells(1, 2) = 5. */
    default void setIndexed(String prop, Object[] args, Object value) { throw noMember(prop); }
    /** Default property ("Label1 = x" / "x = Text1"); null if none. */
    default String defaultProp() { return null; }

    default VbaError noMember(String m) { return Msg.noMember(typeName(), m); }
}
