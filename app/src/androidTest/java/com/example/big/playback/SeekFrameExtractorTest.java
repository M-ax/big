package com.example.big.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SeekFrameExtractorTest {
    @Test
    public void selectsExactPredictedFramesInBothDirectionsAndReusesImmutableCache() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Results results = new Results();
            Bitmap retained;
            try (SeekFrameExtractor extractor = fixture.extractor(results)) {
                int[] frames = {48, 120, 121, 119, 49, 47, 50, 51};
                for (int i = 0; i < frames.length; i++) {
                    long target = Math.round(frames[i] * 1000. / 24);
                    extractor.request(i + 1, target, 320, 180, false);
                    Result result = results.frame(i + 1, target);
                    assertEquals(320, result.bitmap.getWidth());
                    assertEquals(180, result.bitmap.getHeight());
                    assertEquals("Decoded frame at " + target, frames[i], frameNumber(result.bitmap));
                    assertFalse(result.bitmap.isMutable());
                    assertEquals(Bitmap.Config.ARGB_8888, result.bitmap.getConfig());
                    assertFalse(result.mainThread);
                }
                assertEquals(frames.length, extractor.getExtractionCount());
                assertEquals(24, extractor.getFrameRate(), .05);
                extractor.request(20, 2000, 320, 180, false);
                retained = results.frame(20, 2000).bitmap;
                extractor.cancel();
                extractor.request(21, 2000, 320, 180, false);
                assertSame(retained, results.frame(21, 2000).bitmap);
                assertEquals(frames.length, extractor.getExtractionCount());
                assertTrue(extractor.getCachedBytes() > 0);
                assertTrue(extractor.getCachedBytes() <= 32L * 1024 * 1024);
                extractor.request(22, 2000, 160, 160, false);
                Bitmap smaller = results.frame(22, 2000).bitmap;
                assertEquals(160, smaller.getWidth());
                assertEquals(90, smaller.getHeight());
                assertEquals(frames.length + 1, extractor.getExtractionCount());
            }
            assertFalse(retained.isRecycled());
            assertEquals(48, frameNumber(retained));
        }
    }

    @Test
    public void roundedDemandWarmsExactGlobalGridPixelsWithoutExtraExtraction() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Results results = new Results();
            try (SeekFrameExtractor extractor = fixture.extractor(results)) {
                extractor.request(1, 2042, 320, 180, true);
                Bitmap demand = results.frame(1, 2042).bitmap;
                assertEquals(49, frameNumber(demand));
                assertEquals(24, extractor.getFrameRate(), .05);
                awaitCachedBytes(extractor, 5L * demand.getAllocationByteCount());
                assertEquals(5, extractor.getExtractionCount());
                assertTrue(results.values.isEmpty());
                int[] targets = {2083, 2000, 2125, 1958};
                int[] frames = {50, 48, 51, 47};
                for (int i = 0; i < targets.length; i++) {
                    extractor.request(i + 2, targets[i], 320, 180, false);
                    assertEquals(frames[i], frameNumber(results.frame(i + 2, targets[i]).bitmap));
                    assertEquals("Warmed global-grid target " + targets[i], 5, extractor.getExtractionCount());
                }
            }
        }
    }

    @Test
    public void ownsDuplicatedDescriptorWithoutOpeningItsSmbUri() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ParcelFileDescriptor duplicate;
            try (ParcelFileDescriptor original = ParcelFileDescriptor.open(fixture.file,
                    ParcelFileDescriptor.MODE_READ_ONLY)) {
                duplicate = ParcelFileDescriptor.dup(original.getFileDescriptor());
            }
            Results results = new Results();
            try (SeekFrameExtractor extractor = new SeekFrameExtractor(context(),
                    Uri.parse("smb://unused.invalid/share/video.mp4"), duplicate, results)) {
                extractor.request(1, 5000, 320, 180, false);
                assertEquals(120, frameNumber(results.frame(1, 5000).bitmap));
            }
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (duplicate.getFileDescriptor().valid() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10);
            }
            assertFalse("Transferred descriptor must close asynchronously", duplicate.getFileDescriptor().valid());
        }
    }

    @Test
    public void rejectsUnsupportedAndMissingSourcesWithoutUrlFallback() throws Exception {
        for (String value : new String[]{"https://unused.invalid/video.mp4", "rtsp://unused.invalid/video",
                "smb://unused.invalid/share/video.mp4", "file://remote/video.mp4",
                "file:///nonexistent-seek-frame-fixture.mp4", "content://com.example.big.missing/video"}) {
            Results results = new Results();
            try (SeekFrameExtractor extractor = new SeekFrameExtractor(context(), Uri.parse(value), null, results)) {
                extractor.request(1, 2000, 320, 180, false);
                results.unavailable(1);
                extractor.request(2, 5000, 320, 180, false);
                results.unavailable(2);
                assertEquals(0, extractor.getExtractionCount());
                assertEquals(0, extractor.getCachedBytes());
            }
        }
    }

    @Test
    public void emptyInputFailsClosedAndInvalidTargetsDoNotDecode() throws Exception {
        File empty = File.createTempFile("seek-empty-", ".mp4", context().getCacheDir());
        try {
            Results results = new Results();
            try (SeekFrameExtractor extractor = new SeekFrameExtractor(context(), Uri.fromFile(empty), null, results)) {
                extractor.request(1, 0, 320, 180, true);
                results.unavailable(1);
                assertEquals(0, extractor.getCachedBytes());
            }
        } finally {
            assertTrue(empty.delete());
        }
        try (Fixture fixture = new Fixture()) {
            Results results = new Results();
            try (SeekFrameExtractor extractor = fixture.extractor(results)) {
                long id = 0;
                for (long target : new long[]{-1, 10000, 10001, Long.MAX_VALUE}) {
                    extractor.request(++id, target, 320, 180, true);
                    results.unavailable(id);
                }
                extractor.request(++id, 2000, 0, 180, true);
                results.unavailable(id);
                assertEquals(0, extractor.getExtractionCount());
                extractor.request(++id, 2000, 320, 180, false);
                assertEquals(48, frameNumber(results.frame(id, 2000).bitmap));
            }
        }
    }

    @Test
    public void coalescesFloodToLatestDemandWithoutRoundingItsTimestamp() throws Exception {
        FakeSource source = new FakeSource();
        source.blockUs = 2000000;
        Results results = new Results();
        AtomicInteger opens = new AtomicInteger();
        try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> {
            opens.incrementAndGet();
            return source;
        })) {
            extractor.request(1, 2000, 80, 45, true);
            await(source.entered);
            for (int i = 2; i < 1000; i++) extractor.request(i, i, 80, 45, true);
            extractor.request(1000, 2001, 80, 45, false);
            source.proceed.countDown();
            results.frame(1000, 2001);
            assertEquals(Arrays.asList(2000000L, 2001000L), source.times);
            assertEquals(2, extractor.getExtractionCount());
            assertEquals(1, opens.get());
            assertTrue(results.values.isEmpty());
        } finally {
            source.proceed.countDown();
        }
        await(source.closed);
        assertEquals(1, source.closes.get());
    }

    @Test
    public void cancelSuppressesBlockedResultAndAllowsNewDemand() throws Exception {
        FakeSource source = new FakeSource();
        source.blockUs = 2000000;
        Results results = new Results();
        try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source)) {
            extractor.request(1, 2000, 80, 45, true);
            await(source.entered);
            extractor.cancel();
            extractor.request(2, 5000, 80, 45, false);
            source.proceed.countDown();
            results.frame(2, 5000);
            assertEquals(Arrays.asList(2000000L, 5000000L), source.times);
            assertEquals(80L * 45 * 4, extractor.getCachedBytes());
            assertTrue(results.values.isEmpty());
        } finally {
            source.proceed.countDown();
        }
        await(source.closed);
    }

    @Test
    public void closeSuppressesBlockedSuccessAndFailureWithoutWaitingForNativeCall() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            FakeSource source = new FakeSource();
            source.blockUs = 2000000;
            source.failUs = fail ? 2000000 : -1;
            Results results = new Results();
            SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source);
            try {
                extractor.request(1, 2000, 80, 45, true);
                await(source.entered);
                CountDownLatch returned = new CountDownLatch(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    extractor.close();
                    extractor.close();
                    extractor.request(2, 5000, 80, 45, false);
                    returned.countDown();
                });
                assertTrue("close must not wait for decoding", returned.await(2, TimeUnit.SECONDS));
                assertEquals(0, source.closes.get());
            } finally {
                extractor.close();
                source.proceed.countDown();
            }
            await(source.closed);
            assertEquals(1, source.closes.get());
            assertEquals(Collections.singletonList(2000000L), source.times);
            assertEquals(0, extractor.getCachedBytes());
            assertTrue(results.values.isEmpty());
        }
    }

    @Test
    public void closeDropsCachedFramesBeforeBlockedDecodeReturns() throws Exception {
        FakeSource source = new FakeSource();
        source.blockUs = 5000000;
        Results results = new Results();
        SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source);
        try {
            extractor.request(1, 2000, 80, 45, false);
            Bitmap retained = results.frame(1, 2000).bitmap;
            assertTrue(extractor.getCachedBytes() > 0);
            extractor.request(2, 5000, 80, 45, false);
            await(source.entered);
            CountDownLatch returned = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                extractor.close();
                returned.countDown();
            });
            assertTrue("Discard must not wait for native decoding", returned.await(2, TimeUnit.SECONDS));
            assertEquals(0, extractor.getCachedBytes());
            assertEquals(0, source.closes.get());
            assertFalse("Consumer-owned frames must not be recycled", retained.isRecycled());
        } finally {
            extractor.close();
            source.proceed.countDown();
        }
        await(source.closed);
        assertEquals(0, extractor.getCachedBytes());
        assertTrue("Late native frames must not be published", results.values.isEmpty());
    }

    @Test
    public void constructionAndCancellationDoNotOpenMediaOnMainThread() throws Exception {
        FakeSource source = new FakeSource();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        CountDownLatch constructed = new CountDownLatch(1);
        AtomicReference<SeekFrameExtractor> reference = new AtomicReference<>();
        AtomicReference<Thread> openingThread = new AtomicReference<>();
        Results results = new Results();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            reference.set(new SeekFrameExtractor(results, () -> {
                openingThread.set(Thread.currentThread());
                opening.countDown();
                awaitUnchecked(allowOpen);
                return source;
            }));
            constructed.countDown();
        });
        try {
            assertTrue("Construction must not wait for native initialization", constructed.await(2, TimeUnit.SECONDS));
            await(opening);
            CountDownLatch returned = new CountDownLatch(1);
            main.post(() -> {
                reference.get().request(1, 2000, 80, 45, true);
                reference.get().cancel();
                reference.get().close();
                returned.countDown();
            });
            assertTrue("Public operations must not wait for native initialization", returned.await(2, TimeUnit.SECONDS));
            assertNotSame(Looper.getMainLooper().getThread(), openingThread.get());
        } finally {
            allowOpen.countDown();
            await(constructed);
            reference.get().close();
        }
        await(source.closed);
        assertTrue(source.times.isEmpty());
        assertTrue(results.values.isEmpty());
    }

    @Test
    public void prefetchesOnlyFourRoundedGridNeighborsAndCachesThem() throws Exception {
        FakeSource source = new FakeSource();
        source.captureRate = "24";
        Results results = new Results();
        try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source)) {
            extractor.request(1, 2000, 80, 45, true);
            results.frame(1, 2000);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (extractor.getCachedBytes() < 5L * 80 * 45 * 4 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10);
            }
            assertEquals(5L * 80 * 45 * 4, extractor.getCachedBytes());
            assertEquals(Arrays.asList(2000000L, 2042000L, 1958000L, 2083000L, 1917000L), source.times);
            assertTrue(results.values.isEmpty());
            for (int target : new int[]{2042, 1958, 2083, 1917}) {
                extractor.request(target, target, 80, 45, false);
                results.frame(target, target);
            }
            assertEquals(5, extractor.getExtractionCount());
        }
        await(source.closed);
    }

    @Test
    public void offGridDemandsKeepExactKeysWhileNeighborsUseNominalGlobalGrid() throws Exception {
        int[] durations = {10000, 10010};
        long[][] targets = {{2043, 2083, 2000, 2125, 1958}, {2045, 2085, 2002, 2127, 1960}};
        long[] centers = {2042, 2044};
        for (int i = 0; i < durations.length; i++) {
            FakeSource source = new FakeSource();
            source.frameCount = "240";
            source.duration = Integer.toString(durations[i]);
            Results results = new Results();
            try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source)) {
                long[] expected = targets[i];
                extractor.request(1, expected[0], 80, 45, true);
                Bitmap demand = results.frame(1, expected[0]).bitmap;
                awaitCachedBytes(extractor, 5L * 80 * 45 * 4);
                List<Long> times = new ArrayList<>();
                for (long target : expected) times.add(target * 1000);
                assertEquals(times, source.times);
                assertEquals(5, extractor.getExtractionCount());
                assertTrue(results.values.isEmpty());
                for (int j = 1; j < expected.length; j++) {
                    extractor.request(j + 1, expected[j], 80, 45, false);
                    results.frame(j + 1, expected[j]);
                    assertEquals(5, extractor.getExtractionCount());
                }
                extractor.request(6, centers[i], 80, 45, false);
                assertNotSame("Off-grid demand must not alias its nearest grid timestamp",
                        demand, results.frame(6, centers[i]).bitmap);
                assertEquals(6, extractor.getExtractionCount());
                assertEquals(Long.valueOf(centers[i] * 1000), source.times.get(5));
                extractor.request(7, expected[0], 80, 45, false);
                assertSame(demand, results.frame(7, expected[0]).bitmap);
                assertEquals(6, extractor.getExtractionCount());
            }
            await(source.closed);
        }
    }

    @Test
    public void newerDemandInterruptsSpeculationBeforeNextDecode() throws Exception {
        FakeSource source = new FakeSource();
        source.captureRate = "24";
        source.blockUs = 2042000;
        Results results = new Results();
        try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source)) {
            extractor.request(1, 2000, 80, 45, true);
            results.frame(1, 2000);
            await(source.entered);
            extractor.request(2, 5000, 80, 45, false);
            source.proceed.countDown();
            results.frame(2, 5000);
            assertEquals(Arrays.asList(2000000L, 2042000L, 5000000L), source.times);
            assertEquals(2L * 80 * 45 * 4, extractor.getCachedBytes());
            assertTrue(results.values.isEmpty());
        } finally {
            source.proceed.countDown();
        }
        await(source.closed);
    }

    @Test
    public void nominalMetadataPreservesFractionalRatesAndUnknownRateSkipsSpeculation() throws Exception {
        String[] capture = {"23.976", null, "NaN", "0"};
        String[] counts = {"240", "240", "240", null};
        double[] expected = {23.976, 240000. / 10010, 240000. / 10010, 0};
        for (int i = 0; i < capture.length; i++) {
            FakeSource source = new FakeSource();
            source.captureRate = capture[i];
            source.frameCount = counts[i];
            source.duration = "10010";
            Results results = new Results();
            try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source)) {
                extractor.request(1, 2000, 80, 45, expected[i] == 0);
                results.frame(1, 2000);
                assertEquals(expected[i], extractor.getFrameRate(), .000001);
                assertEquals(Long.valueOf(2000000), source.calls.poll(5, TimeUnit.SECONDS));
                assertNull("Unknown rate must not speculate", source.calls.poll(200, TimeUnit.MILLISECONDS));
            }
            await(source.closed);
        }
    }

    @Test
    public void failedDemandDoesNotPoisonLaterRequests() throws Exception {
        FakeSource source = new FakeSource();
        source.failUs = 2000000;
        source.nullUs = 3000000;
        Results results = new Results();
        try (SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source)) {
            extractor.request(1, 2000, 80, 45, true);
            results.unavailable(1);
            extractor.request(2, 3000, 80, 45, true);
            results.unavailable(2);
            extractor.request(3, 5000, 80, 45, false);
            results.frame(3, 5000);
            assertEquals(3, extractor.getExtractionCount());
            assertEquals(80L * 45 * 4, extractor.getCachedBytes());
        }
        await(source.closed);
    }

    @Test
    public void capsDimensionsAndLruBytesWithoutRecyclingPublishedBitmaps() throws Exception {
        FakeSource source = new FakeSource();
        Results results = new Results();
        SeekFrameExtractor extractor = new SeekFrameExtractor(results, () -> source);
        Bitmap retained;
        try {
            extractor.request(1, 1000, 3840, 2160, false);
            retained = results.frame(1, 1000).bitmap;
            assertEquals(1920, retained.getWidth());
            assertEquals(1080, retained.getHeight());
            assertFalse(retained.isMutable());
            assertEquals(Bitmap.Config.ARGB_8888, retained.getConfig());
            for (int i = 2; i <= 4; i++) {
                extractor.request(i, i * 1000, 3840, 2160, false);
                results.frame(i, i * 1000);
            }
            extractor.request(5, 1000, 1920, 1080, false);
            assertSame(retained, results.frame(5, 1000).bitmap);
            assertEquals(4, extractor.getExtractionCount());
            extractor.request(6, 5000, 3840, 2160, false);
            results.frame(6, 5000);
            assertEquals(4L * 1920 * 1080 * 4, extractor.getCachedBytes());
            assertTrue(extractor.getCachedBytes() <= 32L * 1024 * 1024);
            extractor.request(7, 2000, 3840, 2160, false);
            results.frame(7, 2000);
            assertEquals("Least recently used target must have been evicted", 6, extractor.getExtractionCount());
            extractor.request(8, 1000, 1080, 1920, false);
            Bitmap portrait = results.frame(8, 1000).bitmap;
            assertEquals(607, portrait.getWidth());
            assertEquals(1080, portrait.getHeight());
            assertEquals(7, extractor.getExtractionCount());
            assertTrue(extractor.getCachedBytes() <= 32L * 1024 * 1024);
        } finally {
            extractor.close();
        }
        await(source.closed);
        assertEquals(0, extractor.getCachedBytes());
        assertFalse(retained.isRecycled());
        assertEquals(Color.BLACK, retained.getPixel(0, 0));
    }

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private static int frameNumber(Bitmap bitmap) {
        int result = 0;
        for (int bit = 0; bit < 8; bit++) {
            if (Color.red(bitmap.getPixel(20 + 40 * bit, bitmap.getHeight() / 2)) > 128) result |= 1 << bit;
        }
        return result;
    }

    private static void awaitCachedBytes(SeekFrameExtractor extractor, long expected) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (extractor.getCachedBytes() < expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10);
        }
        assertEquals("Neighbor warming did not finish", expected, extractor.getCachedBytes());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Worker did not reach the expected point", latch.await(5, TimeUnit.SECONDS));
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class Result {
        final long id;
        final long target;
        final Bitmap bitmap;
        final boolean mainThread = Looper.myLooper() == Looper.getMainLooper();

        Result(long id, long target, Bitmap bitmap) {
            this.id = id;
            this.target = target;
            this.bitmap = bitmap;
        }
    }

    private static final class Results implements SeekFrameExtractor.Listener {
        final BlockingQueue<Result> values = new LinkedBlockingQueue<>();

        @Override
        public void onFrame(long requestId, long targetMs, Bitmap bitmap) {
            values.add(new Result(requestId, targetMs, bitmap));
        }

        @Override
        public void onUnavailable(long requestId) {
            values.add(new Result(requestId, -1, null));
        }

        Result frame(long id, long target) throws InterruptedException {
            Result result = next(id);
            assertNotNull("Requested frame is unavailable", result.bitmap);
            assertEquals(target, result.target);
            return result;
        }

        void unavailable(long id) throws InterruptedException {
            assertNull(next(id).bitmap);
        }

        private Result next(long id) throws InterruptedException {
            Result result = values.poll(10, TimeUnit.SECONDS);
            assertNotNull("Timed out waiting for extraction", result);
            assertEquals("Only the latest request may publish", id, result.id);
            assertFalse("Callbacks belong on the worker", result.mainThread);
            return result;
        }
    }

    private static final class FakeSource implements SeekFrameExtractor.FrameSource {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());
        final BlockingQueue<Long> calls = new LinkedBlockingQueue<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        long blockUs = -1;
        long failUs = -1;
        long nullUs = -1;
        String captureRate;
        String frameCount;
        String duration = "10000";

        @Override
        public String extractMetadata(int key) {
            if (key == MediaMetadataRetriever.METADATA_KEY_DURATION) return duration;
            if (key == MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) return captureRate;
            if (key == MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT) return frameCount;
            return null;
        }

        @Override
        public Bitmap getFrameAtTime(long timeUs, int width, int height) {
            times.add(timeUs);
            calls.add(timeUs);
            if (timeUs == blockUs) {
                entered.countDown();
                awaitUnchecked(proceed);
            }
            if (timeUs == failUs) throw new IllegalStateException("Synthetic decoder failure");
            if (timeUs == nullUs) return null;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            return bitmap;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File file;

        Fixture() throws Exception {
            file = File.createTempFile("seek-extractor-", ".mp4", context().getCacheDir());
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("seek-frames.mp4"); FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } catch (Exception exception) {
                file.delete();
                throw exception;
            }
        }

        SeekFrameExtractor extractor(Results results) {
            return new SeekFrameExtractor(context(), Uri.fromFile(file), null, results);
        }

        @Override
        public void close() {
            assertTrue(file.delete());
        }
    }
}