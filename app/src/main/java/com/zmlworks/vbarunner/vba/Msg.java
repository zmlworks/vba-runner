package com.zmlworks.vbarunner.vba;

import java.util.Locale;

/** Japanese / English message selection (Japanese when the device language is Japanese). */
public final class Msg {
    private Msg() {}
    public static volatile boolean japanese = "ja".equals(Locale.getDefault().getLanguage());
    public static String t(String ja, String en) { return japanese ? ja : en; }

    static VbaError typeMismatch() { return VbaError.run(13, t("型が一致しません。", "Type mismatch.")); }
    static VbaError overflow() { return VbaError.run(6, t("オーバーフローしました。", "Overflow.")); }
    static VbaError divZero() { return VbaError.run(11, t("0 で除算しました。", "Division by zero.")); }
    static VbaError subscript() { return VbaError.run(9, t("インデックスが有効範囲にありません。", "Subscript out of range.")); }
    static VbaError badCall(String what) { return VbaError.run(5, t("プロシージャの呼び出し、または引数が不正です: ", "Invalid procedure call or argument: ") + what); }
    static VbaError argCount(String name) { return VbaError.run(450, t("引数の数が一致していません: ", "Wrong number of arguments: ") + name); }
    static VbaError noSub(String name) { return VbaError.run(35, t("Sub または Function が定義されていません: ", "Sub or Function not defined: ") + name); }
    public static VbaError argCountPublic(String name) { return argCount(name); }
    static VbaError noVar(String name) { return VbaError.run(0, t("変数が定義されていません: ", "Variable not defined: ") + name); }
    static VbaError needObject(String name) { return VbaError.run(424, t("オブジェクトが必要です: ", "Object required: ") + name); }
    static VbaError noMember(String obj, String m) { return VbaError.run(438, t("オブジェクトはこのプロパティまたはメソッドをサポートしていません: ", "Object doesn't support this property or method: ") + obj + "." + m); }
}
