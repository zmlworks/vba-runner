package com.zmlworks.vbarunner.vba;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Records output and dialogs; answers MsgBox/InputBox from queues. */
class TestHost implements Interp.Host {
    final List<String> out = new ArrayList<>();
    final List<String> dialogs = new ArrayList<>();
    final Deque<Integer> msgAnswers = new ArrayDeque<>();
    final Deque<String> inputAnswers = new ArrayDeque<>();
    long slept;

    @Override public int msgBox(String prompt, int buttons, String title) {
        dialogs.add("MsgBox:" + prompt + "|" + buttons + "|" + title);
        return msgAnswers.isEmpty() ? 1 : msgAnswers.poll();
    }
    @Override public String inputBox(String prompt, String title, String def) {
        dialogs.add("InputBox:" + prompt + "|" + title + "|" + def);
        return inputAnswers.isEmpty() ? null : inputAnswers.poll();
    }
    @Override public void print(String line) { out.add(line); }
    @Override public void sleep(long ms) { slept += ms; }

    String text() { return String.join("\n", out); }
}
