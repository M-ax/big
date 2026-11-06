package com.example.big.playback;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Calculates playback targets without changing playback or depending on Android.
 * Coarse seeking uses a quartic distance curve with a one-hour range per full view
 * width and no minimum jump: about 14 s at 1/4 width, 3 min 45 s at 1/2 width.
 * Vertical gain is 1.25; diagonals wait for a 2:1 dominant axis.
 *
 * <p>Configure density and SOURCE frame rate before begin (both are captured for
 * that gesture). Pass ACTION_DOWN's event time to the timed begin overload. For
 * each ACTION_MOVE, feed historical samples oldest first, then the current sample
 * to move(x, y, eventMs), using the same pointer and view coordinates throughout.
 * Display the final returned Result. On ACTION_UP call finish, NOT move, and
 * commit its SEEK positionMs. Do not feed UP coordinates or its history as MOVE
 * samples. On cancellation/pointer replacement call cancel; begin resets all state.
 * When source FPS metadata arrives asynchronously, call refineFrameRate on the
 * same thread before the next MOVE; it preserves the last displayed Result.
 *
 * <p>After horizontal lock, moving down by 18% of the original view height enters
 * fine seeking; returning to 12% or less leaves it. Each transition preserves the
 * displayed target and reanchors at the current finger, discarding filter lag.
 * Fine seeking rounds horizontal displacement to frames at one frame per 4 dp.
 * Frame timestamps are rounded to milliseconds on a zero-origin rational grid,
 * never adjusted for playback speed. The end is the last grid timestamp not past
 * duration. Unknown FPS uses a 1 ms grid and 1 ms per 4 dp instead of guessing FPS.
 */
public final class PlaybackGesture {
    public enum Action { NONE, SEEK, VOLUME, BRIGHTNESS }

    private static final long COARSE_SEEK_RANGE_MS = 60L * 60L * 1000L;

    private final float touchSlop;
    private final SeekTouchFilter touchFilter = new SeekTouchFilter();
    private float configuredDensity = 1f;
    private float density = 1f;
    private BigInteger configuredFrameNumerator = BigInteger.ONE;
    private BigInteger configuredFramePeriodMs = BigInteger.ONE;
    private BigInteger frameNumerator = BigInteger.ONE;
    private BigInteger framePeriodMs = BigInteger.ONE;
    private BigInteger maximumFrame = BigInteger.ZERO;
    private BigInteger fineAnchorFrame = BigInteger.ZERO;
    private boolean fineSeeking;
    private double anchorX;
    private double currentX;
    private long anchorPositionMs;
    private Result lastResult;
    private boolean active;
    private Action action = Action.NONE;
    private float startX;
    private float startY;
    private int width;
    private int height;
    private long initialPositionMs;
    private long durationMs;
    private boolean seekable;
    private int initialVolume;
    private int maxVolume;
    private float initialBrightness = 0.5f;

    public PlaybackGesture(float touchSlop) {
        this.touchSlop = isFinite(touchSlop) ? Math.max(0f, touchSlop) : 0f;
        lastResult = initialResult();
    }

    /** Sets pixels per dp for subsequent gestures; invalid values use 1. */
    public void setDensity(float density) {
        configuredDensity = isFinite(density) && density > 0f ? density : 1f;
    }

    /**
     * Sets rational source FPS for subsequent gestures, e.g. 60000/1001.
     * Either argument nonpositive clears the rate (1 ms fallback).
     */
    public void setFrameRate(int numerator, int denominator) {
        configuredFrameNumerator = numerator > 0 && denominator > 0
                ? BigInteger.valueOf(numerator) : BigInteger.ONE;
        configuredFramePeriodMs = numerator > 0 && denominator > 0
                ? BigInteger.valueOf(1000L * denominator) : BigInteger.ONE;
    }

    /**
     * Refines only the active gesture's source FPS without replacing its Result.
     * Call on the gesture thread (main thread for Android), before the next MOVE.
     * Invalid rates, inactive gestures and rationally equivalent rates are no-ops.
     * A seek reanchors at its current filtered X and displayed target, retaining
     * filter history. The next MOVE snaps to the new zero-origin grid; fine steps
     * are relative to the nearest new frame, still one frame per 4 dp. An immediate
     * finish returns the old preview unchanged. Vertical controls are unaffected.
     * This does not configure future gestures; use setFrameRate for those.
     */
    public void refineFrameRate(int numerator, int denominator) {
        if (!active || numerator <= 0 || denominator <= 0) {
            return;
        }
        BigInteger refinedNumerator = BigInteger.valueOf(numerator);
        BigInteger refinedPeriodMs = BigInteger.valueOf(1000L * denominator);
        if (refinedNumerator.multiply(framePeriodMs)
                .equals(frameNumerator.multiply(refinedPeriodMs))) {
            return;
        }
        frameNumerator = refinedNumerator;
        framePeriodMs = refinedPeriodMs;
        updateFrameBounds();
        if (lastResult.action == Action.SEEK) {
            anchorX = currentX;
            anchorPositionMs = lastResult.positionMs;
            fineAnchorFrame = nearestFrame(anchorPositionMs).min(maximumFrame);
        }
    }

