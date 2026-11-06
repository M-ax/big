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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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