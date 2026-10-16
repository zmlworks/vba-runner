package com.zmlworks.vbarunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

/** Canvas-drawn spreadsheet grid with frozen headers, 2D scrolling and tap-to-select. */
public final class SheetView extends View {
    public interface OnSelect { void onSelect(int row, int col); }

    private SheetModel model;
    private OnSelect onSelect;
    private final float cw, ch, hw, hh, pad;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), head = new Paint(Paint.ANTI_ALIAS_FLAG),
            line = new Paint(), fill = new Paint(), sel = new Paint();
    private final OverScroller scroller;
    private final GestureDetector gd;
    private float sx, sy; // scroll offsets in px
    private int selR = 1, selC = 1;

    public SheetView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        cw = 76 * d; ch = 28 * d; hw = 36 * d; hh = 24 * d; pad = 4 * d;
        text.setColor(Ui.TEXT); text.setTextSize(13 * d); text.setTypeface(Typeface.MONOSPACE);
        head.setColor(Ui.DIM); head.setTextSize(12 * d); head.setTextAlign(Paint.Align.CENTER);
        line.setColor(Ui.GRID); line.setStrokeWidth(1);
        sel.setColor(Ui.HI); sel.setStyle(Paint.Style.STROKE); sel.setStrokeWidth(2 * d);
        scroller = new OverScroller(c);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { scroller.forceFinished(true); return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollToXY(sx + dx, sy + dy); return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation(); return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (e.getX() < hw || e.getY() < hh) return false;
                int c = (int) ((e.getX() - hw + sx) / cw) + 1, r = (int) ((e.getY() - hh + sy) / ch) + 1;
                if (SheetModel.in(r, c)) select(r, c);
                return true;
            }
        });
        setFocusable(true);
    }

    public void setModel(SheetModel m) { model = m; invalidate(); }
    public void setOnSelect(OnSelect s) { onSelect = s; }
    public int selRow() { return selR; }
    public int selCol() { return selC; }

    public void select(int r, int c) {
        selR = r; selC = c;
        // keep selection visible
        float x = (c - 1) * cw, y = (r - 1) * ch, w = getWidth() - hw, h = getHeight() - hh;
        float nx = sx, ny = sy;
        if (x < sx) nx = x; else if (x + cw > sx + w) nx = x + cw - w;
        if (y < sy) ny = y; else if (y + ch > sy + h) ny = y + ch - h;
        scrollToXY(nx, ny);
        if (onSelect != null) onSelect.onSelect(r, c);
        invalidate();
    }

    private float maxX() { return Math.max(0, SheetModel.COLS * cw - (getWidth() - hw)); }
    private float maxY() { return Math.max(0, SheetModel.ROWS * ch - (getHeight() - hh)); }

    private void scrollToXY(float x, float y) {
        sx = Math.max(0, Math.min(x, maxX()));
        sy = Math.max(0, Math.min(y, maxY()));
        invalidate();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) { scrollToXY(scroller.getCurrX(), scroller.getCurrY()); postInvalidateOnAnimation(); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return gd.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDraw(Canvas cv) {
        if (model == null) return;
        int w = getWidth(), h = getHeight();
        cv.drawColor(Ui.BG);
        int c0 = (int) (sx / cw) + 1, r0 = (int) (sy / ch) + 1;
        float baseline = ch / 2 - (text.descent() + text.ascent()) / 2;
        // cells
        cv.save();
        cv.clipRect(hw, hh, w, h);
        for (int r = r0; r <= SheetModel.ROWS; r++) {
            float y = hh + (r - 1) * ch - sy;
            if (y > h) break;
            for (int c = c0; c <= SheetModel.COLS; c++) {
                float x = hw + (c - 1) * cw - sx;
                if (x > w) break;
                String v = model.get(r, c);
                if (!v.isEmpty()) {
                    cv.save();
                    cv.clipRect(x, y, x + cw, y + ch);
                    boolean num = v.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
                    float tw = text.measureText(v);
                    cv.drawText(v, num ? Math.max(x + pad, x + cw - pad - tw) : x + pad, y + baseline, text);
                    cv.restore();
                }
            }
            cv.drawLine(hw, y + ch, w, y + ch, line);
        }
        for (int c = c0; c <= SheetModel.COLS; c++) {
            float x = hw + c * cw - sx;
            if (x - cw > w) break;
            cv.drawLine(x, hh, x, h, line);
        }
        float selX = hw + (selC - 1) * cw - sx, selY = hh + (selR - 1) * ch - sy;
        cv.drawRect(selX, selY, selX + cw, selY + ch, sel);
        cv.restore();
        // headers
        fill.setColor(Ui.PANEL);
        cv.drawRect(0, 0, w, hh, fill);
        cv.drawRect(0, 0, hw, h, fill);
        float hb = hh / 2 - (head.descent() + head.ascent()) / 2;
        cv.save(); cv.clipRect(hw, 0, w, hh);
        for (int c = c0; c <= SheetModel.COLS; c++) {
            float x = hw + (c - 1) * cw - sx;
            if (x > w) break;
            head.setColor(c == selC ? Ui.HI : Ui.DIM);
            cv.drawText(SheetModel.colName(c), x + cw / 2, hb, head);
        }
        cv.restore();
        cv.save(); cv.clipRect(0, hh, hw, h);
        for (int r = r0; r <= SheetModel.ROWS; r++) {
            float y = hh + (r - 1) * ch - sy;
            if (y > h) break;
            head.setColor(r == selR ? Ui.HI : Ui.DIM);
            cv.drawText(String.valueOf(r), hw / 2, y + baseline, head);
        }
        cv.restore();
        cv.drawLine(0, hh, w, hh, line);
        cv.drawLine(hw, 0, hw, h, line);
    }
}