    /** Active frame duration, otherwise configured; 1 ms if unknown, not rounded. */
    public double getFrameDurationMs() {
        return active ? framePeriodMs.doubleValue() / frameNumerator.doubleValue()
                : configuredFramePeriodMs.doubleValue() / configuredFrameNumerator.doubleValue();
    }

    /**
     * Starts a new gesture, replacing any previous one. Invalid dimensions or
     * coordinates disable it. Unknown durations disable seeking only. Initial
     * values are clamped to valid ranges; unset/nonfinite brightness uses 0.5.
     */
    public void begin(float x, float y, int width, int height, long positionMs,
                      long durationMs, boolean seekable, int volume, int maxVolume,
                      float brightness) {
        startX = x;
        startY = y;
        this.width = width;
        this.height = height;
        this.durationMs = Math.max(0L, durationMs);
        initialPositionMs = Math.max(0L, positionMs);
        if (this.durationMs > 0L) {
            initialPositionMs = Math.min(initialPositionMs, this.durationMs);
        }
        this.seekable = seekable && this.durationMs > 0L;
        this.maxVolume = Math.max(0, maxVolume);
        initialVolume = Math.max(0, Math.min(volume, this.maxVolume));
        initialBrightness = isFinite(brightness) && brightness >= 0f
                ? Math.max(0.01f, Math.min(brightness, 1f)) : 0.5f;
        action = Action.NONE;
        active = width > 0 && height > 0 && isFinite(x) && isFinite(y);
        density = configuredDensity;
        frameNumerator = configuredFrameNumerator;
        framePeriodMs = configuredFramePeriodMs;
        updateFrameBounds();
        fineSeeking = false;
        anchorX = x;
        currentX = x;
        anchorPositionMs = initialPositionMs;
        fineAnchorFrame = BigInteger.ZERO;
        lastResult = initialResult();
        touchFilter.begin(x, -1L, density);
    }

    /**
     * Starts with the DOWN timestamp in monotonic milliseconds (e.g. eventTime).
     * Negative timestamps disable this gesture. The untimed begin also works with
     * timed moves, but its first timed move seeds the filter without smoothing.
     */
    public void begin(float x, float y, int width, int height, long positionMs,
                      long durationMs, boolean seekable, int volume, int maxVolume,
                      float brightness, long eventMs) {
        begin(x, y, width, height, positionMs, durationMs, seekable, volume, maxVolume, brightness);
        active = active && eventMs >= 0L;
        touchFilter.begin(x, eventMs, density);
    }

    /**
     * Unsmooth compatibility overload. Targets are absolute relative to begin or
     * the latest mode transition, never accumulated deltas. Invalid coordinates
     * return NONE without replacing the last valid preview. Prefer timed moves;
     * mixing overloads resets filtering at each untimed move.
     */
    public Result move(float x, float y) {
        if (!active || !isFinite(x) || !isFinite(y)) {
            return initialResult();
        }
        touchFilter.cancel();
        lastResult = calculateMove(x, x, y);
        return lastResult;
    }

    /**
     * Filters horizontal seeking only. Axis/slop detection and vertical controls
     * use raw coordinates. Invalid coordinates or duplicate/backward/negative
     * timestamps leave the last preview unchanged. Gaps over 250 ms reseed the
     * filter at the new coordinate rather than extrapolating stale velocity.
     */
    public Result move(float x, float y, long eventMs) {
        if (!active) {
            return initialResult();
        }
        if (!isFinite(x) || !isFinite(y) || !touchFilter.move(x, eventMs)) {
            return lastResult;
        }
        lastResult = calculateMove(touchFilter.getPosition(), x, y);
        return lastResult;
    }

