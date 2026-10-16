package com.zmlworks.vbarunner.vba;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

public class InterpTest {
    TestHost host;

    @Before public void setUp() { Msg.japanese = true; host = new TestHost(); }

    /** Run module code, then Main if present; return Debug.Print output joined by \n. */
    String run(String code) {
        host = new TestHost();
        Interp in = Interp.compile(code, host);
        in.runModule();
        if (in.hasProc("Main")) in.call("Main");
        return host.text();
    }

    String p(String expr) { return run("Debug.Print " + expr); }

    VbaError runErr(String code) {
        try { run(code); } catch (VbaError e) { return e; }
        fail("expected error for: " + code);
        return null;
    }

    // ---------------------------------------------------------------- operators & values
    @Test public void arithmetic() {
        assertEquals("7", p("1 + 2 * 3"));
        assertEquals("9", p("(1 + 2) * 3"));
        assertEquals("2.5", p("5 / 2"));
        assertEquals("2", p("5 \\ 2"));
        assertEquals("1", p("5 Mod 2"));
        assertEquals("1024", p("2 ^ 10"));
        assertEquals("-9", p("-3 ^ 2"));          // VBA: unary minus binds looser than ^
        assertEquals("0.3", p("0.1 + 0.2"));       // 15 significant digits
        assertEquals("0.333333333333333", p("1 / 3"));
        assertEquals("3", p("7 Mod 4 \\ 1"));      // \ binds tighter than Mod
        assertEquals("2", p("10 - 4 - 4"));
        assertEquals("1E+15", p("10 ^ 15"));
        assertEquals("1E-05", p("1 / 100000"));
        assertEquals("2", p("2.5 \\ 1"));          // banker's rounding of operands
        assertEquals("4", p("3.5 \\ 1"));
        assertEquals("9223372036854775807", p("9223372036854775807"));
        assertEquals("1.84467440737096E+19", p("9223372036854775807 * 2"));   // overflow promotes to Double
    }

    @Test public void stringsAndConcat() {
        assertEquals("ab1", p("\"a\" & \"b\" & 1"));
        assertEquals("12", p("\"1\" + \"2\""));       // + on two strings concatenates
        assertEquals("3", p("\"1\" + 2"));            // string + number adds numerically
        assertEquals("say \"hi\"", p("\"say \"\"hi\"\"\""));
        assertEquals("True", p("1 < 2"));
        assertEquals("False", p("\"a\" = \"A\""));
        assertEquals("True", p("\"10\" = 10"));
        assertEquals("True", p("\"abc\" < \"abd\""));
        assertEquals("日本語OK", p("\"日本\" & \"語\" & \"OK\""));
    }

    @Test public void logic() {
        assertEquals("True", p("True And Not False"));
        assertEquals("False", p("True Xor True"));
        assertEquals("1", p("3 And 5"));
        assertEquals("7", p("3 Or 5"));
        assertEquals("-1", p("CLng(True)"));
        assertEquals("True", p("1 < 2 And 2 < 3"));
        assertEquals("True", p("Not 1 > 2"));
        assertEquals("True", p("\"abc\" Like \"a*\""));
        assertEquals("True", p("\"a1\" Like \"?#\""));
        assertEquals("False", p("\"ab\" Like \"[!a]*\""));
        assertEquals("True", p("\"b\" Like \"[a-c]\""));
    }

    @Test public void comparisonsWithEmpty() {
        assertEquals("True\nTrue", run("Debug.Print x = 0\nDebug.Print x = \"\""));
        assertEquals("True\nTrue", run("Debug.Print IsEmpty(y)\nDebug.Print y & \"\" = \"\""));
    }

    // ---------------------------------------------------------------- statements
    @Test public void kukuPrintsTable() {
        String code = "Sub Main()\n" +
                "    Dim i As Integer, j As Integer, s As String\n" +
                "    For i = 1 To 9\n" +
                "        s = \"\"\n" +
                "        For j = 1 To 9\n" +
                "            s = s & Right(\"  \" & (i * j), 3)\n" +
                "        Next j\n" +
                "        Debug.Print s\n" +
                "    Next i\n" +
                "End Sub\n";
        String out = run(code);
        String[] lines = out.split("\n");
        assertEquals(9, lines.length);
        assertEquals("  1  2  3  4  5  6  7  8  9", lines[0]);
        assertEquals("  9 18 27 36 45 54 63 72 81", lines[8]);
    }

