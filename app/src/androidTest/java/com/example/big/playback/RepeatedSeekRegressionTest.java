package com.example.big.playback;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.PlayerActivity;
import com.example.big.R;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RepeatedSeekRegressionTest {
    @Test
    public void realWorldHevcResumesVideoAndAudioAfterRepeatedScrubs() throws Exception {
        repeatedScrubs("real-world-sample.mp4");
    }

    @Test
    public void mkvResumesVideoAndAudioAfterRepeatedScrubs() throws Exception {
        repeatedScrubs("aspect-ratio-failing-example.mkv");
    }

    @Test
    public void smbHevcResumesVideoAndAudioAfterRepeatedScrubs() throws Exception {
        repeatedScrubs("real-world-sample.mp4", true);
    }

    @Test
    public void smbMkvResumesVideoAndAudioAfterRepeatedScrubs() throws Exception {
        repeatedScrubs("aspect-ratio-failing-example.mkv", true);
    }

    private void repeatedScrubs(String asset) throws Exception {
        repeatedScrubs(asset, false);
    }

    private void repeatedScrubs(String asset, boolean smb) throws Exception {
        try (Fixture fixture = new Fixture(asset, smb)) {
            long[] latency = new long[8];
            long[] presentation = new long[latency.length];
            for (int i = 0; i < latency.length; i++) {
                final int iteration = i;
                long[] target = new long[1];
                fixture.scenario.onActivity(activity -> {
                    long duration = player(activity).getLength();
                    long start = player(activity).getTime();
                    target[0] = (long) (duration * (iteration % 2 == 0 ? .23 + iteration * .06 : .82 - iteration * .06));
                    invoke(activity, "previewSeek", target[0] - 1000, start);
                });
                // Alternate a fully decoded preview with release before decoding has settled.
                if (i % 2 == 0) {
                    await(fixture.scenario, activity -> field(activity, "exactFrame") != null, "decoded scrub preview");
                } else {
                    SystemClock.sleep(35);
                }
                fixture.scenario.onActivity(activity -> invoke(activity, "previewSeek", target[0], player(activity).getTime()));
                SystemClock.sleep(40);
                long[] frames = new long[1];
                fixture.scenario.onActivity(activity -> frames[0] = video(activity).getVideoFrameCount());
                long began = SystemClock.uptimeMillis();
                fixture.scenario.onActivity(activity -> invoke(activity, "finishSeekPreview", target[0]));
                await(fixture.scenario, activity -> video(activity).getVideoFrameCount() > frames[0] + 2
                        && player(activity).isPlaying() && Math.abs(player(activity).getTime() - target[0]) < 4000,
                        "new playback frames after commit");
                presentation[i] = SystemClock.uptimeMillis() - began;
                assertAdvancing(fixture.scenario, target[0]);
                latency[i] = SystemClock.uptimeMillis() - began;
                // Exercise the real +/-10 second controls after every gesture handoff.
                fixture.scenario.onActivity(activity -> activity.findViewById(iteration % 2 == 0
                        ? R.id.seek_forward : R.id.seek_back).performClick());
                assertAdvancing(fixture.scenario, target[0] + (i % 2 == 0 ? 10000 : -10000));
            }
            Arrays.sort(latency);
            Arrays.sort(presentation);
            String metrics = (smb ? "SMB " : "local ") + asset + " commit-to-3-frames median="
                    + presentation[presentation.length / 2] + "ms, max=" + presentation[presentation.length - 1]
                    + "ms; sustained A/V recovery median="
                    + latency[latency.length / 2] + "ms, max=" + latency[latency.length - 1] + "ms";
            android.util.Log.i("RepeatedSeekTest", metrics);
            android.os.Bundle report = new android.os.Bundle();
            report.putString("stream", metrics + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
            for (int burst = 0; burst < 3; burst++) {
                fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
                await(fixture.scenario, activity -> !player(activity).isPlaying(), "pause");
                for (long target : new long[]{60000, 15000, 90000}) {
                    fixture.scenario.onActivity(activity -> invoke(activity, "seekTo", target));
                    await(fixture.scenario, activity -> field(activity, "exactFrame") != null, "paused decoded frame");
                }
                fixture.scenario.onActivity(activity -> activity.findViewById(R.id.play_pause).performClick());
                assertAdvancing(fixture.scenario, 90000);
            }
        }
    }

    private static void assertAdvancing(ActivityScenario<PlayerActivity> scenario, long target) {
        await(scenario, activity -> player(activity).isPlaying()
                && Math.abs(player(activity).getTime() - target) < 4000, "seek clock near " + target);
        long[] before = new long[3];
        scenario.onActivity(activity -> {
            before[0] = video(activity).getVideoFrameCount();
            before[1] = audioBuffers(activity);
            before[2] = player(activity).getTime();
        });
        await(scenario, activity -> video(activity).getVideoFrameCount() > before[0] + 5
                && audioBuffers(activity) > before[1] + 5
                && player(activity).getTime() > before[2] + 250
                && player(activity).isPlaying(), "sustained video AND audio after seek to " + target);
        scenario.onActivity(activity -> {
            assertFalse("No fatal playback error", (boolean) field(activity, "failed"));
            assertNull("Playback must release the extracted still", field(activity, "exactFrame"));
            assertTrue("Audio track must remain selected", player(activity).getAudioTrack() >= 0);
        });
    }

    private static long audioBuffers(PlayerActivity activity) {
        IMedia media = player(activity).getMedia();
        try {
            IMedia.Stats stats = media == null ? null : media.getStats();
            return stats == null ? 0 : stats.playedAbuffers;
        } finally {
            if (media != null) media.release();
        }
    }

    private static GlVideoView video(PlayerActivity activity) {
        return activity.findViewById(R.id.video_layout);
    }

    private static MediaPlayer player(PlayerActivity activity) {
        return (MediaPlayer) field(activity, "mediaPlayer");
    }

    private static Object field(PlayerActivity activity, String name) {
        try {
            Field field = PlayerActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void invoke(PlayerActivity activity, String name, long... values) {
        try {
            Class<?>[] types = new Class<?>[values.length];
            Arrays.fill(types, long.class);
            Method method = PlayerActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            Object[] args = new Object[values.length];
            for (int i = 0; i < values.length; i++) args[i] = values[i];
            method.invoke(activity, args);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(ActivityScenario<PlayerActivity> scenario, Predicate<PlayerActivity> condition, String reason) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] ready = {false};
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25);
        }
        AtomicReference<String> state = new AtomicReference<>();
        scenario.onActivity(activity -> state.set("playing=" + player(activity).isPlaying()
                + ", time=" + player(activity).getTime() + ", frames=" + video(activity).getVideoFrameCount()
                + ", received=" + video(activity).getReceivedFrameCount() + ", audio=" + audioBuffers(activity)
                + ", wants=" + field(activity, "wantsPlay") + ", failed=" + field(activity, "failed")
                + ", focused=" + activity.hasWindowFocus()));
        fail(reason + ": " + state.get());
    }

    private static final class Fixture implements AutoCloseable {
        final File file;
        final ActivityScenario<PlayerActivity> scenario;
        final String token;

        Fixture(String asset, boolean smb) throws Exception {
            Context context = ApplicationProvider.getApplicationContext();
            Intent intent = new Intent(context, PlayerActivity.class);
            if (smb) {
                if (Build.VERSION.SDK_INT >= 37) {
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                            "com.example.big", "android.permission.ACCESS_LOCAL_NETWORK");
                }
                String server = InstrumentationRegistry.getArguments().getString("smbTestServer", "10.0.2.2:1445");
                SmbLocation folder = SmbLocation.parse(server + "/Media/");
                token = new SmbSession(folder, "smbtest", "fixture-password", "", false).register();
                file = null;
                intent.setData(Uri.parse(folder.child(asset, false).getUrl())).putExtra(SmbSession.EXTRA_SESSION, token);
            } else {
                token = null;
                file = File.createTempFile("repeat-seek-", asset.substring(asset.lastIndexOf('.')), context.getCacheDir());
                try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(asset);
                     FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                intent.setData(Uri.fromFile(file));
            }
            scenario = ActivityScenario.launch(intent);
            try {
                scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                await(scenario, activity -> player(activity) != null && player(activity).isPlaying()
                        && player(activity).isSeekable() && video(activity).getVideoFrameCount() > 5
                        && activity.hasWindowFocus() && video(activity).getWidth() > video(activity).getHeight(), "initial playback");
                assertAdvancing(scenario, 1000);
            } catch (Throwable failure) {
                scenario.close();
                if (file != null) file.delete();
                if (token != null) SmbSession.remove(token);
                throw failure;
            }
        }

        @Override
        public void close() {
            scenario.close();
            if (file != null) assertTrue(file.delete());
            if (token != null) SmbSession.remove(token);
        }
    }
}