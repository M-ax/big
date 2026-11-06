package com.example.big.playback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.example.big.TouchActions.click;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class VideoOutputIntegrationTest {
    @Test
    public void fractionalCadenceAndPlaybackSpeedDriveBlurWithoutAPausedRenderLoop() throws Exception {
        try (Fixture fixture = new Fixture("filter-24.mkv")) {
            ActivityScenario<PlayerActivity> scenario = fixture.scenario;
            await(scenario, activity -> Math.abs(video(activity).getVideoFrameRate() - 24000f / 1001) < .01f);
            showLabel(scenario);
            assertCadence(scenario, 24000. / 1001);
            scenario.onActivity(activity -> activity.findViewById(R.id.speed).performClick());
            onView(withText("1.5×")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            await(scenario, activity -> Math.abs(video(activity).getVideoFrameRate() - 36000f / 1001) < .01f);
            assertCadence(scenario, 36000. / 1001);
            scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(scenario, activity -> video(activity).getVideoFrameRate() == 0);
            SystemClock.sleep(350);
            long frames = count(scenario, false);
            long draws = count(scenario, true);
            SystemClock.sleep(500);
            assertEquals(frames, count(scenario, false));
            assertEquals("A paused visible label must not run a GL loop", draws, count(scenario, true));
            scenario.onActivity(activity -> activity.findViewById(R.id.gesture_feedback).setVisibility(View.GONE));
            await(scenario, activity -> !video(activity).isFeedbackRendered());
            assertEquals(frames, count(scenario, false));
            assertEquals(draws + 1, count(scenario, true));
        }
    }

    @Test
    public void sixtyFpsVideoKeepsItsCadenceWithFeedbackVisible() throws Exception {
        try (Fixture fixture = new Fixture("filter-60.mp4")) {
            await(fixture.scenario, activity -> video(activity).getVideoFrameRate() == 60);
            assertCadence(fixture.scenario, 60);
            showLabel(fixture.scenario);
            assertCadence(fixture.scenario, 60);
        }
    }

    @Test
    public void vlcSubtitlesAndBestFitCropAndFillRemainInTheVideoOutput() throws Exception {
        try (Fixture fixture = new Fixture("filter-24.mkv")) {
            ActivityScenario<PlayerActivity> scenario = fixture.scenario;
            await(scenario, activity -> {
                Bitmap image = VideoSnapshot.capture(video(activity), 320, 180);
                if (image == null) {
                    return false;
                }
                int white = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int pixel = image.getPixel(x, y);
                        if (Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180) {
                            white++;
                        }
                    }
                }
                image.recycle();
                return white > 30;
            });
            Bitmap fitted = snapshot(scenario);
            assertEquals("Landscape best-fit must pillarbox 16:9 video on a wider screen", Color.BLACK, fitted.getPixel(2, 90));
            assertTrue(Color.blue(fitted.getPixel(160, 90)) > 60);
            fitted.recycle();
            scenario.onActivity(activity -> activity.findViewById(R.id.fit).performClick());
            await(scenario, activity -> {
                Bitmap image = VideoSnapshot.capture(video(activity), 320, 180);
                boolean filled = image != null && Color.blue(image.getPixel(2, 90)) > 60;
                if (image != null) image.recycle();
                return filled;
            });
            scenario.onActivity(activity -> activity.findViewById(R.id.fit).performClick());
            await(scenario, activity -> {
                Bitmap image = VideoSnapshot.capture(video(activity), 320, 180);
                boolean stretched = image != null && Color.red(image.getPixel(160, 2)) > 180
                        && Color.blue(image.getPixel(160, 178)) > 180;
                if (image != null) image.recycle();
                return stretched;
            });
        }
    }

    @Test
    public void rotatedVideoRetainsPortraitAspectThroughRecreation() throws Exception {
        try (Fixture fixture = new Fixture("filter-rotated.mp4")) {
            Bitmap image = snapshot(fixture.scenario);
            assertEquals("Rotated portrait video must not stretch into the side bars", Color.BLACK, image.getPixel(110, 90));
            assertTrue("The portrait image must be present in the center", image.getPixel(160, 90) != Color.BLACK);
            image.recycle();
            fixture.scenario.recreate();
            // Recreation may first draw with a transient portrait viewport. Compare the same
            // landscape geometry, after new frames have reached the resized output surface.
            await(fixture.scenario, activity -> video(activity).getWidth() > video(activity).getHeight()
                    && !video(activity).isLayoutRequested() && activity.hasWindowFocus());
            long resizedFrames = count(fixture.scenario, false);
            await(fixture.scenario, activity -> video(activity).getVideoFrameCount() > resizedFrames + 5);
            image = snapshot(fixture.scenario);
            assertEquals(Color.BLACK, image.getPixel(110, 90));
            assertTrue(image.getPixel(160, 90) != Color.BLACK);
            image.recycle();
        }
    }

    @Test
    public void pausedRotationPreservesVideoAspectAndThenStopsRendering() throws Exception {
        try (Fixture fixture = new Fixture("filter-24.mkv")) {
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(fixture.scenario, activity -> video(activity).getVideoFrameRate() == 0);
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                fixture.scenario.onActivity(activity -> activity.setRequestedOrientation(orientation));
                await(fixture.scenario, activity -> {
                    GlVideoView output = video(activity);
                    boolean portrait = output.getHeight() > output.getWidth();
                    if (portrait != (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) return false;
                    Bitmap image = VideoSnapshot.capture(output, 320, 180);
                    if (image == null) return false;
                    int left = 320, top = 180, right = 0, bottom = 0;
                    for (int y = 0; y < 180; y++) {
                        for (int x = 0; x < 320; x++) {
                            int pixel = image.getPixel(x, y);
                            if (Color.red(pixel) > 15 || Color.green(pixel) > 15 || Color.blue(pixel) > 15) {
                                left = Math.min(left, x);
                                top = Math.min(top, y);
                                right = Math.max(right, x + 1);
                                bottom = Math.max(bottom, y + 1);
                            }
                        }
                    }
                    image.recycle();
                    double ratio = (right - left) * output.getWidth() * 180.
                            / ((bottom - top) * output.getHeight() * 320.);
                    return Math.abs(ratio - 16. / 9) < .08;
                });
                SystemClock.sleep(700);
                long draws = count(fixture.scenario, true);
                SystemClock.sleep(300);
                assertEquals("Paused rotation must not leave a render loop", draws, count(fixture.scenario, true));
            }
        }
    }

    private static GlVideoView video(PlayerActivity activity) {
        return activity.findViewById(R.id.video_layout);
    }

    private static void showLabel(ActivityScenario<PlayerActivity> scenario) {
        scenario.onActivity(activity -> {
            GestureFeedbackView label = activity.findViewById(R.id.gesture_feedback);
            label.setText("01:23:45   +00:30");
            label.setVisibility(View.VISIBLE);
        });
        await(scenario, activity -> video(activity).isFeedbackRendered());
    }

    private static long count(ActivityScenario<PlayerActivity> scenario, boolean draws) {
        AtomicLong result = new AtomicLong();
        scenario.onActivity(activity -> result.set(draws ? video(activity).getDrawCount() : video(activity).getVideoFrameCount()));
        return result.get();
    }

    private static void assertCadence(ActivityScenario<PlayerActivity> scenario, double expected) {
        SystemClock.sleep(200);
        long before = count(scenario, false);
        long time = SystemClock.elapsedRealtime();
        SystemClock.sleep(1500);
        long after = count(scenario, false);
        double measured = (after - before) * 1000. / (SystemClock.elapsedRealtime() - time);
        android.util.Log.i("VideoCadenceTest", "Expected " + expected + ", rendered " + measured);
        scenario.onActivity(activity -> {
            long last = video(activity).getLastFrameCallbackToSwapNs();
            long maximum = video(activity).getMaxFrameCallbackToSwapNs();
            android.util.Log.i("VideoCadenceTest", "Frame callback to EGL swap: last=" + last / 1000000.
                    + "ms, max=" + maximum / 1000000. + "ms");
            assertTrue("A rendered video must record submission timing", last > 0 && maximum >= last);
        });
        assertTrue("Video filters must follow source cadence; expected " + expected + ", rendered " + measured,
                Math.abs(measured - expected) < expected * .15);
    }

    private static Bitmap snapshot(ActivityScenario<PlayerActivity> scenario) {
        AtomicReference<Bitmap> image = new AtomicReference<>();
        scenario.onActivity(activity -> image.set(VideoSnapshot.capture(video(activity), 320, 180)));
        assertNotNull(image.get());
        return image.get();
    }

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(30);
        }
        AtomicReference<String> state = new AtomicReference<>();
        scenario.onActivity(activity -> state.set("frames=" + video(activity).getVideoFrameCount()
                + ", received=" + video(activity).getReceivedFrameCount()
                + ", draws=" + video(activity).getDrawCount() + ", rate=" + video(activity).getVideoFrameRate()
                + ", size=" + video(activity).getWidth() + "x" + video(activity).getHeight()
                + ", focus=" + activity.hasWindowFocus() + ", status="
                + ((android.widget.TextView) activity.findViewById(R.id.playback_status)).getText()));
        Bitmap failedFrame = snapshot(scenario);
        Context context = ApplicationProvider.getApplicationContext();
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalCacheDir(), "video-output-failure.png"))) {
            failedFrame.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        } finally {
            failedFrame.recycle();
        }
        assertTrue("Timed out waiting for real video output: " + state.get(), ready.get());
    }

    private static final class Fixture implements AutoCloseable {
        final File file;
        final ActivityScenario<PlayerActivity> scenario;

        Fixture(String asset) throws Exception {
            Context context = ApplicationProvider.getApplicationContext();
            file = File.createTempFile("video-filter-", asset.substring(asset.lastIndexOf('.')), context.getCacheDir());
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(asset);
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            scenario = ActivityScenario.launch(new Intent(context, PlayerActivity.class)
                    .setData(Uri.fromFile(file)));
            try {
                scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                await(scenario, activity -> video(activity).getVideoFrameCount() > 5
                        && video(activity).getWidth() > video(activity).getHeight() && activity.hasWindowFocus());
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