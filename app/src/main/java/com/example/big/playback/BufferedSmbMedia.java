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

/** A seekable local descriptor backed by a private, bounded SMB memory cache. */
public final class BufferedSmbMedia implements Closeable {
    private final SeekWindowCache cache;
    private final HandlerThread readerThread;
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
        readerThread = new HandlerThread("smb-playback-reader");
        readerThread.start();
        try {
            storage = context.getApplicationContext().getSystemService(StorageManager.class);
            descriptor = openIndependentDescriptor();
        } catch (IOException | RuntimeException exception) {
            readerThread.quitSafely();
            cache.close();
            throw exception;
        }
    }

    public ParcelFileDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Caller-owned reader with an independent file position, sharing only the immutable byte cache.
     * dup() is not sufficient: Android's frame extractor uses lseek/read and would reposition VLC's
     * descriptor too. This reader is valid until it or the owning BufferedSmbMedia is closed.
     */
    public synchronized ParcelFileDescriptor openIndependentDescriptor() throws IOException {
        if (closed) throw new IOException("Media reader closed");
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                new ProxyFileDescriptorCallback() {
                    @Override
                    public long onGetSize() {
                        return cache.length();
                    }

                    @Override
                    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                        try {
                            return cache.read(offset, data, size);
                        } catch (IOException | RuntimeException exception) {
                            throw new ErrnoException("SMB read", OsConstants.EIO);
                        }
                    }

                    @Override
                    public void onRelease() {
                        // Closing one decoder's reader must not close the other decoder's cache.
                    }
                }, new Handler(readerThread.getLooper()));
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
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            descriptor.close();
        } finally {
            try {
                cache.close();
            } finally {
                readerThread.quitSafely();
            }
        }
    }
}