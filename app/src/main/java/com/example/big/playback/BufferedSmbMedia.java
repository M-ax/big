package com.example.big.playback;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;

import com.example.big.smb.JcifsSmbClient;
import com.example.big.smb.SmbLocation;
import com.example.big.smb.SmbSession;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A seekable local descriptor backed by a private, bounded SMB memory cache. */
public final class BufferedSmbMedia implements Closeable {
    private final SeekWindowCache cache;
    private final Set<ProxyReader> readers = new HashSet<>();
    private final ParcelFileDescriptor descriptor;
    private final StorageManager storage;
    private boolean closed;

    public static BufferedSmbMedia open(Context context, SmbSession session, Uri uri) throws IOException {
        SmbLocation folder = SmbLocation.parse(uri.toString()).parent();
        if (folder == null || uri.getLastPathSegment() == null) {
            throw new IOException("Invalid media location");
        }
        SmbLocation location = folder.child(uri.getLastPathSegment(), false);
        SeekWindowCache cache = new SeekWindowCache(() -> {
            JcifsSmbClient client = (JcifsSmbClient) session.newClient();
            try {
                return client.openSource(location);
            } catch (IOException | RuntimeException exception) {
                client.close();
                throw exception;
            }
        });
        return new BufferedSmbMedia(context, cache);
    }

    BufferedSmbMedia(Context context, SeekWindowCache cache) throws IOException {
        this.cache = cache;
        try {
            storage = context.getApplicationContext().getSystemService(StorageManager.class);
            descriptor = openDescriptor(null);
        } catch (IOException | RuntimeException exception) {
            try {
                cache.close();
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    public ParcelFileDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Caller-owned reader with its own callback thread and lazy source, sharing only the byte cache.
     * dup() is not sufficient: Android's frame extractor uses lseek/read and would reposition VLC's
     * descriptor too. A slow preview read must not block even an uncached playback read. Creation
     * performs no source I/O; this reader is valid until it or the owning BufferedSmbMedia is closed.
     */
    public synchronized ParcelFileDescriptor openIndependentDescriptor() throws IOException {
        if (closed) throw new IOException("Media reader closed");
        return openDescriptor(cache.openReader());
    }

    private synchronized ParcelFileDescriptor openDescriptor(SeekWindowCache.Reader reader) throws IOException {
        ProxyReader proxy = new ProxyReader(reader);
        readers.add(proxy);
        try {
            proxy.thread.start();
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    proxy, new Handler(proxy.thread.getLooper()));
        } catch (IOException | RuntimeException exception) {
            try {
                proxy.close();
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    private final class ProxyReader extends ProxyFileDescriptorCallback implements Closeable {
        private final SeekWindowCache.Reader reader;
        private final HandlerThread thread;
        private boolean released;

        ProxyReader(SeekWindowCache.Reader reader) {
            this.reader = reader;
            thread = new HandlerThread(reader == null ? "smb-playback-reader" : "smb-preview-reader");
        }

        @Override
        public long onGetSize() {
            return cache.length();
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            try {
                return reader == null ? cache.read(offset, data, size) : reader.read(offset, data, size);
            } catch (IOException | RuntimeException exception) {
                throw new ErrnoException("SMB read", OsConstants.EIO);
            }
        }

        @Override
        public void onRelease() {
            try {
                close();
            } catch (IOException ignored) {
                // Release has no error channel, and must still stop this reader's callback thread.
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (BufferedSmbMedia.this) {
                if (released) return;
                released = true;
                readers.remove(this);
            }
            try {
                if (reader != null) reader.close();
            } finally {
                thread.quitSafely();
            }
        }
    }

    public void updatePlayback(long positionMs, long durationMs, boolean activeAndBuffered) {
        cache.updatePlayback(positionMs, durationMs, activeAndBuffered);
    }

    /** Disabling discards resident bytes without closing the descriptor or waiting for network I/O. */
    public void setCachingEnabled(boolean enabled) {
        cache.setCachingEnabled(enabled);
    }

    /** Bounded byte-warming hint, also while paused; does not select or decode media frames. */
    public void updateSeekPreview(long targetMs, long durationMs) {
        cache.updateSeekPreview(targetMs, durationMs);
    }

    /** Restore playback warming after recording the committed/cancelled position with updatePlayback. */
    public void clearSeekPreview() {
        cache.clearSeekPreview();
    }

    public long cachedBytes() {
        return cache.cachedBytes();
    }

    public long prefetchedBytes() {
        return cache.prefetchedBytes();
    }

    @Override
    public void close() throws IOException {
        List<ProxyReader> closing;
        synchronized (this) {
            if (closed) return;
            closed = true;
            closing = new ArrayList<>(readers);
        }
        IOException failure = null;
        try {
            descriptor.close();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            cache.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } finally {
            for (ProxyReader reader : closing) {
                try {
                    reader.close();
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
