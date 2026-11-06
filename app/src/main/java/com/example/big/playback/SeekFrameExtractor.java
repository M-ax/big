package com.example.big.playback;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded still-frame decoding for Android-supported media, separate from playback.
 * Requests use OPTION_CLOSEST, not sync-frame seeking; Android chooses the closest decoded PTS.
 * No frame-index table is built, and nominal-rate neighbors are only timestamp hints for VFR.
 * Native opening, decoding, callbacks and release occur on one worker, never the caller.
 * Closing immediately discards cached bitmap references, even if native decoding is blocked.
 */
public final class SeekFrameExtractor implements Closeable {
    private static final long MAX_CACHED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CACHED_FRAMES = 64;
    private static final int[] NEIGHBORS = {1, -1, 2, -2};

    public interface Listener {
        /**
         * Software, immutable ARGB_8888, shared with the cache; do not mutate or recycle it.
         * Marshal to the UI and check request/media generation there: dispatch already in flight
         * cannot be recalled by cancel/close. Callbacks run without holding the request lock.
         */
        void onFrame(long requestId, long targetMs, Bitmap bitmap);

        /** Unsupported sources and individual decode failures permit the playback fallback. */
        void onUnavailable(long requestId);
    }

    private final Object lock = new Object();
    private final Listener listener;
    private final SourceFactory factory;
    private final ThreadPoolExecutor worker;
    private final LinkedHashMap<CacheKey, Bitmap> cache = new LinkedHashMap<>(16, .75f, true);
    private Request latest;
    private Request pending;
    private boolean scheduled;
    private boolean closed;

    // Only the worker accesses native resources; cache entries are protected by lock.
    private ParcelFileDescriptor ownedDescriptor;
    private FrameSource source;
    private boolean initialized;
    private long durationMs;
    private volatile double frameRate;
    private volatile long extractionCount;
    private volatile long cachedBytes;

    /**
     * Ownership of an optional, already-duplicated descriptor transfers to this instance.
     * With no descriptor, only file/content URIs are opened, using the application context.
     * SMB must supply its private seekable proxy descriptor; URLs are never passed to Android.
     * Initialization is asynchronous; a failed open remains unavailable until close.
     */
    public SeekFrameExtractor(Context context, Uri uri, ParcelFileDescriptor descriptor, Listener listener) {
        this(descriptor, listener, sourceFactory(context, uri, descriptor));
    }

    SeekFrameExtractor(Listener listener, SourceFactory factory) {
        this(null, listener, factory);
    }

