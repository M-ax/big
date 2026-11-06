package com.example.big.playback;

/** Latest-target-only decoder work, confined to the UI thread. Times are monotonic milliseconds. */
public final class SeekPreview {
    private boolean active;
    private long target;
    private long issuedTarget;
    private long issuedAt;
    private long issuedFrame;
    private boolean issued;

    public void begin(long positionMs) {
        active = true;
        target = Math.max(0, positionMs);
        issued = false;
    }

    public void offer(long positionMs) {
        if (active) target = Math.max(0, positionMs);
    }

    public boolean hasPending() {
        return active && (!issued || target != issuedTarget);
    }

    public long poll(long nowMs, long presentedFrames) {
        if (!hasPending()) return -1;
        if (issued && (nowMs - issuedAt < 80
                || (presentedFrames == issuedFrame && nowMs - issuedAt < 250))) return -1;
        issued = true;
        issuedAt = nowMs;
        issuedFrame = presentedFrames;
        issuedTarget = target;
        return target;
    }

    public void cancel() {
        active = false;
    }
}