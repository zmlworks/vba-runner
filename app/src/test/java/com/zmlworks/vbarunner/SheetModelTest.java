package com.zmlworks.vbarunner;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SheetModelTest {
    @Test public void colNames() {
        assertEquals("A", SheetModel.colName(1));
        assertEquals("Z", SheetModel.colName(26));
        assertEquals("AA", SheetModel.colName(27));
    }

    @Test public void csvRoundTrip() {
        SheetModel m = new SheetModel();
        m.set(1, 1, "a,b");
        m.set(1, 3, "say \"hi\"");
        m.set(2, 2, "line1\nline2");
        m.set(3, 1, "日本語");
        String csv = m.toCsv();
        assertEquals("\"a,b\",,\"say \"\"hi\"\"\"\r\n,\"line1\nline2\",\r\n日本語,,\r\n", csv);
        SheetModel n = new SheetModel();
        assertEquals(3, n.fromCsv(csv));
        for (int r = 1; r <= 3; r++) for (int c = 1; c <= 3; c++) assertEquals(m.get(r, c), n.get(r, c));
    }

    @Test public void outOfRangeIgnoredAndLastRow() {
        SheetModel m = new SheetModel();
        m.set(0, 1, "x"); m.set(101, 1, "x"); m.set(1, 27, "x");
        assertEquals("", m.get(0, 1));
        assertEquals(0, m.lastRow(1));
        m.set(7, 1, "1");
        assertEquals(7, m.lastRow(1));
        m.set(7, 1, "");
        assertEquals(0, m.lastRow(1));
    }

    @Test public void csvWithoutTrailingNewline() {
        SheetModel m = new SheetModel();
        m.fromCsv("1,2\n3,4");
        assertEquals("4", m.get(2, 2));
    }
}