    private Result calculateMove(double x, float rawX, float y) {
        currentX = x;
        double dx = (double) rawX - startX;
        double dy = (double) y - startY;
        if (action == Action.NONE) {
            double horizontal = Math.abs(dx);
            double vertical = Math.abs(dy);
            if (Math.max(horizontal, vertical) <= touchSlop) {
                return initialResult();
            }
            if (horizontal > 2.0 * vertical) {
                action = Action.SEEK;
            } else if (vertical > 2.0 * horizontal) {
                action = startX < width / 2.0 ? Action.BRIGHTNESS : Action.VOLUME;
            } else {
                return initialResult();
            }
        }

        if (action == Action.SEEK) {
            if (!seekable) {
                return initialResult();
            }
            boolean nextFine = fineSeeking ? dy > height * 0.12 : dy >= height * 0.18;
            if (lastResult.action == Action.SEEK && nextFine != fineSeeking) {
                fineSeeking = nextFine;
                anchorX = rawX;
                currentX = rawX;
                anchorPositionMs = lastResult.positionMs;
                fineAnchorFrame = nearestFrame(anchorPositionMs).min(maximumFrame);
                touchFilter.reanchor(rawX);
                return new Result(action, anchorPositionMs, initialVolume,
                        initialBrightness, fineSeeking);
            }
            dx = x - anchorX;
            long positionMs;
            if (fineSeeking) {
                BigInteger steps = BigDecimal.valueOf(Math.abs(dx) / (4.0 * density))
                        .setScale(0, RoundingMode.HALF_UP).toBigInteger();
                positionMs = positionForFrame(fineAnchorFrame.add(dx < 0 ? steps.negate() : steps));
            } else {
                double distance = Math.abs(dx) / width;
                long jump = Math.round(COARSE_SEEK_RANGE_MS * Math.pow(distance, 4.0));
                positionMs = dx >= 0.0
                        ? anchorPositionMs + Math.min(jump, durationMs - anchorPositionMs)
                        : anchorPositionMs - Math.min(jump, anchorPositionMs);
                positionMs = positionForFrame(nearestFrame(positionMs));
            }
            return new Result(action, positionMs, initialVolume, initialBrightness, fineSeeking);
        }

        double change = -dy / height * 1.25;
        if (action == Action.VOLUME) {
            int volume = (int) Math.max(0.0,
                    Math.min(maxVolume, initialVolume + change * maxVolume));
            return new Result(action, initialPositionMs, volume, initialBrightness);
        }
        float brightness = (float) Math.max(0.01,
                Math.min(1.0, initialBrightness + change));
        return new Result(action, initialPositionMs, initialVolume, brightness);
    }

    /**
     * Ends the gesture, returning exactly the last accepted MOVE result. There is
     * no extrapolation/filter flush on release. Without a preview (or after cancel
     * or a prior finish), returns NONE. The returned immutable Result stays valid.
     */
    public Result finish() {
        Result result = active ? lastResult : initialResult();
        cancel();
        return result;
    }

    /** Release coordinates are deliberately ignored to reject finger roll. */
    public Result finish(float x, float y) {
        return finish();
    }

    /** Release coordinates AND timestamp are ignored; only MOVE changes targets. */
    public Result finish(float x, float y, long eventMs) {
        return finish();
    }

    public void cancel() {
        active = false;
        action = Action.NONE;
        fineSeeking = false;
        touchFilter.cancel();
        lastResult = initialResult();
    }

    private void updateFrameBounds() {
        // Largest frame whose rounded timestamp is <= duration, without overflow.
        maximumFrame = BigInteger.valueOf(durationMs).shiftLeft(1).add(BigInteger.ONE)
                .multiply(frameNumerator).subtract(BigInteger.ONE)
                .divide(framePeriodMs.shiftLeft(1));
    }

    private BigInteger nearestFrame(long positionMs) {
        return roundedDivide(BigInteger.valueOf(positionMs).multiply(frameNumerator), framePeriodMs);
    }

    private long positionForFrame(BigInteger frame) {
        return roundedDivide(frame.max(BigInteger.ZERO).min(maximumFrame).multiply(framePeriodMs),
                frameNumerator).longValue();
    }

    private static BigInteger roundedDivide(BigInteger value, BigInteger divisor) {
        return value.add(divisor.shiftRight(1)).divide(divisor);
    }

    private Result initialResult() {
        return new Result(Action.NONE, initialPositionMs, initialVolume, initialBrightness);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static final class Result {
        public final Action action;
        public final long positionMs;
        public final int volume;
        public final float brightness;
        public final boolean fineSeeking;

        private Result(Action action, long positionMs, int volume, float brightness) {
            this(action, positionMs, volume, brightness, false);
        }

        private Result(Action action, long positionMs, int volume, float brightness,
                       boolean fineSeeking) {
            this.action = action;
            this.positionMs = positionMs;
            this.volume = volume;
            this.brightness = brightness;
            this.fineSeeking = fineSeeking;
        }
    }
}