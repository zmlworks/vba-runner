package com.zmlworks.vbarunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.EditText;

/** Monospace EditText with a line-number gutter, no wrapping, and auto-indent on Enter. */
public final class CodeEditor extends EditText {
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), gutter = new Paint();
    private final float gap;
    private boolean indenting;

    public CodeEditor(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        gap = 8 * d;
        setTypeface(Typeface.MONOSPACE);
        // setInputType(multi-line) turns horizontal scrolling off, so it must come first
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setHorizontallyScrolling(true);
        setGravity(Gravity.TOP | Gravity.START);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        num.setTypeface(Typeface.MONOSPACE);
        num.setColor(Ui.DIM);
        num.setTextAlign(Paint.Align.RIGHT);
        gutter.setColor(0xFF00140E);
        addTextChangedListener(new TextWatcher() {
            int at = -1;
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int after) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int cnt) {
                at = (!indenting && cnt == 1 && before == 0 && s.charAt(st) == '\n') ? st : -1;
            }
            @Override public void afterTextChanged(Editable e) {
                updateGutter();
                if (at < 0) return;
                int ls = at; // copy leading whitespace of the previous line
                while (ls > 0 && e.charAt(ls - 1) != '\n') ls--;
                int le = ls;
                while (le < at && (e.charAt(le) == ' ' || e.charAt(le) == '\t')) le++;
                String ind = e.subSequence(ls, le).toString();
                if (at > 0 && e.charAt(at - 1) == '{') ind += "  ";
                if (!ind.isEmpty()) { indenting = true; e.insert(at + 1, ind); indenting = false; }
                at = -1;
            }
        });
    }

    private int lastDigits = -1;
    private void updateGutter() {
        num.setTextSize(getTextSize() * 0.85f);
        int lines = 1;
        CharSequence t = getText();
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') lines++;
        int digits = Math.max(2, String.valueOf(lines).length());
        if (digits != lastDigits) {
            lastDigits = digits;
            setPadding((int) (num.measureText("0") * digits + gap * 2), getPaddingTop(), getPaddingRight(), getPaddingBottom());
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); updateGutter(); }

    @Override protected void onDraw(Canvas cv) {
        Layout l = getLayout();
        if (l != null) {
            int sx = getScrollX(), top = getScrollY(), bottom = top + getHeight();
            cv.drawRect(sx, top, sx + getPaddingLeft() - gap / 2, bottom, gutter);
            CharSequence t = getText();
            int first = l.getLineForVertical(Math.max(0, top - getPaddingTop()));
            int last = l.getLineForVertical(bottom - getPaddingTop());
            // logical line number of the first visible visual line
            int n = 1;
            int start = l.getLineStart(first);
            for (int i = 0; i < start; i++) if (t.charAt(i) == '\n') n++;
            for (int i = first; i <= last; i++) {
                int s = l.getLineStart(i);
                if (i == first || (s > 0 && t.charAt(s - 1) == '\n')) {
                    if (i != first) n++;
                    num.setColor(Ui.DIM);
                    cv.drawText(String.valueOf(n), sx + getPaddingLeft() - gap, getPaddingTop() + l.getLineBaseline(i), num);
                }
            }
        }
        super.onDraw(cv);
    }

    /** Move the cursor to the start of a 1-based line. */
    public void goToLine(int line) {
        CharSequence t = getText();
        int pos = 0, n = 1;
        while (n < line && pos < t.length()) { if (t.charAt(pos) == '\n') n++; pos++; }
        requestFocus();
        setSelection(Math.min(pos, t.length()));
    }

    public void insertAtCursor(String s) {
        int a = Math.max(0, getSelectionStart()), b = Math.max(0, getSelectionEnd());
        getText().replace(Math.min(a, b), Math.max(a, b), s);
    }
}
