package com.example.big.playback;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Readback is test-only; the production video path never requests a snapshot. */
public final class VideoSnapshot {
    public static Bitmap capture(SurfaceView source, int width, int height) {
        if (!source.getHolder().getSurface().isValid()) {
            return null;
        }
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        HandlerThread thread = new HandlerThread("video-test-snapshot");
        thread.start();
        CountDownLatch copied = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(-1);
        try {
            PixelCopy.request(source, image, code -> {
                result.set(code);
                copied.countDown();
            }, new Handler(thread.getLooper()));
            if (!copied.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out copying real video surface");
            }
            if (result.get() == PixelCopy.ERROR_SOURCE_NO_DATA) {
                image.recycle();
                return null;
            }
            if (result.get() != PixelCopy.SUCCESS) {
                image.recycle();
                throw new AssertionError("Unable to copy real video surface: " + result.get());
            }
            return image;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } finally {
            thread.quitSafely();
        }
    }
}