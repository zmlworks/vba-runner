package com.zmlworks.vbarunner;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.vbarunner.core.ScriptDoc;

import java.io.IOException;

/** In-app reference. The same text is the "description for an AI", copyable with one tap. */
public final class HelpActivity extends Activity {

    static String guide(Activity a) {
        ScriptStore s = new ScriptStore(a);
        try {
            return s.readAsset(Ui.japanese() ? "help/guide_ja.md" : "help/guide_en.md");
        } catch (IOException e) {
            return "";
        }
    }

    /** Copy the AI description; when a script is given, append it so the AI can modify it. */
    static void copyAiGuide(Activity a, ScriptDoc current) {
        String text = guide(a);
        if (current != null) {
            text += "\n\n" + a.getString(R.string.ai_current_script) + "\n```vba\n" + current.serialize() + "```\n";
        }
        MainActivity.copyText(a, a.getString(R.string.copy_ai), text);
        Toast.makeText(a, R.string.copied_ai, Toast.LENGTH_LONG).show();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(Ui.BG);
        TextView title = Ui.topBar(this, root, true,
                Ui.barButton(this, getString(R.string.copy_ai_short), getString(R.string.copy_ai), v -> copyAiGuide(this, null)));
        title.setText(R.string.help);
        ScrollView sv = new ScrollView(this);
        TextView t = Ui.text(this, render(guide(this)), 14, Ui.TEXT);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 32));
        sv.addView(t);
        root.addView(sv, Ui.fill());
        setContentView(root);
        Ui.edgeToEdge(this, root);
    }

    /** Tiny markdown rendering: headings, code fences (monospace, highlighted), `inline code` and **bold**. */
    private static CharSequence render(String md) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        boolean code = false;
        for (String line : md.split("\n", -1)) {
            if (line.startsWith("```")) { code = !code; continue; }
            int start = sb.length();
            if (code) {
                sb.append(line).append('\n');
                sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Ui.HI), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (line.startsWith("#")) {
                String h = line.replaceFirst("^#+\\s*", "");
                sb.append(h).append('\n');
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(line.startsWith("# ") ? 1.3f : 1.12f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Ui.HI), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            appendInline(sb, line);
            sb.append('\n');
        }
        return sb;
    }

    private static void appendInline(SpannableStringBuilder sb, String line) {
        int i = 0;
        while (i < line.length()) {
            if (line.startsWith("**", i)) {
                int j = line.indexOf("**", i + 2);
                if (j > 0) {
                    int s = sb.length();
                    sb.append(line, i + 2, j);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = j + 2;
                    continue;
                }
            }
            if (line.charAt(i) == '`') {
                int j = line.indexOf('`', i + 1);
                if (j > 0) {
                    int s = sb.length();
                    sb.append(line, i + 1, j);
                    sb.setSpan(new TypefaceSpan("monospace"), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(0xFFE6E6A0), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = j + 1;
                    continue;
                }
            }
            sb.append(line.charAt(i));
            i++;
        }
    }
}
