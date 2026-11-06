package com.example.big.playback;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class GlVideoRendererTest {
    @Test
    public void extractedFrameUsesBlurAndRemainsStableUntilLivePlaybackResumes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.frame(canvas -> canvas.drawColor(Color.RED)).recycle();
            fixture.renderer.setPlaybackActive(false);
            fixture.renderer.setFeedback(new RectF(60, 12, 132, 84));
            Bitmap still = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            still.eraseColor(Color.WHITE);
            long before = fixture.renderer.frameCount;
            fixture.renderer.setSeekStill(still, 0);
            await(() -> fixture.renderer.frameCount > before);
            Bitmap image = fixture.latest();
            assertEquals(Color.BLACK, image.getPixel(12, 48));
            assertEquals(Color.WHITE, image.getPixel(96, 2));
            assertEquals(Color.rgb(158, 158, 158), image.getPixel(96, 48));
            image.recycle();
            long draws = fixture.renderer.drawCount;
            long received = fixture.renderer.receivedFrameCount;
            Canvas canvas = fixture.input.lockCanvas(null);
            canvas.drawColor(Color.GREEN);
            fixture.input.unlockCanvasAndPost(canvas);
            await(() -> fixture.renderer.receivedFrameCount > received);
            assertEquals("Paused VLC refresh must not overwrite the exact frame", draws, fixture.renderer.drawCount);
            fixture.renderer.setPlaybackActive(true);
            image = fixture.frame(target -> target.drawColor(Color.BLUE));
            assertEquals(Color.BLUE, image.getPixel(96, 2));
            assertEquals(Color.rgb(0, 0, 158), image.getPixel(96, 48));
            image.recycle();
            still.recycle();
        }
    }

    @Test
    public void extractedFrameKeepsOrientationAndSupportsFitCropAndStretch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.latest().recycle();
            Bitmap still = Bitmap.createBitmap(48, 96, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(still);
            canvas.drawColor(Color.RED);
            Paint paint = new Paint();
            paint.setColor(Color.BLUE);
            canvas.drawRect(0, 48, 48, 96, paint);
            for (int fit = 0; fit < 3; fit++) {
                long before = fixture.renderer.frameCount;
                fixture.renderer.setSeekStill(still, fit);
                await(() -> fixture.renderer.frameCount > before);
                Bitmap image = fixture.latest();
                assertEquals(Color.RED, image.getPixel(96, 8));
                assertEquals(Color.BLUE, image.getPixel(96, 88));
                assertEquals(fit == 0 ? Color.BLACK : Color.RED, image.getPixel(4, 8));
                image.recycle();
            }
            still.recycle();
        }
    }

    @Test
    public void feedbackIsDarkerWithBroadSmoothEdgesAndRounderCorners() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.renderer.setFeedback(new RectF(24, 12, 168, 84));
            Bitmap image = fixture.frame(canvas -> canvas.drawColor(Color.WHITE));
            int center = Color.red(image.getPixel(96, 48));
            assertTrue("The backdrop should darken white video by roughly 38%: " + center,
                    center >= 150 && center <= 165);
            assertEquals(255, Color.alpha(image.getPixel(96, 48)));
            assertEquals("More rounded corners must leave the video untouched", Color.WHITE,
                    image.getPixel(28, 16));
            int middleFade = Color.red(image.getPixel(96, 24));
            assertTrue("The fade should still be gradual 12px inside the edge: " + middleFade,
                    middleFade > center + 30 && middleFade < 245);
            int previous = 255;
            for (int y = 12; y <= 36; y++) {
                int value = Color.red(image.getPixel(96, y));
                assertTrue("The edge should darken smoothly without steps", value <= previous
                        && previous - value <= 10);
                previous = value;
            }
            assertEquals(Color.WHITE, image.getPixel(96, 2));
            image.recycle();
        }
    }

    @Test
    public void denseDisplayBlurSuppressesRepeatedDetailOnBothAxes() throws Exception {
        try (Fixture fixture = new Fixture(3)) {
            fixture.renderer.setFeedback(new RectF(72, 36, 504, 252));
            for (boolean horizontal : new boolean[]{false, true}) {
                Bitmap image = fixture.frame(canvas -> {
                    canvas.drawColor(Color.WHITE);
                    Paint paint = new Paint();
                    paint.setColor(Color.BLACK);
                    for (int offset = 0; offset < 576; offset += 12) {
                        if (horizontal) {
                            canvas.drawRect(0, offset, 576, offset + 6, paint);
                        } else {
                            canvas.drawRect(offset, 0, offset + 6, 288, paint);
                        }
                    }
                });
                int low = 255;
                int high = 0;
                for (int offset = -18; offset <= 18; offset++) {
                    int value = Color.red(image.getPixel(horizontal ? 288 : 288 + offset,
                            horizontal ? 144 + offset : 144));
                    low = Math.min(low, value);
                    high = Math.max(high, value);
                }
                assertTrue("Dense-display detail must not turn into blur bands: " + low + ".." + high,
                        high - low < 18);
                assertTrue("Blurred detail must remain visible rather than becoming black", low > 55);
                image.recycle();
            }
        }
    }

    @Test
    public void centerReplacesSharpPixelsAndBothBlurAxesFadeAtRoundedEdges() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.renderer.setFeedback(new RectF(24, 12, 168, 84));
            Bitmap image = fixture.frame(canvas -> {
                canvas.drawColor(Color.BLACK);
                Paint paint = new Paint();
                paint.setColor(Color.WHITE);
                canvas.drawRect(96, 0, 192, 96, paint);
            });
            assertEquals("No sharp surface may leak through the blurred center", 255, Color.alpha(image.getPixel(92, 48)));
            assertTrue("Horizontal Gaussian must mix white into the black side", Color.red(image.getPixel(92, 48)) > 35);
            assertTrue("Blur radius fades near the top edge", Color.red(image.getPixel(92, 13)) < 15);
            assertEquals("Outside the label must be unchanged", Color.BLACK, image.getPixel(92, 2));
            assertEquals("Rounded corner must retain the unfiltered frame", Color.WHITE, image.getPixel(167, 12));
            image.recycle();
            image = fixture.frame(canvas -> {
                canvas.drawColor(Color.BLACK);
                Paint paint = new Paint();
                paint.setColor(Color.WHITE);
                canvas.drawRect(0, 48, 192, 96, paint);
            });
            assertTrue("Vertical Gaussian must mix white into the black side", Color.red(image.getPixel(96, 44)) > 35);
            assertTrue("Vertical radius fades near the left edge", Color.red(image.getPixel(25, 44)) < 15);
            image.recycle();
        }
    }

    @Test
    public void highFrequencyDetailDoesNotShowThroughAndOrientationIsPreserved() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.renderer.setFeedback(new RectF(24, 12, 168, 84));
            Bitmap image = fixture.frame(canvas -> {
                canvas.drawColor(Color.WHITE);
                Paint paint = new Paint();
                paint.setColor(Color.BLACK);
                for (int x = 0; x < 192; x += 2) {
                    canvas.drawRect(x, 0, x + 1, 96, paint);
                }
                paint.setColor(Color.RED);
                canvas.drawRect(0, 0, 192, 8, paint);
                paint.setColor(Color.BLUE);
                canvas.drawRect(0, 88, 192, 96, paint);
            });
            assertEquals(Color.RED, image.getPixel(100, 2));
            assertEquals(Color.BLUE, image.getPixel(100, 94));
            int low = 255;
            int high = 0;
            for (int x = 70; x < 120; x++) {
                int value = Color.red(image.getPixel(x, 48));
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
            assertTrue("The blurred center must suppress sharp one-pixel stripes: " + low + ".." + high, high - low < 20);
            assertTrue("Blur must keep image brightness, not replace it with a dark box", low > 70 && high < 170);
            image.recycle();
        }
    }

    @Test
    public void incomingFrameCadenceDrivesRenderingAndPausedFeedbackRedrawsOnlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.renderer.setFeedback(new RectF(24, 12, 168, 84));
            fixture.renderer.setFrameRate(24000f / 1001);
            for (int delay : new int[]{42, 42, 17, 17, 33, 85}) {
                long count = fixture.renderer.frameCount;
                fixture.frame(canvas -> canvas.drawColor(Color.GREEN)).recycle();
                assertEquals(count + 1, fixture.renderer.frameCount);
                long draws = fixture.renderer.drawCount;
                SystemClock.sleep(delay);
                assertEquals("No refresh-rate or polling loop may redraw identical frames", draws, fixture.renderer.drawCount);
            }
            long frames = fixture.renderer.frameCount;
            long draws = fixture.renderer.drawCount;
            fixture.renderer.setFeedback(new RectF());
            await(() -> fixture.renderer.drawCount > draws);
            assertFalse(fixture.renderer.blurActive);
            assertEquals(frames, fixture.renderer.frameCount);
            assertEquals(draws + 1, fixture.renderer.drawCount);
            SystemClock.sleep(200);
            assertEquals(draws + 1, fixture.renderer.drawCount);
        }
    }

    @Test
    public void pausedProducerRefreshesAreNotFilteredRepeatedlyButASeekCanReplaceTheStill() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.frame(canvas -> canvas.drawColor(Color.GREEN)).recycle();
            fixture.renderer.setPlaybackActive(false);
            long before = fixture.renderer.drawCount;
            fixture.renderer.setFeedback(new RectF(24, 12, 168, 84));
            await(() -> fixture.renderer.drawCount > before);
            long frames = fixture.renderer.frameCount;
            long draws = fixture.renderer.drawCount;
            Canvas canvas = fixture.input.lockCanvas(null);
            canvas.drawColor(Color.RED);
            fixture.input.unlockCanvasAndPost(canvas);
            SystemClock.sleep(150);
            assertEquals(frames, fixture.renderer.frameCount);
            assertEquals(draws, fixture.renderer.drawCount);
            fixture.renderer.requestStillFrame();
            Bitmap image = fixture.frame(target -> target.drawColor(Color.BLUE));
            assertEquals("Seeking replaces the still with darkened blue video",
                    Color.rgb(0, 0, 158), image.getPixel(96, 48));
            assertEquals(Color.BLUE, image.getPixel(96, 2));
            image.recycle();
            assertEquals(frames + 1, fixture.renderer.frameCount);
            fixture.renderer.setPlaybackActive(true);
            image = fixture.frame(target -> target.drawColor(Color.WHITE));
            assertEquals("Resuming replaces the still with darkened white video",
                    Color.rgb(158, 158, 158), image.getPixel(96, 48));
            assertEquals(Color.WHITE, image.getPixel(96, 2));
            image.recycle();
            assertEquals(frames + 2, fixture.renderer.frameCount);
        }
    }

    @Test
    public void emptyVideoStaysBlackAndReleaseInvalidatesInputAndStopsRendering() throws Exception {
        Fixture fixture = new Fixture();
        Surface input = fixture.input;
        Bitmap image = fixture.latest();
        assertEquals(Color.BLACK, image.getPixel(96, 48));
        image.recycle();
        assertEquals(0, fixture.renderer.frameCount);
        fixture.close();
        assertFalse(input.isValid());
        long count = fixture.renderer.drawCount;
        SystemClock.sleep(100);
        assertEquals(count, fixture.renderer.drawCount);
        try (Fixture replacement = new Fixture()) {
            image = replacement.frame(canvas -> canvas.drawColor(Color.RED));
            assertEquals(Color.RED, image.getPixel(96, 48));
            image.recycle();
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10);
        }
        assertTrue("Timed out waiting for GPU rendering", condition.getAsBoolean());
    }

    private static final class Fixture implements AutoCloseable {
        final ImageReader reader;
        final AtomicBoolean failed = new AtomicBoolean();
        final GlVideoRenderer renderer;
        Surface input;

        Fixture() throws Exception {
            this(1);
        }

        Fixture(int density) throws Exception {
            reader = ImageReader.newInstance(192 * density, 96 * density, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            CountDownLatch ready = new CountDownLatch(1);
            renderer = new GlVideoRenderer(reader.getSurface(), 192 * density, 96 * density, density,
                    new GlVideoRenderer.Listener() {
                @Override
                public void onReady(Surface surface) {
                    input = surface;
                    ready.countDown();
                }

                @Override
                public void onFailure() {
                    failed.set(true);
                    ready.countDown();
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertFalse("EGL/shader initialization failed", failed.get());
            assertNotNull(input);
            await(() -> renderer.drawCount > 0 || failed.get());
            assertFalse(failed.get());
        }

        Bitmap frame(Consumer<Canvas> draw) {
            try (Image ignored = reader.acquireLatestImage()) {
                // Discard an earlier output before submitting this source frame.
            }
            long before = renderer.frameCount;
            Canvas canvas = input.lockCanvas(null);
            draw.accept(canvas);
            input.unlockCanvasAndPost(canvas);
            await(() -> renderer.frameCount > before || failed.get());
            assertFalse("GPU frame failed", failed.get());
            return latest();
        }

        Bitmap latest() {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < deadline) {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                            assertNotNull(buffer);
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                            assertNotNull(hardware);
                            Bitmap result = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            hardware.recycle();
                            return result;
                        }
                    }
                }
                SystemClock.sleep(10);
            }
            throw new AssertionError("No GPU output frame");
        }

        @Override
        public void close() throws Exception {
            CountDownLatch released = new CountDownLatch(1);
            renderer.release(released::countDown);
            assertTrue("Renderer must release without deadlocking", released.await(5, TimeUnit.SECONDS));
            reader.close();
            assertFalse(failed.get());
        }
    }
}