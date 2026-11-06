package com.example.big.playback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.PlayerActivity;
import com.example.big.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class VideoAspectRegressionTest {
    @Test
    public void realAnamorphicSampleAndResizedPreviewKeepFourByThree() throws Exception {
        // ffprobe: 720x576, SAR 16:15, DAR 4:3, no crop or rotation.
        try (Fixture fixture = new Fixture("aspect-ratio-failing-example.mkv")) {
            fixture.scenario.onActivity(activity -> {
                IMedia.VideoTrack track = player(activity).getCurrentVideoTrack();
                android.util.Log.i("VideoAspectTest", "VLC track=" + track.width + "x" + track.height
                        + ", sar=" + track.sarNum + ":" + track.sarDen + ", orientation=" + track.orientation);
                player(activity).setTime(60000, false);
            });
            await(fixture.scenario, activity -> player(activity).getTime() >= 60000);
            assertPictureAspect(fixture.scenario, 4. / 3, false);
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(fixture.scenario, activity -> video(activity).getVideoFrameRate() == 0);
            // Deliberately not a display-sized bitmap: this tests the Activity-to-view metadata
            // handoff independently of the platform retriever's device-specific bitmap geometry.
            Bitmap still = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
            still.eraseColor(Color.MAGENTA);
            try {
                fixture.scenario.onActivity(activity -> video(activity).setSeekStill(still, 0));
                assertPictureAspect(fixture.scenario, 4. / 3, true);
                for (int fit : new int[]{1, 2}) {
                    fixture.scenario.onActivity(activity -> video(activity).setSeekStill(still, fit));
                    assertPictureAspect(fixture.scenario, 0, true);
                }
            } finally {
                // Closing waits for renderer release before the source bitmap can be recycled.
                fixture.scenario.close();
                still.recycle();
            }
        }
    }

    @Test
    public void rotatedAssetAndAlreadyRotatedPreviewKeepPortraitAspect() throws Exception {
        // A solid nonblack picture makes even heavily cropped fill-mode borders measurable.
        try (Fixture fixture = new Fixture("filter-rotated-solid.mp4")) {
            fixture.scenario.onActivity(activity -> {
                IMedia.VideoTrack track = player(activity).getCurrentVideoTrack();
                android.util.Log.i("VideoAspectTest", "Initial rotated track=" + (track == null ? "null"
                        : track.width + "x" + track.height + " sar=" + track.sarNum + ":" + track.sarDen
                        + " orientation=" + track.orientation) + " override=" + player(activity).getAspectRatio());
            });
            assertPictureAspect(fixture.scenario, 9. / 16, false);
            fixture.scenario.recreate();
            await(fixture.scenario, activity -> video(activity).getWidth() > video(activity).getHeight()
                    && !video(activity).isLayoutRequested() && activity.hasWindowFocus());
            assertPictureAspect(fixture.scenario, 9. / 16, false);
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.fit).performClick());
            assertPictureAspect(fixture.scenario, 0, false);
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.fit).performClick());
            assertPictureAspect(fixture.scenario, 0, false);
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.fit).performClick());
            assertPictureAspect(fixture.scenario, 9. / 16, false);
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(fixture.scenario, activity -> video(activity).getVideoFrameRate() == 0);
            Bitmap still = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888);
            still.eraseColor(Color.MAGENTA);
            try {
                fixture.scenario.onActivity(activity -> video(activity).setSeekStill(still, 0));
                assertPictureAspect(fixture.scenario, 9. / 16, true);
            } finally {
                fixture.scenario.close();
                still.recycle();
            }
        }
    }

    @Test
    public void lateMetadataCorrectsAnExistingPreviewAndCanBeCleared() throws Exception {
        try (Fixture fixture = new Fixture("aspect-ratio-failing-example.mkv")) {
            fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
            await(fixture.scenario, activity -> video(activity).getVideoFrameRate() == 0);
            Bitmap still = Bitmap.createBitmap(720, 576, Bitmap.Config.ARGB_8888);
            still.eraseColor(Color.MAGENTA);
            try {
                fixture.scenario.onActivity(activity -> {
                    video(activity).setVideoGeometry(720, 576, 16, 15, false);
                    video(activity).setSeekStill(still, 0);
                });
                assertPictureAspect(fixture.scenario, 4. / 3, true);
                fixture.scenario.onActivity(activity -> video(activity).setVideoGeometry(0, 0, 0, 0, false));
                assertPictureAspect(fixture.scenario, 1.25, true);
                fixture.scenario.onActivity(activity -> video(activity).setVideoGeometry(720, 576, 16, 15, false));
                assertPictureAspect(fixture.scenario, 4. / 3, true);
            } finally {
                fixture.scenario.close();
                still.recycle();
            }
        }
    }

    private static void assertPictureAspect(ActivityScenario<PlayerActivity> scenario,
                                            double expected, boolean preview) {
        AtomicReference<String> observed = new AtomicReference<>("no frame");
        await(scenario, activity -> {
            GlVideoView output = video(activity);
            Bitmap image = VideoSnapshot.capture(output, 480, 270);
            if (image == null) return false;
            try {
                if (preview && image.getPixel(240, 135) != Color.MAGENTA) return false;
                int left = 480, top = 270, right = 0, bottom = 0;
                for (int y = 0; y < 270; y++) {
                    for (int x = 0; x < 480; x++) {
                        int pixel = image.getPixel(x, y);
                        if (Color.red(pixel) > 20 || Color.green(pixel) > 20 || Color.blue(pixel) > 20) {
                            left = Math.min(left, x);
                            top = Math.min(top, y);
                            right = Math.max(right, x + 1);
                            bottom = Math.max(bottom, y + 1);
                        }
                    }
                }
                if (right <= left || bottom <= top) return false;
                double actual = (right - left) * output.getWidth() * 270.
                        / ((bottom - top) * output.getHeight() * 480.);
                double wanted = expected == 0 ? (double) output.getWidth() / output.getHeight() : expected;
                IMedia.VideoTrack track = player(activity).getCurrentVideoTrack();
                observed.set("expected=" + wanted + ", actual=" + actual + ", preview=" + preview
                        + ", bounds=" + left + "," + top + "," + right + "," + bottom
                        + ", viewport=" + output.getWidth() + "x" + output.getHeight()
                        + ", track=" + (track == null ? "null" : track.width + "x" + track.height
                        + " sar=" + track.sarNum + ":" + track.sarDen + " rotation=" + track.orientation));
                return Math.abs(actual - wanted) < .035;
            } finally {
                image.recycle();
            }
        }, observed);
    }

    private static GlVideoView video(PlayerActivity activity) {
        return activity.findViewById(R.id.video_layout);
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

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition) {
        await(scenario, condition, new AtomicReference<>("video output not ready"));
    }

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition,
                              AtomicReference<String> state) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(50);
        }
        assertTrue("Timed out checking aspect: " + state.get(), ready.get());
    }

    private static final class Fixture implements AutoCloseable {
        final File file;
        final ActivityScenario<PlayerActivity> scenario;

        Fixture(String asset) throws Exception {
            Context context = ApplicationProvider.getApplicationContext();
            file = File.createTempFile("video-aspect-", asset.substring(asset.lastIndexOf('.')), context.getCacheDir());
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(asset);
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            scenario = ActivityScenario.launch(new Intent(context, PlayerActivity.class).setData(Uri.fromFile(file)));
            try {
                scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                await(scenario, activity -> video(activity).getVideoFrameCount() > 5
                        && video(activity).getWidth() > video(activity).getHeight()
                        && !video(activity).isLayoutRequested() && activity.hasWindowFocus());
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