package com.zmlworks.vbarunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import com.zmlworks.vbarunner.core.ControlState;

/** Canvas-drawn table for the Grid control: 2D scrolling inside its own bounds, tap → (row, col) 1-based. */
final class GridControlView extends View {
    interface OnCell { void onCell(int row, int col); }

    private final ControlState state;
    private OnCell onCell;
    private final float density;
    private float cw, ch, pad;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(), sel = new Paint(), fill = new Paint();
    private final OverScroller scroller;
    private final GestureDetector gd;
    private float sx, sy;
    private int selR = -1, selC = -1;

    GridControlView(Context c, ControlState s) {
        super(c);
        state = s;
        density = getResources().getDisplayMetrics().density;
        text.setColor(Ui.TEXT);
        line.setColor(Ui.GRID);
        line.setStrokeWidth(Math.max(1, density));
        sel.setColor(Ui.HI);
        sel.setStyle(Paint.Style.STROKE);
        sel.setStrokeWidth(2 * density);
        fill.setColor(Ui.PANEL);
        updateMetrics();
        scroller = new OverScroller(c);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                // keep the parent ScrollView from stealing gestures only if we can scroll ourselves
                if (maxX() > 0 || maxY() > 0) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollToXY(sx + dx, sy + dy);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int col = (int) ((e.getX() + sx) / cw) + 1, row = (int) ((e.getY() + sy) / ch) + 1;
                if (row >= 1 && row <= state.rows() && col >= 1 && col <= state.cols()) {
                    selR = row;
                    selC = col;
                    invalidate();
                    if (onCell != null && isEnabled()) onCell.onCell(row, col);
                }
                return true;
            }
        });
        setFocusable(true);
    }

    void setOnCell(OnCell l) { onCell = l; }

    void updateMetrics() {
        float fs = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, state.fontSize(), getResources().getDisplayMetrics());
        text.setTextSize(fs * 0.9f);
        ch = fs * 2.0f;
        pad = 4 * density;
        float w = getWidth();
        int cols = Math.max(1, state.cols());
        // fill the width when columns fit, otherwise at least ~3 characters per column
        float minCw = text.measureText("000") + 2 * pad;
        cw = w > 0 ? Math.max(minCw, w / cols) : 72 * density;
        if (w <= 0) cw = 72 * density;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateMetrics();
        scrollToXY(sx, sy);
    }

    private float maxX() { return Math.max(0, state.cols() * cw - getWidth()); }
    private float maxY() { return Math.max(0, state.rows() * ch - getHeight()); }

    private void scrollToXY(float x, float y) {
        sx = Math.max(0, Math.min(x, maxX()));
        sy = Math.max(0, Math.min(y, maxY()));
        invalidate();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollToXY(scroller.getCurrX(), scroller.getCurrY());
            postInvalidateOnAnimation();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return gd.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        cv.drawColor(Ui.BG2);
        int rows = state.rows(), cols = state.cols();
        int c0 = (int) (sx / cw), r0 = (int) (sy / ch);
        float baseline = ch / 2 - (text.descent() + text.ascent()) / 2;
        for (int r = r0; r < rows; r++) {
            float y = r * ch - sy;
            if (y > h) break;
            for (int c = c0; c < cols; c++) {
                float x = c * cw - sx;
                if (x > w) break;
                String v = state.cellText(r, c);
                if (!v.isEmpty()) {
                    cv.save();
                    cv.clipRect(x, y, x + cw, y + ch);
                    boolean num = v.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
                    float tw = text.measureText(v);
                    cv.drawText(v, num ? Math.max(x + pad, x + cw - pad - tw) : x + pad, y + baseline, text);
                    cv.restore();
                }
            }
            cv.drawLine(0, y + ch, Math.min(w, cols * cw - sx), y + ch, line);
        }
        for (int c = c0; c <= cols; c++) {
            float x = c * cw - sx;
            if (x > w) break;
            cv.drawLine(x, 0, x, Math.min(h, rows * ch - sy), line);
        }
        if (selR > 0 && selR <= rows && selC <= cols) {
            float x = (selC - 1) * cw - sx, y = (selR - 1) * ch - sy;
            cv.drawRect(x, y, x + cw, y + ch, sel);
        }
        cv.drawRect(0, 0, w - 1, h - 1, borderPaint());
    }

    private Paint border;
    private Paint borderPaint() {
        if (border == null) {
            border = new Paint();
            border.setStyle(Paint.Style.STROKE);
            border.setColor(Ui.GRID);
            border.setStrokeWidth(density);
        }
        return border;
    }
}
