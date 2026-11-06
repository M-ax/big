package com.example.big.playback;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.PlayerActivity;
import com.example.big.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SeekLatencyTest {

    @Test
    public void pausedExactSeeksDoNotFlushPlaybackDecoderAndResumeOnlyTheLastTarget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long[] original = new long[1];
            fixture.scenario.onActivity(activity -> original[0] = player(activity).getTime());
            for (int target : new int[]{120, 48, 168}) {
                fixture.scenario.onActivity(activity -> seek(activity, Math.round(target * 1000. / 24)));
                awaitFrame(fixture.scenario, target);
                fixture.scenario.onActivity(activity -> {
                    assertFalse(player(activity).isPlaying());
                    assertTrue("Paused exact previews must not reposition the playback decoder",
                            Math.abs(player(activity).getTime() - original[0]) < 100);
                    assertEquals(Math.round(target * 1000f / 240),
                            ((android.widget.SeekBar) activity.findViewById(R.id.seek_bar)).getProgress());
                });
            }
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(fixture.scenario, activity -> player(activity).isPlaying()
                    && player(activity).getTime() >= 7000 && player(activity).getTime() < 9000
                    && frame(activity) > 170);
        }
    }

    @Test
    public void precisePausedSeeksPresentAdjacentPredictedFramesInBothDirections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long[] latency = new long[6];
            int[] targets = {120, 121, 119, 50, 51, 49};
            for (int i = 0; i < targets.length; i++) {
                int target = targets[i];
                long started = SystemClock.elapsedRealtime();
                fixture.scenario.onActivity(activity -> seek(activity, Math.round(target * 1000. / 24)));
                awaitFrame(fixture.scenario, target);
                latency[i] = SystemClock.elapsedRealtime() - started;
                fixture.scenario.onActivity(activity -> assertFalse(player(activity).isPlaying()));
            }
            Arrays.sort(latency);
            android.util.Log.i("SeekLatencyTest", "24fps long-GOP I/P/B exact seeks: median="
                    + latency[latency.length / 2] + "ms, max=" + latency[latency.length - 1]
                    + "ms (includes PixelCopy observation)");
            assertTrue("Local synthetic seeks must not stall for seconds", latency[latency.length - 1] < 1500);
        }
    }

    @Test
    public void gesturePreviewsBeforeReleaseAndCancelRestoresOriginalFrame() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scenario.onActivity(activity -> seek(activity, 2000));
            awaitFrame(fixture.scenario, 48);
            long down = SystemClock.uptimeMillis();
            touch(fixture.scenario, down, MotionEvent.ACTION_DOWN, .3f, .4f);
            SystemClock.sleep(20);
            touch(fixture.scenario, down, MotionEvent.ACTION_MOVE, .5f, .4f);
            await(fixture.scenario, activity -> frame(activity) != 48 && frame(activity) >= 0);
            touch(fixture.scenario, down, MotionEvent.ACTION_CANCEL, .5f, .4f);
            awaitFrame(fixture.scenario, 48);
            SystemClock.sleep(350);
            fixture.scenario.onActivity(activity -> {
                assertEquals("Canceled preview must not reappear later", 48, frame(activity));
                assertFalse(player(activity).isPlaying());
            });
        }
    }

    @Test
    public void fineGestureMovesOneDecodedFrameAndLiftRollDoesNotChangeTarget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scenario.onActivity(activity -> seek(activity, 2000));
            awaitFrame(fixture.scenario, 48);
            long down = SystemClock.uptimeMillis();
            touch(fixture.scenario, down, MotionEvent.ACTION_DOWN, .3f, .25f);
            SystemClock.sleep(300);
            touch(fixture.scenario, down, MotionEvent.ACTION_MOVE, .38f, .25f);
            SystemClock.sleep(300);
            touch(fixture.scenario, down, MotionEvent.ACTION_MOVE, .38f, .6f);
            AtomicInteger before = new AtomicInteger();
            fixture.scenario.onActivity(activity -> {
                PlaybackGesture.Result result = gesture(activity);
                assertTrue(result.fineSeeking);
                before.set((int) Math.round(result.positionMs * 24. / 1000));
            });
            awaitFrame(fixture.scenario, before.get());
            SystemClock.sleep(300);
            long requested = SystemClock.elapsedRealtime();
            fixture.scenario.onActivity(activity -> {
                View surface = activity.findViewById(R.id.gesture_surface);
                float x = surface.getWidth() * .38f + 4 * activity.getResources().getDisplayMetrics().density;
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        x, surface.getHeight() * .6f, 0);
                surface.dispatchTouchEvent(event);
                event.recycle();
                assertEquals(before.get() + 1, Math.round(gesture(activity).positionMs * 24. / 1000));
            });
            awaitFrame(fixture.scenario, before.get() + 1);
            long latency = SystemClock.elapsedRealtime() - requested;
            android.util.Log.i("SeekLatencyTest", "Fine adjacent-frame gesture to observed pixels=" + latency + "ms");
            assertTrue("Fine cached-frame movement must stay responsive", latency < 500);
            touch(fixture.scenario, down, MotionEvent.ACTION_UP, .8f, .85f);
            awaitFrame(fixture.scenario, before.get() + 1);
            SystemClock.sleep(300);
            fixture.scenario.onActivity(activity -> {
                assertEquals(before.get() + 1, frame(activity));
                assertFalse(player(activity).isPlaying());
            });
            AtomicReference<SeekFrameExtractor> oldExtractor = new AtomicReference<>();
            fixture.scenario.onActivity(activity -> {
                oldExtractor.set(extractor(activity));
                assertNotNull(oldExtractor.get());
                assertTrue(oldExtractor.get().getCachedBytes() > 0);
            });
            fixture.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            assertTrue("A paused but visible player retains seek frames", oldExtractor.get().getCachedBytes() > 0);
            fixture.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            assertEquals("Hidden player must discard decoded frames", 0, oldExtractor.get().getCachedBytes());
            fixture.scenario.onActivity(activity -> assertNull(extractor(activity)));
            fixture.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            awaitFrame(fixture.scenario, before.get() + 1);
            fixture.scenario.onActivity(activity -> assertNotSame(oldExtractor.get(), extractor(activity)));
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(fixture.scenario, activity -> player(activity).isPlaying() && frame(activity) > before.get() + 2);
        }
    }

    private static SeekFrameExtractor extractor(PlayerActivity activity) {
        try {
            Field field = PlayerActivity.class.getDeclaredField("frameExtractor");
            field.setAccessible(true);
            return (SeekFrameExtractor) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static PlaybackGesture.Result gesture(PlayerActivity activity) {
        try {
            Field field = PlayerActivity.class.getDeclaredField("gestureResult");
            field.setAccessible(true);
            return (PlaybackGesture.Result) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void touch(ActivityScenario<PlayerActivity> scenario, long down, int action, float x, float y) {
        scenario.onActivity(activity -> {
            View surface = activity.findViewById(R.id.gesture_surface);
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    surface.getWidth() * x, surface.getHeight() * y, 0);
            surface.dispatchTouchEvent(event);
            event.recycle();
        });
    }

    private static MediaPlayer player(PlayerActivity activity) {
        try {
            Field field = PlayerActivity.class.getDeclaredField("mediaPlayer");
            field.setAccessible(true);
            return (MediaPlayer) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void seek(PlayerActivity activity, long position) {
        try {
            org.videolan.libvlc.interfaces.IMedia.VideoTrack track = player(activity).getCurrentVideoTrack();
            android.util.Log.i("SeekLatencyTest", "Seek " + position + ", source rate="
                    + (track == null ? "none" : track.frameRateNum + "/" + track.frameRateDen));
            Method method = PlayerActivity.class.getDeclaredMethod("seekTo", long.class);
            method.setAccessible(true);
            method.invoke(activity, position);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static int frame(PlayerActivity activity) {
        GlVideoView video = activity.findViewById(R.id.video_layout);
        Bitmap image = VideoSnapshot.capture(video, 320, 180);
        if (image == null) return -1;
        int result = 0;
        // The player is portrait by default: sample across the letterboxed image at its center.
        for (int bit = 0; bit < 8; bit++) {
            if (Color.red(image.getPixel(bit * 40 + 20, 90)) > 128) result |= 1 << bit;
        }
        image.recycle();
        return result;
    }

    private static void awaitFrame(ActivityScenario<PlayerActivity> scenario, int expected) {
        AtomicInteger actual = new AtomicInteger(-1);
        await(scenario, activity -> {
            actual.set(frame(activity));
            return actual.get() == expected;
        }, "Expected decoded frame " + expected, actual);
    }

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition) {
        await(scenario, condition, "Timed out waiting for playback state", new AtomicInteger());
    }

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition,
                              String message, AtomicInteger actual) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean[] ready = {false};
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(10);
        }
        scenario.onActivity(activity -> {
            GlVideoView video = activity.findViewById(R.id.video_layout);
            android.util.Log.e("SeekLatencyTest", "Timed out: frames=" + video.getVideoFrameCount()
                    + ", received=" + video.getReceivedFrameCount() + ", focus=" + activity.hasWindowFocus()
                    + ", playing=" + (player(activity) != null && player(activity).isPlaying())
                    + ", time=" + (player(activity) == null ? -1 : player(activity).getTime())
                    + ", status=" + ((android.widget.TextView) activity.findViewById(R.id.playback_status)).getText());
        });
        fail(message + "; last frame=" + actual.get());
    }

    private static final class Fixture implements AutoCloseable {
        final File file;
        final ActivityScenario<PlayerActivity> scenario;

        Fixture() throws Exception {
            Context context = ApplicationProvider.getApplicationContext();
            file = File.createTempFile("seek-frames-", ".mp4", context.getCacheDir());
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("seek-frames.mp4"); FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            scenario = ActivityScenario.launch(new Intent(context, PlayerActivity.class).setData(Uri.fromFile(file)));
            try {
                // Barcode sampling below assumes the fitted image spans the viewport width.
                scenario.onActivity(activity -> activity.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
                await(scenario, activity -> player(activity) != null && player(activity).isPlaying()
                        && player(activity).isSeekable()
                        && ((GlVideoView) activity.findViewById(R.id.video_layout)).getVideoFrameCount() > 3
                        && activity.findViewById(R.id.video_layout).getHeight() > activity.findViewById(R.id.video_layout).getWidth()
                        && !activity.findViewById(R.id.video_layout).isLayoutRequested() && activity.hasWindowFocus());
                scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
                await(scenario, activity -> !player(activity).isPlaying());
                SystemClock.sleep(150);
            } catch (Throwable failure) {
                scenario.close();
                file.delete();
                throw failure;
            }
        }

        @Override
        public void close() {
            scenario.close();
            assertTrue(file.delete());
        }
    }
}