    @Test public void ifForms() {
        String code = "x = 5\n" +
                "If x > 3 Then Debug.Print \"big\" Else Debug.Print \"small\"\n" +
                "If x > 10 Then\n  Debug.Print \"A\"\nElseIf x > 4 Then\n  Debug.Print \"B\"\nElse\n  Debug.Print \"C\"\nEnd If\n" +
                "If x = 5 Then Debug.Print \"one\": Debug.Print \"two\"\n" +
                "If x = 6 Then Debug.Print \"no\": Debug.Print \"no2\"\n" +
                "If x = 5 Then If x > 1 Then Debug.Print \"nested\"\n";
        assertEquals("big\nB\none\ntwo\nnested", run(code));
    }

    @Test public void forStepAndExit() {
        assertEquals("10 8 6 4 2 ", run("For i = 10 To 1 Step -2: s = s & i & \" \": Next\nDebug.Print s"));
        assertEquals("0 0.5 1 ", run("For x = 0 To 1 Step 0.5: s = s & x & \" \": Next x\nDebug.Print s"));
        assertEquals("3", run("For i = 1 To 100\n If i = 3 Then Exit For\nNext\nDebug.Print i"));
        assertEquals("11", run("For i = 1 To 10: Next\nDebug.Print i"));
        assertEquals("", run("For i = 5 To 1\nDebug.Print i\nNext"));
    }

    @Test public void doLoops() {
        assertEquals("5", run("Do While n < 5\n n = n + 1\nLoop\nDebug.Print n"));
        assertEquals("5", run("Do Until n >= 5\n n = n + 1\nLoop\nDebug.Print n"));
        assertEquals("1", run("Do\n n = n + 1\nLoop While n < 0\nDebug.Print n"));
        assertEquals("3", run("Do\n n = n + 1\n If n = 3 Then Exit Do\nLoop\nDebug.Print n"));
        assertEquals("4", run("While n < 4\n n = n + 1\nWend\nDebug.Print n"));
        assertEquals("2", run("Do\n n = n + 1\nLoop Until n >= 2\nDebug.Print n"));
    }

    @Test public void selectCase() {
        String fn = "Function G(x)\n Select Case x\n  Case 1, 2: G = \"low\"\n  Case 3 To 5\n   G = \"mid\"\n  Case \"a\": G = \"letter\"\n" +
                "  Case Is >= 10, -1: G = \"big\"\n  Case Else: G = \"other\"\n End Select\nEnd Function\n";
        assertEquals("low mid big letter other", run(fn + "Debug.Print G(2) & \" \" & G(4) & \" \" & G(99) & \" \" & G(\"a\") & \" \" & G(7)"));
    }

    @Test public void procsByRefByValAndFunctions() {
        String code = "Sub Inc(n)\n n = n + 1\nEnd Sub\n" +
                "Sub IncV(ByVal n)\n n = n + 1\nEnd Sub\n" +
                "Function Fact(n As Long) As Long\n If n <= 1 Then Fact = 1 Else Fact = n * Fact(n - 1)\nEnd Function\n" +
                "Function Greet(name, Optional suffix = \"さん\")\n Greet = name & suffix\nEnd Function\n" +
                "Sub Main()\n a = 1\n Inc a\n Call Inc(a)\n IncV a\n Debug.Print a\n Debug.Print Fact(10)\n" +
                " Debug.Print Greet(\"山田\")\n Debug.Print Greet(\"Bob\", \"!\")\n" +
                " Dim arr(3)\n arr(1) = 5\n Inc arr(1)\n Debug.Print arr(1)\nEnd Sub\n";
        assertEquals("3\n3628800\n山田さん\nBob!\n6", run(code));
    }

