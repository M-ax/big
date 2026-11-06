package com.example.big.playback;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SeekWindowCacheTest {
    private final List<SeekWindowCache> caches = new ArrayList<>();

    @Test
    public void heapBudgetClampsTinyHugeAndFractionalBlockHeaps() {
        for (long heap : new long[]{Long.MIN_VALUE, -1, 0, 1, 4L * 1024 * 1024 - 1}) {
            assertEquals(1, SeekWindowCache.blocksPerSideForHeap(heap));
        }
        assertEquals(1, SeekWindowCache.blocksPerSideForHeap(8L * 1024 * 1024 - 1));
        assertEquals(2, SeekWindowCache.blocksPerSideForHeap(8L * 1024 * 1024));
        assertEquals(64, SeekWindowCache.blocksPerSideForHeap(256L * 1024 * 1024));
        assertEquals(128, SeekWindowCache.blocksPerSideForHeap(512L * 1024 * 1024));
        assertEquals(511, SeekWindowCache.blocksPerSideForHeap(2L * 1024 * 1024 * 1024 - 1));
        assertEquals(512, SeekWindowCache.blocksPerSideForHeap(2L * 1024 * 1024 * 1024));
        assertEquals(512, SeekWindowCache.blocksPerSideForHeap(Long.MAX_VALUE));
    }

    @Test
    public void defaultSlotsRespectHeapBudgetAndAllocateOnlyOnPublication() throws Exception {
        RecordingFactory factory = new RecordingFactory(100L * SeekWindowCache.BLOCK_SIZE);
        SeekWindowCache cache = new SeekWindowCache(factory);
        caches.add(cache);
        assertEquals(2 * SeekWindowCache.blocksPerSideForHeap(Runtime.getRuntime().maxMemory())
                + SeekWindowCache.FOREGROUND_BLOCKS, retainedSlots(cache).length);
        assertNoResidentSlots(cache);
        assertEquals(0, cache.read(0, new byte[0], 0));
        assertNoResidentSlots(cache);
        for (int i = 0; i < 10; i++) {
            assertRead(cache, (long) i * SeekWindowCache.BLOCK_SIZE + 1, 1);
            assertEquals(Math.min(i + 1, 4), cache.cachedBytes());
            assertEquals(Math.min(i + 1, 4) * (long) SeekWindowCache.BLOCK_SIZE,
                    cache.allocatedBytes());
            assertEquals(Math.min(i + 1, 4), residentArrays(cache).size());
        }
    }

    @Test
    public void evictionAndPruningReleasePayloadRatherThanRetainingReusableArrays() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 0, 16);
        assertRead(cache, 16, 16);
        List<byte[]> evicted = residentArrays(cache);
        assertRead(cache, 64, 16);
        assertRead(cache, 80, 16);
        assertEquals(32, cache.allocatedBytes());
        for (byte[] data : residentArrays(cache)) {
            assertFalse(evicted.contains(data));
        }
        cache.setCachingEnabled(false);
        assertNoResidentSlots(cache);
        cache.setCachingEnabled(true);
        cache.updatePlayback(128, 256, true);
        for (int i = 0; i < 4; i++) {
            nextPrefetch(factory).gate.countDown();
        }
        await(() -> cache.cachedBytes() == 64);
        assertEquals(64, cache.allocatedBytes());
        List<byte[]> pruned = residentArrays(cache);
        cache.updatePlayback(224, 256, true);
        Request pending = nextPrefetch(factory);
        assertEquals(224, pending.offset);
        assertNoResidentSlots(cache);
        pending.gate.countDown();
        nextPrefetch(factory);
        assertEquals(16, cache.allocatedBytes());
        assertFalse(pruned.contains(residentArrays(cache).get(0)));
        cache.setCachingEnabled(false);
        assertNoResidentSlots(cache);
        cache.close();
        assertNoResidentSlots(cache);
    }

    @Test
    public void zeroLengthEofMarkersDoNotAllocatePayload() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.actualLength = 0;
        SeekWindowCache cache = cache(factory);
        assertEquals(0, cache.read(0, new byte[1], 1));
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.allocatedBytes());
        assertTrue(residentArrays(cache).isEmpty());
        assertEquals(0, cache.read(0, new byte[1], 1));
        assertEquals(1, factory.demandRequests.size());
        cache.setCachingEnabled(false);
        assertNoResidentSlots(cache);
        assertEquals(0, cache.read(0, new byte[1], 1));
        assertEquals(2, factory.demandRequests.size());
        assertNoResidentSlots(cache);
    }

    @Test
    public void clippingPublishedDataToEofMarkerReleasesItsPayload() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.actualLength = 135;
        factory.stepPrefetch = true;
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        nextPrefetch(factory).gate.countDown();
        nextPrefetch(factory).gate.countDown();
        Request pending = nextPrefetch(factory);
        assertEquals(7, cache.cachedBytes());
        assertEquals(16, cache.allocatedBytes());
        cache.updatePlayback(42000, 64000, true);
        pending.gate.countDown();
        nextPrefetch(factory);
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.allocatedBytes());
        assertTrue(residentArrays(cache).isEmpty());
        assertEquals(0, cache.read(136, new byte[1], 1));
        assertTrue(factory.demandRequests.isEmpty());
    }

    @Test
    public void disabledDemandIsPassThroughAndReenableStartsEmpty() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.maxRead = 3;
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 5, 27);
        assertEquals(32, cache.allocatedBytes());
        List<byte[]> discarded = residentArrays(cache);
        cache.setCachingEnabled(true);
        int requests = factory.demandRequests.size();
        assertRead(cache, 5, 27);
        assertEquals(requests, factory.demandRequests.size());
        cache.setCachingEnabled(false);
        cache.setCachingEnabled(false);
        assertNoResidentSlots(cache);
        for (int i = 0; i < 2; i++) {
            assertRead(cache, 5, 27);
            assertTrue(factory.demandRequests.size() > requests);
            requests = factory.demandRequests.size();
            assertNoResidentSlots(cache);
        }
        assertRead(cache, 253, 3);
        assertEquals(0, cache.read(256, new byte[1], 1));
        factory.failDemand = true;
        expectIo(() -> cache.read(5, new byte[1], 1));
        factory.failDemand = false;
        assertNoResidentSlots(cache);
        cache.setCachingEnabled(true);
        assertNoResidentSlots(cache);
        requests = factory.demandRequests.size();
        assertRead(cache, 5, 27);
        assertTrue(factory.demandRequests.size() > requests);
        requests = factory.demandRequests.size();
        assertRead(cache, 5, 27);
        assertEquals(requests, factory.demandRequests.size());
        assertEquals(32, cache.allocatedBytes());
        for (byte[] data : residentArrays(cache)) {
            assertFalse(discarded.contains(data));
        }
        cache.close();
        cache.setCachingEnabled(true);
        cache.setCachingEnabled(false);
        assertNoResidentSlots(cache);
        expectIo(() -> cache.read(5, new byte[1], 1));
    }

    @Test
    public void pendingDemandCannotPublishAcrossDisableAndReenable() throws Exception {
        for (boolean reenable : new boolean[]{false, true}) {
            for (int maxRead : new int[]{3, 16}) {
                RecordingFactory factory = new RecordingFactory(256);
                factory.maxRead = maxRead;
                factory.demandGate = new CountDownLatch(1);
                SeekWindowCache cache = cache(factory);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread demand = startRead(cache, 5, 27, failure);
                try {
                    assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
                    cache.setCachingEnabled(false);
                    if (reenable) {
                        cache.setCachingEnabled(true);
                    }
                    assertNoResidentSlots(cache);
                } finally {
                    factory.demandGate.countDown();
                    demand.join(1000);
                }
                assertFalse(demand.isAlive());
                assertEquals(null, failure.get());
                assertNoResidentSlots(cache);
                cache.setCachingEnabled(true);
                int requests = factory.demandRequests.size();
                assertRead(cache, 5, 27);
                assertTrue(factory.demandRequests.size() > requests);
                assertEquals(32, cache.allocatedBytes());
            }
        }
    }

    @Test
    public void demandStartedWhileDisabledCannotPublishAfterReenable() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.demandGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        cache.setCachingEnabled(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 0, 16, failure);
        try {
            assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
            cache.setCachingEnabled(true);
        } finally {
            factory.demandGate.countDown();
            demand.join(1000);
        }
        assertFalse(demand.isAlive());
        assertEquals(null, failure.get());
        assertNoResidentSlots(cache);
        assertRead(cache, 0, 16);
        assertEquals(2, factory.demandRequests.size());
        assertEquals(16, cache.allocatedBytes());
    }

    @Test
    public void pendingPrefetchCannotPublishAcrossDisableAndReenable() throws Exception {
        for (int maxRead : new int[]{3, 16}) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.stepPrefetch = true;
            factory.maxRead = maxRead;
            SeekWindowCache cache = cache(factory);
            cache.updatePlayback(128, 256, true);
            Request pending = nextPrefetch(factory);
            cache.setCachingEnabled(false);
            cache.setCachingEnabled(true);
            pending.gate.countDown();
            Request restarted = nextPrefetch(factory);
            assertEquals(128, restarted.offset);
            assertEquals(16, restarted.count);
            assertNoResidentSlots(cache);
            assertEquals(0, cache.prefetchedBytes());
            cache.setCachingEnabled(false);
            restarted.gate.countDown();
            assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
            assertNoResidentSlots(cache);
            assertEquals(0, cache.prefetchedBytes());
        }
    }

    @Test
    public void disabledPlaybackAndPreviewHintsDoNotOpenSourcesAndRestoreLatestPlayback() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        SeekWindowCache cache = previewCache(factory);
        cache.setCachingEnabled(false);
        cache.updatePlayback(32, 256, true);
        cache.updateSeekPreview(192, 256);
        cache.clearSeekPreview();
        cache.updatePlayback(64, 256, true);
        cache.updateSeekPreview(240, 256);
        assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
        assertEquals(1, factory.sources.size());
        assertNoResidentSlots(cache);
        cache.setCachingEnabled(true);
        Request pending = nextPrefetch(factory);
        assertEquals(64, pending.offset);
        pending.gate.countDown();
        assertEquals(48, nextPrefetch(factory).offset);
        assertEquals(16, cache.prefetchedBytes());
    }

    @Test
    public void disablingPreviewClearsOverrideAndRestoresRecordedPausedOrActivePlayback() throws Exception {
        for (boolean active : new boolean[]{false, true}) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.stepPrefetch = true;
            SeekWindowCache cache = previewCache(factory);
            cache.updateSeekPreview(192, 256);
            Request pending = nextPrefetch(factory);
            cache.updatePlayback(64, 256, active);
            cache.setCachingEnabled(false);
            cache.updateSeekPreview(224, 256);
            cache.setCachingEnabled(true);
            pending.gate.countDown();
            if (active) {
                assertEquals(64, nextPrefetch(factory).offset);
            } else {
                assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
            }
            assertNoResidentSlots(cache);
            assertEquals(0, cache.prefetchedBytes());
            if (!active) {
                cache.updateSeekPreview(128, 256);
                assertEquals(128, nextPrefetch(factory).offset);
            }
        }
    }

    @Test
    public void disableDuringSpeculativeOpenDoesNotReadUntilReenabled() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.openGate = new CountDownLatch(1);
        factory.stepPrefetch = true;
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(128, 256, true);
        try {
            assertTrue(factory.speculativeOpen.await(3, TimeUnit.SECONDS));
            cache.setCachingEnabled(false);
            cache.updatePlayback(64, 256, true);
        } finally {
            factory.openGate.countDown();
        }
        await(() -> factory.sources.size() == 2);
        assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
        assertTrue(factory.prefetchRequests.isEmpty());
        assertNoResidentSlots(cache);
        cache.setCachingEnabled(true);
        assertEquals(64, nextPrefetch(factory).offset);
        assertEquals(2, factory.sources.size());
    }

    @Test
    public void closeReleasesResidentStorageBeforeBlockingSourceClose() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.demandCloseGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 0, 16);
        assertEquals(16, cache.allocatedBytes());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                cache.close();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        closer.setDaemon(true);
        closer.start();
        try {
            assertTrue(factory.demandCloseEntered.await(3, TimeUnit.SECONDS));
            assertTrue(closer.isAlive());
            assertNoResidentSlots(cache);
            cache.setCachingEnabled(true);
            cache.updateSeekPreview(128, 256);
            expectIo(() -> cache.read(0, new byte[1], 1));
            assertNoResidentSlots(cache);
        } finally {
            factory.demandCloseGate.countDown();
            closer.join(1000);
        }
        assertFalse(closer.isAlive());
        assertEquals(null, failure.get());
        cache.close();
        assertEquals(1, factory.sources.get(0).closeCalls);
    }

    @Test
    public void concurrentToggleAndDemandKeepMemoryBoundedAndBytesCorrect() throws Exception {
        RecordingFactory factory = new RecordingFactory(10000);
        factory.maxRead = 3;
        SeekWindowCache cache = cache(factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread toggler = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    cache.setCachingEnabled(false);
                    cache.updateSeekPreview(i * 37 % 1000, 1000);
                    cache.updatePlayback(i * 17 % 1000, 1000, i % 3 != 0);
                    assertEquals(0, cache.allocatedBytes());
                    assertEquals(0, cache.cachedBytes());
                    cache.setCachingEnabled(true);
                }
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        toggler.setDaemon(true);
        toggler.start();
        try {
            start.countDown();
            Random random = new Random(17);
            for (int i = 0; i < 200; i++) {
                assertRead(cache, random.nextInt(9900), 1 + random.nextInt(100));
                assertTrue(cache.allocatedBytes() <= 6 * 16);
            }
        } finally {
            toggler.join(5000);
        }
        assertFalse(toggler.isAlive());
        assertEquals(null, failure.get());
        cache.setCachingEnabled(false);
        assertNoResidentSlots(cache);
    }

    private static Object[] retainedSlots(SeekWindowCache cache) throws Exception {
        Field slots = SeekWindowCache.class.getDeclaredField("slots");
        slots.setAccessible(true);
        return (Object[]) slots.get(cache);
    }

    private static List<byte[]> residentArrays(SeekWindowCache cache) throws Exception {
        List<byte[]> arrays = new ArrayList<>();
        long allocated = 0;
        for (Object entry : retainedSlots(cache)) {
            if (entry != null) {
                Field data = entry.getClass().getDeclaredField("data");
                data.setAccessible(true);
                byte[] payload = (byte[]) data.get(entry);
                if (payload != null) {
                    arrays.add(payload);
                    allocated += payload.length;
                }
            }
        }
        assertEquals(allocated, cache.allocatedBytes());
        return arrays;
    }

    private static void assertNoResidentSlots(SeekWindowCache cache) throws Exception {
        assertEquals(0, cache.allocatedBytes());
        assertEquals(0, cache.cachedBytes());
        for (Object entry : retainedSlots(cache)) {
            assertEquals(null, entry);
        }
        Field entries = SeekWindowCache.class.getDeclaredField("entries");
        entries.setAccessible(true);
        assertTrue(((Map<?, ?>) entries.get(cache)).isEmpty());
    }

    @Test
    public void smallDemandDoesNotWaitForUnrequestedBlockBytes() throws Exception {
        RecordingFactory factory = new RecordingFactory(1024 * 1024);
        factory.demandCountLimit = 4096;
        factory.oversizedDemandGate = new CountDownLatch(1);
        SeekWindowCache cache = new SeekWindowCache(factory);
        caches.add(cache);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 12345, 4096, failure);
        try {
            assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
            demand.join(500);
            assertFalse("Small demand waited for unrequested aligned block bytes", demand.isAlive());
            assertEquals(null, failure.get());
            assertArrayEquals(new long[]{12345}, offsets(factory.demandRequests));
            assertEquals(4096, factory.demandRequests.get(0).count);
            assertEquals(4096, cache.cachedBytes());
            assertRead(cache, 12345, 4096);
            assertEquals(1, factory.demandRequests.size());
        } finally {
            factory.oversizedDemandGate.countDown();
            demand.join(1000);
        }
    }

    @Test
    public void demandFetchesOnlyMissingRequestedSlicesAndMergesAdjacentHits() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 5, 3);
        assertRead(cache, 3, 7);
        assertRead(cache, 4, 5);
        assertArrayEquals(new long[]{5, 3, 8}, offsets(factory.demandRequests));
        assertArrayEquals(new long[]{3, 2, 2},
                factory.demandRequests.stream().mapToLong(request -> request.count).toArray());
        assertEquals(7, cache.cachedBytes());
    }

    @Test
    public void shortDemandAcrossBlockBoundaryNeverReadsOutsideRequestedRange() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.maxRead = 2;
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 13, 7);
        assertArrayEquals(new long[]{13, 15, 16, 18}, offsets(factory.demandRequests));
        assertArrayEquals(new long[]{3, 1, 4, 2},
                factory.demandRequests.stream().mapToLong(request -> request.count).toArray());
        assertEquals(7, cache.cachedBytes());
        assertRead(cache, 13, 7);
        assertEquals(4, factory.demandRequests.size());
    }

    @Test
    public void disjointDemandSlicesNeverExposeUninitializedGapOrReusedSlotBytes() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 64, 16);
        assertRead(cache, 80, 16);
        assertRead(cache, 2, 2);
        assertRead(cache, 10, 2);
        int requests = factory.demandRequests.size();
        assertRead(cache, 6, 2);
        assertEquals(requests + 1, factory.demandRequests.size());
        assertEquals(6, factory.demandRequests.get(requests).offset);
        assertEquals(2, factory.demandRequests.get(requests).count);
        assertRead(cache, 0, 16);
    }

    @Test
    public void speculativeFillFetchesOnlyHolesAroundDemandSlice() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 132, 4);
        cache.updatePlayback(500, 1000, true);
        await(() -> cache.cachedBytes() == 64);
        assertEquals(60, cache.prefetchedBytes());
        assertArrayEquals(new long[]{128, 136, 112, 144, 96}, offsets(factory.prefetchRequests));
        assertEquals(4, factory.prefetchRequests.get(0).count);
        assertEquals(8, factory.prefetchRequests.get(1).count);
        assertRead(cache, 96, 64);
        assertEquals(1, factory.demandRequests.size());
    }

    @Test
    public void pendingSpeculationMergesDemandSliceWithoutDoubleCounting() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.prefetchGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.prefetchEntered.await(3, TimeUnit.SECONDS));
        assertRead(cache, 132, 4);
        assertEquals(4, cache.cachedBytes());
        factory.prefetchGate.countDown();
        await(() -> cache.cachedBytes() == 64);
        assertEquals(60, cache.prefetchedBytes());
        assertRead(cache, 96, 64);
        assertEquals(1, factory.demandRequests.size());
    }

    @Test
    public void stalledPreviewMissDoesNotBlockOtherReadersPlaybackMisses() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        ChannelSource previewSource = factory.addSource();
        previewSource.readGate = new CountDownLatch(1);
        ChannelSource playbackSource = factory.addSource();
        SeekWindowCache cache = cache(factory);
        SeekWindowCache.Reader preview = cache.openReader();
        SeekWindowCache.Reader playback = cache.openReader();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch previewDone = new CountDownLatch(1);
        Thread stalled = startAction(() -> assertRead(preview::read, 128, 16),
                failure, previewDone);
        try {
            assertTrue(previewSource.readEntered.await(3, TimeUnit.SECONDS));
            CountDownLatch playbackDone = new CountDownLatch(1);
            startAction(() -> {
                assertRead(cache, 0, 16);
                assertRead(playback::read, 64, 16);
            }, failure, playbackDone);
            assertTrue("Playback misses waited for preview source I/O",
                    playbackDone.await(1, TimeUnit.SECONDS));
            assertEquals(null, failure.get());
            assertEquals(1, previewDone.getCount());
            assertEquals(1, factory.defaultSource.readCalls.get());
            assertEquals(1, playbackSource.readCalls.get());
            assertEquals(3, factory.openCalls.get());
            assertNotSame(previewSource.lastBuffer, factory.defaultSource.lastBuffer);
            assertNotSame(previewSource.lastBuffer, playbackSource.lastBuffer);
            assertNotSame(factory.defaultSource.lastBuffer, playbackSource.lastBuffer);
        } finally {
            previewSource.readGate.countDown();
            stalled.join(1000);
        }
        assertFalse(stalled.isAlive());
        assertEquals(null, failure.get());
    }

    @Test
    public void cachedHitsBypassEvenTheSameReadersBlockedMiss() throws Exception {
        for (boolean blockDefault : new boolean[]{false, true}) {
            ChannelFactory factory = new ChannelFactory(256);
            SeekWindowCache cache = cache(factory);
            assertRead(cache, 0, 16);
            SeekWindowCache.Reader hitOnly = cache.openReader();
            ChannelSource blockedSource = blockDefault
                    ? factory.defaultSource : factory.addSource();
            blockedSource.readEntered = new CountDownLatch(1);
            blockedSource.readGate = new CountDownLatch(1);
            SeekWindowCache.Reader preview = cache.openReader();
            ReadAction blockedReader = blockDefault ? cache::read : preview::read;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch missDone = new CountDownLatch(1);
            Thread stalled = startAction(() -> assertRead(blockedReader, 128, 16),
                    failure, missDone);
            try {
                assertTrue(blockedSource.readEntered.await(3, TimeUnit.SECONDS));
                CountDownLatch hitsDone = new CountDownLatch(1);
                startAction(() -> {
                    assertRead(cache, 2, 11);
                    assertRead(hitOnly::read, 3, 9);
                    assertRead(blockedReader, 5, 7);
                }, failure, hitsDone);
                assertTrue("Cached reads waited for demand network I/O",
                        hitsDone.await(1, TimeUnit.SECONDS));
                assertEquals(null, failure.get());
                assertEquals(1, missDone.getCount());
                assertEquals(blockDefault ? 1 : 2, factory.openCalls.get());
            } finally {
                blockedSource.readGate.countDown();
                stalled.join(1000);
            }
            assertFalse(stalled.isAlive());
            assertEquals(null, failure.get());
        }
    }

    @Test
    public void readerRegistrationAndHitOnlyReadsNeverOpenASource() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 0, 16);
        ChannelSource unused = factory.addSource();
        unused.openGate = new CountDownLatch(1);
        AtomicReference<SeekWindowCache.Reader> opened = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread registration = startAction(() -> {
            SeekWindowCache.Reader reader = cache.openReader();
            opened.set(reader);
            assertRead(reader::read, 1, 14);
            assertEquals(0, reader.read(256, new byte[1], 1));
            assertEquals(0, reader.read(0, new byte[0], 0));
            reader.close();
            reader.close();
        }, failure, done);
        try {
            assertTrue("Reader registration/cache hits performed source I/O",
                    done.await(1, TimeUnit.SECONDS));
            assertEquals(null, failure.get());
            assertNotNull(opened.get());
            assertEquals(1, factory.openCalls.get());
            assertEquals(1, unused.openEntered.getCount());
            assertEquals(0, unused.closeCalls.get());
            assertEquals(0, factory.defaultSource.closeCalls.get());
            expectIo(() -> opened.get().read(0, new byte[1], 1));
            assertRead(cache, 0, 16);
        } finally {
            unused.openGate.countDown();
            registration.join(1000);
        }
        assertFalse(registration.isAlive());
    }

    @Test
    public void queuedMissRechecksCacheAfterTakingItsReadersIoLock() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        ChannelSource source = factory.addSource();
        source.readGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        SeekWindowCache.Reader reader = cache.openReader();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = startAction(() -> assertRead(reader::read, 64, 16),
                failure, new CountDownLatch(1));
        Thread second = null;
        try {
            assertTrue(source.readEntered.await(3, TimeUnit.SECONDS));
            second = startAction(() -> assertRead(reader::read, 0, 16),
                    failure, new CountDownLatch(1));
            Thread queued = second;
            await(() -> queued.getState() == Thread.State.BLOCKED);
            assertRead(cache, 0, 16);
        } finally {
            source.readGate.countDown();
            first.join(1000);
            if (second != null) {
                second.join(1000);
            }
        }
        assertFalse(first.isAlive());
        assertNotNull(second);
        assertFalse(second.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, source.readCalls.get());
        assertEquals(1, factory.defaultSource.readCalls.get());
    }

    @Test
    public void readerCloseCancelsOnlyItsSourceAndPreventsLatePublication() throws Exception {
        for (boolean cooperateWithClose : new boolean[]{false, true}) {
            ChannelFactory factory = new ChannelFactory(256);
            ChannelSource source = factory.addSource();
            source.readGate = new CountDownLatch(1);
            source.releaseReadOnClose = cooperateWithClose;
            source.allowReadAfterClose = !cooperateWithClose;
            ChannelSource otherSource = factory.addSource();
            SeekWindowCache cache = cache(factory);
            SeekWindowCache.Reader reader = cache.openReader();
            SeekWindowCache.Reader other = cache.openReader();
            AtomicReference<Throwable> readFailure = new AtomicReference<>();
            Thread reading = startAction(() -> assertRead(reader::read, 128, 16),
                    readFailure, new CountDownLatch(1));
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            CountDownLatch closed = new CountDownLatch(1);
            try {
                assertTrue(source.readEntered.await(3, TimeUnit.SECONDS));
                assertRead(other::read, 0, 16);
                startAction(reader::close, closeFailure, closed);
                assertTrue("Reader close waited for the demand I/O lock",
                        closed.await(1, TimeUnit.SECONDS));
                assertEquals(null, closeFailure.get());
                reader.close();
                expectIo(() -> reader.read(128, new byte[1], 1));
                assertEquals(1, source.closeCalls.get());
                assertEquals(16, cache.cachedBytes());
                assertRead(cache, 0, 16);
                assertRead(other::read, 16, 16);
                assertEquals(0, otherSource.closeCalls.get());
                assertEquals(0, factory.defaultSource.closeCalls.get());
            } finally {
                source.readGate.countDown();
                reading.join(1000);
            }
            assertFalse(reading.isAlive());
            assertTrue(readFailure.get() instanceof IOException);
            assertEquals(32, cache.cachedBytes());
            cache.close();
            assertEquals(1, source.closeCalls.get());
            assertEquals(1, otherSource.closeCalls.get());
            assertEquals(1, factory.defaultSource.closeCalls.get());
        }
    }

    @Test
    public void readerCleanupDoesNotHoldSharedCacheLocks() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        ChannelSource source = factory.addSource();
        source.closeGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        SeekWindowCache.Reader reader = cache.openReader();
        assertRead(reader::read, 0, 16);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch closeDone = new CountDownLatch(1);
        Thread closer = startAction(reader::close, failure, closeDone);
        try {
            assertTrue(source.closeEntered.await(3, TimeUnit.SECONDS));
            CountDownLatch readsDone = new CountDownLatch(1);
            startAction(() -> {
                assertEquals(16, cache.cachedBytes());
                assertRead(cache, 0, 16);
                assertRead(cache, 16, 16);
                assertRead(cache.openReader()::read, 0, 16);
                expectIo(() -> reader.read(0, new byte[1], 1));
            }, failure, readsDone);
            assertTrue("Reader cleanup held a shared cache/control lock",
                    readsDone.await(1, TimeUnit.SECONDS));
            assertEquals(null, failure.get());
            assertEquals(1, closeDone.getCount());
        } finally {
            source.closeGate.countDown();
            closer.join(1000);
        }
        assertFalse(closer.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1, source.closeCalls.get());
        assertEquals(0, factory.defaultSource.closeCalls.get());
    }

    @Test
    public void closeRacingLazyOpenClosesLateSourceOnDemandThread() throws Exception {
        for (boolean closeOwner : new boolean[]{false, true}) {
            for (boolean failClose : new boolean[]{false, true}) {
                ChannelFactory factory = new ChannelFactory(256);
                ChannelSource source = factory.addSource();
                source.openGate = new CountDownLatch(1);
                if (failClose) {
                    source.closeFailure = new IOException("Late source close failed");
                }
                SeekWindowCache cache = cache(factory);
                SeekWindowCache.Reader reader = cache.openReader();
                AtomicReference<Throwable> readFailure = new AtomicReference<>();
                Thread reading = startAction(() -> assertRead(reader::read, 128, 16),
                        readFailure, new CountDownLatch(1));
                AtomicReference<Throwable> closeFailure = new AtomicReference<>();
                CountDownLatch closed = new CountDownLatch(1);
                try {
                    assertTrue(source.openEntered.await(3, TimeUnit.SECONDS));
                    startAction(closeOwner ? cache::close : reader::close, closeFailure, closed);
                    assertTrue("Close waited for a demand factory.open",
                            closed.await(1, TimeUnit.SECONDS));
                    assertEquals(null, closeFailure.get());
                    assertEquals(0, source.closeCalls.get());
                    expectIo(() -> reader.read(0, new byte[1], 1));
                    if (closeOwner) {
                        expectIo(cache::openReader);
                        expectIo(() -> cache.read(0, new byte[1], 1));
                    } else {
                        assertRead(cache, 0, 16);
                        assertRead(cache.openReader()::read, 0, 16);
                    }
                } finally {
                    source.openGate.countDown();
                    reading.join(1000);
                }
                assertFalse(reading.isAlive());
                assertTrue(readFailure.get() instanceof IOException);
                assertArrayEquals(failClose ? new Throwable[]{source.closeFailure}
                        : new Throwable[0], readFailure.get().getSuppressed());
                assertEquals(reading, source.closingThread);
                assertEquals(1, source.closeCalls.get());
                assertEquals(0, source.readCalls.get());
                assertEquals(0, source.lengthCalls.get());
                assertEquals(closeOwner ? 0 : 16, cache.cachedBytes());
                reader.close();
                cache.close();
                assertEquals(1, source.closeCalls.get());
            }
        }
    }

    @Test
    public void openAndReadFailuresRemainLocalAndDoNotLeakOrChangeRetrySemantics() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        ChannelSource failedOpen = factory.addSource();
        failedOpen.openFailure = new IOException("Demand open failed");
        ChannelSource source = factory.addSource();
        SeekWindowCache cache = cache(factory);
        SeekWindowCache.Reader reader = cache.openReader();
        expectIo(() -> reader.read(128, new byte[16], 16));
        assertEquals(2, factory.openCalls.get());
        assertEquals(0, failedOpen.closeCalls.get()); // No source was returned to the cache.
        assertNoResidentSlots(cache);
        assertRead(cache, 0, 16);
        source.readFailure = new IOException("Demand read failed");
        expectIo(() -> reader.read(128, new byte[16], 16));
        assertEquals(3, factory.openCalls.get());
        assertEquals(16, cache.cachedBytes());
        assertRead(cache.openReader()::read, 0, 16);
        source.readFailure = null;
        assertRead(reader::read, 128, 16);
        assertEquals(3, factory.openCalls.get()); // A later call reuses its source, not a hidden retry.
        assertEquals(0, source.lengthCalls.get());
        source.closeFailure = new IOException("Demand close failed");
        expectIo(reader::close);
        reader.close();
        assertEquals(1, source.closeCalls.get());
        assertRead(cache, 0, 16);
        cache.close();
        assertEquals(1, factory.defaultSource.closeCalls.get());
        assertEquals(1, source.closeCalls.get());
    }

    @Test
    public void ownerCloseCancelsAllReadersAndAggregatesFailures() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        ChannelSource previewSource = factory.addSource();
        ChannelSource playbackSource = factory.addSource();
        factory.defaultSource.closeFailure = new IOException("Default close failed");
        previewSource.closeFailure = new IOException("Preview close failed");
        SeekWindowCache cache = cache(factory);
        SeekWindowCache.Reader preview = cache.openReader();
        SeekWindowCache.Reader playback = cache.openReader();
        SeekWindowCache.Reader lazy = cache.openReader();
        assertRead(preview::read, 32, 16);
        assertRead(playback::read, 64, 16);
        for (ChannelSource source : new ChannelSource[]{
                factory.defaultSource, previewSource, playbackSource}) {
            source.readEntered = new CountDownLatch(1);
            source.readGate = new CountDownLatch(1);
        }
        AtomicReference<Throwable> defaultFailure = new AtomicReference<>();
        AtomicReference<Throwable> previewFailure = new AtomicReference<>();
        AtomicReference<Throwable> playbackFailure = new AtomicReference<>();
        Thread defaultRead = startAction(() -> assertRead(cache, 0, 16),
                defaultFailure, new CountDownLatch(1));
        Thread previewRead = startAction(() -> assertRead(preview::read, 128, 16),
                previewFailure, new CountDownLatch(1));
        Thread playbackRead = startAction(() -> assertRead(playback::read, 192, 16),
                playbackFailure, new CountDownLatch(1));
        try {
            assertTrue(factory.defaultSource.readEntered.await(3, TimeUnit.SECONDS));
            assertTrue(previewSource.readEntered.await(3, TimeUnit.SECONDS));
            assertTrue(playbackSource.readEntered.await(3, TimeUnit.SECONDS));
            IOException failure = null;
            try {
                cache.close();
            } catch (IOException exception) {
                failure = exception;
            }
            assertEquals(factory.defaultSource.closeFailure, failure);
            assertArrayEquals(new Throwable[]{previewSource.closeFailure}, failure.getSuppressed());
            assertNoResidentSlots(cache);
            expectIo(() -> lazy.read(64, new byte[1], 1));
            expectIo(cache::openReader);
        } finally {
            factory.defaultSource.readGate.countDown();
            previewSource.readGate.countDown();
            playbackSource.readGate.countDown();
            defaultRead.join(1000);
            previewRead.join(1000);
            playbackRead.join(1000);
        }
        assertFalse(defaultRead.isAlive());
        assertFalse(previewRead.isAlive());
        assertFalse(playbackRead.isAlive());
        assertTrue(defaultFailure.get() instanceof IOException);
        assertTrue(previewFailure.get() instanceof IOException);
        assertTrue(playbackFailure.get() instanceof IOException);
        cache.close();
        preview.close();
        playback.close();
        lazy.close();
        for (ChannelSource source : new ChannelSource[]{
                factory.defaultSource, previewSource, playbackSource}) {
            assertEquals(1, source.closeCalls.get());
        }
    }

    @Test
    public void additionalReadersHonorCachingEpochsAndDisabledPassThrough() throws Exception {
        ChannelFactory factory = new ChannelFactory(256);
        factory.maxRead = 3;
        ChannelSource source = factory.addSource();
        source.readGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        SeekWindowCache.Reader reader = cache.openReader();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reading = startAction(() -> assertRead(reader::read, 5, 27),
                failure, new CountDownLatch(1));
        try {
            assertTrue(source.readEntered.await(3, TimeUnit.SECONDS));
            cache.setCachingEnabled(false);
            cache.setCachingEnabled(true);
        } finally {
            source.readGate.countDown();
            reading.join(1000);
        }
        assertFalse(reading.isAlive());
        assertEquals(null, failure.get());
        assertNoResidentSlots(cache);
        assertRead(reader::read, 5, 27);
        int reads = source.readCalls.get();
        assertRead(cache, 5, 27);
        assertEquals(0, factory.defaultSource.readCalls.get());
        assertEquals(32, cache.allocatedBytes());
        cache.setCachingEnabled(false);
        assertRead(reader::read, 5, 27);
        assertTrue(source.readCalls.get() > reads);
        assertRead(cache, 5, 27);
        assertTrue(factory.defaultSource.readCalls.get() > 0);
        assertNoResidentSlots(cache);
    }

    @Test
    public void concurrentReaderSourcesKeepPartialBlocksAndEofBytesCorrect() throws Exception {
        for (long reportedLength : new long[]{256, -1}) {
            ChannelFactory factory = new ChannelFactory(reportedLength);
            factory.actualLength = 135;
            factory.maxRead = 3;
            factory.addSource();
            factory.addSource();
            SeekWindowCache cache = cache(factory);
            SeekWindowCache.Reader preview = cache.openReader();
            SeekWindowCache.Reader playback = cache.openReader();
            ReadAction[] channels = new ReadAction[]{cache::read, preview::read, playback::read};
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(channels.length);
            for (int i = 0; i < channels.length; i++) {
                int seed = i;
                ReadAction channel = channels[i];
                startAction(() -> {
                    start.await();
                    Random random = new Random(seed);
                    for (int j = 0; j < 200; j++) {
                        int offset = random.nextInt(150);
                        int count = 1 + random.nextInt(40);
                        byte[] actual = new byte[count + 1];
                        java.util.Arrays.fill(actual, (byte) 0x5a);
                        int expected = (int) Math.min(count,
                                Math.max(0, factory.actualLength - offset));
                        assertEquals(expected, channel.read(offset, actual, count));
                        for (int k = 0; k < expected; k++) {
                            assertEquals(valueAt(offset + k), actual[k]);
                        }
                        for (int k = expected; k < actual.length; k++) {
                            assertEquals((byte) 0x5a, actual[k]);
                        }
                        assertTrue(cache.allocatedBytes() <= 6 * 16);
                    }
                }, failure, done);
            }
            start.countDown();
            assertTrue("Concurrent reader did not complete", done.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            assertTrue(factory.openCalls.get() <= 3);
        }
    }

    @After
    public void closeCaches() throws IOException {
        for (SeekWindowCache cache : caches) {
            cache.close();
        }
    }

    @Test
    public void pausedPreviewWarmsNearestFirstWithoutPeriodicPlaybackClobberingIt() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        SeekWindowCache cache = previewCache(factory);
        cache.updatePlayback(64, 256, false);
        cache.updateSeekPreview(128, 256);
        Request first = nextPrefetch(factory);
        assertEquals(128, first.offset);
        for (int i = 0; i < 20; i++) {
            cache.updatePlayback(i, 256, i % 2 == 0);
        }
        first.gate.countDown();
        Request second = nextPrefetch(factory);
        assertEquals(112, second.offset);
        assertEquals(16, cache.prefetchedBytes());
        second.gate.countDown();
        await(() -> cache.prefetchedBytes() == 32);
        assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
        assertRead(cache, 112, 32);
        assertTrue(factory.demandRequests.isEmpty());
        assertEquals(2, factory.sources.size());
    }

    @Test
    public void productionPreviewIsCappedAtTwoMiBPerSideAndRepeatedHintDoesNotRefetch() throws Exception {
        assertEquals(2 * 1024 * 1024, SeekWindowCache.PREVIEW_BYTES_PER_SIDE);
        RecordingFactory factory = new RecordingFactory(1024L * 1024 * 1024);
        SeekWindowCache cache = new SeekWindowCache(factory);
        caches.add(cache);
        long radius = Math.min(SeekWindowCache.PREVIEW_BYTES_PER_SIDE,
                (long) SeekWindowCache.BLOCK_SIZE
                        * SeekWindowCache.blocksPerSideForHeap(Runtime.getRuntime().maxMemory()));
        cache.updateSeekPreview(500, 1000);
        await(() -> cache.prefetchedBytes() == 2 * radius);
        assertEquals(2 * radius / SeekWindowCache.BLOCK_SIZE, factory.prefetchRequests.size());
        assertEquals(factory.length / 2, factory.prefetchRequests.get(0).offset);
        assertEquals(factory.length / 2 - SeekWindowCache.BLOCK_SIZE,
                factory.prefetchRequests.get(1).offset);
        for (int i = 0; i < 20; i++) {
            cache.updateSeekPreview(500, 1000);
        }
        Thread.sleep(60);
        assertEquals(2 * radius / SeekWindowCache.BLOCK_SIZE, factory.prefetchRequests.size());
        assertEquals(2 * radius, cache.cachedBytes());
        assertEquals(2 * radius, cache.allocatedBytes());
        assertTrue(cache.cachedBytes() <= 4 * 1024 * 1024);
        assertTrue(cache.allocatedBytes() <= 257L * 1024 * 1024);
    }

    @Test
    public void overlappingPreviewKeepsWarmSlicesAndOnlyFetchesNewEdgeWithoutReopening() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(128, 256);
        await(() -> cache.prefetchedBytes() == 32);
        cache.updateSeekPreview(132, 256);
        await(() -> cache.prefetchedBytes() == 36);
        assertArrayEquals(new long[]{128, 112, 144}, offsets(factory.prefetchRequests));
        assertEquals(4, factory.prefetchRequests.get(2).count);
        assertEquals(32, cache.cachedBytes());
        assertRead(cache, 116, 32);
        assertTrue(factory.demandRequests.isEmpty());
        assertEquals(2, factory.sources.size());
    }

    @Test
    public void clearPreviewRestoresLatestPlaybackAndKeepsCommittedPausedNeighborhood() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = previewCache(factory);
        cache.updatePlayback(64, 256, false);
        cache.updateSeekPreview(192, 256);
        await(() -> cache.prefetchedBytes() == 32);
        cache.updatePlayback(192, 256, false);
        cache.clearSeekPreview();
        cache.clearSeekPreview();
        assertRead(cache, 176, 32);
        assertTrue(factory.demandRequests.isEmpty());
        Thread.sleep(60);
        assertEquals(2, factory.prefetchRequests.size());
        cache.updatePlayback(192, 256, true);
        await(() -> cache.prefetchedBytes() == 64);
        assertRead(cache, 160, 64);
        assertTrue(factory.demandRequests.isEmpty());
        assertEquals(2, factory.sources.size());
    }

    @Test
    public void clearPreviewCancelsPendingDataAndResumesLatestActivePlayback() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(192, 256);
        Request pending = nextPrefetch(factory);
        cache.updatePlayback(64, 256, true);
        cache.clearSeekPreview();
        pending.gate.countDown();
        assertEquals(64, nextPrefetch(factory).offset);
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.prefetchedBytes());
    }

    @Test
    public void previewClearDisableAndDisjointReturnNeverRevivePendingShortReads() throws Exception {
        for (int action = 0; action < 3; action++) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.stepPrefetch = true;
            factory.maxRead = 3;
            SeekWindowCache cache = previewCache(factory);
            cache.updateSeekPreview(128, 256);
            Request pending = nextPrefetch(factory);
            if (action == 0) {
                cache.clearSeekPreview();
            } else if (action == 1) {
                cache.updateSeekPreview(128, 0);
            } else {
                cache.updateSeekPreview(240, 256);
            }
            cache.updateSeekPreview(128, 256);
            pending.gate.countDown();
            assertEquals(128, nextPrefetch(factory).offset);
            assertEquals(0, cache.prefetchedBytes());
            assertEquals(0, cache.cachedBytes());
        }
    }

    @Test
    public void previewRetargetRestartsNearestFirstAndCancelStaysPaused() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        factory.maxRead = 3;
        SeekWindowCache cache = previewCache(factory);
        cache.updatePlayback(64, 256, false);
        cache.updateSeekPreview(128, 256);
        Request pending = nextPrefetch(factory);
        cache.updateSeekPreview(144, 256);
        pending.gate.countDown();
        Request latest = nextPrefetch(factory);
        assertEquals(144, latest.offset);
        assertEquals(0, cache.prefetchedBytes());
        cache.clearSeekPreview();
        latest.gate.countDown();
        assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
        assertEquals(0, cache.cachedBytes());
        assertRead(cache, 64, 3);
        assertEquals(3, factory.demandRequests.get(0).count);
    }

    @Test
    public void invalidPreviewSuppressesNormalSpeculationUntilClear() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(128, 0);
        cache.updatePlayback(64, 256, true);
        Thread.sleep(60);
        assertEquals(1, factory.sources.size());
        cache.clearSeekPreview();
        await(() -> cache.prefetchedBytes() == 64);
        assertEquals(64, factory.prefetchRequests.get(0).offset);
    }

    @Test
    public void previewClampsEdgesUnknownSizeAndHugeDurationsWithoutOverflow() throws Exception {
        RecordingFactory factory = new RecordingFactory(Long.MAX_VALUE);
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(Long.MIN_VALUE, Long.MAX_VALUE);
        await(() -> cache.prefetchedBytes() == 16);
        assertEquals(0, factory.prefetchRequests.get(0).offset);
        cache.updateSeekPreview(Long.MAX_VALUE, Long.MAX_VALUE);
        await(() -> cache.prefetchedBytes() == 32);
        assertRead(cache, Long.MAX_VALUE - 16, 16);
        assertTrue(factory.demandRequests.isEmpty());
        for (long size : new long[]{-1, 0, 7}) {
            RecordingFactory smallFactory = new RecordingFactory(size);
            SeekWindowCache small = previewCache(smallFactory);
            small.updateSeekPreview(1000, Long.MAX_VALUE);
            Thread.sleep(20);
            assertEquals(1, smallFactory.sources.size());
        }
    }

    @Test
    public void pausedPreviewShortReadYieldsToExactForegroundDemand() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        factory.maxRead = 3;
        factory.demandGate = new CountDownLatch(1);
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(128, 256);
        Request pending = nextPrefetch(factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 5, 2, failure);
        assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
        pending.gate.countDown();
        assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
        factory.demandGate.countDown();
        demand.join(1000);
        assertFalse(demand.isAlive());
        assertEquals(null, failure.get());
        assertEquals(2, factory.demandRequests.get(0).count);
        assertEquals(131, nextPrefetch(factory).offset);
    }

    @Test
    public void failingPreviewBacksOffAcrossRetargetsAndDoesNotBreakDemand() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.failPrefetch = true;
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(128, 256);
        await(() -> !factory.prefetchRequests.isEmpty());
        for (int i = 0; i < 20; i++) {
            cache.updateSeekPreview(129 + i, 256);
        }
        Thread.sleep(80);
        assertEquals(1, factory.prefetchRequests.size());
        assertRead(cache, 5, 2);
        factory.failPrefetch = false;
        await(() -> cache.prefetchedBytes() == 32);
    }

    @Test
    public void closePreviewCancelsPendingSourceAndReleasesMemoryAndIgnoresLateHints() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.prefetchGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 0, 16);
        cache.updateSeekPreview(128, 256);
        assertTrue(factory.prefetchEntered.await(3, TimeUnit.SECONDS));
        cache.close();
        cache.updateSeekPreview(192, 256);
        cache.clearSeekPreview();
        assertEquals(0, cache.allocatedBytes());
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.prefetchedBytes());
        assertEquals(2, factory.sources.size());
        for (RecordingSource source : factory.sources) {
            assertEquals(1, source.closeCalls);
        }
    }

    @Test
    public void failedDemandExtensionKeepsInitializedSliceAndMergedEofIsNotRetried() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.actualLength = 11;
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 5, 3);
        factory.maxRead = 2;
        factory.failReadNumber = 3;
        expectIo(() -> cache.read(8, new byte[8], 8));
        assertEquals(3, cache.cachedBytes());
        factory.failReadNumber = 0;
        int requests = factory.demandRequests.size();
        assertRead(cache, 5, 3);
        assertEquals(requests, factory.demandRequests.size());
        assertEquals(3, cache.read(8, new byte[8], 8));
        assertEquals(6, cache.cachedBytes());
        requests = factory.demandRequests.size();
        assertEquals(1, cache.read(10, new byte[4], 4));
        assertEquals(0, cache.read(11, new byte[4], 4));
        assertEquals(requests, factory.demandRequests.size());
        assertRead(cache, 3, 2);
        assertEquals(8, cache.cachedBytes());
        assertEquals(0, cache.read(11, new byte[4], 4));
    }

    @Test
    public void previewByteLimitClipsUnalignedEdgesAndCannotExceedNormalCap() throws Exception {
        for (long limit : new long[]{7, Long.MAX_VALUE}) {
            RecordingFactory factory = new RecordingFactory(256);
            SeekWindowCache cache = new SeekWindowCache(factory,
                    16, 2, 500, 2, limit);
            caches.add(cache);
            long radius = Math.min(limit, 32);
            cache.updateSeekPreview(129, 256);
            await(() -> cache.prefetchedBytes() == 2 * radius);
            for (Request request : factory.prefetchRequests) {
                assertTrue(request.offset >= 129 - radius);
                assertTrue(request.offset + request.count <= 129 + radius);
            }
            assertEquals(2 * radius, cache.cachedBytes());
            assertRead(cache, 129 - radius, (int) (2 * radius));
            assertTrue(factory.demandRequests.isEmpty());
            int requests = factory.prefetchRequests.size();
            Thread.sleep(40);
            assertEquals(requests, factory.prefetchRequests.size());
        }
        for (long limit : new long[]{0, -1}) {
            RecordingFactory factory = new RecordingFactory(256);
            try {
                new SeekWindowCache(factory, 16, 2, 500, 2, limit);
                fail("Invalid preview limit accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(factory.sources.isEmpty());
            }
        }
    }

    @Test
    public void previewRetargetAndMetadataReadsKeepMemoryBoundedWithoutRepeatedSourceOpens() throws Exception {
        RecordingFactory factory = new RecordingFactory(10000);
        SeekWindowCache cache = new SeekWindowCache(factory, 16, 2, 500, 2, 16);
        caches.add(cache);
        for (int position = 1000; position <= 9000; position += 1000) {
            long before = cache.prefetchedBytes();
            cache.updateSeekPreview(position, 10000);
            await(() -> cache.prefetchedBytes() == before + 32);
            for (int i = 0; i < 8; i++) {
                assertRead(cache, 16L * i, 16);
                assertTrue(cache.allocatedBytes() <= 6 * 16);
                assertTrue(cache.cachedBytes() <= 6 * 16);
            }
            int requests = factory.demandRequests.size();
            assertRead(cache, position - 16, 32);
            assertEquals(requests, factory.demandRequests.size());
        }
        assertEquals(2, factory.sources.size());
    }

    @Test
    public void clearingWhileSpeculativeSourceOpensDoesNotReadOrReopenUntilNextHint() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.openGate = new CountDownLatch(1);
        SeekWindowCache cache = previewCache(factory);
        cache.updateSeekPreview(128, 256);
        try {
            assertTrue(factory.speculativeOpen.await(3, TimeUnit.SECONDS));
            cache.clearSeekPreview();
        } finally {
            factory.openGate.countDown();
        }
        await(() -> factory.sources.size() == 2);
        Thread.sleep(60);
        assertTrue(factory.prefetchRequests.isEmpty());
        cache.updateSeekPreview(192, 256);
        await(() -> cache.prefetchedBytes() == 32);
        assertEquals(192, factory.prefetchRequests.get(0).offset);
        assertEquals(2, factory.sources.size());
        cache.close();
        for (RecordingSource source : factory.sources) {
            assertEquals(1, source.closeCalls);
        }
    }

    @Test
    public void startupAndInvalidPlaybackNeverOpenSpeculativeSource() throws Exception {
        RecordingFactory factory = new RecordingFactory(1024);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, false);
        cache.updatePlayback(500, 0, true);
        cache.updatePlayback(500, -1, true);
        Thread.sleep(60);
        assertEquals(1, factory.sources.size());
        assertEquals(0, cache.prefetchedBytes());
        assertRead(cache, 700, 31);
        assertEquals(1024, cache.length());
        assertEquals(1, factory.sources.size());
    }

    @Test
    public void fillsBothSidesNearestFirstAndReusesMemory() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        await(() -> cache.prefetchedBytes() == 64);
        assertArrayEquals(new long[]{128, 112, 144, 96}, offsets(factory.prefetchRequests));
        assertEquals(64, cache.cachedBytes());
        assertRead(cache, 96, 64);
        assertTrue(factory.demandRequests.isEmpty());
        assertTrue(factory.sources.get(0) != factory.sources.get(1));
    }

    @Test
    public void timeWindowClipsEdgesWithoutRoundingPastBudget() throws Exception {
        RecordingFactory factory = new RecordingFactory(1000);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(50500, 100000, true);
        await(() -> cache.prefetchedBytes() == 10);
        assertArrayEquals(new long[]{500}, offsets(factory.prefetchRequests));
        assertEquals(10, factory.prefetchRequests.get(0).count);
        assertRead(cache, 500, 10);
        assertTrue(factory.demandRequests.isEmpty());
    }

    @Test
    public void negativePositionAndPastEndAreClamped() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(-10, 1000, true);
        await(() -> cache.prefetchedBytes() == 32);
        assertArrayEquals(new long[]{0, 16}, offsets(factory.prefetchRequests));
        cache.updatePlayback(Long.MAX_VALUE, 1000, true);
        await(() -> cache.prefetchedBytes() == 64);
        assertArrayEquals(new long[]{0, 16, 240, 224}, offsets(factory.prefetchRequests));
        assertTrue(cache.cachedBytes() <= 64);
    }

    @Test
    public void smallAndEmptyFilesHaveExactEofSemantics() throws Exception {
        RecordingFactory factory = new RecordingFactory(7);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(250, 500, true);
        await(() -> cache.prefetchedBytes() == 7);
        byte[] buffer = new byte[20];
        assertEquals(7, cache.read(0, buffer, buffer.length));
        assertEquals(0, cache.read(7, buffer, buffer.length));
        assertEquals(0, cache.read(Long.MAX_VALUE, buffer, buffer.length));
        assertTrue(factory.demandRequests.isEmpty());
        RecordingFactory emptyFactory = new RecordingFactory(0);
        SeekWindowCache empty = cache(emptyFactory);
        empty.updatePlayback(0, 100, true);
        assertEquals(0, empty.read(0, buffer, 1));
        assertEquals(1, emptyFactory.sources.size());
    }

    @Test
    public void unknownSizeAllowsDemandButNotPrefetch() throws Exception {
        RecordingFactory factory = new RecordingFactory(-1);
        factory.actualLength = 35;
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(50, 100, true);
        assertRead(cache, 27, 8);
        assertEquals(0, cache.read(35, new byte[10], 10));
        assertEquals(-1, cache.length());
        assertEquals(1, factory.sources.size());
    }

    @Test
    public void hugeSizesAndDurationsDoNotOverflow() throws Exception {
        RecordingFactory factory = new RecordingFactory(Long.MAX_VALUE);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(Long.MAX_VALUE, Long.MAX_VALUE, true);
        await(() -> cache.prefetchedBytes() == 32);
        for (Request request : factory.prefetchRequests) {
            assertTrue(request.offset >= Long.MAX_VALUE - 32);
            assertTrue(request.count <= Long.MAX_VALUE - request.offset);
        }
        assertRead(cache, Long.MAX_VALUE - 32, 32);
        assertTrue(factory.demandRequests.isEmpty());
    }

    @Test
    public void productionDefaultsAreFiveMinutesWithAdaptive128MiBPerSideCap() throws Exception {
        assertEquals(256 * 1024, SeekWindowCache.BLOCK_SIZE);
        assertEquals(512, SeekWindowCache.BLOCKS_PER_SIDE);
        assertEquals(300000, SeekWindowCache.WINDOW_MS);
        assertEquals(4, SeekWindowCache.FOREGROUND_BLOCKS);
        RecordingFactory factory = new RecordingFactory(1024);
        SeekWindowCache cache = new SeekWindowCache(factory);
        caches.add(cache);
        assertEquals(0, cache.allocatedBytes());
        cache.updatePlayback(500000, 1000000, true);
        await(() -> cache.prefetchedBytes() == 614);
        assertEquals(205, factory.prefetchRequests.get(0).offset);
        assertEquals(614, factory.prefetchRequests.get(0).count);
        assertEquals(SeekWindowCache.BLOCK_SIZE, cache.allocatedBytes());
        assertTrue(cache.allocatedBytes() <= 257L * 1024 * 1024);
    }

    @Test
    public void memorySlotsStayBoundedAcrossSeeksAndMetadataReads() throws Exception {
        RecordingFactory factory = new RecordingFactory(10000);
        SeekWindowCache cache = cache(factory);
        for (int position = 100; position <= 900; position += 100) {
            long before = cache.prefetchedBytes();
            cache.updatePlayback(position, 1000, true);
            long center = position * 10L;
            await(() -> cache.prefetchedBytes() == before + 64);
            for (int i = 0; i < 12; i++) {
                assertRead(cache, 16L * i, 16);
                assertTrue(cache.allocatedBytes() <= 6 * 16);
                assertTrue(cache.cachedBytes() <= 6 * 16);
            }
            int demands = factory.demandRequests.size();
            assertRead(cache, center - 32, 64);
            assertEquals(demands, factory.demandRequests.size());
        }
        assertTrue(cache.allocatedBytes() <= 6 * 16);
    }

    @Test
    public void outsideWindowDemandDoesNotMoveOrEvictFilledWindow() throws Exception {
        RecordingFactory factory = new RecordingFactory(1024);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        await(() -> cache.prefetchedBytes() == 64);
        for (int i = 0; i < 20; i++) {
            assertRead(cache, i * 16L, 16);
        }
        int requests = factory.demandRequests.size();
        assertRead(cache, 480, 64);
        assertEquals(requests, factory.demandRequests.size());
        assertEquals(64, cache.prefetchedBytes());
        assertEquals(6 * 16, cache.allocatedBytes());
        assertRead(cache, 0, 16);
        assertEquals(requests + 1, factory.demandRequests.size());
    }

    @Test
    public void inactiveDemandRetainsOnlyForegroundAllowance() throws Exception {
        RecordingFactory factory = new RecordingFactory(1024);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 0, 512);
        assertEquals(32, cache.cachedBytes());
        assertEquals(32, cache.allocatedBytes());
    }

    @Test
    public void shortReadsAreAssembledAndOnlyInitializedBytesAreReturned() throws Exception {
        RecordingFactory factory = new RecordingFactory(100);
        factory.maxRead = 3;
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 13, 54);
        cache.updatePlayback(500, 1000, true);
        await(() -> cache.cachedBytes() >= 64);
        assertRead(cache, 18, 64);
        byte[] end = new byte[20];
        assertEquals(3, cache.read(97, end, 20));
        assertEquals(valueAt(99), end[2]);
    }

    @Test
    public void unexpectedEofIsNotExposedAsZeroFilledDataOrRetried() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.actualLength = 135;
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        await(() -> factory.prefetchRequests.size() >= 5);
        await(() -> cache.cachedBytes() == 39);
        int requests = factory.prefetchRequests.size();
        byte[] buffer = new byte[64];
        assertEquals(7, cache.read(128, buffer, 64));
        assertEquals(0, cache.read(140, buffer, 8));
        Thread.sleep(60);
        assertEquals(requests, factory.prefetchRequests.size());
        assertTrue(factory.demandRequests.isEmpty());
    }

    @Test
    public void demandErrorsAndZeroProgressArePropagatedWithoutCaching() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        factory.failDemand = true;
        expectIo(() -> cache.read(0, new byte[16], 16));
        assertEquals(0, cache.cachedBytes());
        factory.failDemand = false;
        factory.zeroDemand = true;
        expectIo(() -> cache.read(0, new byte[16], 16));
        assertEquals(0, cache.cachedBytes());
        factory.zeroDemand = false;
        assertRead(cache, 0, 16);
    }

    @Test
    public void failureAfterShortReadNeverPublishesPartialBlock() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.maxRead = 3;
        factory.failReadNumber = 2;
        SeekWindowCache cache = cache(factory);
        expectIo(() -> cache.read(0, new byte[16], 16));
        assertEquals(0, cache.cachedBytes());
        cache.updatePlayback(500, 1000, true);
        await(() -> factory.prefetchRequests.size() == 2);
        Thread.sleep(60);
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.prefetchedBytes());
        factory.failReadNumber = 0;
        assertRead(cache, 128, 16);
    }

    @Test
    public void invalidSourceCountsFailRatherThanLoopOrExposeGarbage() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        SeekWindowCache cache = cache(factory);
        for (int result : new int[]{-2, 17}) {
            factory.invalidCount = result;
            expectIo(() -> cache.read(0, new byte[16], 16));
            assertEquals(0, cache.cachedBytes());
        }
    }

    @Test
    public void subByteTimeWindowDisablesSpeculation() throws Exception {
        RecordingFactory factory = new RecordingFactory(7);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(1000, Long.MAX_VALUE, true);
        Thread.sleep(60);
        assertEquals(1, factory.sources.size());
        assertRead(cache, 0, 7);
    }

    @Test
    public void speculativeFailureBacksOffAndDoesNotBreakDemand() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.failPrefetch = true;
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        await(() -> !factory.prefetchRequests.isEmpty());
        for (int i = 0; i < 10; i++) {
            cache.updatePlayback(501 + i, 1000, true);
        }
        Thread.sleep(80);
        assertEquals(1, factory.prefetchRequests.size());
        assertRead(cache, 128, 16);
        factory.failPrefetch = false;
        await(() -> cache.prefetchedBytes() > 0);
    }

    @Test
    public void speculativeOpenFailureDoesNotBreakDemand() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.failSpeculativeOpen = true;
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.speculativeOpen.await(3, TimeUnit.SECONDS));
        assertRead(cache, 0, 16);
        assertEquals(0, cache.prefetchedBytes());
    }

    @Test
    public void advancingPlaybackPublishesEveryPendingOverlappingBlock() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        Request request = nextPrefetch(factory);
        for (int i = 1; i <= 8; i++) {
            long before = cache.prefetchedBytes();
            cache.updatePlayback(32000 + i * 250, 64000, true);
            request.gate.countDown();
            request = nextPrefetch(factory);
            assertTrue("Advancing playback discarded a useful completed read",
                    cache.prefetchedBytes() > before);
            assertTrue(request.count <= 16);
            assertTrue(cache.cachedBytes() <= 64);
        }
    }

    @Test
    public void advancingPlaybackContinuesShortReadsWithoutRestartingBlock() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        factory.maxRead = 3;
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        for (int i = 0; i < 6; i++) {
            Request request = nextPrefetch(factory);
            assertEquals(128 + i * 3, request.offset);
            assertEquals(16 - i * 3, request.count);
            assertEquals(0, cache.cachedBytes());
            cache.updatePlayback(32000 + (i + 1) * 250, 64000, true);
            request.gate.countDown();
        }
        nextPrefetch(factory);
        assertEquals(16, cache.prefetchedBytes());
        assertRead(cache, 128, 16);
        assertTrue(factory.demandRequests.isEmpty());
    }

    @Test
    public void pendingBlockPublishesOnlyLatestWindowIntersectionInEitherDirection() throws Exception {
        for (int position : new int[]{41000, 26000}) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.stepPrefetch = true;
            SeekWindowCache cache = movingCache(factory);
            cache.updatePlayback(32000, 64000, true);
            Request request = nextPrefetch(factory);
            assertEquals(128, request.offset);
            cache.updatePlayback(position, 64000, true);
            request.gate.countDown();
            nextPrefetch(factory);
            int start = position == 41000 ? 132 : 128;
            int count = position == 41000 ? 12 : 8;
            assertEquals(count, cache.prefetchedBytes());
            assertEquals(count, cache.cachedBytes());
            assertRead(cache, start, count);
            assertTrue(factory.demandRequests.isEmpty());
            assertRead(cache, position == 41000 ? 128 : 136, 4);
            assertEquals(1, factory.demandRequests.size());
        }
    }

    @Test
    public void overlappingRetargetDropsPendingRangeThatIsNoLongerUseful() throws Exception {
        for (int maxRead : new int[]{3, 16}) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.stepPrefetch = true;
            factory.maxRead = maxRead;
            SeekWindowCache cache = movingCache(factory);
            cache.updatePlayback(32000, 64000, true);
            Request request = nextPrefetch(factory);
            cache.updatePlayback(44000, 64000, true);
            request.gate.countDown();
            assertEquals(176, nextPrefetch(factory).offset);
            assertEquals(0, cache.prefetchedBytes());
            assertEquals(0, cache.cachedBytes());
        }
    }

    @Test
    public void advancingShortReadStillYieldsToPendingForegroundRequest() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        factory.maxRead = 3;
        factory.demandGate = new CountDownLatch(1);
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        Request request = nextPrefetch(factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 0, 16, failure);
        assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
        cache.updatePlayback(32250, 64000, true);
        request.gate.countDown();
        assertEquals(null, factory.pendingPrefetch.poll(80, TimeUnit.MILLISECONDS));
        assertEquals(0, cache.prefetchedBytes());
        factory.demandGate.countDown();
        demand.join(1000);
        assertFalse(demand.isAlive());
        assertEquals(null, failure.get());
        Request continued = nextPrefetch(factory);
        assertEquals(131, continued.offset);
        assertEquals(13, continued.count);
    }

    @Test
    public void clippingPastUnexpectedEofPreservesZeroLengthMarker() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.actualLength = 135;
        factory.stepPrefetch = true;
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        nextPrefetch(factory).gate.countDown();
        Request eof = nextPrefetch(factory);
        assertEquals(135, eof.offset);
        cache.updatePlayback(42000, 64000, true);
        eof.gate.countDown();
        nextPrefetch(factory);
        assertEquals(0, cache.prefetchedBytes());
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.read(136, new byte[4], 4));
        assertTrue(factory.demandRequests.isEmpty());
    }

    @Test
    public void clippingBeforeUnexpectedEofDoesNotCreateEarlierEof() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.actualLength = 135;
        factory.stepPrefetch = true;
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        nextPrefetch(factory).gate.countDown();
        Request eof = nextPrefetch(factory);
        assertEquals(135, eof.offset);
        cache.updatePlayback(25000, 64000, true);
        eof.gate.countDown();
        nextPrefetch(factory);
        assertEquals(4, cache.prefetchedBytes());
        assertEquals(4, cache.cachedBytes());
        assertRead(cache, 128, 4);
        assertTrue(factory.demandRequests.isEmpty());
        assertRead(cache, 132, 3);
        assertEquals(0, cache.read(135, new byte[1], 1));
        assertEquals(2, factory.demandRequests.size());
    }

    @Test
    public void pauseAndImmediateResumeNeverRevivePendingData() throws Exception {
        for (int maxRead : new int[]{3, 16}) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.stepPrefetch = true;
            factory.maxRead = maxRead;
            SeekWindowCache cache = movingCache(factory);
            cache.updatePlayback(32000, 64000, true);
            Request request = nextPrefetch(factory);
            cache.updatePlayback(32000, 64000, false);
            cache.updatePlayback(32250, 64000, true);
            request.gate.countDown();
            assertEquals(128, nextPrefetch(factory).offset);
            assertEquals(0, cache.prefetchedBytes());
            assertEquals(0, cache.cachedBytes());
        }
    }

    @Test
    public void disjointSeekAndReturnNeverRevivePendingData() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.stepPrefetch = true;
        SeekWindowCache cache = movingCache(factory);
        cache.updatePlayback(32000, 64000, true);
        Request request = nextPrefetch(factory);
        cache.updatePlayback(60000, 64000, true);
        cache.updatePlayback(32250, 64000, true);
        request.gate.countDown();
        assertEquals(128, nextPrefetch(factory).offset);
        assertEquals(0, cache.prefetchedBytes());
        assertEquals(0, cache.cachedBytes());
    }

    @Test
    public void retargetDiscardsPendingOldWindow() throws Exception {
        RecordingFactory factory = new RecordingFactory(1024);
        factory.prefetchGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(250, 1000, true);
        assertTrue(factory.prefetchEntered.await(3, TimeUnit.SECONDS));
        cache.updatePlayback(750, 1000, true);
        factory.prefetchGate.countDown();
        await(() -> cache.prefetchedBytes() == 64);
        assertArrayEquals(new long[]{256, 768, 752, 784, 736}, offsets(factory.prefetchRequests));
        assertRead(cache, 736, 64);
        assertTrue(factory.demandRequests.isEmpty());
        assertRead(cache, 256, 16);
        assertEquals(1, factory.demandRequests.size());
    }

    @Test
    public void pauseDuringShortReadStopsFurtherRequestsAndDropsPartialData() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.maxRead = 3;
        factory.prefetchGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.prefetchEntered.await(3, TimeUnit.SECONDS));
        cache.updatePlayback(500, 1000, false);
        factory.prefetchGate.countDown();
        Thread.sleep(80);
        assertEquals(1, factory.prefetchRequests.size());
        assertEquals(0, cache.cachedBytes());
        cache.updatePlayback(500, 1000, true);
        await(() -> cache.prefetchedBytes() == 64);
    }

    @Test
    public void pendingPrefetchDoesNotBlockDemandAndDuplicatePublishIsSafe() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.prefetchGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.prefetchEntered.await(3, TimeUnit.SECONDS));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 128, 16, failure);
        demand.join(1000);
        assertFalse("Demand must not wait for speculative network I/O", demand.isAlive());
        assertEquals(null, failure.get());
        factory.prefetchGate.countDown();
        await(() -> cache.cachedBytes() == 64);
        assertEquals(48, cache.prefetchedBytes());
        assertRead(cache, 96, 64);
    }

    @Test
    public void pendingDemandGatesFurtherSpeculativeRequests() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.demandGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 0, 16, failure);
        assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
        cache.updatePlayback(500, 1000, true);
        Thread.sleep(60);
        assertTrue(factory.prefetchRequests.isEmpty());
        factory.demandGate.countDown();
        demand.join(1000);
        assertFalse(demand.isAlive());
        assertEquals(null, failure.get());
        await(() -> cache.prefetchedBytes() == 64);
    }

    @Test
    public void closeCancelsPendingReadsClosesSourcesAndReleasesMemory() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.prefetchGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 32, 16);
        factory.demandGate = new CountDownLatch(1);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.prefetchEntered.await(3, TimeUnit.SECONDS));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread demand = startRead(cache, 0, 16, failure);
        assertTrue(factory.demandEntered.await(3, TimeUnit.SECONDS));
        cache.close();
        demand.join(1000);
        assertFalse(demand.isAlive());
        assertTrue(failure.get() instanceof IOException);
        assertEquals(0, cache.allocatedBytes());
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.prefetchedBytes());
        for (RecordingSource source : factory.sources) {
            assertEquals(1, source.closeCalls);
        }
        cache.close();
        cache.updatePlayback(500, 1000, true);
        expectIo(() -> cache.read(0, new byte[1], 1));
    }

    @Test
    public void workerFinallyClosesWithoutCancellationInterruptAndRestoresIt() throws Exception {
        assertCancellationCleanup(new CleanupFactory());
    }

    @Test
    public void workerReadFailureClosesWithoutCancellationInterruptAndRestoresIt() throws Exception {
        CleanupFactory factory = new CleanupFactory();
        factory.failRead = true;
        assertCancellationCleanup(factory);
    }

    @Test
    public void lateOpenedSourceClosesWithoutCancellationInterruptAndRestoresIt() throws Exception {
        CleanupFactory factory = new CleanupFactory();
        factory.openPending = true;
        assertCancellationCleanup(factory);
    }

    @Test
    public void workerCleanupStillPropagatesRealCloseFailureAndRestoresInterrupt() throws Exception {
        CleanupFactory factory = new CleanupFactory();
        factory.failClose = true;
        assertCancellationCleanup(factory);
    }

    @Test
    public void lateOpenedSourceStillPropagatesRealCloseFailureAndRestoresInterrupt() throws Exception {
        CleanupFactory factory = new CleanupFactory();
        factory.openPending = true;
        factory.failClose = true;
        assertCancellationCleanup(factory);
    }

    @Test
    public void workerCleanupStillSuppressesRealCloseFailureBehindDemandCloseFailure() throws Exception {
        CleanupFactory factory = new CleanupFactory();
        factory.failRead = true;
        factory.failClose = true;
        factory.failDemandClose = true;
        assertCancellationCleanup(factory);
    }

    @Test
    public void cancellationDoesNotInterruptWorkerSourceCloseAlreadyInProgress() throws Exception {
        CleanupFactory factory = new CleanupFactory();
        factory.waitForCancellation = false;
        factory.failRead = true;
        factory.closeGate = new CountDownLatch(1);
        SeekWindowCache cache = new SeekWindowCache(factory, 16, 2, 500, 2);
        caches.add(cache);
        cache.updatePlayback(500, 1000, true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                cache.close();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        closer.setDaemon(true);
        try {
            assertTrue(factory.closeEntered.await(3, TimeUnit.SECONDS));
            closer.start();
            assertTrue(factory.demandClosed.await(3, TimeUnit.SECONDS));
        } finally {
            factory.closeGate.countDown();
            closer.join(1000);
        }
        assertFalse(closer.isAlive());
        assertEquals(null, failure.get());
        assertEquals(factory.worker, factory.closingThread);
        assertFalse(factory.worker.isAlive());
        assertEquals(1, factory.prefetchCloseCalls);
        assertEquals(1, factory.demandCloseCalls);
        assertEquals(0, cache.allocatedBytes());
    }

    @Test
    public void closePreservesCallerInterruptOnSuccessAndFailureAndStillReleasesMemory() throws Exception {
        for (boolean failClose : new boolean[]{false, true}) {
            RecordingFactory factory = new RecordingFactory(256);
            factory.failClose = failClose;
            SeekWindowCache cache = cache(factory);
            assertRead(cache, 0, 16);
            Thread.currentThread().interrupt();
            try {
                if (failClose) {
                    expectIo(cache::close);
                } else {
                    cache.close();
                }
                assertTrue("Caller interrupt was lost", Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(1, factory.sources.get(0).closeCalls);
            assertEquals(0, cache.allocatedBytes());
        }
    }

    @Test
    public void closeFailureStillClosesAllResourcesAndReleasesMemory() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.failClose = true;
        SeekWindowCache cache = cache(factory);
        cache.updatePlayback(500, 1000, true);
        await(() -> cache.prefetchedBytes() == 64);
        expectIo(cache::close);
        assertEquals(0, cache.allocatedBytes());
        for (RecordingSource source : factory.sources) {
            assertEquals(1, source.closeCalls);
        }
    }

    @Test
    public void closeJoinsPendingOpenAndClosesLateSourceWithoutPublishing() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.openGate = new CountDownLatch(1);
        SeekWindowCache cache = cache(factory);
        assertRead(cache, 0, 16);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.speculativeOpen.await(3, TimeUnit.SECONDS));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                cache.close();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        closer.setDaemon(true);
        closer.start();
        try {
            await(() -> factory.sources.get(0).closed);
            assertTrue("Close must join the pending open", closer.isAlive());
            assertEquals(0, cache.allocatedBytes());
            assertEquals(0, cache.cachedBytes());
        } finally {
            factory.openGate.countDown();
        }
        closer.join(1000);
        assertFalse(closer.isAlive());
        assertEquals(null, failure.get());
        assertEquals(2, factory.sources.size());
        assertEquals(1, factory.sources.get(1).closeCalls);
        assertTrue(factory.prefetchRequests.isEmpty());
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.allocatedBytes());
    }

    @Test
    public void concurrentRandomReadsAndRetargetingKeepSeekDataCorrect() throws Exception {
        RecordingFactory factory = new RecordingFactory(10000);
        factory.maxRead = 7;
        SeekWindowCache cache = cache(factory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int seed = i;
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    Random random = new Random(seed);
                    for (int j = 0; j < 100; j++) {
                        cache.updatePlayback(random.nextInt(1000), 1000, j % 7 != 0);
                        if (j % 5 == 0) {
                            cache.updateSeekPreview(random.nextInt(1000), 1000);
                        } else if (j % 5 == 3) {
                            cache.clearSeekPreview();
                        }
                        assertRead(cache, random.nextInt(9900), 1 + random.nextInt(100));
                        assertTrue(cache.allocatedBytes() <= 6 * 16);
                    }
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                }
            });
            reader.setDaemon(true);
            readers.add(reader);
            reader.start();
        }
        start.countDown();
        for (Thread reader : readers) {
            reader.join(5000);
            assertFalse("Concurrent reader did not complete", reader.isAlive());
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    public void constructorFailureClosesSource() throws Exception {
        RecordingFactory factory = new RecordingFactory(256);
        factory.failLength = true;
        expectIo(() -> new SeekWindowCache(factory));
        assertEquals(1, factory.sources.get(0).closeCalls);
    }

    @Test
    public void validatesArgumentsAndSupportsZeroCount() throws Exception {
        SeekWindowCache cache = cache(new RecordingFactory(256));
        assertEquals(0, cache.read(0, new byte[0], 0));
        try {
            cache.read(-1, new byte[1], 1);
            fail("Negative offset accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
        try {
            cache.read(0, new byte[1], 2);
            fail("Oversized count accepted");
        } catch (IndexOutOfBoundsException expected) {
            assertNotNull(expected);
        }
    }

    private void assertCancellationCleanup(CleanupFactory factory) throws Exception {
        SeekWindowCache cache = new SeekWindowCache(factory, 16, 2, 500, 2);
        caches.add(cache);
        cache.updatePlayback(500, 1000, true);
        assertTrue(factory.entered.await(3, TimeUnit.SECONDS));
        IOException failure = null;
        try {
            cache.close();
        } catch (IOException exception) {
            failure = exception;
        }
        assertEquals(factory.failDemandClose ? factory.demandCloseFailure
                : factory.failClose ? factory.closeFailure : null, failure);
        if (factory.failDemandClose) {
            assertArrayEquals(new Throwable[]{factory.closeFailure}, failure.getSuppressed());
        }
        assertEquals(factory.worker, factory.closingThread);
        assertFalse(factory.worker.isAlive());
        assertTrue("Worker cancellation interrupt was not restored", factory.worker.isInterrupted());
        assertFalse("Worker cancellation leaked to caller", Thread.currentThread().isInterrupted());
        assertEquals(1, factory.prefetchCloseCalls);
        assertEquals(1, factory.demandCloseCalls);
        assertEquals(factory.openPending ? 0 : 1, factory.readCalls);
        assertEquals(0, cache.cachedBytes());
        assertEquals(0, cache.prefetchedBytes());
        assertEquals(0, cache.allocatedBytes());
        cache.close();
        assertEquals(1, factory.prefetchCloseCalls);
        assertEquals(1, factory.demandCloseCalls);
    }

    private SeekWindowCache previewCache(RecordingFactory factory) throws IOException {
        SeekWindowCache cache = new SeekWindowCache(factory,
                16, 2, 500, 2, 16);
        caches.add(cache);
        return cache;
    }

    private SeekWindowCache movingCache(RecordingFactory factory) throws IOException {
        SeekWindowCache cache = new SeekWindowCache(factory,
                16, 2, SeekWindowCache.WINDOW_MS, 2);
        caches.add(cache);
        return cache;
    }

    private static Request nextPrefetch(RecordingFactory factory) throws InterruptedException {
        Request request = factory.pendingPrefetch.poll(3, TimeUnit.SECONDS);
        assertNotNull("Timed out waiting for speculative source request", request);
        return request;
    }

    private SeekWindowCache cache(SeekWindowCache.SourceFactory factory) throws IOException {
        SeekWindowCache cache = new SeekWindowCache(factory, 16, 2, 500, 2);
        caches.add(cache);
        return cache;
    }

    private static void assertRead(SeekWindowCache cache, long offset, int count) throws IOException {
        byte[] actual = new byte[count];
        assertEquals(count, cache.read(offset, actual, count));
        for (int i = 0; i < count; i++) {
            assertEquals("Byte at " + (offset + i), valueAt(offset + i), actual[i]);
        }
    }

    private static void assertRead(ReadAction reader, long offset, int count) throws IOException {
        byte[] actual = new byte[count];
        assertEquals(count, reader.read(offset, actual, count));
        for (int i = 0; i < count; i++) {
            assertEquals("Byte at " + (offset + i), valueAt(offset + i), actual[i]);
        }
    }

    private static byte valueAt(long offset) {
        return (byte) (offset ^ (offset >>> 8) ^ (offset >>> 32));
    }

    private static long[] offsets(List<Request> requests) {
        return requests.stream().mapToLong(request -> request.offset).toArray();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("Timed out waiting for cache worker", condition.getAsBoolean());
    }

    private static Thread startRead(SeekWindowCache cache, long offset, int count,
                                    AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                assertRead(cache, offset, count);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Thread startAction(IoAction action, AtomicReference<Throwable> failure,
                                      CountDownLatch done) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            } finally {
                done.countDown();
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void expectIo(IoAction action) throws Exception {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }

    private interface IoAction {
        void run() throws Exception;
    }

    private interface ReadAction {
        int read(long offset, byte[] buffer, int count) throws IOException;
    }

    private static final class Request {
        final long offset;
        final int count;
        final CountDownLatch gate = new CountDownLatch(1);

        Request(long offset, int count) {
            this.offset = offset;
            this.count = count;
        }
    }

    /** Independent, explicitly gated demand channels; existing prefetch fakes stay unchanged. */
    private static final class ChannelFactory implements SeekWindowCache.SourceFactory {
        final long length;
        final BlockingQueue<ChannelSource> plannedSources = new LinkedBlockingQueue<>();
        final AtomicInteger openCalls = new AtomicInteger();
        final ChannelSource defaultSource;
        volatile long actualLength;
        volatile int maxRead = Integer.MAX_VALUE;

        ChannelFactory(long length) {
            this.length = length;
            actualLength = length;
            defaultSource = addSource();
        }

        ChannelSource addSource() {
            ChannelSource source = new ChannelSource(this);
            plannedSources.add(source);
            return source;
        }

        @Override
        public SeekWindowCache.Source open() throws IOException {
            openCalls.incrementAndGet();
            ChannelSource source = plannedSources.poll();
            if (source == null) {
                throw new IOException("Unexpected source open");
            }
            source.openEntered.countDown();
            CleanupFactory.awaitClose(source.openGate);
            if (source.openFailure != null) {
                throw source.openFailure;
            }
            return source;
        }
    }

    private static final class ChannelSource implements SeekWindowCache.Source {
        final ChannelFactory factory;
        final CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final AtomicInteger lengthCalls = new AtomicInteger();
        final AtomicInteger readCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicInteger activeReads = new AtomicInteger();
        volatile CountDownLatch readEntered = new CountDownLatch(1);
        volatile CountDownLatch openGate = new CountDownLatch(0);
        volatile CountDownLatch readGate = new CountDownLatch(0);
        volatile CountDownLatch closeGate = new CountDownLatch(0);
        volatile boolean releaseReadOnClose = true;
        volatile boolean allowReadAfterClose;
        volatile boolean closed;
        volatile byte[] lastBuffer;
        volatile Thread closingThread;
        volatile IOException openFailure;
        volatile IOException readFailure;
        volatile IOException closeFailure;

        ChannelSource(ChannelFactory factory) {
            this.factory = factory;
        }

        @Override
        public long length() {
            lengthCalls.incrementAndGet();
            return factory.length;
        }

        @Override
        public int read(long offset, byte[] buffer, int offsetInBuffer, int count) throws IOException {
            int concurrent = activeReads.incrementAndGet();
            try {
                assertEquals("One source was used concurrently", 1, concurrent);
                readCalls.incrementAndGet();
                lastBuffer = buffer;
                readEntered.countDown();
                CleanupFactory.awaitClose(readGate);
                if (readFailure != null) {
                    throw readFailure;
                }
                if (closed && !allowReadAfterClose) {
                    throw new IOException("Source closed");
                }
                if (offset >= factory.actualLength) {
                    return -1;
                }
                int result = (int) Math.min(Math.min(count, factory.maxRead),
                        factory.actualLength - offset);
                for (int i = 0; i < result; i++) {
                    buffer[offsetInBuffer + i] = valueAt(offset + i);
                }
                return result;
            } finally {
                activeReads.decrementAndGet();
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            closeCalls.incrementAndGet();
            closingThread = Thread.currentThread();
            if (releaseReadOnClose) {
                readGate.countDown();
            }
            closeEntered.countDown();
            CleanupFactory.awaitClose(closeGate);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class CleanupFactory implements SeekWindowCache.SourceFactory {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch sourceClosed = new CountDownLatch(1);
        final CountDownLatch demandClosed = new CountDownLatch(1);
        final IOException closeFailure = new IOException("Protocol close failed");
        final IOException demandCloseFailure = new IOException("Demand close failed");
        CountDownLatch closeGate = new CountDownLatch(0);
        boolean openPending;
        boolean failRead;
        boolean failClose;
        boolean failDemandClose;
        boolean waitForCancellation = true;
        boolean opened;
        volatile Thread worker;
        volatile Thread closingThread;
        int prefetchCloseCalls;
        int demandCloseCalls;
        int readCalls;

        @Override
        public SeekWindowCache.Source open() throws IOException {
            boolean speculative = opened;
            opened = true;
            if (speculative) {
                worker = Thread.currentThread();
                if (openPending) {
                    awaitCancellation();
                }
            }
            return new SeekWindowCache.Source() {
                @Override
                public long length() {
                    return 256;
                }

                @Override
                public int read(long offset, byte[] buffer, int offsetInBuffer, int count)
                        throws IOException {
                    readCalls++;
                    if (speculative && waitForCancellation) {
                        awaitCancellation();
                    }
                    if (failRead) {
                        throw new IOException("Read failed");
                    }
                    for (int i = 0; i < count; i++) {
                        buffer[offsetInBuffer + i] = valueAt(offset + i);
                    }
                    return count;
                }

                @Override
                public void close() throws IOException {
                    if (speculative) {
                        prefetchCloseCalls++;
                        closingThread = Thread.currentThread();
                        closeEntered.countDown();
                        try {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IOException("Source close inherited cancellation interrupt");
                            }
                            awaitClose(closeGate);
                            if (failClose) {
                                throw closeFailure;
                            }
                        } finally {
                            sourceClosed.countDown();
                        }
                    } else {
                        demandCloseCalls++;
                        demandClosed.countDown();
                        // Force the worker to own speculative cleanup, not the close caller.
                        awaitClose(sourceClosed);
                        if (failDemandClose) {
                            throw demandCloseFailure;
                        }
                    }
                }
            };
        }

        private void awaitCancellation() throws IOException {
            entered.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                throw new IOException("Cancellation did not arrive");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private static void awaitClose(CountDownLatch gate) throws IOException {
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Close gate timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Source close was interrupted", exception);
            }
        }
    }

    private static final class RecordingFactory implements SeekWindowCache.SourceFactory {
        final long length;
        final List<RecordingSource> sources = new CopyOnWriteArrayList<>();
        final List<Request> demandRequests = new CopyOnWriteArrayList<>();
        final List<Request> prefetchRequests = new CopyOnWriteArrayList<>();
        final BlockingQueue<Request> pendingPrefetch = new LinkedBlockingQueue<>();
        final CountDownLatch prefetchEntered = new CountDownLatch(1);
        final CountDownLatch demandEntered = new CountDownLatch(1);
        final CountDownLatch speculativeOpen = new CountDownLatch(1);
        final CountDownLatch demandCloseEntered = new CountDownLatch(1);
        volatile boolean stepPrefetch;
        volatile long actualLength;
        volatile int maxRead = Integer.MAX_VALUE;
        volatile boolean failDemand;
        volatile boolean failPrefetch;
        volatile boolean zeroDemand;
        volatile boolean failClose;
        volatile boolean failLength;
        volatile boolean failSpeculativeOpen;
        volatile int failReadNumber;
        volatile int invalidCount;
        volatile CountDownLatch openGate;
        volatile CountDownLatch prefetchGate;
        volatile CountDownLatch demandGate;
        volatile CountDownLatch demandCloseGate;
        volatile int demandCountLimit = Integer.MAX_VALUE;
        volatile CountDownLatch oversizedDemandGate;

        RecordingFactory(long length) {
            this.length = length;
            actualLength = length;
        }

        @Override
        public synchronized SeekWindowCache.Source open() throws IOException {
            boolean speculative = !sources.isEmpty();
            if (speculative) {
                speculativeOpen.countDown();
                if (failSpeculativeOpen) {
                    throw new IOException("Speculative open failed");
                }
                if (openGate != null) {
                    boolean interrupted = false;
                    while (true) {
                        try {
                            if (!openGate.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("Open gate timed out");
                            }
                            break;
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            RecordingSource source = new RecordingSource(this, speculative);
            sources.add(source);
            return source;
        }
    }

    private static final class RecordingSource implements SeekWindowCache.Source {
        final RecordingFactory factory;
        final boolean speculative;
        volatile boolean closed;
        volatile int closeCalls;
        int reads;

        RecordingSource(RecordingFactory factory, boolean speculative) {
            this.factory = factory;
            this.speculative = speculative;
        }

        @Override
        public long length() throws IOException {
            if (factory.failLength) {
                throw new IOException("Length failed");
            }
            return factory.length;
        }

        @Override
        public int read(long offset, byte[] buffer, int offsetInBuffer, int count) throws IOException {
            reads++;
            Request request = new Request(offset, count);
            (speculative ? factory.prefetchRequests : factory.demandRequests).add(request);
            (speculative ? factory.prefetchEntered : factory.demandEntered).countDown();
            CountDownLatch gate = speculative ? factory.prefetchGate : factory.demandGate;
            if (speculative && factory.stepPrefetch) {
                gate = request.gate;
                factory.pendingPrefetch.add(request);
            }
            if (!speculative && count > factory.demandCountLimit) {
                gate = factory.oversizedDemandGate;
            }
            if (gate != null) {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Test gate timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            if (closed || reads == factory.failReadNumber
                    || (speculative ? factory.failPrefetch : factory.failDemand)) {
                throw new IOException("Read failed");
            }
            if (factory.invalidCount != 0) {
                return factory.invalidCount;
            }
            if (!speculative && factory.zeroDemand) {
                return 0;
            }
            if (offset >= factory.actualLength) {
                return -1;
            }
            int result = (int) Math.min(Math.min(count, factory.maxRead), factory.actualLength - offset);
            for (int i = 0; i < result; i++) {
                buffer[offsetInBuffer + i] = valueAt(offset + i);
            }
            return result;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            closeCalls++;
            if (!speculative && factory.demandCloseGate != null) {
                factory.demandCloseEntered.countDown();
                CleanupFactory.awaitClose(factory.demandCloseGate);
            }
            CountDownLatch gate = speculative ? factory.prefetchGate : factory.demandGate;
            if (gate != null) {
                gate.countDown();
            }
            if (!speculative && factory.oversizedDemandGate != null) {
                factory.oversizedDemandGate.countDown();
            }
            if (factory.failClose) {
                throw new IOException("Close failed");
            }
        }
    }
}