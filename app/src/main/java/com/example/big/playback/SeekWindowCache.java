package com.example.big.playback;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Private, lazily allocated, fixed-slot heap cache for an immutable source. Playback time is mapped to
 * bytes using size/duration, an estimate rather than a media index (especially for variable bitrate).
 * Each side is limited to five estimated minutes or 128 MiB. Edge slots contain only their
 * initialized window slice; demand fetches only missing requested bytes without moving the window.
 * Adjacent/overlapping slices merge; disjoint slices replace rather than exposing uninitialized gaps.
 * Explicit seek previews warm at most 2 MiB per side, even paused, within the normal window limits.
 * The default per-side slot budget is also capped at maxMemory()/16, rounded down to 256-KiB
 * blocks with a minimum of one per side, plus four foreground slots (at most 1,028 slots total).
 * Resident payload is thus about 1/8 of the maximum heap plus the foreground allowance, excluding
 * metadata, one block-sized transfer buffer per active demand reader, and the prefetch buffer.
 * Slots allocate only on publication and release storage on eviction/discard; there is no retained
 * pool of unused payload arrays. Demand readers share cache payload, never source I/O locks.
 */
public final class SeekWindowCache implements Closeable {
    public interface Source extends Closeable {
        long length() throws IOException;

        /** Returns a positive byte count, or -1 at EOF; short reads are allowed. */
        int read(long offset, byte[] buffer, int offsetInBuffer, int count) throws IOException;
    }

    public interface SourceFactory {
        /** Each call must return an independent reader of the same immutable content. */
        Source open() throws IOException;
    }

    static final int BLOCK_SIZE = 256 * 1024;
    static final int BLOCKS_PER_SIDE = 512;
    static final long WINDOW_MS = 5 * 60 * 1000;
    static final int FOREGROUND_BLOCKS = 4;
    static final long PREVIEW_BYTES_PER_SIDE = 2 * 1024 * 1024;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final SourceFactory factory;
    private final Reader defaultReader;
    private final Set<Reader> readers = new LinkedHashSet<>();
    private final long size;
    private final int blockSize;
    private final long bytesPerSide;
    private final long previewBytesPerSide;
    private final long windowMs;
    private final int foregroundBlocks;
    private final Entry[] slots;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Object memoryLock = new Object();
    private final Object control = new Object();
    private final Object closeLock = new Object();
    private final Thread worker;
    private volatile boolean closed;
    private volatile Window window = new Window(0, 0, 0, false, 0);
    private Source prefetchSource;
    private boolean workerClosingSource;
    private long playbackPositionMs;
    private long playbackDurationMs;
    private boolean playbackActive;
    private boolean seekPreview;
    private boolean cachingEnabled = true;
    private long cacheGeneration;
    private int foregroundRequests;
    private long revision;
    private long access;
    private long cached;
    private long prefetched;
    private IOException workerCloseFailure;

    public SeekWindowCache(SourceFactory factory) throws IOException {
        this(factory, BLOCK_SIZE, blocksPerSideForHeap(Runtime.getRuntime().maxMemory()),
                WINDOW_MS, FOREGROUND_BLOCKS);
    }

    static int blocksPerSideForHeap(long maxHeap) {
        return (int) Math.max(1, Math.min(BLOCKS_PER_SIDE, maxHeap / 16 / BLOCK_SIZE));
    }

    SeekWindowCache(SourceFactory factory, int blockSize, int blocksPerSide,
                    long windowMs, int foregroundBlocks) throws IOException {
        this(factory, blockSize, blocksPerSide, windowMs, foregroundBlocks,
                PREVIEW_BYTES_PER_SIDE);
    }