    @Test public void exitSubAndFunction() {
        assertEquals("before", run("Sub Main()\n Debug.Print \"before\"\n Exit Sub\n Debug.Print \"after\"\nEnd Sub"));
        assertEquals("1", run("Function F()\n F = 1\n Exit Function\n F = 2\nEnd Function\nDebug.Print F()"));
    }

    @Test public void arraysAndBuiltins() {
        String code = "Dim a(1 To 3) As Long\n a(1) = 10: a(2) = 20: a(3) = \"30\"\n" +
                "For Each v In a: t = t + v: Next\nDebug.Print t\n" +
                "Debug.Print LBound(a) & \"-\" & UBound(a)\n" +
                "b = a: b(1) = 99: Debug.Print a(1)\n" +       // arrays copy on assignment
                "Dim g(2, 3)\n g(2, 3) = \"x\"\nDebug.Print UBound(g, 2) & g(2, 3)\n" +
                "ReDim d(2): d(2) = \"keep\": ReDim Preserve d(5): Debug.Print d(2) & UBound(d)\n" +
                "s = Split(\"a,b,c\", \",\")\nDebug.Print UBound(s) & Join(s, \"|\")\n" +
                "Debug.Print Split(\"x y\")(1)\n" +
                "Debug.Print Join(Array(1, \"two\", 3.5), \";\")";
        assertEquals("60\n1-3\n10\n3x\nkeep5\n2a|b|c\ny\n1;two;3.5", run(code));
    }

    @Test public void stringFunctions() {
        assertEquals("abc", p("Left(\"abcdef\", 3)"));
        assertEquals("ef", p("Right(\"abcdef\", 2)"));
        assertEquals("cd", p("Mid(\"abcdef\", 3, 2)"));
        assertEquals("cdef", p("Mid(\"abcdef\", 3)"));
        assertEquals("3", p("InStr(\"abcabc\", \"c\")"));
        assertEquals("6", p("InStr(4, \"abcabc\", \"c\")"));
        assertEquals("6", p("InStrRev(\"abcabc\", \"c\")"));
        assertEquals("0", p("InStr(\"abc\", \"z\")"));
        assertEquals("a-b-c", p("Replace(\"a b c\", \" \", \"-\")"));
        assertEquals("[x]", p("\"[\" & Trim(\"  x \u3000\") & \"]\""));
        assertEquals("ABC", p("UCase(\"abc\")"));
        assertEquals("5", p("Len(\"あいうえお\")"));
        assertEquals("cba", p("StrReverse(\"abc\")"));
        assertEquals("***", p("String(3, \"*\")"));
        assertEquals("A", p("Chr(65)"));
        assertEquals("65", p("Asc(\"A\")"));
        assertEquals("12.5", p("Val(\"12.5kg\")"));
        assertEquals("0", p("Val(\"abc\")"));
        assertEquals("123", p("Val(\"１２３\")"));     // full-width digits from IME
        assertEquals("FF", p("Hex(255)"));
        assertEquals("255", p("&HFF"));
        assertEquals("ab", p("LCase$(\"AB\")"));
    }

    @Test public void conversionAndInspection() {
        assertEquals("2", p("CInt(2.5)"));
        assertEquals("4", p("CInt(3.5)"));
        assertEquals("-3", p("Int(-2.5)"));
        assertEquals("-2", p("Fix(-2.5)"));
        assertEquals("2.34", p("Round(2.345, 2)"));   // banker's rounding like VBA
        assertEquals("2.36", p("Round(2.355, 2)"));
        assertEquals("2", p("Round(2.5)"));
        assertEquals("True", p("IsNumeric(\"12\")"));
        assertEquals("False", p("IsNumeric(\"12a\")"));
        assertEquals("False", p("IsNumeric(\"\")"));
        assertEquals("String", p("TypeName(\"a\")"));
        assertEquals("Long", p("TypeName(1)"));
        assertEquals("Double", p("TypeName(1.5)"));
        assertEquals("Empty", p("TypeName(zz)"));
        assertEquals("yes", p("IIf(1 > 0, \"yes\", \"no\")"));
        assertEquals("3", p("Abs(-3)"));
        assertEquals("3", p("Sqr(9)"));
        assertEquals("5", p("Max(1, 5, 3)"));
        assertEquals("b", p("Choose(2, \"a\", \"b\")"));
    }

