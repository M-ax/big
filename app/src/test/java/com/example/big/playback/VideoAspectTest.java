package com.example.big.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VideoAspectTest {
    @Test
    public void uniformCropRemovesFittedBarsWithoutNativeAbsoluteZoom() {
        for (double aspect : new double[]{9. / 16, 16. / 9, 4. / 3, 1}) {
            for (int[] size : new int[][]{{1989, 960}, {960, 1989}}) {
                float zoom = VideoAspect.cropZoom(size[0], size[1], aspect);
                assertEquals(VideoAspect.width(size[0], size[1], aspect, 1),
                        VideoAspect.width(size[0], size[1], aspect, 0) * zoom, .001);
                assertEquals(VideoAspect.height(size[0], size[1], aspect, 1),
                        VideoAspect.height(size[0], size[1], aspect, 0) * zoom, .001);
            }
        }
        assertEquals(1, VideoAspect.cropZoom(1920, 1080, 16. / 9), 0);
        assertEquals(1, VideoAspect.cropZoom(0, 1080, 1), 0);
        assertEquals(1, VideoAspect.cropZoom(1920, 0, 1), 0);
        assertEquals(1, VideoAspect.cropZoom(1920, 1080, Double.NaN), 0);
    }

    @Test
    public void reportedVlcSarReducesToFourByThree() {
        assertEquals("4:3", VideoAspect.vlcAspectRatio(720, 576, 442368, 414720));
        assertEquals("4:3", VideoAspect.vlcAspectRatio(720, 576, 16, 15));
    }

    @Test
    public void reportedUnreducedRatioReproducesNativeOverflow() {
        assertEquals(19.7837837838, nativeDisplayAspect("318504960:238878720", 720, 576),
                .000001);
        assertEquals(4. / 3, nativeDisplayAspect(
                VideoAspect.vlcAspectRatio(720, 576, 442368, 414720), 720, 576), .000001);
    }

    @Test
    public void vlcRatioKeepsWidescreenAndPortraitGeometry() {
        assertEquals("16:9", VideoAspect.vlcAspectRatio(1920, 1080, 1, 1));
        assertEquals("16:9", VideoAspect.vlcAspectRatio(720, 576, 64, 45));
        assertEquals("9:16", VideoAspect.vlcAspectRatio(1080, 1920, 1, 1));
    }

    @Test
    public void vlcRatioRejectsInvalidDimensionsAndSar() {
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertNull(VideoAspect.vlcAspectRatio(invalid, 576, 16, 15));
            assertNull(VideoAspect.vlcAspectRatio(720, invalid, 16, 15));
            assertNull(VideoAspect.vlcAspectRatio(720, 576, invalid, 15));
            assertNull(VideoAspect.vlcAspectRatio(720, 576, 16, invalid));
        }
    }

    @Test
    public void vlcRatioReducesHugeProductsWithoutOverflow() {
        assertEquals("1:1", VideoAspect.vlcAspectRatio(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals("1:1", VideoAspect.vlcAspectRatio(Integer.MAX_VALUE, 1,
                1, Integer.MAX_VALUE));
        assertEquals("16:9", VideoAspect.vlcAspectRatio(1920, 1080,
                Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertNull(VideoAspect.vlcAspectRatio(Integer.MAX_VALUE, Integer.MAX_VALUE - 1,
                Integer.MAX_VALUE, Integer.MAX_VALUE - 1));
    }

    @Test
    public void vlcRatioBoundsNativeDimensionProducts() {
        int limit = Integer.MAX_VALUE / 2;
        assertEquals(limit + ":1", VideoAspect.vlcAspectRatio(2, 2, limit, 1));
        assertEquals("1:" + limit, VideoAspect.vlcAspectRatio(2, 2, 1, limit));
        assertNull(VideoAspect.vlcAspectRatio(2, 2, limit + 1, 1));
        assertNull(VideoAspect.vlcAspectRatio(2, 2, 1, limit + 1));
        assertNull(VideoAspect.vlcAspectRatio(2, 2, Integer.MAX_VALUE, 1));
    }

    @Test
    public void vlcRatioAlsoBoundsReducedSarForNativePlacement() {
        // The DAR 1000003:1000033 fits conversion, but the derived SAR overflows placement.
        assertNull(VideoAspect.vlcAspectRatio(1920, 1080, 9000027, 16000528));
    }

    @Test
    public void anamorphicSamplePreviewUsesDisplayAspectNotBitmapDimensions() {
        double aspect = VideoAspect.displayAspect(720, 576, 16, 15, false);
        assertEquals(4. / 3, aspect, .000001);
        assertArea(1920, 1080, 1440, 1080, VideoAspect.previewAspect(aspect, 720, 576), 0);
    }

    @Test
    public void resizedPreviewCannotSquashFourByThreeToWidescreen() {
        double aspect = VideoAspect.displayAspect(720, 576, 16, 15, false);
        // A retriever may return a resized bitmap with a different pixel aspect.
        assertArea(1920, 1080, 1440, 1080, VideoAspect.previewAspect(aspect, 720, 405), 0);
    }

    @Test
    public void displaySizedPreviewDoesNotApplySarTwice() {
        double aspect = VideoAspect.displayAspect(720, 576, 16, 15, false);
        assertEquals(4. / 3, VideoAspect.previewAspect(aspect, 768, 576), .000001);
    }

    @Test
    public void rotatedPreviewUsesPostRotationDisplayAspectOnlyOnce() {
        double aspect = VideoAspect.displayAspect(320, 180, 1, 1, true);
        assertArea(1920, 1080, 607.5f, 1080, VideoAspect.previewAspect(aspect, 180, 320), 0);
        assertEquals(.75, VideoAspect.previewAspect(
                VideoAspect.displayAspect(720, 576, 16, 15, true), 576, 720), .000001);
    }

    @Test
    public void fitCropAndStretchKeepExistingModes() {
        assertArea(1920, 1080, 1440, 1080, 4. / 3, 0);
        assertArea(1920, 1080, 1920, 1440, 4. / 3, 1);
        assertArea(1920, 1080, 1920, 1080, 4. / 3, 2);
        assertArea(1080, 1920, 1080, 810, 4. / 3, 0);
        assertArea(1080, 1920, 2560, 1920, 4. / 3, 1);
        assertArea(1080, 1920, 1080, 1920, 4. / 3, 2);
    }

    @Test
    public void squarePixelWidescreenKeepsSixteenByNine() {
        double aspect = VideoAspect.displayAspect(1920, 1080, 1, 1, false);
        assertArea(2400, 1080, 1920, 1080, VideoAspect.previewAspect(aspect, 640, 360), 0);
    }

    @Test
    public void missingMetadataFallsBackWithoutPartialSarOrInvalidGeometry() {
        assertEquals(1.25, VideoAspect.displayAspect(720, 576, 16, 0, false), .000001);
        assertEquals(1.25, VideoAspect.displayAspect(720, 576, 0, 15, false), .000001);
        assertEquals(0, VideoAspect.displayAspect(0, 576, 16, 15, false), 0);
        for (double aspect : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(16. / 9, VideoAspect.previewAspect(aspect, 320, 180), .000001);
            assertArea(1920, 1080, 1920, 1080, aspect, 0);
        }
    }

    @Test
    public void largeDimensionsDoNotOverflowIntegerProducts() {
        assertEquals(1, VideoAspect.displayAspect(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false), 0);
    }

    private static double nativeDisplayAspect(String ratio, int width, int height) {
        String[] terms = ratio.split(":");
        // VLC 3 vout_SetDisplayAspect multiplies unsigned 32-bit values before reducing.
        long sarNum = (Long.parseLong(terms[0]) * height) & 0xffffffffL;
        long sarDen = (Long.parseLong(terms[1]) * width) & 0xffffffffL;
        return (double) width * sarNum / ((double) height * sarDen);
    }

    private static void assertArea(int width, int height, float expectedWidth, float expectedHeight,
                                   double aspect, int fit) {
        assertEquals(expectedWidth, VideoAspect.width(width, height, aspect, fit), .001f);
        assertEquals(expectedHeight, VideoAspect.height(width, height, aspect, fit), .001f);
    }
}