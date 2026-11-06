package com.example.big.smb;

public final class SmbScrollPosition {
    public final int position;
    public final int top;
    public final String anchor;
    public final boolean titleVisible;

    public SmbScrollPosition(int position, int top, String anchor, boolean titleVisible) {
        this.position = Math.max(0, position);
        this.top = top;
        this.anchor = anchor;
        this.titleVisible = titleVisible;
    }

    public int resolve(SmbBrowserRows rows, int headers) {
        int index = anchor == null ? -1 : rows.indexOf(anchor);
        return index >= 0 ? index + headers : Math.min(position, Math.max(0, rows.size() + headers - 1));
    }
}