    @Test public void formatFunction() {
        assertEquals("1,234,568", p("Format(1234567.6, \"#,##0\")"));
        assertEquals("0.50", p("Format(0.5, \"0.00\")"));
        assertEquals("007", p("Format(7, \"000\")"));
        assertEquals("12.5%", p("Format(0.125, \"0.0%\")"));
        assertEquals("1,234.50", p("Format(1234.5, \"#,##0.00\")"));
        assertEquals("¥1,000", p("Format(1000, \"¥#,##0\")"));
        assertEquals("3 個", p("Format(3, \"0 \"\"個\"\"\")"));
        assertEquals("-5", p("Format(-5, \"0\")"));
        assertEquals("(5)", p("Format(-5, \"0;(0)\")"));
        assertEquals("2026/03/05", p("Format(DateSerial(2026, 3, 5), \"yyyy/mm/dd\")"));
        assertEquals("2026年3月5日(木)", p("Format(DateSerial(2026, 3, 5), \"yyyy年m月d日(aaa)\")"));
        assertEquals("09:05", p("Format(TimeSerial(9, 5, 0), \"hh:nn\")"));
        assertEquals("09:05", p("Format(TimeSerial(9, 5, 0), \"hh:mm\")"));   // mm after hh = minutes
        assertEquals("abc", p("Format(\"abc\", \"0\")"));
        assertEquals("1234.5", p("Format(1234.5)"));
    }

    @Test public void dates() {
        assertEquals("2026/03/05", p("DateSerial(2026, 3, 5)"));
        assertEquals("2026/04/04", p("DateAdd(\"d\", 30, DateSerial(2026, 3, 5))"));
        assertEquals("30", p("DateDiff(\"d\", DateSerial(2026, 3, 5), DateSerial(2026, 4, 4))"));
        assertEquals("5", p("Weekday(DateSerial(2026, 3, 5))"));   // Thursday
        assertEquals("2026", p("Year(CDate(\"2026/03/05 10:20\"))"));
        assertEquals("20", p("Minute(CDate(\"2026/03/05 10:20\"))"));
        assertEquals("True", p("Year(Now) >= 2026"));
        assertEquals("True", p("Timer >= 0"));
        assertEquals("1", p("DateSerial(2026, 3, 6) - DateSerial(2026, 3, 5)"));
    }

    @Test public void caseInsensitiveNamesAndComments() {
        String code = "Option Explicit\n' comment\nRem another comment\nDIM Total AS LONG\n" +
                "total = 5 ' trailing\nDEBUG.PRINT ToTaL\n" +
                "Dim longLine\nlongLine = 1 + _\n   2\nDebug.Print longLine";
        assertEquals("5\n3", run(code));
    }

    @Test public void debugPrintSeparators() {
        assertEquals("ab", run("Debug.Print \"a\";\nDebug.Print \"b\""));
        assertEquals("a1", run("Debug.Print \"a\"; 1"));
        assertEquals("a             b", run("Debug.Print \"a\", \"b\""));
        assertEquals("", run("Debug.Print"));
        assertEquals("(3)", run("Debug.Print (1 + 2) & \")\"").replace("3)", "(3)").substring(0, 3));
    }

    @Test public void typedVariablesCoerce() {
        // a common mistake: text box values are strings; As Long converts them
        assertEquals("7", run("Dim a As Long, b As Long\na = \"3\": b = \"4\"\nDebug.Print a + b"));
        assertEquals("34", run("a = \"3\": b = \"4\"\nDebug.Print a + b"));
        assertEquals("0|False|", run("Dim n As Integer, f As Boolean, s As String\nDebug.Print n & \"|\" & f & \"|\" & s"));
        assertEquals(6, runErr("Dim n As Integer\nn = 40000").number);
        assertEquals(13, runErr("Dim n As Long\nn = \"abc\"").number);
    }