    private SeekFrameExtractor(ParcelFileDescriptor descriptor, Listener listener, SourceFactory factory) {
        this.listener = Objects.requireNonNull(listener);
        this.factory = Objects.requireNonNull(factory);
        ownedDescriptor = descriptor;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "seek-frame-extractor");
            thread.setDaemon(true);
            return thread;
        });
        synchronized (lock) {
            scheduleLocked();
        }
    }

    /** Replace the pending demand without waiting for native work. Supply a unique request ID. */
    public void request(long requestId, long targetMs, int width, int height, boolean prefetchNeighbors) {
        synchronized (lock) {
            if (closed) return;
            latest = new Request(requestId, new CacheKey(targetMs, width, height), prefetchNeighbors);
            pending = latest;
            scheduleLocked();
        }
    }

    /** Invalidate demand and speculation without discarding reusable frames or waiting. */
    public void cancel() {
        synchronized (lock) {
            latest = null;
            pending = null;
        }
    }

    /** Nonblocking and idempotent; native resources close after the current native call returns. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            latest = null;
            pending = null;
            clearCache();
            scheduleLocked();
        }
    }

    /** Zero until known. Capture rate or count/duration is nominal, not a guaranteed VFR grid. */
    public double getFrameRate() {
        return frameRate;
    }

    /** Native frame calls, including failed or subsequently superseded calls; excludes cache hits. */
    public long getExtractionCount() {
        return extractionCount;
    }

    /** Retained bitmap allocation bytes, excluding frames independently retained by consumers. */
    public long getCachedBytes() {
        return cachedBytes;
    }

    private void scheduleLocked() {
        if (scheduled) return;
        scheduled = true;
        worker.execute(this::drain);
    }

    private boolean isCurrent(Request request) {
        synchronized (lock) {
            return !closed && latest == request;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void drain() {
        initialize();
        while (true) {
            Request request;
            synchronized (lock) {
                if (closed) break;
                request = pending;
                pending = null;
                if (request == null) {
                    scheduled = false;
                    return;
                }
            }
            Bitmap bitmap = decode(request, request.key);
            if (!isCurrent(request)) continue;
            try {
                if (bitmap == null) listener.onUnavailable(request.id);
                else listener.onFrame(request.id, request.key.targetMs, bitmap);
            } catch (RuntimeException ignored) {
                // A consumer must not prevent subsequent demands or resource release.
            }
            if (bitmap != null && request.prefetch && frameRate > 0) {
                // Round absolute nominal-grid times, not offsets from an already-rounded demand.
                // For off-grid/VFR demands these are hints only; never alias the demand cache key.
                long center = Math.round(request.key.targetMs * frameRate / 1000);
                for (int offset : NEIGHBORS) {
                    if (!isCurrent(request)) break;
                    long targetMs = Math.round((center + offset) * 1000. / frameRate);
                    if (targetMs == request.key.targetMs) continue;
                    decode(request, new CacheKey(targetMs, request.key.width, request.key.height));
                }
            }
        }
        try {
            releaseResources();
        } finally {
            worker.shutdown();
        }
    }

    private void initialize() {
        if (initialized || isClosed()) return;
        initialized = true;
        try {
            source = factory.open();
            if (isClosed()) return;
            durationMs = positiveLong(metadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            double rate = positiveRate(metadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
            if (rate == 0 && durationMs > 0) {
                long frames = positiveLong(metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                rate = frames * 1000. / durationMs;
            }
            frameRate = validRate(rate) ? rate : 0;
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            releaseResources();
        }
    }

    private Bitmap decode(Request request, CacheKey key) {
        if (!isCurrent(request) || source == null || key.width <= 0 || key.height <= 0
                || key.targetMs < 0 || key.targetMs > Long.MAX_VALUE / 1000
                || (durationMs > 0 && key.targetMs >= durationMs)) return null;
        Bitmap bitmap;
        synchronized (lock) {
            if (!isCurrent(request)) return null;
            bitmap = cache.get(key);
        }
        if (bitmap != null) return bitmap;
        try {
            if (!isCurrent(request)) return null;
            extractionCount++;
            bitmap = source.getFrameAtTime(key.targetMs * 1000, key.width, key.height);
            if (bitmap == null || !isCurrent(request)) return null;
            if (bitmap.getWidth() > key.width || bitmap.getHeight() > key.height) {
                double scale = Math.min((double) key.width / bitmap.getWidth(), (double) key.height / bitmap.getHeight());
                bitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (bitmap.getWidth() * scale)),
                        Math.max(1, (int) (bitmap.getHeight() * scale)), true);
            }
            if (bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (bitmap == null || !isCurrent(request)) return null;
            cache(key, bitmap);
            return bitmap;
        } catch (RuntimeException ignored) {
            return null;
        } catch (OutOfMemoryError ignored) {
            clearCache();
            return null;
        }
    }

    private void cache(CacheKey key, Bitmap bitmap) {
        synchronized (lock) {
            if (closed) return;
            long bytes = bitmap.getAllocationByteCount();
            if (bytes > MAX_CACHED_BYTES) return;
            Iterator<Map.Entry<CacheKey, Bitmap>> entries = cache.entrySet().iterator();
            while (cachedBytes + bytes > MAX_CACHED_BYTES || cache.size() >= MAX_CACHED_FRAMES) {
                cachedBytes -= entries.next().getValue().getAllocationByteCount();
                entries.remove();
            }
            cache.put(key, bitmap);
            cachedBytes += bytes;
        }
    }

    private void clearCache() {
        // GL may still be uploading an evicted frame. GC, not recycle(), owns its lifetime.
        synchronized (lock) {
            cache.clear();
            cachedBytes = 0;
        }
    }

    private void releaseResources() {
        clearCache();
        closeQuietly(source);
        source = null;
        closeQuietly(ownedDescriptor);
        ownedDescriptor = null;
    }

    private String metadata(int key) {
        try {
            return source.extractMetadata(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long positiveLong(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double positiveRate(String value) {
        if (value == null) return 0;
        try {
            double rate = Double.parseDouble(value);
            return validRate(rate) ? rate : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean validRate(double rate) {
        return Double.isFinite(rate) && rate > 0 && rate <= 1000;
    }

    private static SourceFactory sourceFactory(Context context, Uri uri, ParcelFileDescriptor descriptor) {
        Context application = context.getApplicationContext();
        return () -> openSource(application, uri, descriptor);
    }

    private static FrameSource openSource(Context context, Uri uri, ParcelFileDescriptor descriptor) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (descriptor != null) {
                retriever.setDataSource(descriptor.getFileDescriptor());
            } else if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
                try (AssetFileDescriptor asset = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (asset == null) throw new IOException("Unavailable media descriptor");
                    if (asset.getDeclaredLength() < 0) retriever.setDataSource(asset.getFileDescriptor());
                    else retriever.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getDeclaredLength());
                }
            } else if (uri != null && "file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null
                    && (uri.getAuthority() == null || uri.getAuthority().isEmpty())) {
                File file = new File(uri.getPath());
                if (!file.isAbsolute()) throw new IOException("Unsupported media source");
                try (ParcelFileDescriptor local = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    retriever.setDataSource(local.getFileDescriptor());
                }
            } else {
                throw new IOException("Unsupported media source");
            }
            return new FrameSource() {
                private final MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();

                @Override
                public String extractMetadata(int key) {
                    return retriever.extractMetadata(key);
                }

                @Override
                public Bitmap getFrameAtTime(long timeUs, int width, int height) {
                    params.setPreferredConfig(Bitmap.Config.ARGB_8888);
                    return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height, params);
                }

                @Override
                public void close() throws IOException {
                    retriever.close();
                }
            };
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            closeQuietly(retriever);
            throw exception;
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Do not expose source paths, provider messages or remote credentials in logs.
        }
    }

    interface SourceFactory {
        FrameSource open() throws IOException;
    }

    interface FrameSource extends Closeable {
        String extractMetadata(int key);
        Bitmap getFrameAtTime(long timeUs, int width, int height);
    }

    private static final class Request {
        final long id;
        final CacheKey key;
        final boolean prefetch;

        Request(long id, CacheKey key, boolean prefetch) {
            this.id = id;
            this.key = key;
            this.prefetch = prefetch;
        }
    }

    private static final class CacheKey {
        final long targetMs;
        final int width;
        final int height;

        CacheKey(long targetMs, int width, int height) {
            this.targetMs = targetMs;
            double scale = width > 0 && height > 0 ? Math.min(1, Math.min(1920. / width, 1080. / height)) : 0;
            this.width = scale > 0 ? Math.max(1, (int) (width * scale)) : 0;
            this.height = scale > 0 ? Math.max(1, (int) (height * scale)) : 0;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof CacheKey)) return false;
            CacheKey other = (CacheKey) object;
            return targetMs == other.targetMs && width == other.width && height == other.height;
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(targetMs) * 31 + width) * 31 + height;
        }
    }
}