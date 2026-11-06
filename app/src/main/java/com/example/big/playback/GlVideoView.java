package com.example.big.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Orthographic video output; frame delivery, not display refresh, drives the filter context. */
public final class GlVideoView extends SurfaceView implements SurfaceHolder.Callback {
    public interface Listener {
        void onOutputReady();
        void onOutputLost();
        void onOutputSizeChanged();
        void onOutputError();
    }

    private Listener listener;
    private GlVideoRenderer renderer;
    private GlVideoRenderer retiring;
    private Surface input;
    private float frameRate;
    private boolean playbackActive = true;
    private int generation;
    private int outputWidth;
    private int outputHeight;
    private double displayAspect;
    private boolean cropToFill;
    private final RectF feedback = new RectF();

    public GlVideoView(Context context, AttributeSet attributes) {
        super(context, attributes);
        getHolder().addCallback(this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Surface getVideoSurface() {
        return input;
    }

    public void setFeedbackBounds(RectF bounds) {
        if (!feedback.equals(bounds)) {
            feedback.set(bounds);
            if (renderer != null) {
                renderer.setFeedback(bounds);
            }
        }
    }

    public void setVideoFrameRate(float rate) {
        rate = Float.isFinite(rate) && rate > 0 && rate <= 1000 ? rate : 0;
        if (frameRate != rate) {
            frameRate = rate;
            setRequestedFrameRate(rate);
            if (renderer != null) {
                renderer.setFrameRate(rate);
            }
        }
    }

    public void setPlaybackActive(boolean active) {
        playbackActive = active;
        if (renderer != null) {
            renderer.setPlaybackActive(active);
        }
    }

    public void requestStillFrame() {
        if (renderer != null) {
            renderer.requestStillFrame();
        }
    }

    public void requestSeekFrame() {
        if (renderer != null) renderer.requestSeekFrame();
    }

    /**
     * Supply the media's visible size and SAR, not a padded decoder or resized bitmap size.
     * swapped is true for orientations that exchange the axes. Preview pixels are already rotated;
     * VLC owns live-frame layout. Pass zero dimensions to clear geometry when changing media.
     */
    public void setVideoGeometry(int visibleWidth, int visibleHeight, int sarNum, int sarDen,
                                 boolean swapped) {
        double aspect = VideoAspect.displayAspect(visibleWidth, visibleHeight, sarNum, sarDen, swapped);
        if (displayAspect != aspect) {
            displayAspect = aspect;
            if (renderer != null) renderer.setVideoAspect(aspect, cropToFill);
        }
    }

    /** Crop VLC's best-fit picture uniformly when the requested native zoom exceeds its 10x cap. */
    public void setVideoCrop(boolean enabled) {
        if (cropToFill != enabled) {
            cropToFill = enabled;
            if (renderer != null) renderer.setVideoAspect(displayAspect, cropToFill);
        }
    }

    public void setSeekStill(Bitmap bitmap, int fit) {
        if (renderer != null) renderer.setSeekStill(bitmap, fit);
    }

    public void clearSeekStill() {
        if (renderer != null) renderer.clearSeekStill();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int currentGeneration = ++generation;
        outputWidth = getWidth();
        outputHeight = getHeight();
        renderer = new GlVideoRenderer(holder.getSurface(), outputWidth, outputHeight, false,
                getResources().getDisplayMetrics().density, new GlVideoRenderer.Listener() {
                    @Override
                    public void onReady(Surface ready) {
                        post(() -> {
                            if (generation == currentGeneration && renderer != null) {
                                input = ready;
                                renderer.setFeedback(feedback);
                                renderer.setVideoAspect(displayAspect, cropToFill);
                                renderer.setFrameRate(frameRate);
                                renderer.setPlaybackActive(playbackActive);
                                if (listener != null) {
                                    listener.onOutputReady();
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure() {
                        post(() -> {
                            if (generation == currentGeneration && listener != null) {
                                listener.onOutputError();
                            }
                        });
                    }
                });
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (width != outputWidth || height != outputHeight) {
            int currentGeneration = ++generation;
            outputWidth = width;
            outputHeight = height;
            // VLC's EGL producer may keep its original buffer geometry, including while paused.
            // Recreate it instead of stretching a stale, already-letterboxed input frame.
            releaseOutput(() -> post(() -> {
                if (generation == currentGeneration && holder.getSurface().isValid()) {
                    surfaceCreated(holder);
                }
            }));
            return;
        }
        if (listener != null) {
            listener.onOutputSizeChanged();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        generation++;
        CountDownLatch stopped = new CountDownLatch(1);
        releaseOutput(stopped::countDown);
        try {
            // The holder owns this Surface; finish EGL access before Android invalidates it.
            if (!stopped.await(2, TimeUnit.SECONDS)) {
                android.util.Log.w("VideoFilters", "Timed out releasing the video surface");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseOutput(Runnable complete) {
        input = null;
        if (listener != null) {
            listener.onOutputLost();
        }
        GlVideoRenderer previous = renderer;
        renderer = null;
        if (previous != null) {
            retiring = previous;
            previous.release(() -> {
                complete.run();
                post(() -> {
                    if (retiring == previous) {
                        retiring = null;
                    }
                });
            });
        } else if (retiring != null) {
            retiring.release(complete);
        } else {
            complete.run();
        }
    }

    public void release() {
        releaseOutput(() -> { });
        listener = null;
    }


    public long getVideoFrameCount() {
        return renderer == null ? 0 : renderer.frameCount;
    }

    public long getDrawCount() {
        return renderer == null ? 0 : renderer.drawCount;
    }

    public long getReceivedFrameCount() {
        return renderer == null ? 0 : renderer.receivedFrameCount;
    }

    /** GPU submission timing, not decoder/network latency or physical display scanout. */
    public long getLastFrameCallbackToSwapNs() {
        return renderer == null ? 0 : renderer.lastFrameCallbackToSwapNs;
    }

    public long getMaxFrameCallbackToSwapNs() {
        return renderer == null ? 0 : renderer.maxFrameCallbackToSwapNs;
    }

    public float getVideoFrameRate() {
        return frameRate;
    }

    public boolean isFeedbackRendered() {
        return !feedback.isEmpty() && renderer != null && renderer.blurActive;
    }
}