    @Test public void moduleVariablesPersistBetweenCalls() {
        Interp in = Interp.compile("Dim count\nSub Button1_Click()\n count = count + 1\n Debug.Print count\nEnd Sub", host);
        in.runModule();
        in.call("Button1_Click");
        in.call("Button1_Click");
        in.call("button1_click");
        assertEquals("1\n2\n3", host.text());
    }

    @Test public void dimInsideLoopKeepsValue() {
        assertEquals("3", run("For i = 1 To 3\n Dim c\n c = c + 1\nNext\nDebug.Print c"));
    }

    @Test public void constants() {
        assertEquals("3.14", run("Const PI = 3.14\nDebug.Print PI"));
        assertEquals("a\r\nb", run("Debug.Print \"a\" & vbCrLf & \"b\""));
        assertEquals("6 7 1", run("Debug.Print vbYes & \" \" & vbNo & \" \" & vbOK"));
        assertEquals("定数には代入できません。", runErr("Const A = 1\nA = 2").getMessage());
    }

    // ---------------------------------------------------------------- dialogs & stop
    @Test public void msgBoxAndInputBox() {
        host = new TestHost();
        host.msgAnswers.add(6);
        host.inputAnswers.add("太郎");
        Interp in = Interp.compile("Sub Main()\n If MsgBox(\"続けますか?\", vbYesNo, \"確認\") = vbYes Then\n" +
                "  n = InputBox(\"名前は?\", \"入力\", \"名無し\")\n  MsgBox \"こんにちは \" & n\n End If\n" +
                " MsgBox \"x\", , \"t\"\nEnd Sub", host);
        in.runModule();
        in.call("Main");
        assertEquals("[MsgBox:続けますか?|4|確認, InputBox:名前は?|入力|名無し, MsgBox:こんにちは 太郎|0|, MsgBox:x|0|t]", host.dialogs.toString());
    }

    @Test public void inputBoxCancelReturnsEmptyString() {
        assertEquals("[]", run("x = InputBox(\"?\")\nDebug.Print \"[\" & x & \"]\""));
    }

    @Test public void endStopsEverything() {
        host = new TestHost();
        Interp in = Interp.compile("Sub Main()\n Debug.Print 1\n Sub2\n Debug.Print 3\nEnd Sub\nSub Sub2()\n End\nEnd Sub", host);
        in.runModule();
        try { in.call("Main"); fail(); } catch (Interp.Halt h) { assertFalse(h.byUser); }
        assertEquals("1", host.text());
        try { run("Debug.Print 1\nStop\nDebug.Print 2"); fail(); } catch (Interp.Halt h) { assertEquals("1", host.text()); }
    }

    @Test public void requestStopHaltsInfiniteLoop() throws Exception {
        final Interp in = Interp.compile("Do\n n = n + 1\nLoop", host);
        final Throwable[] got = new Throwable[1];
        Thread t = new Thread(() -> { try { in.runModule(); } catch (Throwable e) { got[0] = e; } });
        t.start();
        Thread.sleep(100);
        in.requestStop();
        t.join(2000);
        assertFalse(t.isAlive());
        assertTrue(got[0] instanceof Interp.Halt);
        assertTrue(((Interp.Halt) got[0]).byUser);
    }

    @Test public void sleepUsesHost() {
        run("Sleep 250\nSleep 50");
        assertEquals(300, host.slept);
    }

    // ---------------------------------------------------------------- errors
    @Test public void runtimeErrorsHaveLineAndNumber() {
        VbaError e = runErr("a = 1\nb = 0\nc = a / b");
        assertEquals(11, e.number);
        assertEquals(3, e.line);
        assertEquals("行 3: 0 で除算しました。 (11)", e.display());

        e = runErr("Sub Main()\n x = 1\n Foo 1\nEnd Sub");
        assertEquals(35, e.number);
        assertEquals(3, e.line);
        assertEquals("行 3 (Main): Sub または Function が定義されていません: Foo (35)", e.display());

        e = runErr("Dim a(2)\na(3) = 1");
        assertEquals(9, e.number);
        e = runErr("x = \"abc\" * 2");
        assertEquals(13, e.number);
        e = runErr("Function F(a)\nEnd Function\nx = F(1, 2)");
        assertEquals(450, e.number);
        e = runErr("Option Explicit\nx = 1");
        assertTrue(e.getMessage().contains("変数が定義されていません"));
        e = runErr("x = Text1.Value");
        assertEquals(424, e.number);
    }

