package com.example.big;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.playback.BufferedSmbMedia;
import com.example.big.playback.SeekFrameExtractor;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.example.big.TouchActions.click;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SmbBufferIntegrationTest {
    @Test
    public void frameExtractionDoesNotMoveThePlaybackDescriptorPosition() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String server = InstrumentationRegistry.getArguments().getString("smbTestServer", "10.0.2.2:1445");
        SmbLocation folder = SmbLocation.parse(server + "/Media/People & Topics/");
        SmbSession session = new SmbSession(folder, "smbtest", "fixture-password", "", false);
        Uri uri = Uri.parse(folder.child("Clip #1 + 100% café.mp4", false).getUrl());
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Bitmap> frame = new AtomicReference<>();
        try (BufferedSmbMedia media = BufferedSmbMedia.open(context, session, uri)) {
            Os.lseek(media.descriptor().getFileDescriptor(), 12345, android.system.OsConstants.SEEK_SET);
            try (SeekFrameExtractor extractor = new SeekFrameExtractor(context, uri,
                    media.openIndependentDescriptor(), new SeekFrameExtractor.Listener() {
                        @Override
                        public void onFrame(long requestId, long targetMs, Bitmap bitmap) {
                            frame.set(bitmap);
                            ready.countDown();
                        }

                        @Override
                        public void onUnavailable(long requestId) {
                            ready.countDown();
                        }
                    })) {
                extractor.request(1, 15000, 320, 180, false);
                assertTrue("Frame decode timed out", ready.await(15, TimeUnit.SECONDS));
                org.junit.Assert.assertNotNull("The independent reader must decode video", frame.get());
                assertEquals("Preview decoding must not reposition VLC's next sequential read", 12345,
                        Os.lseek(media.descriptor().getFileDescriptor(), 0, android.system.OsConstants.SEEK_CUR));
            }
        }
    }

    @Test
    public void exactFrameExtractionReadsThePrivateSmbDescriptorWhilePreviewWarming() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String server = InstrumentationRegistry.getArguments().getString("smbTestServer", "10.0.2.2:1445");
        SmbLocation folder = SmbLocation.parse(server + "/Media/People & Topics/");
        SmbSession session = new SmbSession(folder, "smbtest", "fixture-password", "", false);
        Uri uri = Uri.parse(folder.child("Clip #1 + 100% café.mp4", false).getUrl());
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Bitmap> frame = new AtomicReference<>();
        try (BufferedSmbMedia media = BufferedSmbMedia.open(context, session, uri);
             SeekFrameExtractor extractor = new SeekFrameExtractor(context, uri,
                     media.openIndependentDescriptor(), new SeekFrameExtractor.Listener() {
                         @Override
                         public void onFrame(long requestId, long targetMs, Bitmap bitmap) {
                             frame.set(bitmap);
                             ready.countDown();
                         }

                         @Override
                         public void onUnavailable(long requestId) {
                             ready.countDown();
                         }
                     })) {
            media.updateSeekPreview(15000, 30000);
            media.updatePlayback(0, 30000, false);
            extractor.request(1, 15000, 320, 180, true);
            assertTrue("SMB extraction timed out", ready.await(15, TimeUnit.SECONDS));
            org.junit.Assert.assertNotNull("SMB must not fall back from the private descriptor", frame.get());
            assertTrue(frame.get().getWidth() > 0 && frame.get().getHeight() > 0);
            assertTrue(media.cachedBytes() > 0);
            media.updatePlayback(15000, 30000, false);
            media.clearSeekPreview();
        }
    }

    @Before
    public void permission() {
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                    "com.example.big", "android.permission.ACCESS_LOCAL_NETWORK");
        }
    }

    @Test
    public void realSmbSpecialNamePrefetchAndRandomDescriptorReadsMatchOriginalVideo() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String server = InstrumentationRegistry.getArguments().getString("smbTestServer", "10.0.2.2:1445");
        SmbLocation folder = SmbLocation.parse(server + "/Media/People & Topics/");
        SmbSession session = new SmbSession(folder, "smbtest", "fixture-password", "", false);
        byte[] original;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("player-test.mp4");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                output.write(chunk, 0, count);
            }
            original = output.toByteArray();
        }
        Uri uri = Uri.parse(folder.child("Clip #1 + 100% café.mp4", false).getUrl());
        try (BufferedSmbMedia media = BufferedSmbMedia.open(context, session, uri)) {
            assertEquals(original.length, Os.fstat(media.descriptor().getFileDescriptor()).st_size);
            media.updatePlayback(15000, 30000, false);
            assertEquals(0, media.prefetchedBytes());
            media.updatePlayback(15000, 30000, true);
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (media.cachedBytes() < original.length && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20);
            }
            assertEquals(original.length, media.cachedBytes());
            assertTrue(media.prefetchedBytes() > 0);
            for (int offset : new int[]{original.length - 4096, 0, original.length / 2, 37}) {
                byte[] actual = new byte[4096];
                assertEquals(actual.length, Os.pread(media.descriptor().getFileDescriptor(), actual, 0, actual.length, offset));
                assertArrayEquals(Arrays.copyOfRange(original, offset, offset + actual.length), actual);
            }
        }
    }

    @Test
    public void playerPrefetchesDuringPlaybackAndRestoresPausedSmbAfterRecreation() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String server = InstrumentationRegistry.getArguments().getString("smbTestServer", "10.0.2.2:1445");
        SmbLocation folder = SmbLocation.parse(server + "/Media");
        SmbSession session = new SmbSession(folder, "smbtest", "fixture-password", "", false);
        String token = session.register();
        Intent intent = new Intent(context, PlayerActivity.class)
                .setData(Uri.parse(folder.child("Buffer-test.mp4", false).getUrl()))
                .putExtra(SmbSession.EXTRA_SESSION, token);
        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
            await(scenario, activity -> playing(activity) && cache(activity) != null
                    && cache(activity).prefetchedBytes() > 0);
            onView(withId(R.id.play_pause)).perform(click());
            await(scenario, activity -> activity.getString(R.string.play).contentEquals(
                    activity.findViewById(R.id.play_pause).getContentDescription()) && !playing(activity));
            AtomicInteger start = new AtomicInteger();
            scenario.onActivity(activity -> start.set(progress(activity)));
            onView(withId(R.id.seek_forward)).perform(click());
            await(scenario, activity -> Math.abs(progress(activity) - start.get() - 333) < 40 && !playing(activity));
            AtomicReference<BufferedSmbMedia> hiddenCache = new AtomicReference<>();
            scenario.onActivity(activity -> {
                hiddenCache.set(cache(activity));
                assertTrue(hiddenCache.get().cachedBytes() > 0);
            });
            scenario.moveToState(Lifecycle.State.STARTED);
            assertTrue("Visible paused player must retain seek data", hiddenCache.get().cachedBytes() > 0);
            scenario.moveToState(Lifecycle.State.CREATED);
            assertEquals("Backgrounding must discard seek data", 0, hiddenCache.get().cachedBytes());
            SystemClock.sleep(250);
            assertEquals("Late reads must not repopulate the hidden cache", 0, hiddenCache.get().cachedBytes());
            scenario.moveToState(Lifecycle.State.RESUMED);
            // A restored frame may read entirely from Android's proxy-FD page cache. Demand an
            // untouched padded tail page to verify the application cache itself is enabled again.
            byte[] tail = new byte[4096];
            assertEquals(tail.length, Os.pread(hiddenCache.get().descriptor().getFileDescriptor(),
                    tail, 0, tail.length, 31 * 1024 * 1024));
            assertArrayEquals(new byte[tail.length], tail);
            await(scenario, activity -> Math.abs(progress(activity) - start.get() - 333) < 40
                    && !playing(activity) && cache(activity).cachedBytes() > 0);
            scenario.recreate();
            assertEquals("Recreation must discard the old seek cache", 0, hiddenCache.get().cachedBytes());
            await(scenario, activity -> Math.abs(progress(activity) - start.get() - 333) < 40
                    && activity.findViewById(R.id.seek_bar).isEnabled() && !playing(activity));
            onView(withId(R.id.seek_back)).perform(click());
            await(scenario, activity -> Math.abs(progress(activity) - start.get()) < 40 && !playing(activity));
            onView(withId(R.id.play_pause)).perform(click());
            await(scenario, SmbBufferIntegrationTest::playing);
        } finally {
            SmbSession.remove(token);
        }
    }

    private static BufferedSmbMedia cache(PlayerActivity activity) {
        try {
            Field field = PlayerActivity.class.getDeclaredField("bufferedSmbMedia");
            field.setAccessible(true);
            return (BufferedSmbMedia) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static int progress(PlayerActivity activity) {
        return ((SeekBar) activity.findViewById(R.id.seek_bar)).getProgress();
    }

    private static boolean playing(PlayerActivity activity) {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                && activity.findViewById(R.id.playback_status).getVisibility() == View.GONE;
    }

    private void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition) {
        int callerLine = new Throwable().getStackTrace()[1].getLineNumber();
        long deadline = SystemClock.uptimeMillis() + 20000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(50);
        }
        scenario.onActivity(activity -> fail("SMB playback/cache state did not settle at line " + callerLine
                + ": focus=" + activity.hasWindowFocus() + ", progress=" + progress(activity)
                + ", playing=" + playing(activity) + ", control="
                + activity.findViewById(R.id.play_pause).getContentDescription()
                + ", prefetched=" + (cache(activity) == null ? -1 : cache(activity).prefetchedBytes())));
    }
}