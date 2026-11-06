package com.example.big;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.example.big.TouchActions.click;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class PlayerActivityTest {
    private Context context;
    private Uri mediaUri;
    private String mediaName;
    private int originalVolume;

    @Before
    public void createMedia() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        originalVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "big-player-test.mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BigTests");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        mediaUri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(mediaUri);
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("player-test.mp4");
             OutputStream output = context.getContentResolver().openOutputStream(mediaUri)) {
            assertNotNull(output);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        context.getContentResolver().update(mediaUri, values, null, null);
        try (Cursor cursor = context.getContentResolver().query(mediaUri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            mediaName = cursor.getString(0);
        }
    }

    @After
    public void cleanup() {
        if (mediaUri != null) {
            context.getContentResolver().delete(mediaUri, null, null);
        }
        ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE))
                .setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
    }

    @Test
    public void decodesContentUriAndSupportsPlaybackControls() throws Exception {
        try (ActivityScenario<PlayerActivity> scenario = launchPlayer()) {
            awaitPlaying(scenario);
            assertVideoFrame(scenario);
            onView(withId(R.id.media_title)).check(matches(withText(mediaName)));
            onView(withId(R.id.play_pause)).perform(click());
            await(scenario, "paused", activity -> text(activity, R.id.play_pause).equals(context.getString(R.string.play)));
            AtomicInteger start = new AtomicInteger();
            scenario.onActivity(activity -> start.set(progress(activity)));
            onView(withId(R.id.seek_forward)).perform(click());
            await(scenario, "forward ten-second seek", activity -> Math.abs(progress(activity) - start.get() - 333) < 40);
            onView(withId(R.id.seek_back)).perform(click());
            await(scenario, "backward ten-second seek", activity -> Math.abs(progress(activity) - start.get()) < 40);
            onView(withId(R.id.speed)).perform(click());
            onView(withText("1.5×")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            onView(withId(R.id.speed)).check(matches(withText("1.5×")));
            onView(withId(R.id.tracks)).perform(click());
            onView(withText(R.string.audio_tracks)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            onView(withText(R.string.cancel)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());
            onView(withId(R.id.fit)).perform(click());
            onView(withId(R.id.play_pause)).perform(click());
            awaitPlaying(scenario);
        }
    }

    @Test
    public void gesturesSeekAndAdjustBrightnessAndVolumeWithoutSystemBrightnessPermission() {
        try (ActivityScenario<PlayerActivity> scenario = launchPlayer()) {
            awaitPlaying(scenario);
            onView(withId(R.id.play_pause)).perform(click());
            await(scenario, "paused", activity -> text(activity, R.id.play_pause).equals(context.getString(R.string.play)));
            scenario.onActivity(activity -> {
                WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                attributes.screenBrightness = .4f;
                activity.getWindow().setAttributes(attributes);
                swipe(activity, .2f, .55f, .2f, .3f, false);
                assertTrue(activity.getWindow().getAttributes().screenBrightness > .4f);
                AudioManager manager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0);
                swipe(activity, .8f, .55f, .8f, .3f, false);
                assertTrue(manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 2);
                swipe(activity, .3f, .45f, .65f, .45f, false);
            });
            await(scenario, "gesture seek committed", activity -> progress(activity) >= 300);
            AtomicInteger before = new AtomicInteger();
            scenario.onActivity(activity -> {
                before.set(progress(activity));
                swipe(activity, .3f, .45f, .65f, .45f, true);
            });
            SystemClock.sleep(600);
            scenario.onActivity(activity -> assertEquals(before.get(), progress(activity)));
            onView(withId(R.id.lock)).perform(click());
            scenario.onActivity(activity -> {
                float brightness = activity.getWindow().getAttributes().screenBrightness;
                swipe(activity, .2f, .55f, .2f, .3f, false);
                assertEquals(brightness, activity.getWindow().getAttributes().screenBrightness, .001f);
            });
            onView(withId(R.id.unlock)).perform(click());
            onView(withId(R.id.play_pause)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void preservesPauseAndPositionAcrossBackgroundAndRecreation() {
        try (ActivityScenario<PlayerActivity> scenario = launchPlayer()) {
            awaitPlaying(scenario);
            onView(withId(R.id.play_pause)).perform(click());
            await(scenario, "paused", activity -> text(activity, R.id.play_pause).equals(context.getString(R.string.play)));
            onView(withId(R.id.seek_forward)).perform(click());
            await(scenario, "seek", activity -> progress(activity) >= 300);
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertFalse((activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0));
            scenario.recreate();
            await(scenario, "restored paused position", activity -> progress(activity) >= 300
                    && text(activity, R.id.play_pause).equals(context.getString(R.string.play)));
            onView(withId(R.id.play_pause)).perform(click());
            awaitPlaying(scenario);
        }
    }

    @Test
    public void playsDirectHttpStream() throws Exception {
        byte[] media;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("player-test.mp4")) {
            media = input.readAllBytes();
        }
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        AtomicBoolean requested = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            serverExecutor.submit(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader request = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String line = request.readLine();
                        boolean head = line != null && line.startsWith("HEAD ");
                        while (line != null && !line.isEmpty()) {
                            line = request.readLine();
                        }
                        requested.set(true);
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "
                                + media.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        if (!head) {
                            output.write(media);
                        }
                    } catch (java.io.IOException exception) {
                        if (server.isClosed()) {
                            return;
                        }
                    }
                }
            });
            Intent intent = new Intent(context, PlayerActivity.class)
                    .setData(Uri.parse("http://127.0.0.1:" + server.getLocalPort() + "/video.mp4"));
            try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
                await(scenario, "network playback", activity ->
                        (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                                && activity.findViewById(R.id.playback_status).getVisibility() == View.GONE);
                assertTrue("LibVLC must request the actual HTTP media", requested.get());
                assertVideoFrame(scenario);
            }
        } finally {
            serverExecutor.shutdownNow();
            assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void inaccessibleMediaShowsAnErrorWithoutCrashing() {
        Intent intent = new Intent(context, PlayerActivity.class)
                .setData(Uri.parse("file:///does-not-exist.mp4"));
        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    onView(withText(R.string.playback_error)).check(matches(isDisplayed()));
                    return;
                } catch (androidx.test.espresso.NoMatchingViewException exception) {
                    SystemClock.sleep(100);
                }
            }
            fail("Playback error was not shown");
        }
    }

    @Test
    public void openingControlsKeepsSystemBarsHidden() {
        try (ActivityScenario<PlayerActivity> scenario = launchPlayer()) {
            awaitPlaying(scenario);
            onView(withId(R.id.play_pause)).perform(click());
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.gesture_surface).performClick();
                activity.findViewById(R.id.gesture_surface).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.player_controls).getVisibility());
            });
            await(scenario, "controls remain fullscreen", activity -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.findViewById(R.id.player_root));
                return insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars())
                        && !insets.isVisible(WindowInsetsCompat.Type.navigationBars());
            });
        }
    }

    @Test
    public void buttonRowStaysFlatWithCenteredPlaybackInBothOrientations() {
        try (ActivityScenario<PlayerActivity> scenario = launchPlayer()) {
            awaitPlaying(scenario);
            onView(withId(R.id.play_pause)).perform(click());
            scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            await(scenario, "portrait layout", activity -> {
                View root = activity.findViewById(R.id.player_root);
                return root.getHeight() > root.getWidth();
            });
            scenario.onActivity(PlayerActivityTest::assertButtonRow);
            scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            await(scenario, "landscape layout", activity -> {
                View root = activity.findViewById(R.id.player_root);
                return root.getWidth() > root.getHeight();
            });
            scenario.onActivity(PlayerActivityTest::assertButtonRow);
        }
    }

    private static void assertButtonRow(PlayerActivity activity) {
        View seek = activity.findViewById(R.id.seek_bar);
        View time = activity.findViewById(R.id.playback_time);
        int[] seekPosition = new int[2];
        int[] timePosition = new int[2];
        seek.getLocationOnScreen(seekPosition);
        time.getLocationOnScreen(timePosition);
        assertTrue("Time must be to the right of the seek bar", timePosition[0] >= seekPosition[0] + seek.getWidth());
        assertEquals("Time and seek bar must share a row", seekPosition[1] + seek.getHeight() / 2f,
                timePosition[1] + time.getHeight() / 2f, 1f);
        assertTrue("Seek bar must remain usable", seek.getWidth() >= 100 * activity.getResources().getDisplayMetrics().density);
        int[] ids = {R.id.tracks, R.id.seek_back, R.id.play_pause, R.id.seek_forward, R.id.speed, R.id.fit};
        int lastRight = -1;
        int rowY = -1;
        for (int id : ids) {
            View button = activity.findViewById(id);
            int[] position = new int[2];
            button.getLocationOnScreen(position);
            assertTrue("Buttons must not overlap", position[0] >= lastRight);
            if (rowY != -1) {
                assertEquals("All controls must share one row", rowY, position[1]);
            }
            rowY = position[1];
            lastRight = position[0] + button.getWidth();
            assertNotNull(button.getContentDescription());
        }
        View row = activity.findViewById(R.id.player_button_row);
        View play = activity.findViewById(R.id.play_pause);
        int[] rowPosition = new int[2];
        int[] playPosition = new int[2];
        row.getLocationOnScreen(rowPosition);
        play.getLocationOnScreen(playPosition);
        assertEquals(rowPosition[0] + row.getWidth() / 2f, playPosition[0] + play.getWidth() / 2f, 1f);
    }

    private ActivityScenario<PlayerActivity> launchPlayer() {
        return ActivityScenario.launch(new Intent(context, PlayerActivity.class).setData(mediaUri));
    }

    private void awaitPlaying(ActivityScenario<PlayerActivity> scenario) {
        await(scenario, "LibVLC decoded and started playback", activity ->
                activity.findViewById(R.id.seek_bar).isEnabled()
                        && (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                        && activity.findViewById(R.id.playback_status).getVisibility() == View.GONE);
    }

    private void await(ActivityScenario<PlayerActivity> scenario, String description, Predicate<PlayerActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        AtomicBoolean success = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> success.set(condition.test(activity)));
            if (success.get()) {
                return;
            }
            SystemClock.sleep(100);
        }
        scenario.onActivity(activity -> fail("Timed out waiting for " + description
                + "; time=" + text(activity, R.id.playback_time)
                + "; control=" + text(activity, R.id.play_pause)
                + "; status=" + text(activity, R.id.playback_status)));
    }

    private static int progress(PlayerActivity activity) {
        return ((SeekBar) activity.findViewById(R.id.seek_bar)).getProgress();
    }

    private static String text(PlayerActivity activity, int id) {
        View view = activity.findViewById(id);
        return (view instanceof TextView ? ((TextView) view).getText() : view.getContentDescription()).toString();
    }

    private void swipe(PlayerActivity activity, float startX, float startY, float endX, float endY, boolean cancel) {
        View surface = activity.findViewById(R.id.gesture_surface);
        long down = SystemClock.uptimeMillis();
        sendTouch(surface, down, down, MotionEvent.ACTION_DOWN, startX, startY);
        sendTouch(surface, down, down + 100, MotionEvent.ACTION_MOVE, endX, endY);
        sendTouch(surface, down, down + 150, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, endX, endY);
    }

    private void sendTouch(View surface, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, surface.getWidth() * x, surface.getHeight() * y, 0);
        surface.dispatchTouchEvent(event);
        event.recycle();
    }

    private void assertVideoFrame(ActivityScenario<PlayerActivity> scenario) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Bitmap> image = new java.util.concurrent.atomic.AtomicReference<>();
        SystemClock.sleep(1000);
        scenario.onActivity(activity -> {
            com.example.big.playback.GlVideoView surface = activity.findViewById(R.id.video_layout);
            assertNotNull(surface);
            assertTrue(surface.getVideoFrameCount() > 0);
            image.set(com.example.big.playback.VideoSnapshot.capture(surface, 64, 64));
        });
        Bitmap frame = image.get();
        assertNotNull(frame);
        boolean hasColorVariation = false;
        int first = frame.getPixel(0, 0);
        for (int x = 0; x < frame.getWidth(); x++) {
            for (int y = 0; y < frame.getHeight(); y++) {
                hasColorVariation |= frame.getPixel(x, y) != first;
            }
        }
        frame.recycle();
        assertTrue("Video must render the test pattern, not a blank surface", hasColorVariation);
    }

}