    @Test public void errorInsideCalledProcReportsInnerLine() {
        VbaError e = runErr("Sub A()\n B\nEnd Sub\nSub B()\n x = 1 / 0\nEnd Sub\nSub Main()\n A\nEnd Sub");
        assertEquals(5, e.line);
        assertEquals("B", e.proc);
    }

    @Test public void onErrorResumeNext() {
        String code = "On Error Resume Next\nx = 1 / 0\nDebug.Print Err.Number & \" \" & Err.Description\nErr.Clear\nDebug.Print Err.Number\n" +
                "On Error GoTo 0\nDebug.Print \"ok\"";
        assertEquals("11 0 で除算しました。\n0\nok", run(code));
        assertEquals(1234, runErr("Err.Raise 1234, , \"自作エラー\"").number);
    }

    @Test public void recursionLimit() {
        VbaError e = runErr("Sub R()\n R\nEnd Sub\nR");
        assertEquals(28, e.number);
    }

    @Test public void syntaxErrorsHaveLines() {
        assertSyntax("Sub Main()\n x = 1\n", 1, "End Sub がありません");
        assertSyntax("If x Then\n y = 1\n", 1, "End If がありません");
        assertSyntax("For i = 1 To 3\n", 1, "Next がありません");
        assertSyntax("x = \"abc\n", 1, "文字列が閉じられていません");
        assertSyntax("x = 1 +\n", 1, "予期しない");
        assertSyntax("y = 1\nx = (1 + 2\n", 2, "')' が必要です");
        assertSyntax("x = 1\nExit For\n", 2, "Exit For が For の外にあります");
        assertSyntax("x ＝ 1", 1, "全角");
        assertSyntax("For i = 1 To 2\nNext j", 2, "Next の変数が For と違います");
        assertSyntax("Sub A()\nEnd Sub\nSub A()\nEnd Sub", 3, "名前が重複しています");
        assertSyntax("GoTo L1", 1, "サポートしていません");
        assertSyntax("Select Case x\nCase 1\n", 1, "End Select がありません");
        assertSyntax("If a Then\nElse If b Then\nEnd If", 2, "ElseIf");
    }

    private void assertSyntax(String code, int line, String contains) {
        try {
            Parser.parse(code);
            fail("expected syntax error: " + code);
        } catch (VbaError e) {
            assertTrue(e.syntax);
            assertEquals(code, line, e.line);
            assertTrue(e.getMessage() + " should contain " + contains, e.getMessage().contains(contains));
        }
    }

    @Test public void statementCallForms() {
        String code = "Sub Show(a, Optional b = \"-\")\n Debug.Print a & b\nEnd Sub\n" +
                "Show 1\nShow 1, 2\nShow (3)\nShow (1) + 1\nCall Show(5, 6)\nShow(7)";
        assertEquals("1-\n12\n3-\n2-\n56\n7-", run(code));
    }

    @Test public void zeroArgFunctionCalls() {
        assertEquals("42", run("Function Answer()\n Answer = 42\nEnd Function\nDebug.Print Answer"));
        assertEquals("True", run("Debug.Print Len(Now) > 0"));
    }

    @Test public void englishMessages() {
        Msg.japanese = false;
        try {
            VbaError e = runErr("x = 1 / 0");
            assertEquals("Line 1: Division by zero. (11)", e.display());
        } finally { Msg.japanese = true; }
    }

    @Test public void japaneseIdentifiers() {
        assertEquals("15", run("合計 = 0\nFor 番号 = 1 To 5: 合計 = 合計 + 番号: Next\nDebug.Print 合計"));
    }
}
