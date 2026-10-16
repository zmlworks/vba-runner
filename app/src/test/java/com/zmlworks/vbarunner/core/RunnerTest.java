package com.zmlworks.vbarunner.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zmlworks.vbarunner.vba.Msg;
import com.zmlworks.vbarunner.vba.VbaError;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

public class RunnerTest {

    /** Fake UI: callbacks run synchronously on the calling thread (script thread). */
    static class FakeUi implements ScriptRunner.Ui {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final List<String> dialogs = Collections.synchronizedList(new ArrayList<>());
        final List<ScriptRunner.State> states = Collections.synchronizedList(new ArrayList<>());
        final Deque<Integer> msgAnswers = new ArrayDeque<>();
        final Deque<String> inputAnswers = new ArrayDeque<>();
        volatile VbaError error;
        volatile ScriptRunner.Reply<Integer> heldMsg;   // when holdDialogs, MsgBox stays open
        volatile boolean holdDialogs;

        @Override public void onPrint(List<String> lines) { log.addAll(lines); }
        @Override public void onState(ScriptRunner.State s) { states.add(s); }
        @Override public void onError(VbaError e) { error = e; }
        @Override public void showMsgBox(String prompt, int buttons, String title, ScriptRunner.Reply<Integer> reply) {
            dialogs.add("MsgBox:" + prompt);
            if (holdDialogs) { heldMsg = reply; return; }
            synchronized (msgAnswers) { reply.answer(msgAnswers.isEmpty() ? 1 : msgAnswers.poll()); }
        }
        @Override public void showInputBox(String prompt, String title, String def, ScriptRunner.Reply<String> reply) {
            dialogs.add("InputBox:" + prompt + "|" + def);
            synchronized (inputAnswers) { reply.answer(inputAnswers.isEmpty() ? null : inputAnswers.poll()); }
        }
    }

    static class MemBackend implements ScriptStorage.Backend {
        String json;
        int saves;
        @Override public String load() { return json; }
        @Override public void save(String j) { json = j; saves++; }
    }

    static final Executor DIRECT = Runnable::run;

    FakeUi ui;
    MemBackend mem;

    @Before public void setUp() { Msg.japanese = true; ui = new FakeUi(); mem = new MemBackend(); }

    ScriptRunner start(String text) throws Exception {
        ScriptDoc d = ScriptDoc.parse(text);
        ScriptRunner r = new ScriptRunner(d, new ScriptStorage(mem), new FormObject(d.name, null), DIRECT, ui);
        r.start();
        assertTrue("settle", r.awaitSettled(3000));
        return r;
    }

    void tap(ScriptRunner r, String proc, Object... args) throws Exception {
        r.post(proc, args);
        Thread.sleep(5);
        assertTrue(r.awaitSettled(3000));
    }

