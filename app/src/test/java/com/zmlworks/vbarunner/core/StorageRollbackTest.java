package com.zmlworks.vbarunner.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zmlworks.vbarunner.vba.Msg;

import org.junit.Before;
import org.junit.Test;

/** A Set / Remove / Clear whose write-through fails must leave Storage exactly as it was (= as saved). */
public class StorageRollbackTest {
    static class FlakyBackend implements ScriptStorage.Backend {
        String json;
        boolean broken;
        @Override public String load() { return json; }
        @Override public void save(String j) {
            if (broken) throw new IllegalStateException("disk full");
            json = j;
        }
    }

    FlakyBackend be;
    ScriptStorage st;

    @Before public void setUp() {
        Msg.japanese = true;
        be = new FlakyBackend();
        st = new ScriptStorage(be);
        st.call("set", new Object[]{"a", 1L});
        st.call("set", new Object[]{"b", "x"});
    }

    private void expectFail(String m, Object... args) {
        try { st.call(m, args); fail(m + " should fail"); } catch (RuntimeException expected) { }
    }

    private void assertUnchanged() {
        assertEquals(2L, st.get("count"));
        assertEquals(1L, st.call("get", new Object[]{"a"}));
        assertEquals("x", st.call("get", new Object[]{"b"}));
        assertFalse((Boolean) st.call("exists", new Object[]{"c"}));
        // and what is on disk matches what the script sees
        assertEquals(st.snapshot(), new ScriptStorage(be).snapshot());
    }

    @Test public void failedWritesRollBack() {
        be.broken = true;
        expectFail("set", "c", 3L);
        assertUnchanged();
        expectFail("set", "a", 99L);
        assertUnchanged();
        expectFail("remove", "a");
        assertUnchanged();
        expectFail("clear");
        assertUnchanged();
        be.broken = false;
        st.call("set", new Object[]{"c", 3L});
        assertEquals(3L, new ScriptStorage(be).snapshot().get("c"));
    }

    @Test public void overLimitSetRollsBack() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < ScriptStorage.MAX_TOTAL_CHARS; i++) big.append('z');
        expectFail("set", "c", big.toString());
        assertUnchanged();
        expectFail("set", "a", big.toString());
        assertUnchanged();
        assertTrue(be.json.length() < 100);
    }
}
