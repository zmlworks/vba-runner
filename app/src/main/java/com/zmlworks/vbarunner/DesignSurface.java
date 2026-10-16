package com.zmlworks.vbarunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.zmlworks.vbarunner.core.ControlSpec;

import java.util.List;

/**
 * Form designer canvas. Coordinates of {@link ControlSpec} are dp; the surface is as wide as the run screen, so the
 * layout is WYSIWYG. Touch: tap = select (tap again = properties), drag = move, pinch or the corner handle = resize.
 * Positions/sizes snap to 4 dp. Touches on empty space are left to the parent ScrollView.
 */
final class DesignSurface extends View {
    interface Listener {
        void onSelect(ControlSpec c);
        void onMoved(ControlSpec c);
        void onTapSelected(ControlSpec c);
    }

    static final int SNAP = 4, MIN = 24;

    private List<ControlSpec> controls;
    private ControlSpec sel;
    private Listener listener;
    private final float d;
    private final float handle, slop;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            selPaint = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(), handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG), tag = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private enum Mode { NONE, PENDING, DRAG, RESIZE, PINCH }
    private Mode mode = Mode.NONE;
    private float downX, downY, spanX0, spanY0;
    private int x0, y0, w0, h0;
    private boolean wasSelected;

    DesignSurface(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        handle = 28 * d;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(d);
        stroke.setColor(Ui.GRID);
        dash.set(stroke);
        dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{4 * d, 4 * d}, 0));
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(2 * d);
        selPaint.setColor(Ui.HI);
        dot.setColor(0xFF0B3528);
        handlePaint.setColor(Ui.HI);
        text.setColor(Ui.TEXT);
        tag.setColor(Ui.HI);
        tag.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
    }

    void setControls(List<ControlSpec> cs) { controls = cs; requestLayout(); invalidate(); }
    void setListener(Listener l) { listener = l; }
    ControlSpec selected() { return sel; }

    void select(ControlSpec c) {
        sel = c;
        if (listener != null) listener.onSelect(c);
        invalidate();
    }

    /** Width of the designable area in dp. */
    int widthDp() { return Math.max(200, Math.round(getWidth() / d)); }

    private float px(int dp) { return dp * d; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int bottom = 0;
        if (controls != null) for (ControlSpec c : controls) bottom = Math.max(bottom, c.y + c.h);
        int h = Math.max(Math.round(px(bottom + 320)), getSuggestedMinimumHeight());
        int parentH = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.UNSPECIFIED) h = Math.max(h, parentH);
        setMeasuredDimension(w, Math.max(h, Math.round(px(600))));
    }

    // ------------------------------------------------------------------ touch
    private ControlSpec hit(float x, float y) {
        if (controls == null) return null;
        for (int i = controls.size() - 1; i >= 0; i--) {
            ControlSpec c = controls.get(i);
            if (x >= px(c.x) && x <= px(c.x + c.w) && y >= px(c.y) && y <= px(c.y + c.h)) return c;
        }
        return null;
    }

    private boolean onHandle(float x, float y) {
        if (sel == null) return false;
        float r = px(sel.x + sel.w), b = px(sel.y + sel.h);
        return x >= r - handle && x <= r + handle / 2 && y >= b - handle && y <= b + handle / 2;
    }

    private static int snap(float v) { return Math.round(v / SNAP) * SNAP; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                if (onHandle(downX, downY)) {
                    mode = Mode.RESIZE;
                    remember(sel);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                ControlSpec h = hit(downX, downY);
                if (h == null) {
                    mode = Mode.NONE;
                    return true;   // tap on empty space deselects (on UP); scrolling is left to the parent
                }
                wasSelected = h == sel;
                if (h != sel) select(h);
                remember(h);
                mode = Mode.PENDING;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (sel != null && e.getPointerCount() == 2) {
                    mode = Mode.PINCH;
                    spanX0 = Math.abs(e.getX(0) - e.getX(1));
                    spanY0 = Math.abs(e.getY(0) - e.getY(1));
                    remember(sel);
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (mode == Mode.PENDING && (Math.abs(dx) > slop || Math.abs(dy) > slop)) mode = Mode.DRAG;
                if (mode == Mode.DRAG && sel != null) {
                    sel.x = clamp(snap(x0 + dx / d), 0, Math.max(0, widthDp() - sel.w));
                    sel.y = Math.max(0, snap(y0 + dy / d));
                    moved();
                } else if (mode == Mode.RESIZE && sel != null) {
                    sel.w = clamp(snap(w0 + dx / d), MIN, Math.max(MIN, widthDp() - sel.x));
                    sel.h = Math.max(MIN, snap(h0 + dy / d));
                    moved();
                } else if (mode == Mode.PINCH && sel != null && e.getPointerCount() >= 2) {
                    float sx = Math.abs(e.getX(0) - e.getX(1)), sy = Math.abs(e.getY(0) - e.getY(1));
                    // each axis scales only if the fingers were spread along it (so a horizontal pinch changes width only)
                    if (spanX0 > 40 * d) sel.w = clamp(snap(w0 * sx / spanX0), MIN, Math.max(MIN, widthDp() - sel.x));
                    if (spanY0 > 40 * d) sel.h = Math.max(MIN, snap(h0 * sy / spanY0));
                    moved();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mode == Mode.NONE) {
                    if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop && sel != null) select(null);
                } else if (mode == Mode.PENDING && sel != null) {
                    performClick();
                    if (wasSelected && listener != null) listener.onTapSelected(sel);
                }
                mode = Mode.NONE;
                requestLayout();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                mode = Mode.NONE;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void remember(ControlSpec c) { x0 = c.x; y0 = c.y; w0 = c.w; h0 = c.h; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void moved() {
        invalidate();
        if (listener != null) listener.onMoved(sel);
    }

    // ------------------------------------------------------------------ drawing
    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(Ui.BG);
        float step = 16 * d;
        for (float y = 0; y < getHeight(); y += step)
            for (float x = 0; x < getWidth(); x += step) cv.drawRect(x, y, x + d, y + d, dot);
        if (controls == null) return;
        for (ControlSpec c : controls) drawControl(cv, c);
        if (sel != null) {
            RectF r = new RectF(px(sel.x), px(sel.y), px(sel.x + sel.w), px(sel.y + sel.h));
            cv.drawRect(r, selPaint);
            String label = sel.name + "  " + sel.w + "×" + sel.h;
            float ty = r.top - 4 * d;
            if (ty < tag.getTextSize()) ty = r.bottom + tag.getTextSize() + 2 * d;
            cv.drawText(label, r.left, ty, tag);
            float hs = 10 * d;
            cv.drawRect(r.right - hs, r.bottom - hs, r.right, r.bottom, handlePaint);
        }
    }

    private void drawControl(Canvas cv, ControlSpec c) {
        RectF r = new RectF(px(c.x), px(c.y), px(c.x + c.w), px(c.y + c.h));
        float rad = 4 * d;
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, c.fontSize, getResources().getDisplayMetrics()));
        text.setColor(Ui.TEXT);
        cv.save();
        cv.clipRect(r.left - d, r.top - d, r.right + d, r.bottom + d);
        switch (c.type) {
            case BUTTON:
                fill.setColor(Ui.PANEL);
                cv.drawRoundRect(r, rad, rad, fill);
                cv.drawRoundRect(r, rad, rad, stroke);
                centered(cv, r, c.caption);
                break;
            case TEXTBOX:
                fill.setColor(Ui.BG2);
                cv.drawRoundRect(r, rad, rad, fill);
                cv.drawRoundRect(r, rad, rad, stroke);
                if (c.caption.isEmpty()) { text.setColor(Ui.DIM); leftText(cv, r, c.name + (c.multiline ? " ¶" : "")); }
                else leftText(cv, r, c.caption);
                break;
            case COMBOBOX:
                fill.setColor(Ui.PANEL);
                cv.drawRoundRect(r, rad, rad, fill);
                cv.drawRoundRect(r, rad, rad, stroke);
                String first = !c.caption.isEmpty() ? c.caption : (c.items.isEmpty() ? c.name : c.items.get(0));
                leftText(cv, r, first + "  ▾");
                break;
            case GRID: {
                fill.setColor(Ui.BG2);
                cv.drawRect(r, fill);
                float ch = text.getTextSize() * 2f;
                float cw = Math.max(r.width() / Math.max(1, c.cols), text.measureText("000") + 8 * d);
                for (int i = 0; i <= c.rows && r.top + i * ch <= r.bottom; i++) cv.drawLine(r.left, r.top + i * ch, r.right, r.top + i * ch, stroke);
                for (int j = 0; j <= c.cols && r.left + j * cw <= r.right; j++) cv.drawLine(r.left + j * cw, r.top, r.left + j * cw, r.bottom, stroke);
                cv.drawRect(r, stroke);
                text.setColor(Ui.DIM);
                centered(cv, r, c.name + "  " + c.rows + "×" + c.cols);
                break;
            }
            default:
                if (c.caption.isEmpty()) { text.setColor(Ui.DIM); leftText(cv, r, "(" + c.name + ")"); }
                else leftText(cv, r, c.caption);
                cv.drawRect(r, dash);
                break;
        }
        cv.restore();
    }

    private void centered(Canvas cv, RectF r, String s) {
        String t = TextUtils.ellipsize(s, text, Math.max(0, r.width() - 8 * d), TextUtils.TruncateAt.END).toString();
        float base = r.centerY() - (text.descent() + text.ascent()) / 2;
        cv.drawText(t, r.centerX() - text.measureText(t) / 2, base, text);
    }

    private void leftText(Canvas cv, RectF r, String s) {
        String t = TextUtils.ellipsize(s.replace('\n', ' '), text, Math.max(0, r.width() - 16 * d), TextUtils.TruncateAt.END).toString();
        float base = r.centerY() - (text.descent() + text.ascent()) / 2;
        cv.drawText(t, r.left + 8 * d, base, text);
    }
}
