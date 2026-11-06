package com.example.big.playback;

import java.math.BigInteger;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlaybackGestureTest {
    private final PlaybackGesture gesture = new PlaybackGesture(10f);

    private void begin(float x) {
        gesture.begin(x, 500f, 1000, 1000, 1200000L, 2400000L, true, 8, 20, 0.5f);
    }

    private void assertResult(PlaybackGesture.Result result, PlaybackGesture.Action action,
                              long positionMs, int volume, float brightness) {
        assertEquals(action, result.action);
        assertEquals(positionMs, result.positionMs);
        assertEquals(volume, result.volume);
        assertEquals(brightness, result.brightness, 0.00001f);
    }

    @Test
    public void coarseSeekStartsAtZeroAndAllowsSubFrameChanges() {
        begin(500f);
        assertEquals(1200000L, gesture.move(511f, 500f).positionMs);
        assertEquals(1200002L, gesture.move(540f, 500f).positionMs);
        assertEquals(1199998L, gesture.move(460f, 500f).positionMs);
        assertEquals(1200000L, gesture.move(500f, 500f).positionMs);
    }

    @Test
    public void rightSwipePreviewsForwardSeekWithQuarticCurve() {
        begin(500f);
        assertResult(gesture.move(750f, 500f), PlaybackGesture.Action.SEEK,
                1202344L, 8, 0.5f);
        assertResult(gesture.move(1000f, 500f), PlaybackGesture.Action.SEEK,
                1237500L, 8, 0.5f);
        assertResult(gesture.move(1500f, 500f), PlaybackGesture.Action.SEEK,
                1800000L, 8, 0.5f);
    }

    @Test
    public void leftSwipePreviewsBackwardSeek() {
        begin(500f);
        assertResult(gesture.move(250f, 500f), PlaybackGesture.Action.SEEK,
                1197656L, 8, 0.5f);
    }

    @Test
    public void seekTargetsAreAbsoluteAndCanReverseToStart() {
        begin(500f);
        PlaybackGesture.Result first = gesture.move(750f, 500f);
        assertResult(gesture.move(750f, 500f), PlaybackGesture.Action.SEEK,
                1202344L, 8, 0.5f);
        gesture.move(1500f, 500f);
        assertResult(gesture.move(750f, 500f), PlaybackGesture.Action.SEEK,
                1202344L, 8, 0.5f);
        assertResult(gesture.move(500f, 500f), PlaybackGesture.Action.SEEK,
                1200000L, 8, 0.5f);
        assertResult(gesture.move(250f, 500f), PlaybackGesture.Action.SEEK,
                1197656L, 8, 0.5f);
        assertResult(first, PlaybackGesture.Action.SEEK, 1202344L, 8, 0.5f);
    }

    @Test
    public void seekUsesFractionOfViewWidth() {
        gesture.begin(250f, 500f, 500, 1000, 1200000L, 2400000L, true, 8, 20, 0.5f);
        assertResult(gesture.move(375f, 500f), PlaybackGesture.Action.SEEK,
                1202344L, 8, 0.5f);
    }

    @Test
    public void seekClampsAtBothEnds() {
        gesture.begin(500f, 500f, 1000, 1000, 5000L, 10000L, true, 8, 20, 0.5f);
        assertResult(gesture.move(1000f, 500f), PlaybackGesture.Action.SEEK,
                10000L, 8, 0.5f);
        assertResult(gesture.move(0f, 500f), PlaybackGesture.Action.SEEK,
                0L, 8, 0.5f);
    }

    @Test
    public void rightSideUpIncreasesAndDownDecreasesVolume() {
        begin(900f);
        assertResult(gesture.move(900f, 300f), PlaybackGesture.Action.VOLUME,
                1200000L, 13, 0.5f);
        assertResult(gesture.move(900f, 700f), PlaybackGesture.Action.VOLUME,
                1200000L, 3, 0.5f);
    }

    @Test
    public void volumeClampsAndDoesNotAccumulate() {
        begin(900f);
        assertResult(gesture.move(900f, -1000f), PlaybackGesture.Action.VOLUME,
                1200000L, 20, 0.5f);
        assertResult(gesture.move(900f, 300f), PlaybackGesture.Action.VOLUME,
                1200000L, 13, 0.5f);
        assertResult(gesture.move(900f, 300f), PlaybackGesture.Action.VOLUME,
                1200000L, 13, 0.5f);
        assertResult(gesture.move(900f, 2000f), PlaybackGesture.Action.VOLUME,
                1200000L, 0, 0.5f);
        assertResult(gesture.move(900f, 500f), PlaybackGesture.Action.VOLUME,
                1200000L, 8, 0.5f);
    }

    @Test
    public void volumeTruncatesLikeVlc() {
        begin(900f);
        assertEquals(8, gesture.move(900f, 480f).volume);
        assertEquals(7, gesture.move(900f, 520f).volume);
    }

    @Test
    public void leftSideUpIncreasesAndDownDecreasesBrightness() {
        begin(100f);
        assertResult(gesture.move(100f, 300f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.75f);
        assertResult(gesture.move(100f, 700f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.25f);
    }

    @Test
    public void brightnessClampsAndDoesNotAccumulate() {
        begin(100f);
        assertResult(gesture.move(100f, -1000f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 1f);
        assertResult(gesture.move(100f, 300f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.75f);
        assertResult(gesture.move(100f, 300f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.75f);
        assertResult(gesture.move(100f, 2000f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.01f);
        assertResult(gesture.move(100f, 500f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.5f);
    }

    @Test
    public void centerBelongsToVolumeAndSideStaysLocked() {
        begin(500f);
        assertEquals(PlaybackGesture.Action.VOLUME, gesture.move(500f, 300f).action);
        assertResult(gesture.move(100f, 300f), PlaybackGesture.Action.VOLUME,
                1200000L, 13, 0.5f);
        begin(499f);
        assertEquals(PlaybackGesture.Action.BRIGHTNESS, gesture.move(499f, 300f).action);
        assertResult(gesture.move(900f, 300f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.75f);
    }

    @Test
    public void sideIsTakenFromBeginNotFirstRecognizedMove() {
        begin(499f);
        assertEquals(PlaybackGesture.Action.BRIGHTNESS, gesture.move(501f, 300f).action);
    }

    @Test
    public void slopIncludesBoundaryAndKeepsInitialValues() {
        begin(500f);
        assertResult(gesture.move(500f, 500f), PlaybackGesture.Action.NONE,
                1200000L, 8, 0.5f);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(510f, 500f).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(490f, 500f).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(500f, 510f).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(500f, 490f).action);
        assertEquals(PlaybackGesture.Action.SEEK, gesture.move(511f, 500f).action);
    }

    @Test
    public void diagonalsWaitForDominantAxis() {
        begin(500f);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(600f, 600f).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(700f, 600f).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(600f, 700f).action);
        assertEquals(PlaybackGesture.Action.SEEK, gesture.move(701f, 600f).action);
        begin(100f);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(150f, 450f).action);
        assertEquals(PlaybackGesture.Action.BRIGHTNESS, gesture.move(150f, 399f).action);
    }

    @Test
    public void horizontalAxisCannotSwitchToVertical() {
        begin(900f);
        gesture.move(1000f, 500f);
        assertResult(gesture.move(900f, 100f), PlaybackGesture.Action.SEEK,
                1200000L, 8, 0.5f);
    }

    @Test
    public void verticalAxisCannotSwitchToHorizontal() {
        begin(100f);
        gesture.move(100f, 300f);
        assertResult(gesture.move(900f, 500f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.5f);
    }

    @Test
    public void nonseekableHorizontalGestureDoesNotSeekOrSwitchAxis() {
        gesture.begin(900f, 500f, 1000, 1000, 1234L, 10000L, false, 8, 20, 0.5f);
        assertResult(gesture.move(1000f, 500f), PlaybackGesture.Action.NONE,
                1234L, 8, 0.5f);
        assertResult(gesture.move(900f, 100f), PlaybackGesture.Action.NONE,
                1234L, 8, 0.5f);
    }

    @Test
    public void nonseekableStreamsStillAllowVerticalGestures() {
        gesture.begin(900f, 500f, 1000, 1000, 1234L, 10000L, false, 8, 20, 0.5f);
        assertResult(gesture.move(900f, 300f), PlaybackGesture.Action.VOLUME,
                1234L, 13, 0.5f);
    }

    @Test
    public void unknownDurationsDisableSeekButNotBrightness() {
        for (long duration : new long[] {0L, -1L, Long.MIN_VALUE}) {
            gesture.begin(100f, 500f, 1000, 1000, 1234L, duration, true, 8, 20, 0.5f);
            assertResult(gesture.move(900f, 500f), PlaybackGesture.Action.NONE,
                    1234L, 8, 0.5f);
            gesture.begin(100f, 500f, 1000, 1000, 1234L, duration, true, 8, 20, 0.5f);
            assertResult(gesture.move(100f, 300f), PlaybackGesture.Action.BRIGHTNESS,
                    1234L, 8, 0.75f);
        }
    }

    @Test
    public void cancelAndUnbegunMovesDoNothing() {
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(1000f, 0f).action);
        gesture.cancel();
        begin(500f);
        gesture.move(750f, 500f);
        gesture.cancel();
        gesture.cancel();
        assertResult(gesture.move(1000f, 500f), PlaybackGesture.Action.NONE,
                1200000L, 8, 0.5f);
        begin(100f);
        assertEquals(PlaybackGesture.Action.BRIGHTNESS, gesture.move(100f, 300f).action);
    }

    @Test
    public void beginReplacesPreviousAxisAndBaselines() {
        begin(500f);
        gesture.move(750f, 500f);
        gesture.begin(100f, 200f, 500, 500, 200L, 1000L, true, 2, 10, 0.2f);
        assertResult(gesture.move(100f, 100f), PlaybackGesture.Action.BRIGHTNESS,
                200L, 2, 0.45f);
    }

    @Test
    public void invalidDimensionsDisableAllGestures() {
        for (int[] dimensions : new int[][] {{0, 1000}, {1000, 0}, {-1, 1000}, {1000, -1}}) {
            gesture.begin(100f, 500f, dimensions[0], dimensions[1], 1234L, 10000L,
                    true, 8, 20, 0.5f);
            assertResult(gesture.move(900f, 500f), PlaybackGesture.Action.NONE,
                    1234L, 8, 0.5f);
            assertEquals(PlaybackGesture.Action.NONE, gesture.move(100f, 0f).action);
        }
    }

    @Test
    public void invalidPositionsAndVolumesAreClamped() {
        gesture.begin(500f, 500f, 1000, 1000, -1L, 10000L, true, -3, 20, 0.5f);
        assertResult(gesture.move(500f, 500f), PlaybackGesture.Action.NONE,
                0L, 0, 0.5f);
        gesture.begin(500f, 500f, 1000, 1000, Long.MAX_VALUE, 10000L, true, 50, 20, 0.5f);
        assertResult(gesture.move(500f, 500f), PlaybackGesture.Action.NONE,
                10000L, 20, 0.5f);
        for (int maximum : new int[] {0, -1, Integer.MIN_VALUE}) {
            gesture.begin(900f, 500f, 1000, 1000, 1234L, 10000L, true, 8, maximum, 0.5f);
            assertResult(gesture.move(900f, 0f), PlaybackGesture.Action.VOLUME,
                    1234L, 0, 0.5f);
        }
    }

    @Test
    public void invalidBrightnessGetsSafeBaseline() {
        for (float brightness : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            gesture.begin(100f, 500f, 1000, 1000, 0L, 10000L, true, 8, 20, brightness);
            assertEquals(0.5f, gesture.move(100f, 500f).brightness, 0f);
            assertEquals(0.75f, gesture.move(100f, 300f).brightness, 0.00001f);
        }
        gesture.begin(100f, 500f, 1000, 1000, 0L, 10000L, true, 8, 20, 0f);
        assertEquals(0.01f, gesture.move(100f, 500f).brightness, 0f);
        gesture.begin(100f, 500f, 1000, 1000, 0L, 10000L, true, 8, 20, 2f);
        assertEquals(1f, gesture.move(100f, 500f).brightness, 0f);
    }

    @Test
    public void nonfiniteCoordinatesDoNotSelectOrChangeAxis() {
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            gesture.begin(invalid, 500f, 1000, 1000, 0L, 10000L, true, 8, 20, 0.5f);
            assertEquals(PlaybackGesture.Action.NONE, gesture.move(750f, 500f).action);
            gesture.begin(500f, invalid, 1000, 1000, 0L, 10000L, true, 8, 20, 0.5f);
            assertEquals(PlaybackGesture.Action.NONE, gesture.move(750f, 500f).action);
            begin(500f);
            assertEquals(PlaybackGesture.Action.NONE, gesture.move(invalid, 500f).action);
            assertEquals(PlaybackGesture.Action.NONE, gesture.move(500f, invalid).action);
            assertEquals(PlaybackGesture.Action.SEEK, gesture.move(750f, 500f).action);
            assertEquals(PlaybackGesture.Action.NONE, gesture.move(invalid, 300f).action);
            assertResult(gesture.move(500f, 300f), PlaybackGesture.Action.SEEK,
                    1200000L, 8, 0.5f);
        }
    }

    @Test
    public void invalidSlopIsTreatedAsZero() {
        for (float slop : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            PlaybackGesture calculator = new PlaybackGesture(slop);
            calculator.begin(500f, 500f, 1000, 1000, 5000L, 10000L, true, 8, 20, 0.5f);
            assertEquals(PlaybackGesture.Action.NONE, calculator.move(500f, 500f).action);
            assertEquals(PlaybackGesture.Action.SEEK, calculator.move(501f, 500f).action);
        }
    }

    @Test
    public void smallSeekKeepsPrecisionNearLongMaximum() {
        gesture.begin(500f, 500f, 1000, 1000, Long.MAX_VALUE - 10000L,
                Long.MAX_VALUE, true, 8, 20, 0.5f);
        assertEquals(Long.MAX_VALUE - 9998L, gesture.move(540f, 500f).positionMs);
        assertEquals(Long.MAX_VALUE - 10000L, gesture.move(500f, 500f).positionMs);
        assertEquals(Long.MAX_VALUE - 10002L, gesture.move(460f, 500f).positionMs);
    }

    @Test
    public void extremeFiniteInputsCannotOverflowSeekOrVolume() {
        gesture.begin(-Float.MAX_VALUE, 500f, 1, 1, Long.MAX_VALUE - 100L,
                Long.MAX_VALUE, true, 8, 20, 0.5f);
        assertEquals(Long.MAX_VALUE, gesture.move(Float.MAX_VALUE, 500f).positionMs);
        gesture.begin(Float.MAX_VALUE, 500f, 1, 1, Long.MAX_VALUE - 100L,
                Long.MAX_VALUE, true, 8, 20, 0.5f);
        assertEquals(0L, gesture.move(-Float.MAX_VALUE, 500f).positionMs);
        gesture.begin(900f, 0f, 1000, 1, 0L, 10000L, true, Integer.MAX_VALUE,
                Integer.MAX_VALUE, 0.5f);
        assertEquals(Integer.MAX_VALUE, gesture.move(900f, -Float.MAX_VALUE).volume);
        assertEquals(0, gesture.move(900f, Float.MAX_VALUE).volume);
    }

    @Test
    public void fineSeekingStepsOnSourceFrameGridAtEverySupportedRate() {
        for (int[] rate : new int[][] {{24, 1}, {30, 1}, {60000, 1001}}) {
            gesture.setFrameRate(rate[0], rate[1]);
            gesture.setDensity(2f);
            begin(500f);
            PlaybackGesture.Result coarse = gesture.move(1000f, 500f);
            double frameMs = 1000.0 * rate[1] / rate[0];
            long frame = Math.round(coarse.positionMs / frameMs);
            assertEquals(Math.round(frame * frameMs), coarse.positionMs);
            PlaybackGesture.Result fine = gesture.move(1000f, 680f);
            assertTrue(fine.fineSeeking);
            assertEquals(coarse.positionMs, fine.positionMs);
            for (int step : new int[] {1, 2, 1, 0, -1, -2, 0}) {
                PlaybackGesture.Result result = gesture.move(1000f + step * 8f, 680f);
                assertTrue(result.fineSeeking);
                assertEquals(Math.round((frame + step) * frameMs), result.positionMs);
            }
        }
    }

    @Test
    public void rationalFrameStepsNeverAccumulateRoundedFrameDuration() {
        gesture.setFrameRate(60000, 1001);
        begin(500f);
        long coarse = gesture.move(1000f, 500f).positionMs;
        long frame = Math.round(coarse * 60000.0 / 1001000.0);
        gesture.move(1000f, 680f);
        for (int step = 1; step <= 1000; step++) {
            assertEquals(Math.round((frame + step) * 1001000.0 / 60000.0),
                    gesture.move(1000f + step * 4f, 680f).positionMs);
        }
    }

    @Test
    public void fineModeHasHysteresisAndReanchorsBothTransitions() {
        begin(500f);
        long coarse = gesture.move(750f, 500f).positionMs;
        assertFalse(gesture.move(750f, 679f).fineSeeking);
        PlaybackGesture.Result fine = gesture.move(900f, 680f);
        assertTrue(fine.fineSeeking);
        assertEquals(coarse, fine.positionMs);
        assertEquals(coarse + 1L, gesture.move(904f, 650f).positionMs);
        assertTrue(gesture.move(904f, 621f).fineSeeking);
        PlaybackGesture.Result exit = gesture.move(950f, 620f);
        assertFalse(exit.fineSeeking);
        assertEquals(coarse + 1L, exit.positionMs);
        assertEquals(exit.positionMs, gesture.move(950f, 500f).positionMs);
        assertEquals(exit.positionMs + 2344L, gesture.move(1200f, 500f).positionMs);
        assertEquals(exit.positionMs - 2344L, gesture.move(700f, 500f).positionMs);
    }

    @Test
    public void fineThresholdsUseHeightAndDoNotUnlockVerticalControls() {
        gesture.begin(500f, 100f, 1000, 500, 50000L, 100000L, true, 8, 20, 0.5f);
        gesture.move(750f, 100f);
        assertFalse(gesture.move(750f, 189f).fineSeeking);
        assertTrue(gesture.move(750f, 190f).fineSeeking);
        assertTrue(gesture.move(750f, 161f).fineSeeking);
        assertFalse(gesture.move(750f, 160f).fineSeeking);
        begin(900f);
        PlaybackGesture.Result vertical = gesture.move(900f, 700f);
        assertEquals(PlaybackGesture.Action.VOLUME, vertical.action);
        assertFalse(vertical.fineSeeking);
        assertEquals(PlaybackGesture.Action.VOLUME, gesture.move(1000f, 700f).action);
    }

    @Test
    public void invalidOrUnknownFrameRateUsesOneMillisecondNotThirtyFps() {
        for (int[] rate : new int[][] {{0, 0}, {0, 1}, {30, 0}, {-24, 1}, {24, -1}}) {
            gesture.setFrameRate(30, 1);
            gesture.setFrameRate(rate[0], rate[1]);
            assertEquals(1.0, gesture.getFrameDurationMs(), 0.0);
            begin(500f);
            long coarse = gesture.move(750f, 500f).positionMs;
            gesture.move(750f, 680f);
            assertEquals(coarse + 1L, gesture.move(754f, 680f).positionMs);
        }
    }

    @Test
    public void invalidDensityUsesOnePixelPerDp() {
        for (float density : new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            gesture.setDensity(density);
            begin(500f);
            long coarse = gesture.move(750f, 500f).positionMs;
            gesture.move(750f, 680f);
            assertEquals(coarse + 1L, gesture.move(754f, 680f).positionMs);
        }
    }

    @Test
    public void frameAndDensityConfigurationAreCapturedAtBegin() {
        gesture.setFrameRate(24, 1);
        gesture.setDensity(2f);
        begin(500f);
        long coarse = gesture.move(1000f, 500f).positionMs;
        gesture.move(1000f, 680f);
        gesture.setFrameRate(30, 1);
        gesture.setDensity(1f);
        assertEquals(coarse + 42L, gesture.move(1008f, 680f).positionMs);
        begin(500f);
        coarse = gesture.move(1000f, 500f).positionMs;
        gesture.move(1000f, 680f);
        assertEquals(coarse + 33L, gesture.move(1004f, 680f).positionMs);
    }

    @Test
    public void liveFrameRateCorrectionReanchorsFineStepsOnTrueSourceGrid() {
        gesture.setFrameRate(5760, 241);
        gesture.setDensity(2f);
        begin(500f);
        gesture.move(750f, 500f);
        gesture.move(750f, 680f);
        PlaybackGesture.Result displayed = gesture.move(774f, 680f);
        long frame = Math.round(displayed.positionMs * 24.0 / 1000.0);
        assertNotEquals(Math.round(frame * 1000.0 / 24.0), displayed.positionMs);

        gesture.refineFrameRate(24, 1);

        assertEquals(1000.0 / 24.0, gesture.getFrameDurationMs(), 0.0);
        assertSame(displayed, gesture.move(Float.NaN, 680f, 100L));
        for (int step : new int[] {1, 2, 1, 0, -1, -2, 0}) {
            PlaybackGesture.Result result = gesture.move(774f + step * 8f, 680f);
            assertTrue(result.fineSeeking);
            assertEquals(Math.round((frame + step) * 1000.0 / 24.0), result.positionMs);
        }
    }

    @Test
    public void liveFrameRateCorrectionDoesNotChangeImmediateRelease() {
        gesture.setFrameRate(5760, 241);
        begin(500f);
        gesture.move(750f, 500f);
        PlaybackGesture.Result displayed = gesture.move(750f, 680f);
        gesture.refineFrameRate(24, 1);
        assertSame(displayed, gesture.finish(1000f, 900f, 100L));
    }

    @Test
    public void repeatedAndEquivalentRefinementsDoNotResetFineDisplacement() {
        begin(500f);
        gesture.move(750f, 500f);
        long coarse = gesture.move(750f, 680f).positionMs;
        gesture.refineFrameRate(24, 1);
        long frame = Math.round(coarse * 24.0 / 1000.0);
        for (int pixel = 1; pixel <= 12; pixel++) {
            gesture.refineFrameRate(pixel % 2 == 0 ? 5760 : 24, pixel % 2 == 0 ? 240 : 1);
            long step = Math.round(pixel / 4.0);
            assertEquals(Math.round((frame + step) * 1000.0 / 24.0),
                    gesture.move(750f + pixel, 680f).positionMs);
        }
    }

    @Test
    public void liveFrameRateCorrectionReanchorsCoarseCurveAndModeTransitions() {
        gesture.setFrameRate(5760, 241);
        begin(500f);
        PlaybackGesture.Result displayed = gesture.move(1000f, 500f);
        gesture.refineFrameRate(24, 1);
        assertSame(displayed, gesture.move(Float.NaN, 500f, 100L));
        for (int direction : new int[] {0, 1, -1, 0}) {
            long target = displayed.positionMs + direction * 2344L;
            assertEquals(Math.round(Math.round(target * 24.0 / 1000.0) * 1000.0 / 24.0),
                    gesture.move(1000f + direction * 250f, 500f).positionMs);
        }
        long snapped = Math.round(Math.round(displayed.positionMs * 24.0 / 1000.0)
                * 1000.0 / 24.0);
        assertEquals(snapped, gesture.move(1000f, 680f).positionMs);
        long frame = Math.round(snapped * 24.0 / 1000.0);
        long next = gesture.move(1004f, 680f).positionMs;
        assertEquals(Math.round((frame + 1) * 1000.0 / 24.0), next);
        assertEquals(next, gesture.move(1100f, 620f).positionMs);
        assertEquals(next, gesture.move(1100f, 500f).positionMs);
    }

    @Test
    public void timedRefinementUsesCurrentFilteredPositionAndPreservesFilterHistory() {
        gesture.setFrameRate(5760, 241);
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        gesture.move(750f, 500f, 116L);
        gesture.move(900f, 680f, 132L);
        SeekTouchFilter reference = new SeekTouchFilter();
        reference.begin(900f, 132L, 1f);
        reference.move(904f, 148L);
        PlaybackGesture.Result displayed = gesture.move(904f, 680f, 148L);
        double filteredAnchor = reference.getPosition();
        assertTrue(filteredAnchor > 902.0 && filteredAnchor < 903.0);
        long frame = Math.round(displayed.positionMs * 24.0 / 1000.0);
        gesture.refineFrameRate(24, 1);
        assertSame(displayed, gesture.move(1000f, 680f, 148L));
        assertSame(displayed, gesture.move(1000f, 680f, 147L));
        float[] samples = {906.1f, 910f, 906f, 900f, 898f, 904f};
        for (int i = 0; i < samples.length; i++) {
            long eventMs = 164L + i * 16L;
            assertTrue(reference.move(samples[i], eventMs));
            double dx = reference.getPosition() - filteredAnchor;
            long steps = Math.round(Math.abs(dx) / 4.0) * (dx < 0.0 ? -1 : 1);
            PlaybackGesture.Result result = gesture.move(samples[i], 680f, eventMs);
            assertEquals(Math.round((frame + steps) * 1000.0 / 24.0), result.positionMs);
            gesture.refineFrameRate(48, 2);
        }
    }

    @Test
    public void timedRefinementAfterModeTransitionUsesReanchoredPosition() {
        gesture.setFrameRate(5760, 241);
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        gesture.move(750f, 500f, 116L);
        PlaybackGesture.Result displayed = gesture.move(900f, 680f, 117L);
        gesture.refineFrameRate(24, 1);
        long frame = Math.round(displayed.positionMs * 24.0 / 1000.0);
        assertEquals(Math.round((frame + 1) * 1000.0 / 24.0),
                gesture.move(904f, 680f, 133L).positionMs);
    }

    @Test
    public void invalidRefinementsDoNotClearRateOrResetDisplacement() {
        gesture.setFrameRate(24, 1);
        begin(500f);
        gesture.move(750f, 500f);
        long coarse = gesture.move(750f, 680f).positionMs;
        int[][] invalid = {{0, 0}, {0, 1}, {30, 0}, {-24, 1}, {24, -1},
                {Integer.MIN_VALUE, Integer.MAX_VALUE}};
        for (int i = 0; i < invalid.length; i++) {
            PlaybackGesture.Result displayed = gesture.move(750f + i, 680f);
            gesture.refineFrameRate(invalid[i][0], invalid[i][1]);
            assertEquals(1000.0 / 24.0, gesture.getFrameDurationMs(), 0.0);
            assertSame(displayed, gesture.move(Float.NaN, 680f, 100L));
        }
        long frame = Math.round(coarse * 24.0 / 1000.0);
        assertEquals(Math.round((frame + 2) * 1000.0 / 24.0),
                gesture.move(758f, 680f).positionMs);
    }

    @Test
    public void refinementsAreActiveOnlyAndDoNotReplaceNextGestureConfiguration() {
        gesture.setFrameRate(30, 1);
        gesture.refineFrameRate(24, 1);
        assertEquals(1000.0 / 30.0, gesture.getFrameDurationMs(), 0.0);
        begin(500f);
        gesture.setFrameRate(60000, 1001);
        assertEquals(1000.0 / 30.0, gesture.getFrameDurationMs(), 0.0);
        gesture.refineFrameRate(24, 1);
        assertEquals(1000.0 / 24.0, gesture.getFrameDurationMs(), 0.0);
        gesture.cancel();
        gesture.refineFrameRate(25, 1);
        assertEquals(1001000.0 / 60000.0, gesture.getFrameDurationMs(), 0.0);
        assertEquals(PlaybackGesture.Action.NONE, gesture.finish().action);
        begin(500f);
        assertEquals(1001000.0 / 60000.0, gesture.getFrameDurationMs(), 0.0);
        long coarse = gesture.move(750f, 500f).positionMs;
        gesture.move(750f, 680f);
        long frame = Math.round(coarse * 60000.0 / 1001000.0);
        assertEquals(Math.round((frame + 1) * 1001000.0 / 60000.0),
                gesture.move(754f, 680f).positionMs);
    }

    @Test
    public void refinementBeforeAxisLockKeepsSlopAndOriginalCoarseOrigin() {
        begin(500f);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(510f, 500f).action);
        gesture.refineFrameRate(24, 1);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(510f, 500f).action);
        assertEquals(1200000L, gesture.move(511f, 500f).positionMs);
        assertEquals(Math.round(Math.round(1202344L * 24.0 / 1000.0) * 1000.0 / 24.0),
                gesture.move(750f, 500f).positionMs);
    }

    @Test
    public void refinementDoesNotAffectVerticalControlsOrEnableNonseekableMedia() {
        begin(900f);
        PlaybackGesture.Result volume = gesture.move(900f, 300f);
        gesture.refineFrameRate(24, 1);
        assertSame(volume, gesture.move(Float.NaN, 300f, 100L));
        assertResult(gesture.move(900f, 700f), PlaybackGesture.Action.VOLUME,
                1200000L, 3, 0.5f);
        begin(100f);
        gesture.move(100f, 300f);
        gesture.refineFrameRate(24, 1);
        assertResult(gesture.move(100f, 700f), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.25f);
        gesture.begin(500f, 500f, 1000, 1000, 1234L, 10000L, false, 8, 20, 0.5f);
        gesture.move(750f, 500f);
        gesture.refineFrameRate(24, 1);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(754f, 680f).action);
    }

    @Test
    public void refinementRecalculatesBoundsOnNewRationalGridWithoutOverflow() {
        gesture.begin(500f, 500f, 1000, 1000, 99999L, 100001L, true, 8, 20, 0.5f);
        gesture.move(511f, 500f);
        PlaybackGesture.Result displayed = gesture.move(511f, 680f);
        gesture.refineFrameRate(24, 1);
        assertSame(displayed, gesture.move(Float.NaN, 680f, 100L));
        assertEquals(100000L, gesture.move(Float.MAX_VALUE, 680f).positionMs);
        assertEquals(0L, gesture.move(-Float.MAX_VALUE, 680f).positionMs);
        for (int[] rate : new int[][] {{60000, 1001}, {Integer.MAX_VALUE, 1},
                {1, Integer.MAX_VALUE}}) {
            gesture.begin(500f, 500f, 1000, 1000, Long.MAX_VALUE - 10000L,
                    Long.MAX_VALUE, true, 8, 20, 0.5f);
            gesture.move(511f, 500f);
            gesture.move(511f, 680f);
            gesture.refineFrameRate(rate[0], rate[1]);
            BigInteger numerator = BigInteger.valueOf(rate[0]);
            BigInteger period = BigInteger.valueOf(1000L * rate[1]);
            BigInteger lastFrame = BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1)
                    .add(BigInteger.ONE).multiply(numerator).subtract(BigInteger.ONE)
                    .divide(period.shiftLeft(1));
            long end = lastFrame.multiply(period).add(numerator.shiftRight(1))
                    .divide(numerator).longValueExact();
            assertEquals(end, gesture.move(Float.MAX_VALUE, 680f).positionMs);
            assertEquals(0L, gesture.move(-Float.MAX_VALUE, 680f).positionMs);
        }
    }

    @Test
    public void coarseSnapsToNearestFrameAndBoundsRemainOnGrid() {
        gesture.setFrameRate(24, 1);
        gesture.begin(500f, 500f, 1000, 1000, 99980L, 100001L, true, 8, 20, 0.5f);
        assertEquals(100000L, gesture.move(511f, 500f).positionMs);
        assertEquals(100000L, gesture.move(Float.MAX_VALUE, 500f).positionMs);
        assertEquals(0L, gesture.move(-Float.MAX_VALUE, 500f).positionMs);
        gesture.move(500f, 500f);
        gesture.move(500f, 680f);
        assertEquals(100000L, gesture.move(Float.MAX_VALUE, 680f).positionMs);
        assertEquals(0L, gesture.move(-Float.MAX_VALUE, 680f).positionMs);
        assertEquals(100000L, gesture.move(500f, 680f).positionMs);
    }

    @Test
    public void rationalSnappingIsExactNearLongMaximum() {
        gesture.setFrameRate(24, 1);
        gesture.begin(500f, 500f, 1000, 1000, Long.MAX_VALUE - 10000L,
                Long.MAX_VALUE, true, 8, 20, 0.5f);
        BigInteger numerator = BigInteger.valueOf(24L);
        BigInteger period = BigInteger.valueOf(1000L);
        BigInteger frame = BigInteger.valueOf(Long.MAX_VALUE - 10000L)
                .multiply(numerator).add(period.divide(BigInteger.valueOf(2L))).divide(period);
        long expected = frame.multiply(period).add(numerator.divide(BigInteger.valueOf(2L)))
                .divide(numerator).longValueExact();
        assertEquals(expected, gesture.move(511f, 500f).positionMs);
        gesture.move(511f, 680f);
        long next = frame.add(BigInteger.ONE).multiply(period)
                .add(numerator.divide(BigInteger.valueOf(2L))).divide(numerator).longValueExact();
        assertEquals(next, gesture.move(515f, 680f).positionMs);
    }

    @Test
    public void extremePositiveFrameRatesAndDensityCannotOverflow() {
        for (int[] rate : new int[][] {{Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}}) {
            gesture.setFrameRate(rate[0], rate[1]);
            gesture.setDensity(Float.MIN_VALUE);
            gesture.begin(500f, 500f, 1000, 1000, Long.MAX_VALUE / 2,
                    Long.MAX_VALUE, true, 8, 20, 0.5f);
            long coarse = gesture.move(750f, 500f).positionMs;
            assertTrue(coarse >= 0 && coarse <= Long.MAX_VALUE);
            gesture.move(750f, 680f);
            long end = gesture.move(Float.MAX_VALUE, 680f).positionMs;
            assertTrue(end >= coarse);
            assertTrue(Long.MAX_VALUE - end <= gesture.getFrameDurationMs());
            assertEquals(0L, gesture.move(-Float.MAX_VALUE, 680f).positionMs);
        }
    }

    @Test
    public void timedFinishCommitsExactlyLastDisplayedMoveDespiteLiftRoll() {
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        PlaybackGesture.Result displayed = gesture.move(750f, 500f, 116L);
        assertEquals(PlaybackGesture.Action.SEEK, displayed.action);
        assertSame(displayed, gesture.finish(1100f, 900f, 132L));
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(1200f, 500f, 148L).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.finish(0f, 0f, 164L).action);
    }

    @Test
    public void timedInvalidOrNonmonotonicSamplesCannotChangePreviewOrMode() {
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        PlaybackGesture.Result displayed = gesture.move(750f, 500f, 116L);
        assertSame(displayed, gesture.move(1000f, 800f, 116L));
        assertSame(displayed, gesture.move(1000f, 800f, 115L));
        assertSame(displayed, gesture.move(1000f, 800f, -1L));
        assertSame(displayed, gesture.move(Float.NaN, 800f, 120L));
        assertSame(displayed, gesture.move(1000f, Float.POSITIVE_INFINITY, 120L));
        assertSame(displayed, gesture.finish(Float.NaN, Float.NaN, 0L));
    }

    @Test
    public void timestampedFineTransitionDiscardsFilterTail() {
        gesture.setFrameRate(30, 1);
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        long coarse = gesture.move(750f, 500f, 116L).positionMs;
        PlaybackGesture.Result fine = gesture.move(900f, 680f, 132L);
        assertTrue(fine.fineSeeking);
        assertEquals(coarse, fine.positionMs);
        assertEquals(coarse, gesture.move(900f, 680f, 148L).positionMs);
        long frame = Math.round(coarse * 30.0 / 1000.0);
        assertEquals(Math.round((frame + 1) * 1000.0 / 30.0),
                gesture.move(904f, 680f, 164L).positionMs);
        PlaybackGesture.Result exit = gesture.move(1000f, 620f, 180L);
        assertFalse(exit.fineSeeking);
        assertEquals(exit.positionMs, gesture.move(1000f, 500f, 196L).positionMs);
    }

    @Test
    public void timestampedHistoryMatchesIndividuallyDeliveredMoves() {
        PlaybackGesture batched = new PlaybackGesture(10f);
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        batched.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        PlaybackGesture.Result expected = null;
        for (int i = 1; i <= 20; i++) {
            expected = gesture.move(500f + i * 8f, 500f, 100L + i * 8L);
        }
        for (int i = 1; i < 20; i++) {
            batched.move(500f + i * 8f, 500f, 100L + i * 8L);
        }
        assertEquals(expected.positionMs, batched.move(660f, 500f, 260L).positionMs);
        assertEquals(expected.positionMs, batched.finish(1000f, 500f, 276L).positionMs);
    }

    @Test
    public void cancelAndNewBeginClearTimedPreviewFilterAndFineMode() {
        gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        gesture.move(750f, 500f, 116L);
        gesture.move(750f, 680f, 132L);
        gesture.cancel();
        assertEquals(PlaybackGesture.Action.NONE, gesture.finish(900f, 700f, 148L).action);
        gesture.begin(500f, 500f, 1000, 1000, 5000L, 10000L,
                true, 8, 20, 0.5f, 0L);
        PlaybackGesture.Result restarted = gesture.move(511f, 500f, 16L);
        assertFalse(restarted.fineSeeking);
        assertEquals(5000L, restarted.positionMs);
        assertSame(restarted, gesture.finish());
        begin(500f);
        assertEquals(PlaybackGesture.Action.NONE, gesture.finish().action);
    }

    @Test
    public void legacyBeginCanUseTimedMovesAndUntimedFinish() {
        begin(500f);
        PlaybackGesture.Result displayed = gesture.move(750f, 500f, 100L);
        assertEquals(1202344L, displayed.positionMs);
        assertSame(displayed, gesture.finish(1000f, 700f));
    }

    @Test
    public void timedFineSeekingRejectsJitterButAcceptsIntentionalFrameAndStableRelease() {
        for (int[] rate : new int[][] {{24, 1}, {30, 1}, {60000, 1001}}) {
            gesture.setFrameRate(rate[0], rate[1]);
            gesture.begin(500f, 500f, 1000, 1000, 1200000L, 2400000L,
                    true, 8, 20, 0.5f, 100L);
            long coarse = gesture.move(750f, 500f, 116L).positionMs;
            gesture.move(750f, 680f, 132L);
            for (int i = 1; i <= 100; i++) {
                PlaybackGesture.Result result = gesture.move(750f + (i % 2 == 0 ? -1f : 1f),
                        680f, 132L + i * 8L);
                assertTrue(result.fineSeeking);
                assertEquals(coarse, result.positionMs);
            }
            PlaybackGesture.Result frameStep = gesture.move(754f, 680f, 948L);
            double frameMs = 1000.0 * rate[1] / rate[0];
            assertEquals(Math.round((Math.round(coarse / frameMs) + 1) * frameMs),
                    frameStep.positionMs);
            assertSame(frameStep, gesture.finish(900f, 500f, 964L));
        }
    }

    @Test
    public void fineFrameStepsWorkAtBackwardNearOriginAndFarCoarseDestinations() {
        gesture.setFrameRate(60000, 1001);
        for (float x : new float[] {250f, 511f, 750f, 1500f}) {
            begin(500f);
            long coarse = gesture.move(x, 500f).positionMs;
            long frame = Math.round(coarse * 60000.0 / 1001000.0);
            assertEquals(coarse, gesture.move(x, 680f).positionMs);
            assertEquals(Math.round((frame + 1) * 1001000.0 / 60000.0),
                    gesture.move(x + 4f, 680f).positionMs);
            assertEquals(Math.round((frame - 1) * 1001000.0 / 60000.0),
                    gesture.move(x - 4f, 680f).positionMs);
        }
    }

    @Test
    public void timedNegativeBeginDisablesGestureAndNewBeginReplacesAllState() {
        begin(500f);
        gesture.move(750f, 500f, 1000L);
        gesture.move(750f, 680f, 1016L);
        gesture.begin(500f, 500f, 1000, 1000, 5000L, 10000L,
                true, 8, 20, 0.5f, -1L);
        assertEquals(PlaybackGesture.Action.NONE, gesture.move(900f, 500f, 16L).action);
        assertEquals(PlaybackGesture.Action.NONE, gesture.finish().action);
        gesture.begin(500f, 500f, 1000, 1000, 5000L, 10000L,
                true, 8, 20, 0.5f, 0L);
        PlaybackGesture.Result result = gesture.move(511f, 500f, 16L);
        assertFalse(result.fineSeeking);
        assertEquals(PlaybackGesture.Action.SEEK, result.action);
        assertEquals(5000L, result.positionMs);
    }

    @Test
    public void timedVerticalControlsKeepOriginalGainWithoutFiltering() {
        gesture.begin(900f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        assertResult(gesture.move(900f, 300f, 101L), PlaybackGesture.Action.VOLUME,
                1200000L, 13, 0.5f);
        assertResult(gesture.move(900f, 700f, 102L), PlaybackGesture.Action.VOLUME,
                1200000L, 3, 0.5f);
        gesture.begin(100f, 500f, 1000, 1000, 1200000L, 2400000L,
                true, 8, 20, 0.5f, 100L);
        assertResult(gesture.move(100f, 300f, 101L), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.75f);
        assertResult(gesture.move(100f, 700f, 102L), PlaybackGesture.Action.BRIGHTNESS,
                1200000L, 8, 0.25f);
    }
}