    static String sample(String file) throws Exception {
        File f = new File("src/main/assets/samples/" + file);
        if (!f.exists()) f = new File("app/src/main/assets/samples/" + file);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ ScriptDoc format
    @Test public void docRoundTrip() {
        String text = "'@form name=\"テスト \"\"引用\"\"\"\n" +
                "'@TextBox Text1 x=16 y=20 w=150 h=48 text=\"初期\"\n" +
                "'@ComboBox Combo1 x=1 y=2 w=3 h=40 items=\"a|b c|ｄ\"\n" +
                "'@Grid Grid1 x=0 y=0 w=300 h=200 rows=4 cols=2 font=12\n" +
                "'@Unknown Foo x=1\n" +
                "\n" +
                "Sub Main()\n  Debug.Print 1\nEnd Sub\n";
        ScriptDoc d = ScriptDoc.parse(text);
        assertEquals("テスト \"引用\"", d.name);
        assertEquals(3, d.controls.size());
        assertEquals("初期", d.find("text1").caption);
        assertEquals("[a, b c, ｄ]", d.find("Combo1").items.toString());
        assertEquals(4, d.find("Grid1").rows);
        assertEquals(12, d.find("Grid1").fontSize);
        assertEquals(16, d.find("Combo1").w);   // clamped minimum size
        assertEquals("Sub Main()\n  Debug.Print 1\nEnd Sub\n", d.code);   // code line 1 = Sub Main
        ScriptDoc d2 = ScriptDoc.parse(d.serialize());
        assertEquals(d.serialize(), d2.serialize());
        assertEquals(d.code, d2.code);
    }

    @Test public void plainCodeImport() {
        ScriptDoc d = ScriptDoc.parse("Sub Main()\r\nMsgBox 1\r\nEnd Sub");
        assertEquals(0, d.controls.size());
        assertEquals("Sub Main()\nMsgBox 1\nEnd Sub\n", d.code);
    }

    @Test public void pastedFromAiChat() {
        String chat = "はい、こちらです。\n\n```vba\n'@form name=\"AI作\"\n'@Button Button1 x=8 y=8 w=100 h=48 caption=\"押す\"\n" +
                "Sub Button1_Click()\n    MsgBox \"hi\"\nEnd Sub\n```\n\n使い方: ...\n```\nother\n```";
        ScriptDoc d = ScriptDoc.fromPasted(chat);
        assertEquals("AI作", d.name);
        assertEquals(1, d.controls.size());
        assertEquals("Sub Button1_Click()\n    MsgBox \"hi\"\nEnd Sub\n", d.code);
        ScriptDoc plain = ScriptDoc.fromPasted("```\nSub Main()\nEnd Sub\n```");
        assertEquals("Sub Main()\nEnd Sub\n", plain.code);
        assertEquals("x = 1\n", ScriptDoc.fromPasted("x = 1").code);
    }

    @Test public void renameIdentifierInCode() {
        String code = "Sub Button1_Click()\n  Button1.Caption = \"Button1\" ' Button1 comment\n  x = Button10 + Text1.Button1\nEnd Sub\n";
        String r = ScriptDoc.renameIdentifier(code, "Button1", "OkButton");
        assertEquals("Sub OkButton_Click()\n  OkButton.Caption = \"Button1\" ' Button1 comment\n  x = Button10 + Text1.Button1\nEnd Sub\n", r);
        assertTrue(ScriptDoc.hasSub(r, "okbutton_click"));
        assertEquals(0, ScriptDoc.subLine(r, "OkButton_Click"));
        assertFalse(ScriptDoc.hasSub(r, "Button1_Click"));
    }

    @Test public void nextNameAndValidity() {
        ScriptDoc d = ScriptDoc.parse("'@Button Button1 x=0 y=0 w=10 h=10\n'@Button Button3 x=0 y=0 w=10 h=10\n");
        assertEquals("Button2", d.nextName(ControlSpec.Type.BUTTON));
        assertEquals("Text1", d.nextName(ControlSpec.Type.TEXTBOX));
        assertTrue(ControlSpec.validName("合計ラベル"));
        assertFalse(ControlSpec.validName("1abc"));
        assertFalse(ControlSpec.validName("a b"));
        assertFalse(ControlSpec.validName("Storage"));
    }

    // ------------------------------------------------------------------ controls
    @Test public void formControlsAndEvents() throws Exception {
        ScriptRunner r = start("'@TextBox Text1 x=0 y=0 w=100 h=40\n'@TextBox Text2 x=0 y=0 w=100 h=40\n" +
                "'@Button Button1 x=0 y=0 w=100 h=40 caption=\"計算\"\n'@Label Label1 x=0 y=0 w=100 h=40\n" +
                "Sub Button1_Click()\n Label1.Caption = CDbl(Text1.Value) + CDbl(Text2.Value)\n Button1.Caption = \"もう一度\"\nEnd Sub\n");
        assertEquals(ScriptRunner.State.WAITING, r.state());
        r.control("Text1").userSetText("1.5");
        r.control("Text2").userSetText("2");
        tap(r, "Button1_Click");
        assertEquals("3.5", r.control("Label1").text());
        assertEquals("もう一度", r.control("Button1").text());
        assertNull(ui.error);
        r.stop();
        assertTrue(r.join(2000));
        assertEquals(ScriptRunner.State.STOPPED, r.state());
    }

    @Test public void comboBoxApi() throws Exception {
        ScriptRunner r = start("'@ComboBox Combo1 x=0 y=0 w=100 h=40 items=\"A|B\"\n'@Label Label1 x=0 y=0 w=1 h=1\n" +
                "Sub Combo1_Change()\n Label1 = Combo1.Text & \"/\" & Combo1.ListIndex & \"/\" & Combo1.ListCount\nEnd Sub\n" +
                "Sub Form_Load()\n Combo1.AddItem \"C\"\n Combo1.AddItem \"Z\", 0\n Combo1.RemoveItem 1\n Debug.Print Join(Combo1.List, \",\") & \" \" & Combo1.List(0) & \" \" & Combo1.Text\n" +
                " Combo1.List(0) = \"Y\"\n Combo1.Text = \"C\"\n Debug.Print Combo1.ListIndex\nEnd Sub\n");
        // removing the selected item clears the selection -> Text = ""
        assertEquals("[Z,B,C Z , 2]", ui.log.toString());
        assertTrue(r.control("Combo1").userSelect(1));
        tap(r, "Combo1_Change");
        assertEquals("B/1/3", r.control("Label1").text());
        r.stop();
    }

    @Test public void comboBoxListIndexDefault() {
        ControlSpec s = new ControlSpec(ControlSpec.Type.COMBOBOX, "Combo1");
        s.items.add("x"); s.items.add("y");
        ControlState c = new ControlState(s);
        assertEquals(0, c.listIndex());
        assertEquals("x", c.text());
        s.caption = "y";
        assertEquals("y", new ControlState(s).text());
        assertEquals(-1, new ControlState(new ControlSpec(ControlSpec.Type.COMBOBOX, "C")).listIndex());
    }

    @Test public void comboBoxInvalidTextIsError() throws Exception {
        start("'@ComboBox Combo1 x=0 y=0 w=100 h=40 items=\"A|B\"\nSub Form_Load()\n Combo1.Text = \"Q\"\nEnd Sub\nSub Combo1_Change()\nEnd Sub");
        assertNotNull(ui.error);
        assertEquals(380, ui.error.number);
        assertEquals(2, ui.error.line);   // layout ('@) lines are not part of the code tab
    }

    @Test public void gridApi() throws Exception {
        ScriptRunner r = start("'@Grid Grid1 x=0 y=0 w=100 h=100 rows=2 cols=2\n'@Label Label1 x=0 y=0 w=1 h=1\n" +
                "Sub Form_Load()\n Grid1.Cells(1, 1) = 5\n Grid1.Cells(2, 2) = \"x\"\n Grid1.Rows = 3\n Grid1.Cells(3, 1) = Grid1.Cells(1, 1) * 2\nEnd Sub\n" +
                "Sub Grid1_Click(Row, Col)\n Label1.Caption = Row & \",\" & Col & \"=\" & Grid1.Cells(Row, Col)\nEnd Sub\n");
        assertEquals("5", r.control("Grid1").cellText(0, 0));
        assertEquals("x", r.control("Grid1").cellText(1, 1));
        assertEquals("10", r.control("Grid1").cellText(2, 0));
        assertEquals(3, r.control("Grid1").rows());
        tap(r, "Grid1_Click", 2L, 2L);
        assertEquals("2,2=x", r.control("Label1").text());
        r.stop();
    }

    @Test public void gridOutOfRangeMentionsSize() throws Exception {
        start("'@Grid Grid1 x=0 y=0 w=100 h=100 rows=2 cols=2\nSub Form_Load()\n Grid1.Cells(3, 1) = 1\nEnd Sub\nSub Grid1_Click(r, c)\nEnd Sub");
        assertEquals(9, ui.error.number);
        assertTrue(ui.error.getMessage(), ui.error.getMessage().contains("Rows=2"));
    }

    @Test public void controlCommonProps() throws Exception {
        ScriptRunner r = start("'@Button Button1 x=10 y=20 w=100 h=40\n" +
                "Sub Form_Load()\n Button1.Visible = False\n Button1.Enabled = False\n Button1.Left = Button1.Left + 5\n Button1.Width = 50\n Debug.Print Button1.Top & \" \" & TypeName(Button1)\nEnd Sub\nSub Button1_Click()\nEnd Sub");
        ControlState b = r.control("Button1");
        assertFalse(b.visible());
        assertFalse(b.enabled());
        assertEquals(15, b.bounds()[0]);
        assertEquals(50, b.bounds()[2]);
        assertEquals("[20 Button]", ui.log.toString());
        r.stop();
    }

    @Test public void unknownMemberError() throws Exception {
        start("'@Label Label1 x=0 y=0 w=1 h=1\nLabel1.Colour = 1");
        assertEquals(438, ui.error.number);
        assertTrue(ui.error.getMessage().contains("Label.colour"));
        assertEquals(1, ui.error.line);
    }

    // ------------------------------------------------------------------ runner lifecycle
    @Test public void syntaxErrorIsReportedNotSilent() throws Exception {
        ScriptRunner r = start("Sub Main()\n x = (1 + \nEnd Sub");
        assertEquals(ScriptRunner.State.ERROR, r.state());
        assertNotNull(ui.error);
        assertTrue(ui.error.syntax);
        assertEquals(2, ui.error.line);
        assertTrue(ui.error.display().startsWith("行 2: 構文エラー: "));
    }

    @Test public void runtimeErrorInEventStopsScriptWithLine() throws Exception {
        ScriptRunner r = start("'@Button Button1 x=0 y=0 w=1 h=1\nSub Button1_Click()\n Debug.Print \"before\"\n x = 1 / 0\nEnd Sub");
        tap(r, "Button1_Click");
        assertEquals(ScriptRunner.State.ERROR, r.state());
        assertEquals(11, ui.error.number);
        assertEquals(3, ui.error.line);
        assertEquals("Button1_Click", ui.error.proc);
        assertEquals("[before]", ui.log.toString());   // output before the error is kept
        r.post("Button1_Click");                         // ignored after error
        Thread.sleep(20);
        assertEquals("[before]", ui.log.toString());
    }

    @Test public void plainScriptWithoutEventsEnds() throws Exception {
        ScriptRunner r = start("Sub Main()\n Debug.Print \"hi\"\nEnd Sub");
        assertEquals(ScriptRunner.State.ENDED, r.state());
        assertEquals("[hi]", ui.log.toString());
        assertNull(ui.error);
    }

    @Test public void endStatementEndsRun() throws Exception {
        ScriptRunner r = start("'@Button Button1 x=0 y=0 w=1 h=1\nSub Button1_Click()\n End\n Debug.Print \"no\"\nEnd Sub");
        assertEquals(ScriptRunner.State.WAITING, r.state());
        tap(r, "Button1_Click");
        assertEquals(ScriptRunner.State.ENDED, r.state());
        assertTrue(ui.log.isEmpty());
        assertNull(ui.error);
    }

    @Test public void stopDuringInfiniteLoop() throws Exception {
        ScriptDoc d = ScriptDoc.parse("Sub Main()\n Do\n  n = n + 1\n Loop\nEnd Sub");
        ScriptRunner r = new ScriptRunner(d, new ScriptStorage(mem), new FormObject("", null), DIRECT, ui);
        r.start();
        Thread.sleep(100);
        assertEquals(ScriptRunner.State.RUNNING, r.state());
        r.stop();
        assertTrue(r.join(2000));
        assertEquals(ScriptRunner.State.STOPPED, r.state());
        assertNull(ui.error);
    }

    @Test public void stopWhileMsgBoxOpen() throws Exception {
        ui.holdDialogs = true;
        ScriptDoc d = ScriptDoc.parse("Sub Main()\n MsgBox \"wait\"\n Debug.Print \"after\"\nEnd Sub");
        ScriptRunner r = new ScriptRunner(d, new ScriptStorage(mem), new FormObject("", null), DIRECT, ui);
        r.start();
        for (int i = 0; i < 200 && ui.heldMsg == null; i++) Thread.sleep(5);
        assertNotNull("MsgBox shown", ui.heldMsg);
        assertEquals(ScriptRunner.State.RUNNING, r.state());   // blocked inside MsgBox
        r.stop();
        assertTrue(r.join(2000));
        assertEquals(ScriptRunner.State.STOPPED, r.state());
        assertTrue(ui.log.isEmpty());
        ui.heldMsg.answer(1);   // late answer after stop must be harmless
    }

    @Test public void msgBoxBlocksUntilAnswered() throws Exception {
        ui.holdDialogs = true;
        ScriptDoc d = ScriptDoc.parse("Sub Main()\n r = MsgBox(\"q\", vbYesNo)\n Debug.Print r\nEnd Sub");
        ScriptRunner r = new ScriptRunner(d, new ScriptStorage(mem), new FormObject("", null), DIRECT, ui);
        r.start();
        for (int i = 0; i < 200 && ui.heldMsg == null; i++) Thread.sleep(5);
        Thread.sleep(50);
        assertTrue(ui.log.isEmpty());       // still blocked
        ui.heldMsg.answer(7);
        assertTrue(r.join(2000));
        assertEquals("[7]", ui.log.toString());
    }

    @Test public void eventWithTooManyParamsIsReported() throws Exception {
        ScriptRunner r = start("'@Button Button1 x=0 y=0 w=1 h=1\nSub Button1_Click(a)\nEnd Sub");
        tap(r, "Button1_Click");
        assertNotNull(ui.error);
        assertEquals(450, ui.error.number);
    }

    @Test public void formCaption() throws Exception {
        final String[] cap = new String[1];
        ScriptDoc d = ScriptDoc.parse("'@form name=\"orig\"\nForm.Caption = Form.Caption & \"!\"");
        FormObject f = new FormObject(d.name, c -> cap[0] = c);
        ScriptRunner r = new ScriptRunner(d, new ScriptStorage(mem), f, DIRECT, ui);
        r.start();
        assertTrue(r.join(2000));
        assertEquals("orig!", cap[0]);
    }

    // ------------------------------------------------------------------ storage
    @Test public void storagePersistsTypes() {
        ScriptStorage s = new ScriptStorage(mem);
        s.call("set", new Object[]{"n", 42L});
        s.call("set", new Object[]{"d", 1.5});
        s.call("set", new Object[]{"b", true});
        s.call("set", new Object[]{"s", "日本語"});
        assertEquals(4, mem.saves);
        ScriptStorage t = new ScriptStorage(mem);
        assertEquals(42L, t.call("get", new Object[]{"n"}));
        assertEquals(1.5, t.call("get", new Object[]{"d"}));
        assertEquals(true, t.call("get", new Object[]{"b"}));
        assertEquals("日本語", t.call("get", new Object[]{"s"}));
        assertNull(t.call("get", new Object[]{"missing"}));
        assertEquals("def", t.call("get", new Object[]{"missing", "def"}));
        assertEquals(true, t.call("exists", new Object[]{"n"}));
        t.call("remove", new Object[]{"n"});
        assertEquals(false, new ScriptStorage(mem).call("exists", new Object[]{"n"}));
        assertEquals(3L, t.get("count"));
        t.call("clear", new Object[0]);
        assertEquals(0L, new ScriptStorage(mem).get("count"));
    }

    @Test public void storageCorruptFileStartsEmpty() {
        mem.json = "{not json";
        assertEquals(0L, new ScriptStorage(mem).get("count"));
    }

    @Test public void storageRejectsArrays() throws Exception {
        start("Storage.Set \"a\", Array(1, 2)");
        assertEquals(13, ui.error.number);
    }

    // ------------------------------------------------------------------ bundled samples
    @Test public void sampleKuku() throws Exception {
        ScriptRunner r = start(sample("01_kuku.bas"));
        assertNull(String.valueOf(ui.error), ui.error);
        ControlState g = r.control("Grid1");
        assertEquals("×", g.cellText(0, 0));
        assertEquals("81", g.cellText(9, 9));
        assertEquals("12", g.cellText(3, 4));
        tap(r, "Grid1_Click", 4L, 5L);
        assertEquals("3 × 4 = 12", r.control("Label1").text());
        tap(r, "Button1_Click");
        assertEquals(9, ui.log.size());
        assertEquals("  1  2  3  4  5  6  7  8  9", ui.log.get(0));
        tap(r, "Button2_Click");
        assertEquals("81", g.cellText(9, 9));
        assertNull(ui.error);
        r.stop();
    }

    @Test public void sampleSum() throws Exception {
        ScriptRunner r = start(sample("02_sum.bas"));
        r.control("Text1").userSetText("12");
        r.control("Text2").userSetText("30.5");
        tap(r, "Button1_Click");
        assertEquals("答え: 42.5", r.control("Label1").text());
        tap(r, "Text1_Change");
        assertEquals("答え:", r.control("Label1").text());
        r.control("Text1").userSetText("abc");
        tap(r, "Button1_Click");
        assertEquals("[MsgBox:数値を入力してください]", ui.dialogs.toString());
        r.control("Text1").userSetText("１２");    // full-width digits from a Japanese IME
        tap(r, "Button1_Click");
        assertEquals("答え: 42.5", r.control("Label1").text());
        assertNull(ui.error);
        r.stop();
    }

    @Test public void sampleDropdown() throws Exception {
        ScriptRunner r = start(sample("03_dropdown.bas"));
        assertEquals("りんご は 150 円", r.control("Label1").text());
        r.control("Combo1").userSelect(2);
        tap(r, "Combo1_Change");
        assertEquals("ぶどう は 400 円", r.control("Label1").text());
        r.control("Text1").userSetText(" メロン ");
        tap(r, "Button1_Click");
        assertEquals("メロン", r.control("Combo1").text());
        assertEquals("メロン (価格未登録)", r.control("Label1").text());
        assertEquals("", r.control("Text1").text());
        tap(r, "Button1_Click");
        assertEquals(1, ui.dialogs.size());
        assertNull(ui.error);
        r.stop();
    }

    @Test public void sampleCounterPersists() throws Exception {
        ScriptRunner r = start(sample("04_counter.bas"));
        assertEquals("0", r.control("Label1").text());
        assertEquals("前回: (なし)", r.control("Label2").text());
        tap(r, "Button1_Click");
        tap(r, "Button1_Click");
        tap(r, "Button1_Click");
        tap(r, "Button2_Click");
        assertEquals("2", r.control("Label1").text());
        r.stop();
        // "restart the app": new runner, same storage backend
        ui = new FakeUi();
        ScriptRunner r2 = start(sample("04_counter.bas"));
        assertEquals("2", r2.control("Label1").text());
        assertTrue(r2.control("Label2").text().startsWith("前回: 20"));
        ui.msgAnswers.add(7);          // No
        tap(r2, "Button3_Click");
        assertEquals("2", r2.control("Label1").text());
        ui.msgAnswers.add(6);          // Yes
        tap(r2, "Button3_Click");
        assertEquals("0", r2.control("Label1").text());
        assertNull(ui.error);
        r2.stop();
    }

    @Test public void sampleGreeting() throws Exception {
        ScriptRunner r = start(sample("05_greeting.bas"));
        ui.inputAnswers.add("花子");
        tap(r, "Button1_Click");
        assertEquals("花子 さん (1 回目)", r.control("Label1").text());
        assertEquals("[InputBox:お名前は?|ゲスト, MsgBox:こんにちは、花子 さん!\r\n1 回目のあいさつです。]", ui.dialogs.toString());
        tap(r, "Button1_Click");     // cancelled
        assertEquals("キャンセルされました", r.control("Label1").text());
        tap(r, "Button2_Click");
        assertEquals(ScriptRunner.State.ENDED, r.state());
        assertNull(ui.error);
    }

    /** The example in the help / AI guide must really work. */
    @Test public void guideExamplesRun() throws Exception {
        for (String g : new String[]{"guide_ja.md", "guide_en.md"}) {
            File f = new File("src/main/assets/help/" + g);
            if (!f.exists()) f = new File("app/src/main/assets/help/" + g);
            String md = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int a = md.indexOf("```vba\n'@form");
            assertTrue(g, a >= 0);
            int b = md.indexOf("```", a + 6);
            ui = new FakeUi();
            ScriptRunner r = start(ScriptDoc.fromPasted(md.substring(a, b + 3)).serialize());
            assertNull(String.valueOf(ui.error), ui.error);
            r.control("Text1").userSetText("2");
            r.control("Text2").userSetText("3.5");
            tap(r, "Button1_Click");
            assertTrue(g + ": " + r.control("Label1").text(), r.control("Label1").text().endsWith(" 5.5"));
            r.control("Text2").userSetText("x");
            tap(r, "Button1_Click");
            assertEquals(1, ui.dialogs.size());
            assertNull(ui.error);
            r.stop();
        }
    }

    @Test public void allSamplesParse() throws Exception {
        File dir = new File("src/main/assets/samples");
        if (!dir.exists()) dir = new File("app/src/main/assets/samples");
        File[] fs = dir.listFiles();
        assertNotNull(fs);
        assertTrue(fs.length >= 5);
        for (File f : fs) {
            ScriptDoc d = ScriptDoc.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            assertFalse(f.getName(), d.name.isEmpty());
            com.zmlworks.vbarunner.vba.Parser.parse(d.code);
            for (ControlSpec c : d.controls) assertTrue(f.getName() + " " + c.name + " fits 360dp", c.x + c.w <= 360);
        }
    }
}
