package com.example.big.playback;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class BufferedSmbMediaTest {
    @Test
    public void independentReadersShareCacheButNeverSequentialPositionsOrOwnership() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicInteger reads = new AtomicInteger();
        SeekWindowCache cache = new SeekWindowCache(
                () -> source(2 * 1024 * 1024, new AtomicBoolean(), reads, new AtomicInteger()));
        BufferedSmbMedia media = new BufferedSmbMedia(context, cache);
        try (BufferedSmbMedia owner = media) {
            Os.lseek(media.descriptor().getFileDescriptor(), 12345, OsConstants.SEEK_SET);
            int networkReads;
            for (int i = 0; i < 12; i++) {
                try (ParcelFileDescriptor preview = media.openIndependentDescriptor()) {
                    assertEquals(0, Os.lseek(preview.getFileDescriptor(), 0, OsConstants.SEEK_CUR));
                    Os.lseek(preview.getFileDescriptor(), 65536, OsConstants.SEEK_SET);
                    byte[] bytes = new byte[4096];
                    assertEquals(bytes.length, Os.read(preview.getFileDescriptor(), bytes, 0, bytes.length));
                    for (int j = 0; j < bytes.length; j++) assertEquals((byte) ((65536 + j) % 251), bytes[j]);
                    assertEquals(12345, Os.lseek(media.descriptor().getFileDescriptor(), 0, OsConstants.SEEK_CUR));
                    networkReads = reads.get();
                    assertEquals(bytes.length, cache.read(65536, bytes, bytes.length));
                    assertEquals("Readers must reuse the same byte cache", networkReads, reads.get());
                }
                assertTrue("Closing previews must not discard the live player's cache", cache.cachedBytes() > 0);
            }
            byte[] bytes = new byte[4096];
            assertEquals(bytes.length, Os.read(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length));
            for (int j = 0; j < bytes.length; j++) assertEquals((byte) ((12345 + j) % 251), bytes[j]);
        }
        assertEquals(0, cache.allocatedBytes());
        try {
            media.openIndependentDescriptor().close();
            fail("Closed media must not create another proxy reader");
        } catch (IOException expected) {
            assertEquals(0, cache.cachedBytes());
        }
    }

    @Test
    public void descriptorReadsBothPrefetchedDirectionsWithoutNetworkAndReleasesMemory() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicBoolean offline = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        int length = 2 * 1024 * 1024;
        SeekWindowCache cache = new SeekWindowCache(() -> source(length, offline, reads, closed));
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache)) {
            assertEquals(length, Os.fstat(media.descriptor().getFileDescriptor()).st_size);
            media.updatePlayback(15000, 30000, false);
            SystemClock.sleep(150);
            assertEquals(0, reads.get());
            media.updatePlayback(15000, 30000, true);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (media.cachedBytes() < length && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20);
            }
            assertEquals(length, media.cachedBytes());
            assertEquals(length, media.prefetchedBytes());
            offline.set(true);
            int networkReads = reads.get();
            for (int offset : new int[]{length - 8192, 17, length / 2, 0}) {
                byte[] bytes = new byte[4096];
                assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length, offset));
                for (int i = 0; i < bytes.length; i++) {
                    assertEquals((byte) ((offset + i) % 251), bytes[i]);
                }
            }
            assertEquals(networkReads, reads.get());
            assertEquals(0, Os.pread(media.descriptor().getFileDescriptor(), new byte[8], 0, 8, length));
        }
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.allocatedBytes());
        assertEquals(2, closed.get());
    }

    @Test
    public void descriptorMapsNetworkFailureToIoError() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicInteger closed = new AtomicInteger();
        SeekWindowCache cache = new SeekWindowCache(
                () -> source(1024, new AtomicBoolean(true), new AtomicInteger(), closed));
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache)) {
            try {
                Os.pread(media.descriptor().getFileDescriptor(), new byte[4], 0, 4, 0);
                fail("An unavailable uncached byte must report an error");
            } catch (ErrnoException expected) {
                assertEquals(OsConstants.EIO, expected.errno);
            }
        }
        assertEquals(0, cache.allocatedBytes());
        assertTrue(closed.get() >= 1);
    }

    @Test
    public void hiddenCacheDiscardsMemoryButDescriptorRemainsReadableAndRefillsOnReturn() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicInteger reads = new AtomicInteger();
        AtomicLong transferred = new AtomicLong();
        SeekWindowCache cache = new SeekWindowCache(
                () -> source(8 * 1024 * 1024, new AtomicBoolean(), reads, new AtomicInteger(), transferred));
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache)) {
            byte[] bytes = new byte[4096];
            assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length, 0));
            // Proxy reads can exceed pread's size and even the page size reported to the app.
            assertTrue(transferred.get() >= bytes.length);
            assertEquals(transferred.get(), cache.cachedBytes());
            assertTrue(cache.allocatedBytes() > 0);
            media.setCachingEnabled(false);
            assertEquals(0, cache.cachedBytes());
            assertEquals(0, cache.allocatedBytes());
            media.updatePlayback(500, 1000, true);
            media.updateSeekPreview(500, 1000);
            int before = reads.get();
            // Use untouched pages: Android's proxy-FD page cache can satisfy repeated pread itself.
            int hiddenOffset = 3 * 1024 * 1024;
            assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length, hiddenOffset));
            for (int i = 0; i < bytes.length; i++) assertEquals((byte) ((hiddenOffset + i) % 251), bytes[i]);
            assertTrue(reads.get() > before);
            SystemClock.sleep(150);
            assertEquals(0, cache.allocatedBytes());
            assertEquals(0, cache.prefetchedBytes());
            media.updatePlayback(500, 1000, false);
            media.clearSeekPreview();
            media.setCachingEnabled(true);
            assertEquals(0, cache.cachedBytes());
            transferred.set(0);
            int resumedOffset = 6 * 1024 * 1024;
            before = reads.get();
            assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length, resumedOffset));
            for (int i = 0; i < bytes.length; i++) assertEquals((byte) ((resumedOffset + i) % 251), bytes[i]);
            assertTrue(reads.get() > before);
            assertTrue(transferred.get() >= bytes.length);
            assertEquals(transferred.get(), cache.cachedBytes());
            before = reads.get();
            assertEquals(bytes.length, cache.read(resumedOffset, bytes, bytes.length));
            assertEquals(before, reads.get());
        }
        assertEquals(0, cache.allocatedBytes());
    }

    @Test
    public void cachedPlaybackDoesNotWaitForAnUncachedPreview() throws Exception {
        assertPlaybackContinuesDuringPreviewRead(true);
    }

    @Test
    public void uncachedPlaybackUsesItsOwnSourceWhilePreviewIsBlocked() throws Exception {
        assertPlaybackContinuesDuringPreviewRead(false);
    }

    private void assertPlaybackContinuesDuringPreviewRead(boolean cached) throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BlockingPreviewFactory factory = new BlockingPreviewFactory();
        SeekWindowCache cache = new SeekWindowCache(factory);
        if (cached) {
            // Warm only app memory: the playback descriptor must still invoke its callback.
            byte[] warm = new byte[256 * 1024];
            assertEquals(warm.length, cache.read(0, warm, warm.length));
        }
        ExecutorService reads = Executors.newFixedThreadPool(2);
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache);
             ParcelFileDescriptor preview = media.openIndependentDescriptor()) {
            try {
                Future<Integer> pendingPreview = reads.submit(() -> {
                    byte[] bytes = new byte[4096];
                    int count = Os.pread(preview.getFileDescriptor(), bytes, 0,
                            bytes.length, BlockingPreviewFactory.PREVIEW_OFFSET);
                    assertBytes(BlockingPreviewFactory.PREVIEW_OFFSET, bytes);
                    return count;
                });
                assertTrue("Preview must enter an uncached source read",
                        factory.entered.await(5, TimeUnit.SECONDS));
                int offset = cached ? 0 : 2 * 1024 * 1024;
                Future<Integer> playback = reads.submit(() -> {
                    byte[] bytes = new byte[4096];
                    int count = Os.pread(media.descriptor().getFileDescriptor(),
                            bytes, 0, bytes.length, offset);
                    assertBytes(offset, bytes);
                    return count;
                });
                assertEquals("Playback must finish before the preview source is released",
                        4096, (int) playback.get(2, TimeUnit.SECONDS));
                assertEquals("The preview must still be blocked", 1, factory.release.getCount());
                if (!cached) {
                    assertEquals("Playback must keep the constructor's source", 0, factory.playbackSource.get());
                    assertTrue("Preview must use a different source", factory.previewSource.get() > 0);
                }
                factory.release.countDown();
                assertEquals(4096, (int) pendingPreview.get(5, TimeUnit.SECONDS));
            } finally {
                factory.release.countDown();
                reads.shutdownNow();
                assertTrue(reads.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals("All opened sources must close", factory.opened.get(), factory.closed.get());
    }

    @Test
    public void independentDescriptorOpensNoSourceForCachedReadsAndReleasesItsCallback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BlockingPreviewFactory factory = new BlockingPreviewFactory();
        factory.release.countDown();
        SeekWindowCache cache = new SeekWindowCache(factory);
        byte[] warm = new byte[256 * 1024];
        assertEquals(warm.length, cache.read(0, warm, warm.length));
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache)) {
            try (ParcelFileDescriptor preview = media.openIndependentDescriptor()) {
                assertEquals("Creating a descriptor must not open a network source", 1, factory.opened.get());
                byte[] bytes = new byte[4096];
                assertEquals(bytes.length, Os.pread(preview.getFileDescriptor(), bytes, 0, bytes.length, 0));
                assertBytes(0, bytes);
                assertEquals("A shared cache hit must not open a preview source", 1, factory.opened.get());
                assertEquals(bytes.length, Os.pread(preview.getFileDescriptor(), bytes, 0,
                        bytes.length, BlockingPreviewFactory.PREVIEW_OFFSET));
            }
            awaitClosed(factory.previewThread.get());
            assertEquals("Closing the preview must release only its own source", 1, factory.closed.get());
            byte[] bytes = new byte[4096];
            assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(),
                    bytes, 0, bytes.length, 2 * 1024 * 1024));
            assertBytes(2 * 1024 * 1024, bytes);
        }
        assertEquals(factory.opened.get(), factory.closed.get());
    }

    @Test
    public void closingOwnerCancelsBlockedPreviewAndStopsBothCallbacks() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BlockingPreviewFactory factory = new BlockingPreviewFactory();
        SeekWindowCache cache = new SeekWindowCache(factory);
        ExecutorService reads = Executors.newSingleThreadExecutor();
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache);
             ParcelFileDescriptor preview = media.openIndependentDescriptor()) {
            try {
                byte[] bytes = new byte[4096];
                assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(),
                        bytes, 0, bytes.length, 0));
                Future<Integer> pending = reads.submit(() -> Os.pread(preview.getFileDescriptor(),
                        new byte[4096], 0, 4096, BlockingPreviewFactory.PREVIEW_OFFSET));
                assertTrue(factory.entered.await(5, TimeUnit.SECONDS));
                media.close();
                try {
                    pending.get(2, TimeUnit.SECONDS);
                    fail("Closing the owner must invalidate the blocked preview read");
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof ErrnoException);
                    int errno = ((ErrnoException) exception.getCause()).errno;
                    // An in-flight callback can report EIO; a stopped proxy loop reports EBADF.
                    assertTrue("Closing the owner must invalidate its proxy reader",
                            errno == OsConstants.EIO || errno == OsConstants.EBADF);
                }
                awaitClosed(factory.previewThread.get());
                awaitClosed(factory.playbackThread.get());
                assertEquals(0, cache.allocatedBytes());
                assertEquals(factory.opened.get(), factory.closed.get());
            } finally {
                factory.release.countDown();
                reads.shutdownNow();
                assertTrue(reads.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void ownerCloseFailureStillReleasesOtherSourcesAndCallbackThreads() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BlockingPreviewFactory factory = new BlockingPreviewFactory();
        factory.failPlaybackClose = true;
        factory.release.countDown();
        SeekWindowCache cache = new SeekWindowCache(factory);
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache);
             ParcelFileDescriptor preview = media.openIndependentDescriptor()) {
            byte[] bytes = new byte[4096];
            assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length, 0));
            assertEquals(bytes.length, Os.pread(preview.getFileDescriptor(), bytes, 0,
                    bytes.length, BlockingPreviewFactory.PREVIEW_OFFSET));
            try {
                media.close();
                fail("Source close failure must be reported");
            } catch (IOException exception) {
                assertTrue(exception.getMessage().contains("Playback close failed"));
            }
            awaitClosed(factory.playbackThread.get());
            awaitClosed(factory.previewThread.get());
            assertEquals(factory.opened.get(), factory.closed.get());
            assertEquals(0, cache.allocatedBytes());
        }
    }

    @Test
    public void previewSourceOpenFailureDoesNotCloseOrPoisonPlayback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        SeekWindowCache cache = new SeekWindowCache(() -> {
            // Keep preview opens unavailable even if Android retries a failed page read.
            if (opened.getAndIncrement() > 0) throw new IOException("Preview open failed");
            return source(8 * 1024 * 1024, new AtomicBoolean(), new AtomicInteger(), closed);
        });
        try (BufferedSmbMedia media = new BufferedSmbMedia(context, cache);
             ParcelFileDescriptor preview = media.openIndependentDescriptor()) {
            try {
                Os.pread(preview.getFileDescriptor(), new byte[4096], 0, 4096, 4 * 1024 * 1024);
                fail("The preview failure must be reported to its own reader");
            } catch (ErrnoException exception) {
                assertEquals(OsConstants.EIO, exception.errno);
            }
            byte[] bytes = new byte[4096];
            assertEquals(bytes.length, Os.pread(media.descriptor().getFileDescriptor(), bytes, 0, bytes.length, 0));
            assertBytes(0, bytes);
            assertEquals(0, closed.get());
        }
        assertEquals(1, closed.get());
    }

    private static void assertBytes(int offset, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) assertEquals((byte) ((offset + i) % 251), bytes[i]);
    }

    private static void awaitClosed(Thread thread) throws InterruptedException {
        assertTrue("A real proxy callback must have run", thread != null);
        thread.join(5000);
        assertTrue("Released descriptor callback thread must stop", !thread.isAlive());
    }

    private static final class BlockingPreviewFactory implements SeekWindowCache.SourceFactory {
        static final int PREVIEW_OFFSET = 4 * 1024 * 1024;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger previewSource = new AtomicInteger(-1);
        final AtomicInteger playbackSource = new AtomicInteger(-1);
        final AtomicReference<Thread> previewThread = new AtomicReference<>();
        final AtomicReference<Thread> playbackThread = new AtomicReference<>();
        boolean failPlaybackClose;

        @Override
        public SeekWindowCache.Source open() {
            int id = opened.getAndIncrement();
            AtomicBoolean sourceClosed = new AtomicBoolean();
            return new SeekWindowCache.Source() {
                @Override
                public long length() {
                    return 8 * 1024 * 1024;
                }

                @Override
                public int read(long offset, byte[] buffer, int from, int count) throws IOException {
                    if (offset == PREVIEW_OFFSET) {
                        previewSource.set(id);
                        previewThread.set(Thread.currentThread());
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Preview gate timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                    } else {
                        playbackSource.set(id);
                        if ("smb-playback-reader".equals(Thread.currentThread().getName())) {
                            playbackThread.set(Thread.currentThread());
                        }
                    }
                    if (sourceClosed.get()) throw new IOException("Reader closed");
                    int amount = (int) Math.min(count, length() - offset);
                    if (amount <= 0) return -1;
                    for (int i = 0; i < amount; i++) buffer[from + i] = (byte) ((offset + i) % 251);
                    return amount;
                }

                @Override
                public void close() throws IOException {
                    if (sourceClosed.compareAndSet(false, true)) {
                        closed.incrementAndGet();
                        if (id == previewSource.get()) release.countDown();
                        if (id == 0 && failPlaybackClose) throw new IOException("Playback close failed");
                    }
                }
            };
        }
    }

    private SeekWindowCache.Source source(int length, AtomicBoolean offline, AtomicInteger reads,
                                          AtomicInteger closed) {
        return source(length, offline, reads, closed, new AtomicLong());
    }

    private SeekWindowCache.Source source(int length, AtomicBoolean offline, AtomicInteger reads,
                                          AtomicInteger closed, AtomicLong transferred) {
        return new SeekWindowCache.Source() {
            @Override
            public long length() {
                return length;
            }

            @Override
            public int read(long offset, byte[] buffer, int offsetInBuffer, int count) throws IOException {
                reads.incrementAndGet();
                if (offline.get()) {
                    throw new IOException("Offline fixture");
                }
                int size = (int) Math.min(count, length - offset);
                for (int i = 0; i < size; i++) {
                    buffer[offsetInBuffer + i] = (byte) ((offset + i) % 251);
                }
                transferred.addAndGet(size);
                return size == 0 ? -1 : size;
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
    }
}