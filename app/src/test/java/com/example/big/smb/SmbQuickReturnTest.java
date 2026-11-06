package com.example.big.smb;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SmbQuickReturnTest {
    @Test
    public void returnsOnTheFirstUpwardPixelEvenDeepInTheList() {
        SmbQuickReturn title = new SmbQuickReturn();
        title.reset(60, -20, false);
        assertTrue(title.onScroll(60, -19));
        assertTrue(title.onScroll(60, -19));
        assertFalse(title.onScroll(60, -20));
        assertFalse(title.onScroll(61, 0));
        assertTrue(title.onScroll(60, -40));
    }

    @Test
    public void restoringAnAnchorDoesNotCountAsScrollingUp() {
        SmbQuickReturn title = new SmbQuickReturn();
        title.reset(70, -6, false);
        title.reset(30, -12, false);
        assertFalse(title.onScroll(30, -12));
        title.reset(30, -12, true);
        assertTrue(title.onScroll(30, -12));
    }
}