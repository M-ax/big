package com.example.big.playback;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/** A frame-available driven EGL consumer. No render timer, frame readback, or bitmap allocation. */
final class GlVideoRenderer {
    interface Listener {
        void onReady(Surface input);
        void onFailure();
    }

    private final HandlerThread thread = new HandlerThread("video-filters", Process.THREAD_PRIORITY_DISPLAY);
    private final Handler handler;
    private final Surface output;
    private final boolean ownsOutput;
    private final Listener listener;
    private final float density;
    private final float[] transform = new float[16];
    private final Runnable redraw = () -> draw(false);
    private final RectF label = new RectF();
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private VideoFrameShader shader;
    private SurfaceTexture frames;
    private Surface input;
    private int texture;
    private final int width;
    private final int height;
    private boolean hasFrame;
    private boolean playbackActive = true;
    private int pendingStillFrames;
    private boolean failed;
    private volatile boolean closing;
    private final List<Runnable> releaseCallbacks = new ArrayList<>();
    private boolean released;
    volatile long frameCount;
    volatile long receivedFrameCount;
    volatile long drawCount;
    volatile long frameTimestamp;
    volatile boolean blurActive;
    volatile long lastFrameCallbackToSwapNs;
    volatile long maxFrameCallbackToSwapNs;
    private long seekRefreshUntilNs;
    private boolean seekStill;
    private boolean resumeFromStill;

    GlVideoRenderer(Surface output, int width, int height, float density, Listener listener) {
        this(output, width, height, true, density, listener);
    }

    GlVideoRenderer(Surface output, int width, int height, boolean ownsOutput, float density, Listener listener) {
        this.output = output;
        this.ownsOutput = ownsOutput;
        this.width = width;
        this.height = height;
        this.density = density;
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::initialize);
    }

    private void initialize() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            require(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0));
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0) && count[0] > 0);
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(context != EGL14.EGL_NO_CONTEXT);
            window = EGL14.eglCreateWindowSurface(display, configs[0], output, new int[]{EGL14.EGL_NONE}, 0);
            require(window != EGL14.EGL_NO_SURFACE);
            require(EGL14.eglMakeCurrent(display, window, window, context));
            // VLC already paces the producer. A second vsync wait would halve throughput on some devices.
            EGL14.eglSwapInterval(display, 0);
            shader = new VideoFrameShader();
            int[] names = new int[1];
            GLES20.glGenTextures(1, names, 0);
            texture = names[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            frames = new SurfaceTexture(texture);
            frames.setDefaultBufferSize(width, height);
            frames.setOnFrameAvailableListener(source -> draw(true), handler);
            input = new Surface(frames);
            if (!closing) {
                listener.onReady(input);
                draw(false);
            }
        } catch (RuntimeException exception) {
            android.util.Log.e("VideoFilters", "Unable to initialize video filters", exception);
            fail();
        }
    }


    void setVideoAspect(double aspect, boolean cropToFill) {
        handler.post(() -> {
            if (!closing && shader != null) {
                shader.setVideoAspect(aspect, cropToFill);
                requestRedraw();
            }
        });
    }

    void setFeedback(RectF bounds) {
        RectF snapshot = new RectF(bounds);
        handler.post(() -> {
            if (!closing && !label.equals(snapshot)) {
                label.set(snapshot);
                requestRedraw();
            }
        });
    }

    void setFrameRate(float rate) {
        handler.post(() -> {
            if (!closing && !failed) {
                output.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            }
        });
    }

    void setPlaybackActive(boolean active) {
        handler.post(() -> {
            playbackActive = active;
            if (active && seekStill) resumeFromStill = true;
        });
    }

    void setSeekStill(Bitmap bitmap, int fit) {
        handler.post(() -> {
            if (closing || failed || shader == null) return;
            shader.setStill(bitmap, fit);
            seekStill = true;
            resumeFromStill = false;
            draw(false);
            frameCount++;
        });
    }

    void clearSeekStill() {
        handler.post(() -> {
            if (closing || failed || shader == null) return;
            shader.clearStill();
            seekStill = false;
            resumeFromStill = false;
            requestRedraw();
        });
    }

    void requestStillFrame() {
        handler.post(() -> {
            if (!closing && !playbackActive) {
                // Drain the producer's queued buffers after geometry/seek changes, then freeze.
                pendingStillFrames = 3;
                requestRedraw();
            }
        });
    }

    private void requestRedraw() {
        handler.removeCallbacks(redraw);
        handler.post(redraw);
    }

    void requestSeekFrame() {
        handler.post(() -> {
            // Decoder completion can follow paused producer refreshes; do not spend a three-buffer budget on them.
            seekRefreshUntilNs = System.nanoTime() + 1_000_000_000L;
        });
    }

    private void draw(boolean newFrame) {
        if (closing || failed || shader == null) {
            return;
        }
        long arrival = newFrame ? System.nanoTime() : 0;
        try {
            if (newFrame) {
                receivedFrameCount++;
                frames.updateTexImage();
                long timestamp = frames.getTimestamp();
                // A slow consumer may receive several callbacks for one latched latest frame.
                if (hasFrame && timestamp > 0 && timestamp == frameTimestamp) {
                    return;
                }
                frames.getTransformMatrix(transform);
                frameTimestamp = timestamp;
                if (seekStill && !resumeFromStill) return;
                if (resumeFromStill) {
                    shader.clearStill();
                    seekStill = false;
                    resumeFromStill = false;
                }
                if (hasFrame && !playbackActive && pendingStillFrames == 0 && System.nanoTime() >= seekRefreshUntilNs) {
                    return;
                }
                pendingStillFrames = Math.max(0, pendingStillFrames - 1);
                hasFrame = true;
            }
            handler.removeCallbacks(redraw);
            if (hasFrame || seekStill) {
                shader.draw(texture, transform, width, height, label, density);
            } else {
                GLES20.glClearColor(0, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR);
            require(EGL14.eglSwapBuffers(display, window));
            blurActive = (hasFrame || seekStill) && !label.isEmpty();
            drawCount++;
            if (newFrame) {
                lastFrameCallbackToSwapNs = System.nanoTime() - arrival;
                maxFrameCallbackToSwapNs = Math.max(maxFrameCallbackToSwapNs, lastFrameCallbackToSwapNs);
                frameCount++;
            }
        } catch (RuntimeException exception) {
            android.util.Log.e("VideoFilters", "Unable to render video filters", exception);
            fail();
        }
    }

    private void fail() {
        failed = true;
        blurActive = false;
        if (!closing) {
            listener.onFailure();
        }
    }

    // The owner must detach VLC before calling this; native producers must never see a released input.
    synchronized void release(Runnable complete) {
        if (released) {
            complete.run();
            return;
        }
        releaseCallbacks.add(complete);
        if (closing) {
            return;
        }
        closing = true;
        handler.post(() -> {
            if (frames != null) {
                frames.setOnFrameAvailableListener(null);
            }
            if (input != null) {
                input.release();
            }
            if (frames != null) {
                frames.release();
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                if (shader != null) {
                    shader.release();
                }
                GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            if (window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, window);
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context);
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(display);
            }
            EGL14.eglReleaseThread();
            if (ownsOutput) {
                output.release();
            }
            blurActive = false;
            synchronized (GlVideoRenderer.this) {
                released = true;
                for (Runnable callback : releaseCallbacks) {
                    callback.run();
                }
                releaseCallbacks.clear();
            }
            thread.quitSafely();
        });
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("Video filter rendering unavailable");
        }
    }
}