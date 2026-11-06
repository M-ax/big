package com.example.big.smb;

/** Uses adjacent visible-row coordinates, so even one upward pixel returns the title. */
public final class SmbQuickReturn {
    private int position;
    private int top;
    private boolean visible = true;

    public void reset(int position, int top, boolean visible) {
        this.position = position;
        this.top = top;
        this.visible = visible;
    }

    public boolean onScroll(int position, int top) {
        if (position != this.position) {
            visible = position < this.position;
        } else if (top != this.top) {
            visible = top > this.top;
        }
        this.position = position;
        this.top = top;
        return visible;
    }

    public boolean isVisible() {
        return visible;
    }
}