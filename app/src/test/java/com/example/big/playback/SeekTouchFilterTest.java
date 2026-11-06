package com.example.big.playback;

import org.junit.Test;

import static org.junit.Assert.*;

public class SeekTouchFilterTest {
    private final SeekTouchFilter filter = new SeekTouchFilter();

    @Test
    public void restJitterIsStronglyAttenuated() {
        filter.begin(100f, 0L, 1f);
        double squaredOutput = 0.0;
        for (int i = 1; i <= 200; i++) {
            assertTrue(filter.move(100f + (i % 2 == 0 ? -1f : 1f), i * 8L));
            double error = filter.getPosition() - 100.0;
            squaredOutput += error * error;
        }
        assertTrue(Math.sqrt(squaredOutput / 200) < 0.25);
    }

    @Test
    public void fastMotionHasMinimalLagAndNeverOvershoots() {
        filter.begin(0f, 0L, 1f);
        for (int i = 1; i <= 30; i++) {
            float x = i * 32f;
            assertTrue(filter.move(x, i * 16L));
            assertTrue(filter.getPosition() <= x);
            assertTrue(x - filter.getPosition() < 2.0);
        }
    }

    @Test
    public void deliberateFourDpMovementCrossesHalfFrameThresholdPromptly() {
        filter.begin(100f, 100L, 2f);
        filter.move(108f, 116L);
        assertTrue(filter.getPosition() > 104.0);
        assertTrue(filter.getPosition() <= 108.0);
    }

    @Test
    public void reversalRespondsWithoutOvershootingPreviousDirection() {
        filter.begin(0f, 0L, 1f);
        for (int i = 1; i <= 20; i++) {
            filter.move(i * 16f, i * 16L);
        }
        double previous = filter.getPosition();
        for (int i = 1; i <= 20; i++) {
            float x = 320f - i * 16f;
            filter.move(x, 320L + i * 16L);
            assertTrue(filter.getPosition() < previous);
            assertTrue(filter.getPosition() >= x);
            assertTrue(filter.getPosition() - x < 12.0);
            previous = filter.getPosition();
        }
    }

    @Test
    public void sameTrajectoryAtDifferentSampleRatesHasSimilarOutput() {
        for (int interval : new int[] {4, 8, 16, 32}) {
            filter.begin(0f, 0L, 1f);
            SeekTouchFilter reference = new SeekTouchFilter();
            reference.begin(0f, 0L, 1f);
            for (int t = 4; t <= 1024; t += 4) {
                float x = t <= 512 ? t * 0.25f : 128f;
                reference.move(x, t);
                if (t % interval == 0) {
                    filter.move(x, t);
                    assertEquals(reference.getPosition(), filter.getPosition(), 3.0);
                }
            }
        }
    }

    @Test
    public void samplingUsesElapsedTimeRatherThanTimestampMagnitude() {
        SeekTouchFilter shifted = new SeekTouchFilter();
        filter.begin(0f, 0L, 1f);
        shifted.begin(0f, Long.MAX_VALUE - 1000L, 1f);
        for (int t = 1; t <= 500; t++) {
            filter.move(t * 0.25f, t);
            shifted.move(t * 0.25f, Long.MAX_VALUE - 1000L + t);
            assertEquals(filter.getPosition(), shifted.getPosition(), 0.0);
        }
    }

    @Test
    public void densityMakesFilteringIndependentOfPixelScale() {
        SeekTouchFilter scaled = new SeekTouchFilter();
        filter.begin(0f, 0L, 1f);
        scaled.begin(0f, 0L, 3f);
        for (int i = 1; i <= 50; i++) {
            filter.move(i * 4f, i * 8L);
            scaled.move(i * 12f, i * 8L);
            assertEquals(filter.getPosition(), scaled.getPosition() / 3, 0.000001);
        }
    }

    @Test
    public void nonmonotonicAndNonfiniteSamplesDoNotPoisonNextValidMove() {
        filter.begin(100f, 100L, 1f);
        assertFalse(filter.move(1000f, 100L));
        assertFalse(filter.move(1000f, 99L));
        assertFalse(filter.move(1000f, -1L));
        assertFalse(filter.move(Float.NaN, 116L));
        assertFalse(filter.move(Float.POSITIVE_INFINITY, 116L));
        assertFalse(filter.move(Float.NEGATIVE_INFINITY, 116L));
        assertEquals(100.0, filter.getPosition(), 0.0);
        assertTrue(filter.move(104f, 116L));
        assertTrue(filter.getPosition() > 102.0);
    }

    @Test
    public void longGapReseedsInsteadOfCarryingOldVelocity() {
        filter.begin(0f, 0L, 1f);
        filter.move(200f, 16L);
        filter.move(100f, 1000L);
        assertEquals(100.0, filter.getPosition(), 0.0);
        filter.move(101f, 1016L);
        assertTrue(filter.getPosition() < 100.5);
    }

    @Test
    public void reanchorCancelAndNewBeginDiscardOldFilterTail() {
        filter.begin(0f, 0L, 1f);
        filter.move(4f, 16L);
        filter.reanchor(4f);
        filter.move(4f, 32L);
        assertEquals(4.0, filter.getPosition(), 0.0);
        filter.cancel();
        filter.move(100f, 0L);
        assertEquals(100.0, filter.getPosition(), 0.0);
        filter.begin(500f, 0L, 1f);
        filter.move(500f, 16L);
        assertEquals(500.0, filter.getPosition(), 0.0);
    }

    @Test
    public void invalidBeginAndExtremeInputsRemainFinite() {
        filter.begin(Float.NaN, -1L, Float.NaN);
        assertFalse(filter.move(Float.NaN, 0L));
        assertTrue(filter.move(100f, 0L));
        assertEquals(100.0, filter.getPosition(), 0.0);
        filter.begin(-Float.MAX_VALUE, 0L, Float.MIN_VALUE);
        assertTrue(filter.move(Float.MAX_VALUE, 1L));
        assertFalse(Double.isNaN(filter.getPosition()));
        assertFalse(Double.isInfinite(filter.getPosition()));
        assertTrue(filter.getPosition() >= -Float.MAX_VALUE);
        assertTrue(filter.getPosition() <= Float.MAX_VALUE);
    }
}