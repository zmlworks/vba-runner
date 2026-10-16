package com.zmlworks.vbarunner.vba;

/** VBA array with per-dimension lower/upper bounds (row-major). */
public final class VArray {
    final int[] lo, hi;
    Object[] data;
    /** Declared element type (lower-case) or null for Variant. */
    String elemType;

    public VArray(int[] lo, int[] hi) {
        this.lo = lo.clone();
        this.hi = hi.clone();
        long n = 1;
        for (int d = 0; d < lo.length; d++) {
            if (hi[d] < lo[d] - 1) throw Msg.subscript();
            n *= (hi[d] - lo[d] + 1);
        }
        if (n > 10_000_000) throw VbaError.run(7, Msg.t("メモリが不足しています (配列が大きすぎます)。", "Out of memory (array too large)."));
        data = new Object[(int) n];
    }

    public static VArray of(Object... items) {
        VArray a = new VArray(new int[]{0}, new int[]{items.length - 1});
        System.arraycopy(items, 0, a.data, 0, items.length);
        return a;
    }

    public int dims() { return lo.length; }
    public int lower(int dim) { check(dim); return lo[dim - 1]; }
    public int upper(int dim) { check(dim); return hi[dim - 1]; }
    public int length() { return data.length; }
    private void check(int dim) { if (dim < 1 || dim > lo.length) throw Msg.subscript(); }

    int index(Object[] idx) {
        if (idx.length != lo.length) throw Msg.subscript();
        int k = 0;
        for (int d = 0; d < lo.length; d++) {
            long i = Values.toLong(idx[d]);
            if (i < lo[d] || i > hi[d]) throw Msg.subscript();
            k = k * (hi[d] - lo[d] + 1) + (int) (i - lo[d]);
        }
        return k;
    }

    public Object get(Object... idx) { return data[index(idx)]; }
    public void set(Object v, Object... idx) { data[index(idx)] = v; }
    public Object at(int flat) { return data[flat]; }

    public VArray copy() {
        VArray c = new VArray(lo, hi);
        c.elemType = elemType;
        for (int i = 0; i < data.length; i++) c.data[i] = data[i] instanceof VArray ? ((VArray) data[i]).copy() : data[i];
        return c;
    }

    /** ReDim Preserve: only the last dimension may change (as in VBA). */
    VArray resize(int[] nlo, int[] nhi, boolean preserve) {
        VArray n = new VArray(nlo, nhi);
        n.elemType = elemType;
        if (!preserve) return n;
        if (nlo.length != lo.length) throw Msg.subscript();
        for (int d = 0; d < lo.length - 1; d++) if (nlo[d] != lo[d] || nhi[d] != hi[d]) throw Msg.subscript();
        if (lo.length == 1) {
            for (int i = Math.max(lo[0], nlo[0]); i <= Math.min(hi[0], nhi[0]); i++) n.data[i - nlo[0]] = data[i - lo[0]];
        } else {
            int oldLast = hi[lo.length - 1] - lo[lo.length - 1] + 1, newLast = nhi[lo.length - 1] - nlo[lo.length - 1] + 1;
            int rows = data.length / Math.max(1, oldLast);
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < Math.min(oldLast, newLast); c++) n.data[r * newLast + c] = data[r * oldLast + c];
        }
        return n;
    }
}
