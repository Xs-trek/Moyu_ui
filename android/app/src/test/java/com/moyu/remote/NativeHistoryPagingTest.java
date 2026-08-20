package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class NativeHistoryPagingTest {
    @Test public void followsServerCursorInsteadOfDerivingFromVisibleItems() {
        assertEquals(173, NativeHistoryPaging.next(100, 173, true));
        assertEquals(173, NativeHistoryPaging.next(173, 999, false));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNonProgressingCursor() {
        NativeHistoryPaging.next(100, 100, true);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsCursorBeyondServerBound() {
        NativeHistoryPaging.next(3900, 4001, true);
    }
}
