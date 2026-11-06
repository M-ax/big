package com.example.big.playback;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.SeekBar;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.PlayerActivity;
import com.example.big.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class GestureFeedbackViewTest {
    @Test
    public void backdropIsDarkenedWithTransparentRoundedFeatheredEdges() {
        FeatheredBackdrop backdrop = new FeatheredBackdrop(1f);
        Bitmap frame = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
        frame.eraseColor(Color.WHITE);
        backdrop.setFrame(frame, new RectF(0, 0, 128, 64));
        Bitmap rendered = render(backdrop);
        int center = rendered.getPixel(64, 32);
        assertEquals("Sharp video underneath must not leak through the center", 255, Color.alpha(center));
        assertTrue("The blurred video should be darkened, not replaced by black",
                Color.red(center) >= 150 && Color.red(center) <= 165);
        assertEquals(0, Color.alpha(rendered.getPixel(0, 0)));
        assertEquals("Larger rounded corners remain transparent", 0, Color.alpha(rendered.getPixel(4, 4)));
        int edge = Color.alpha(rendered.getPixel(64, 1));
        int feather = Color.alpha(rendered.getPixel(64, 6));
        assertTrue(edge < 15);
        assertTrue(feather > edge && feather < Color.alpha(center));
        assertTrue("Fade extends beyond the old 12px border", Color.alpha(rendered.getPixel(64, 12)) < 160);
        rendered.recycle();
    }

    @Test
    public void actualVideoSamplesBlurMoreInCenterThanAtEdges() {
        FeatheredBackdrop backdrop = new FeatheredBackdrop(1f);
        Bitmap frame = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(Color.BLACK);
        canvas.clipRect(64, 0, 128, 64);
        canvas.drawColor(Color.WHITE);
        backdrop.setFrame(frame, new RectF(0, 0, 128, 64));
        Bitmap rendered = render(backdrop);
        int centralBlur = Color.red(rendered.getPixel(60, 32));
        int edgeBlur = Color.red(rendered.getPixel(60, 2));
        assertTrue("The black side must receive blurred white video samples", centralBlur > 35);
        assertTrue("Blur radius must fall toward zero at the edge", edgeBlur < centralBlur / 2);
        assertTrue(Color.red(rendered.getPixel(90, 32)) >= 150
                && Color.red(rendered.getPixel(90, 32)) <= 165);
        rendered.recycle();
    }

    @Test
    public void missingVideoUsesOnlyFaintFeatheredTint() {
        FeatheredBackdrop backdrop = new FeatheredBackdrop(1f);
        Bitmap rendered = render(backdrop);
        assertFalse(backdrop.hasFrame());
        assertTrue(Color.alpha(rendered.getPixel(64, 32)) <= 32);
        assertEquals(0, Color.alpha(rendered.getPixel(0, 0)));
        assertTrue(Color.alpha(rendered.getPixel(64, 1)) < 5);
        rendered.recycle();
    }

    @Test
    public void realVideoBackdropSurvivesResizeAndReleasesWhenHiddenOrBackgrounded() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "big-backdrop-test.mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BigTests");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        try {
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("player-test.mp4");
                 OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                assertNotNull(output);
                input.transferTo(output);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(
                    new Intent(context, PlayerActivity.class).setData(uri))) {
                await(scenario, activity -> ((SeekBar) activity.findViewById(R.id.seek_bar)).isEnabled()
                        && activity.findViewById(R.id.playback_status).getVisibility() == View.GONE);
                scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
                awaitVideoFrame(scenario);
                assertBackdropDraw(scenario, GestureFeedbackViewTest::previewSeek);
                scenario.onActivity(activity -> {
                    Spanned text = (Spanned) feedback(activity).getText();
                    ForegroundColorSpan[] colors = text.getSpans(0, text.length(), ForegroundColorSpan.class);
                    assertEquals(2, colors.length);
                    assertEquals(Color.LTGRAY, colors[0].getForegroundColor());
                    assertEquals(Color.WHITE, colors[1].getForegroundColor());
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                });
                await(scenario, activity -> {
                    View root = activity.findViewById(R.id.player_root);
                    return root.getWidth() > root.getHeight() && !root.isLayoutRequested();
                });
                awaitVideoFrame(scenario);
                assertBackdropDraw(scenario, activity -> {
                    previewSeek(activity);
                    feedback(activity).setText(SeekFeedback.format(12345000, 12335000));
                });
                Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(screenshot);
                try (OutputStream output = new FileOutputStream(new File(context.getExternalCacheDir(),
                        "big-feathered-seek.png"))) {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
                screenshot.recycle();
                SystemClock.sleep(1700);
                scenario.onActivity(activity -> {
                    assertEquals("Feedback remains visible while the finger is held", View.VISIBLE,
                            feedback(activity).getVisibility());
                    View surface = activity.findViewById(R.id.gesture_surface);
                    long now = SystemClock.uptimeMillis();
                    MotionEvent up = MotionEvent.obtain(now - 2000, now, MotionEvent.ACTION_UP,
                            surface.getWidth() * .65f, surface.getHeight() * .45f, 0);
                    surface.dispatchTouchEvent(up);
                    up.recycle();
                });
                await(scenario, activity -> feedback(activity).getVisibility() == View.GONE);
                scenario.onActivity(activity -> assertFalse(feedback(activity).hasVideoBackdrop()));
                assertBackdropDraw(scenario, GestureFeedbackViewTest::previewSeek);
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.onActivity(activity -> assertFalse(feedback(activity).hasVideoBackdrop()));
                scenario.moveToState(Lifecycle.State.RESUMED);
                scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
                awaitVideoFrame(scenario);
                assertBackdropDraw(scenario, GestureFeedbackViewTest::previewSeek);
                scenario.onActivity(activity -> {
                    feedback(activity).setVideoSurface(null);
                    assertFalse(feedback(activity).hasVideoBackdrop());
                });
                SystemClock.sleep(250);
                scenario.onActivity(activity -> assertFalse(feedback(activity).hasVideoBackdrop()));
            }
        } finally {
            context.getContentResolver().delete(uri, null, null);
        }
    }

    private static GestureFeedbackView feedback(PlayerActivity activity) {
        return activity.findViewById(R.id.gesture_feedback);
    }

    private static void previewSeek(PlayerActivity activity) {
        View surface = activity.findViewById(R.id.gesture_surface);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
                surface.getWidth() * .25f, surface.getHeight() * .45f, 0);
        MotionEvent move = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_MOVE,
                surface.getWidth() * .65f, surface.getHeight() * .45f, 0);
        surface.dispatchTouchEvent(down);
        surface.dispatchTouchEvent(move);
        down.recycle();
        move.recycle();
    }

    private static void assertBackdropDraw(ActivityScenario<PlayerActivity> scenario,
                                            Consumer<PlayerActivity> action) throws Exception {
        CountDownLatch drawn = new CountDownLatch(1);
        AtomicReference<ViewTreeObserver.OnDrawListener> observer = new AtomicReference<>();
        scenario.onActivity(activity -> {
            GestureFeedbackView view = feedback(activity);
            ViewTreeObserver.OnDrawListener listener = () -> {
                if (view.isShown() && view.hasVideoBackdrop()) {
                    drawn.countDown();
                }
            };
            observer.set(listener);
            view.getViewTreeObserver().addOnDrawListener(listener);
            action.accept(activity);
        });
        try {
            assertTrue("The visible label must render a real video backdrop", drawn.await(5, TimeUnit.SECONDS));
        } finally {
            scenario.onActivity(activity -> feedback(activity).getViewTreeObserver().removeOnDrawListener(observer.get()));
        }
    }

    private static void awaitVideoFrame(ActivityScenario<PlayerActivity> scenario) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Bitmap> image = new AtomicReference<>();
            scenario.onActivity(activity -> {
                GlVideoView source = activity.findViewById(R.id.video_layout);
                image.set(VideoSnapshot.capture(source, 320, 180));
            });
            Bitmap frame = image.get();
            boolean visibleVideo = false;
            if (frame != null) {
                int first = frame.getPixel(0, 0);
                for (int x = 0; x < frame.getWidth(); x++) {
                    visibleVideo |= frame.getPixel(x, frame.getHeight() / 2) != first;
                }
                frame.recycle();
            }
            if (visibleVideo) {
                return;
            }
            SystemClock.sleep(100);
        }
        fail("No decoded video frame available before seeking");
    }

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        AtomicBoolean success = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> success.set(condition.test(activity)));
            if (success.get()) {
                return;
            }
            SystemClock.sleep(40);
        }
        fail("Timed out waiting for video backdrop state");
    }

    private static Bitmap render(FeatheredBackdrop backdrop) {
        try (ImageReader reader = ImageReader.newInstance(128, 64, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            HardwareRenderer renderer = new HardwareRenderer();
            RenderNode node = new RenderNode("backdrop-test");
            try {
                renderer.setOpaque(false);
                renderer.setSurface(reader.getSurface());
                node.setPosition(0, 0, 128, 64);
                Canvas canvas = node.beginRecording();
                backdrop.draw(canvas, 128, 64);
                node.endRecording();
                renderer.setContentRoot(node);
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                try (Image image = reader.acquireLatestImage()) {
                    assertNotNull(image);
                    try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                        assertNotNull(buffer);
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                        assertNotNull(hardware);
                        Bitmap result = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        hardware.recycle();
                        return result;
                    }
                }
            } finally {
                renderer.destroy();
                node.discardDisplayList();
            }
        }
    }
}