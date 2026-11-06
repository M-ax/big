package com.example.big.playback;

import org.junit.Test;

import static org.junit.Assert.*;

public class SeekPreviewTest {
    @Test
    public void firstTargetIsImmediateAndRapidInputKeepsOnlyNewest() {
        SeekPreview preview = new SeekPreview();
        preview.begin(1000);
        assertEquals(1000, preview.poll(0, 10));
        preview.offer(1100);
        preview.offer(1200);
        assertEquals(-1, preview.poll(79, 11));
        assertEquals(1200, preview.poll(80, 11));
        assertFalse(preview.hasPending());
        assertEquals(-1, preview.poll(1000, 12));
    }

    @Test
    public void slowDecoderIsNotRestartedOnEveryTouchButCannotBlockForever() {
        SeekPreview preview = new SeekPreview();
        preview.begin(0);
        assertEquals(0, preview.poll(10, 0));
        preview.offer(5000);
        assertEquals(-1, preview.poll(259, 0));
        assertEquals(5000, preview.poll(260, 0));
        preview.offer(4000);
        assertEquals(4000, preview.poll(340, 1));
    }

    @Test
    public void cancelAndNewGestureCannotReplayStaleTargets() {
        SeekPreview preview = new SeekPreview();
        preview.begin(1000);
        preview.offer(2000);
        preview.cancel();
        preview.offer(3000);
        assertFalse(preview.hasPending());
        assertEquals(-1, preview.poll(1000, 10));
        preview.begin(500);
        assertEquals(500, preview.poll(1001, 10));
    }

    @Test
    public void clockRegressionDoesNotIssueExtraWork() {
        SeekPreview preview = new SeekPreview();
        preview.begin(0);
        preview.poll(100, 1);
        preview.offer(50);
        assertEquals(-1, preview.poll(99, 2));
        assertEquals(50, preview.poll(180, 2));
    }
}