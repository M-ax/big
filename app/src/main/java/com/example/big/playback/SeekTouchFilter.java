package com.example.big.playback;

/**
 * Timestamp-based horizontal low-pass filter used by PlaybackGesture. It smooths
 * signed velocity over 40 ms so alternating sensor jitter does not look like fast
 * intentional motion, then adapts the position cutoff from 2 Hz at rest to 60 Hz.
 * Exponential coefficients use elapsed time, not event counts. Density normalizes
 * velocity to dp/second. This is causal smoothing, not prediction: small deliberate
 * motion has some lag, fast motion has little lag, and there is no overshoot.
 *
 * <p>Feed DOWN to begin and MOVE/history in timestamp order to move; false means
 * rejected (no state changed). Read getPosition only after an accepted sample.
 * PlaybackGesture handles Y validation, mode reanchoring and release; callers of
 * that class should not filter the same coordinates a second time.
 */
public final class SeekTouchFilter {
    private static final long MAX_GAP_MS = 250L;
    private boolean initialized;
    private long lastEventMs;
    private double lastRawX;
    private double position;
    private double velocity;
    private float density = 1f;

    /** Resets all history. Invalid initial coordinates/time leave it unseeded. */
    public void begin(float x, long eventMs, float density) {
        cancel();
        this.density = isFinite(density) && density > 0f ? density : 1f;
        if (isFinite(x) && eventMs >= 0L) {
            seed(x, eventMs);
        }
    }

    /**
     * Accepts strictly increasing, nonnegative millisecond times and finite X.
     * The first valid sample or a gap over 250 ms seeds without smoothing.
     */
    public boolean move(float x, long eventMs) {
        if (!isFinite(x) || eventMs < 0L || (initialized && eventMs <= lastEventMs)) {
            return false;
        }
        if (!initialized || eventMs - lastEventMs > MAX_GAP_MS) {
            seed(x, eventMs);
            return true;
        }
        double seconds = (eventMs - lastEventMs) / 1000.0;
        double rawVelocity = ((double) x - lastRawX) / density / seconds;
        velocity += -Math.expm1(-seconds / 0.04) * (rawVelocity - velocity);
        double cutoff = Math.min(60.0, 2.0 + 0.08 * Math.abs(velocity));
        double alpha = -Math.expm1(-2.0 * Math.PI * cutoff * seconds);
        position += alpha * ((double) x - position);
        lastRawX = x;
        lastEventMs = eventMs;
        return true;
    }

    public double getPosition() {
        return position;
    }

    /** Discards positional/velocity lag at a mode transition, retaining the clock. */
    public void reanchor(float x) {
        if (isFinite(x)) {
            lastRawX = x;
            position = x;
            velocity = 0.0;
        }
    }

    /** Clears all samples; a subsequent move starts a fresh stream. */
    public void cancel() {
        initialized = false;
        lastEventMs = 0L;
        lastRawX = 0.0;
        position = 0.0;
        velocity = 0.0;
    }

    private void seed(float x, long eventMs) {
        reanchor(x);
        lastEventMs = eventMs;
        initialized = true;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}