    SeekWindowCache(SourceFactory factory, int blockSize, int blocksPerSide,
                    long windowMs, int foregroundBlocks, long previewBytesPerSide) throws IOException {
        this.factory = Objects.requireNonNull(factory);
        if (blockSize <= 0 || blocksPerSide <= 0 || windowMs <= 0 || foregroundBlocks <= 0
                || previewBytesPerSide <= 0
                || 2L * blocksPerSide + foregroundBlocks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid cache limits");
        }
        this.blockSize = blockSize;
        this.bytesPerSide = (long) blockSize * blocksPerSide;
        this.previewBytesPerSide = Math.min(this.bytesPerSide, previewBytesPerSide);
        this.windowMs = windowMs;
        this.foregroundBlocks = foregroundBlocks;
        slots = new Entry[2 * blocksPerSide + foregroundBlocks];
        Source opened = null;
        long openedSize;
        try {
            opened = factory.open();
            openedSize = opened.length();
        } catch (IOException | RuntimeException exception) {
            IOException cleanup = closeResource(opened, null);
            if (cleanup != null) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
        size = openedSize;
        defaultReader = new Reader(opened);
        readers.add(defaultReader);
        worker = new Thread(this::prefetchLoop, "seek-window-cache");
        worker.setDaemon(true);
        worker.start();
    }

    /** The source size recorded at construction; a negative value means unknown. */
    public long length() {
        return size;
    }

    /** Reads into buffer[0..count), returning zero at EOF, as required by proxy FD onRead. */
    public int read(long offset, byte[] buffer, int count) throws IOException {
        return defaultReader.read(offset, buffer, count);
    }

    /**
     * Registers an independent demand reader without source I/O. Cache hits need no connection;
     * its source and block-sized transfer buffer are opened/allocated lazily on the first miss.
     */
    public Reader openReader() throws IOException {
        synchronized (control) {
            ensureOpen();
            Reader reader = new Reader(null);
            readers.add(reader);
            return reader;
        }
    }

    /** An independent demand-I/O channel, invalidated by its own close or the owning cache's close. */
    public final class Reader implements Closeable {
        private final Object ioLock = new Object();
        // Source/buffer installation and detachment use control, never the I/O lock.
        private Source source;
        private byte[] transferBuffer;
        private volatile boolean readerClosed;

        private Reader(Source source) {
            this.source = source;
        }

        /** Same offset/count and zero-at-EOF contract as the default cache reader. */
        public int read(long offset, byte[] buffer, int count) throws IOException {
            Objects.requireNonNull(buffer);
            if (offset < 0) {
                throw new IllegalArgumentException("Negative offset");
            }
            if (count < 0 || count > buffer.length) {
                throw new IndexOutOfBoundsException("Invalid count");
            }
            long generation;
            synchronized (control) {
                ensureReaderOpen();
                generation = cacheGeneration;
                foregroundRequests++;
                revision++;
                control.notifyAll();
            }
            try {
                long remaining = (size >= 0 ? size : Long.MAX_VALUE) - offset;
                int wanted = (int) Math.min(count, Math.max(0, remaining));
                int copied = 0;
                while (copied < wanted) {
                    long position = offset + copied;
                    int hit = readCached(this, position, buffer, copied, wanted - copied);
                    if (hit == 0) {
                        break;
                    }
                    if (hit > 0) {
                        copied += hit;
                        continue;
                    }
                    // Only misses serialize, and only on this reader's source/transfer buffer.
                    synchronized (ioLock) {
                        hit = readCached(this, position, buffer, copied, wanted - copied);
                        if (hit == 0) {
                            break;
                        }
                        if (hit > 0) {
                            copied += hit;
                            continue;
                        }
                        Source input = demandSource();
                        byte[] transfer;
                        synchronized (control) {
                            ensureReaderOpen();
                            if (transferBuffer == null) {
                                transferBuffer = new byte[blockSize];
                            }
                            transfer = transferBuffer;
                        }
                        long block = position / blockSize;
                        int from = (int) (position % blockSize);
                        int expected = demandLength(block, from, wanted - copied);
                        Fetch fetched = fetch(input, position, expected, transfer, null, this);
                        publish(block, from, fetched, transfer, null, generation, this);
                        System.arraycopy(transfer, 0, buffer, copied, fetched.length);
                        copied += fetched.length;
                        if (fetched.eof) {
                            break;
                        }
                    }
                }
                ensureReaderOpen();
                return copied;
            } finally {
                synchronized (control) {
                    foregroundRequests--;
                    revision++;
                    control.notifyAll();
                }
            }
        }

        private Source demandSource() throws IOException {
            synchronized (control) {
                ensureReaderOpen();
                if (source != null) {
                    return source;
                }
            }
            Source opened = factory.open();
            synchronized (control) {
                if (!closed && !readerClosed) {
                    source = opened;
                    return opened;
                }
            }
            throw closeResource(opened, new IOException("Reader closed while opening source"));
        }

        /**
         * Cancels only this reader, without waiting for its I/O lock. Call off the UI thread:
         * source cleanup may perform I/O. A pending open cleans up its own late source before the
         * read fails; close does not wait for that open or permit it to install/publish afterward.
         */
        @Override
        public void close() throws IOException {
            Source detached;
            synchronized (control) {
                if (readerClosed) {
                    return;
                }
                readerClosed = true;
                detached = source;
                source = null;
                transferBuffer = null;
                readers.remove(this);
            }
            IOException failure = closeResource(detached, null);
            if (failure != null) {
                throw failure;
            }
        }

        private void ensureReaderOpen() throws IOException {
            ensureOpen();
            if (readerClosed) {
                throw new IOException("Reader is closed");
            }
        }
    }

    /**
     * No source I/O or worker join. Overlapping updates (including seeks) retain useful
     * in-flight reads of the immutable source; only their latest-window intersection is published.
     * Disabling speculation or moving to a disjoint window invalidates unfinished reads permanently.
     * While an explicit preview is set, playback state is recorded but not applied until cleared.
     */
    public void updatePlayback(long positionMs, long durationMs, boolean activeAndBuffered) {
        synchronized (control) {
            if (closed) {
                return;
            }
            playbackPositionMs = positionMs;
            playbackDurationMs = durationMs;
            playbackActive = activeAndBuffered;
            if (cachingEnabled && !seekPreview) {
                updateWindow(positionMs, durationMs, activeAndBuffered, bytesPerSide, false);
            }
        }
    }

    /**
     * Sets a nearest-first byte-warming hint, including while paused; never performs I/O or joins.
     * The size/duration estimate is not a frame index: libVLC still owns demux and I/P/B decoding.
     * Each changed preview window cancels unfinished speculation, retaining useful published data.
     * Invalid duration/unknown size disables warming but keeps the override until clearSeekPreview.
     */
    public void updateSeekPreview(long targetMs, long durationMs) {
        synchronized (control) {
            if (closed || !cachingEnabled) {
                return;
            }
            boolean entering = !seekPreview;
            seekPreview = true;
            updateWindow(targetMs, durationMs, true, previewBytesPerSide, entering);
        }
    }

    /**
     * Cancels unfinished preview work and restores the latest recorded playback state, without I/O.
     * After commit/cancel, record that position with updatePlayback before clearing; useful warmed
     * bytes remain cached, including for a paused commit. Repeated clears are harmless.
     */
    public void clearSeekPreview() {
        synchronized (control) {
            if (closed || !seekPreview) {
                return;
            }
            seekPreview = false;
            updateWindow(playbackPositionMs, playbackDurationMs, playbackActive, bytesPerSide, true);
        }
    }

    /**
     * Disabling immediately discards all resident slots and cancels unfinished publications without
     * source I/O or a worker join. Demand reads remain pass-through for retained proxy descriptors.
     * Reenabling starts empty at the latest recorded playback state, never a hidden preview hint.
     * Reads begun before either transition cannot publish in the new caching epoch.
     */
    public void setCachingEnabled(boolean enabled) {
        synchronized (memoryLock) {
            synchronized (control) {
                if (closed || cachingEnabled == enabled) {
                    return;
                }
                cachingEnabled = enabled;
                cacheGeneration++;
                seekPreview = false;
                clearEntries();
                updateWindow(playbackPositionMs, playbackDurationMs,
                        enabled && playbackActive, enabled ? bytesPerSide : 0, true);
            }
        }
    }

    private void updateWindow(long positionMs, long durationMs, boolean activeAndBuffered,
                              long limit, boolean invalidate) {
        long start = 0;
        long end = 0;
        long center = 0;
        if (size > 0 && durationMs > 0) {
            long position = Math.max(0, Math.min(positionMs, durationMs));
            center = scale(size, position, durationMs);
            long radius = Math.min(limit, scale(size, Math.min(windowMs, durationMs), durationMs));
            start = center - Math.min(center, radius);
            end = center + Math.min(size - center, radius);
        }
        long anchor = Math.min(center, Math.max(0, size - 1)) / blockSize;
        boolean enabled = activeAndBuffered && end > start;
        if (invalidate || window.start != start || window.end != end
                || window.anchor != anchor || window.enabled != enabled) {
            long generation = window.generation;
            if (invalidate || seekPreview || !enabled || !window.enabled
                    || start >= window.end || end <= window.start) {
                generation++;
            }
            window = new Window(start, end, anchor, enabled, generation);
            revision++;
            control.notifyAll();
        }
    }

    /** Currently initialized, readable payload (not allocated slot storage). */
    public long cachedBytes() {
        synchronized (memoryLock) {
            return cached;
        }
    }

    /** Retained slot byte-array storage only; excludes metadata and readers' transfer buffers. */
    public long allocatedBytes() {
        synchronized (memoryLock) {
            long allocated = 0;
            for (Entry entry : slots) {
                if (entry != null && entry.data != null) {
                    allocated += entry.data.length;
                }
            }
            return allocated;
        }
    }

    /** Cumulative successfully published speculative bytes, excluding discarded/duplicate fetches. */
    public long prefetchedBytes() {
        synchronized (memoryLock) {
            return prefetched;
        }
    }

    /**
     * Discards resident slots before cancelling sources and joining the worker, even if close fails.
     * Call off the UI thread. Adapters must support concurrent close/read cancellation or bounded
     * I/O timeouts; a speculative factory.open must also terminate for the worker join to finish.
     * Demand opens are not joined: their readers reject and clean up late sources on the demand
     * thread, as described by Reader.close.
     */
    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            Reader[] closing;
            synchronized (memoryLock) {
                synchronized (control) {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    clearEntries();
                    closing = readers.toArray(new Reader[0]);
                    // Source close can perform network I/O; do not interrupt cleanup already underway.
                    if (!workerClosingSource) {
                        worker.interrupt();
                    }
                    control.notifyAll();
                }
            }
            IOException failure = null;
            for (Reader reader : closing) {
                failure = closeResource(reader, failure);
            }
            failure = closeResource(detachPrefetchSource(), failure);
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            failure = merge(failure, workerCloseFailure);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private int readCached(Reader reader, long position, byte[] buffer, int offset, int count)
            throws IOException {
        synchronized (memoryLock) {
            reader.ensureReaderOpen();
            Entry entry = entries.get(position / blockSize);
            int from = (int) (position % blockSize);
            if (entry == null || from < entry.from) {
                return -1;
            }
            if (from >= entry.from + entry.length) {
                return entry.eof ? 0 : -1;
            }
            int amount = Math.min(count, entry.from + entry.length - from);
            System.arraycopy(entry.data, from, buffer, offset, amount);
            entry.foreground = true;
            entry.access = ++access;
            return amount;
        }
    }

    private int demandLength(long block, int from, int count) {
        synchronized (memoryLock) {
            int to = from + Math.min(count, blockLength(block * blockSize) - from);
            Entry entry = entries.get(block);
            if (entry != null && from < entry.from) {
                to = Math.min(to, entry.from);
            }
            return to - from;
        }
    }

    private Fetch fetch(Source source, long start, int count, byte[] buffer, Window target,
                        Reader reader)
            throws IOException {
        int total = 0;
        // The original block-sized request stays bounded even while playback keeps advancing.
        while (total < count) {
            ensureOpen();
            if (reader != null) {
                reader.ensureReaderOpen();
            }
            if (target != null && !awaitSpeculation(target, start, count)) {
                return null;
            }
            int amount = source.read(start + total, buffer, total, count - total);
            if (amount == -1) {
                return new Fetch(total, true);
            }
            if (amount <= 0 || amount > count - total) {
                throw new IOException("Source made no progress or returned an invalid count");
            }
            total += amount;
        }
        return new Fetch(total, size >= 0 && start + total == size);
    }

    private boolean awaitSpeculation(Window target, long start, int count) throws IOException {
        synchronized (control) {
            while (useful(target, start, count) && foregroundRequests > 0) {
                try {
                    control.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Prefetch interrupted", exception);
                }
            }
            return useful(target, start, count);
        }
    }

    private boolean useful(Window target, long start, int count) {
        return !closed && window.enabled && window.generation == target.generation
                && start < window.end && window.start - start < count;
    }

    private void prefetchLoop() {
        byte[] buffer = new byte[blockSize];
        long retryAt = 0;
        try {
            while (!closed) {
                try {
                    Window target;
                    long version;
                    synchronized (control) {
                        while (!closed) {
                            long delay = retryAt == 0 ? 0 : retryAt - System.nanoTime();
                            if (window.enabled && foregroundRequests == 0 && delay <= 0) {
                                break;
                            }
                            if (window.enabled && foregroundRequests == 0 && delay > 0) {
                                TimeUnit.NANOSECONDS.timedWait(control, delay);
                            } else {
                                control.wait();
                            }
                        }
                        if (closed) {
                            return;
                        }
                        target = window;
                        version = revision;
                    }
                    Candidate candidate = nextCandidate(target);
                    if (candidate == null) {
                        synchronized (control) {
                            if (!closed && revision == version) {
                                control.wait();
                            }
                        }
                        continue;
                    }
                    if (!awaitSpeculation(target, candidate.block * blockSize + candidate.from,
                            candidate.length)) {
                        continue;
                    }
                    Source source = speculativeSource();
                    if (source == null) {
                        return;
                    }
                    Fetch fetched = fetch(source, candidate.block * blockSize + candidate.from,
                            candidate.length, buffer, target, null);
                    if (fetched != null) {
                        publish(candidate.block, candidate.from, fetched, buffer, target, 0, null);
                    }
                } catch (IOException exception) {
                    retryAt = System.nanoTime() + RETRY_NANOS;
                    IOException cleanup = closeWorkerSource(detachPrefetchSource());
                    if (closed) {
                        workerCloseFailure = merge(workerCloseFailure, cleanup);
                    }
                } catch (InterruptedException exception) {
                    if (closed) {
                        return;
                    }
                }
            }
        } finally {
            workerCloseFailure = merge(workerCloseFailure, closeWorkerSource(detachPrefetchSource()));
        }
    }

    private Source speculativeSource() throws IOException {
        synchronized (control) {
            if (closed) {
                return null;
            }
            if (prefetchSource != null) {
                return prefetchSource;
            }
        }
        Source opened = factory.open();
        synchronized (control) {
            if (!closed) {
                prefetchSource = opened;
                return opened;
            }
        }
        workerCloseFailure = merge(workerCloseFailure, closeWorkerSource(opened));
        return null;
    }

    private IOException closeWorkerSource(Source source) {
        if (source == null) {
            return null;
        }
        boolean interrupted;
        synchronized (control) {
            workerClosingSource = true;
            // Consume only our shutdown interrupt while protocol cleanup needs to wait.
            interrupted = closed && Thread.interrupted();
        }
        try {
            return closeResource(source, null);
        } finally {
            synchronized (control) {
                workerClosingSource = false;
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private Source detachPrefetchSource() {
        synchronized (control) {
            Source source = prefetchSource;
            prefetchSource = null;
            return source;
        }
    }

    private Candidate nextCandidate(Window target) {
        synchronized (memoryLock) {
            if (closed || window != target) {
                return null;
            }
            prune(target);
            long first = target.start / blockSize;
            long last = (target.end - 1) / blockSize;
            long distance = Math.max(last - target.anchor, target.anchor - first);
            for (long i = 0; i <= distance; i++) {
                if (i <= last - target.anchor) {
                    Candidate forward = missing(target.anchor + i, target);
                    if (forward != null) {
                        return forward;
                    }
                }
                if (i < target.anchor - first) {
                    Candidate backward = missing(target.anchor - i - 1, target);
                    if (backward != null) {
                        return backward;
                    }
                }
            }
            return null;
        }
    }

    private Candidate missing(long block, Window target) {
        long start = block * blockSize;
        int from = (int) Math.max(0, target.start - start);
        int to = (int) Math.min(blockLength(start), target.end - start);
        Entry entry = entries.get(block);
        if (entry != null) {
            if (entry.covers(from, to)) {
                return null;
            }
            if (from < entry.from) {
                to = Math.min(to, entry.from);
            } else {
                from = Math.max(from, entry.from + entry.length);
            }
        }
        return new Candidate(block, from, to - from);
    }

    private void publish(long block, int from, Fetch fetched, byte[] buffer, Window target,
                         long generation, Reader reader)
            throws IOException {
        synchronized (memoryLock) {
            synchronized (control) {
                if (target != null && closed) {
                    return;
                }
                ensureOpen();
                if (reader != null) {
                    reader.ensureReaderOpen();
                }
                if (!cachingEnabled || (target == null ? generation != cacheGeneration
                        : !window.enabled || window.generation != target.generation)) {
                    return;
                }
                Window current = window;
                prune(current);
                Entry previous = entries.get(block);
                if (previous != null && previous.covers(from, from + fetched.length)
                        && (!fetched.eof || previous.eof)) {
                    if (target == null) {
                        previous.foreground = true;
                        previous.access = ++access;
                    }
                    return;
                }
                int slot = previous != null ? previous.slot : availableSlot(block, current);
                Entry entry = new Entry(block, slot, from, fetched.length, fetched.eof,
                        target == null, ++access);
                if (target != null) {
                    entry = clip(entry, current);
                    if (entry == null) {
                        return;
                    }
                }
                int added = entry.length;
                if (previous != null) {
                    added -= Math.max(0, Math.min(entry.from + entry.length,
                            previous.from + previous.length) - Math.max(entry.from, previous.from));
                    entry = combine(previous, entry);
                }
                remove(slots[slot]);
                if (entry.length > 0) {
                    entry.data = previous != null ? previous.data : null;
                    if (entry.data == null) {
                        entry.data = new byte[blockSize];
                    }
                    int copyFrom = Math.max(from, entry.from);
                    int copyTo = Math.min(from + fetched.length, entry.from + entry.length);
                    if (copyTo > copyFrom) {
                        System.arraycopy(buffer, copyFrom - from, entry.data, copyFrom,
                                copyTo - copyFrom);
                    }
                }
                slots[slot] = entry;
                entries.put(block, entry);
                cached += entry.length;
                if (target != null) {
                    prefetched += Math.min(Long.MAX_VALUE - prefetched, added);
                }
            }
        }
    }

    private Entry combine(Entry previous, Entry entry) {
        int previousEnd = previous.from + previous.length;
        int end = entry.from + entry.length;
        if (previousEnd < entry.from || end < previous.from) {
            return entry;
        }
        int from = Math.min(previous.from, entry.from);
        int to = Math.max(previousEnd, end);
        boolean eof = (previous.eof && previousEnd == to) || (entry.eof && end == to);
        return new Entry(entry.block, entry.slot, from, to - from, eof,
                previous.foreground || entry.foreground, entry.access);
    }

    private void prune(Window target) {
        int outside = 0;
        for (Entry entry : slots) {
            if (entry != null && !inside(entry.block, target)) {
                if (!entry.foreground) {
                    remove(entry);
                } else {
                    outside++;
                }
            } else if (entry != null && !entry.foreground) {
                Entry clipped = clip(entry, target);
                if (clipped != entry) {
                    remove(entry);
                    if (clipped != null) {
                        slots[clipped.slot] = clipped;
                        entries.put(clipped.block, clipped);
                        cached += clipped.length;
                    }
                }
            }
        }
        while (outside-- > foregroundBlocks) {
            remove(oldest(target, true));
        }
    }

    private Entry clip(Entry entry, Window target) {
        long start = entry.block * blockSize;
        if (!inside(entry.block, target) || entry.from >= target.end - start) {
            return null;
        }
        int from = (int) Math.max(entry.from, target.start - start);
        int to = (int) Math.min(entry.from + entry.length, target.end - start);
        boolean eof = entry.eof && to == entry.from + entry.length;
        if (from >= to && !eof) {
            return null;
        }
        int length = Math.max(0, to - from);
        if (from == entry.from && length == entry.length && eof == entry.eof) {
            return entry;
        }
        Entry clipped = new Entry(entry.block, entry.slot, from, length, eof,
                entry.foreground, entry.access);
        clipped.data = length > 0 ? entry.data : null;
        return clipped;
    }

    private int availableSlot(long block, Window target) {
        int outside = 0;
        int free = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                if (free < 0) {
                    free = i;
                }
            } else if (!inside(slots[i].block, target)) {
                outside++;
            }
        }
        Entry victim = oldest(target, true);
        if (!inside(block, target) && outside >= foregroundBlocks) {
            return victim.slot;
        }
        if (free >= 0) {
            return free;
        }
        return (victim != null ? victim : oldest(target, false)).slot;
    }

    private Entry oldest(Window target, boolean outsideOnly) {
        Entry result = null;
        for (Entry entry : slots) {
            if (entry != null && (!outsideOnly || !inside(entry.block, target))
                    && (result == null || entry.access < result.access)) {
                result = entry;
            }
        }
        return result;
    }

    private boolean inside(long block, Window target) {
        long start = block * blockSize;
        return start < target.end && target.start - start < blockLength(start);
    }

    private void remove(Entry entry) {
        if (entry != null) {
            entries.remove(entry.block);
            slots[entry.slot] = null;
            cached -= entry.length;
        }
    }

    private void clearEntries() {
        entries.clear();
        Arrays.fill(slots, null);
        cached = 0;
    }

    private int blockLength(long start) {
        return (int) Math.min(blockSize, (size >= 0 ? size : Long.MAX_VALUE) - start);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Cache is closed");
        }
    }


    private static IOException closeResource(Closeable resource, IOException failure) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException exception) {
                return merge(failure, exception);
            }
        }
        return failure;
    }

    private static IOException merge(IOException failure, IOException next) {
        if (failure == null) {
            return next;
        }
        if (next != null && next != failure) {
            failure.addSuppressed(next);
        }
        return failure;
    }

    private static long scale(long value, long numerator, long denominator) {
        return BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator)).longValue();
    }

    private static final class Window {
        final long start;
        final long end;
        final long anchor;
        final boolean enabled;
        final long generation;

        Window(long start, long end, long anchor, boolean enabled, long generation) {
            this.start = start;
            this.end = end;
            this.anchor = anchor;
            this.enabled = enabled;
            this.generation = generation;
        }
    }

    private static final class Candidate {
        final long block;
        final int from;
        final int length;

        Candidate(long block, int from, int length) {
            this.block = block;
            this.from = from;
            this.length = length;
        }
    }

    private static final class Fetch {
        final int length;
        final boolean eof;

        Fetch(int length, boolean eof) {
            this.length = length;
            this.eof = eof;
        }
    }

    private static final class Entry {
        final long block;
        final int slot;
        final int from;
        final int length;
        final boolean eof;
        byte[] data;
        boolean foreground;
        long access;

        Entry(long block, int slot, int from, int length, boolean eof, boolean foreground, long access) {
            this.block = block;
            this.slot = slot;
            this.from = from;
            this.length = length;
            this.eof = eof;
            this.foreground = foreground;
            this.access = access;
        }

        boolean covers(int start, int end) {
            return from <= start && (from + length >= end || eof);
        }
    }
}