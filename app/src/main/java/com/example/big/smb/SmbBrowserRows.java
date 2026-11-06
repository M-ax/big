package com.example.big.smb;

import java.util.ArrayList;
import java.util.List;

/** Adapter positions include the optional separator, never the ListView header. */
public final class SmbBrowserRows {
    private static final String SEPARATOR_ANCHOR = "smb-browser-separator";
    private final List<SmbEntry> rows = new ArrayList<>();

    public SmbBrowserRows(List<SmbEntry> entries) {
        List<SmbEntry> sorted = new ArrayList<>(entries);
        sorted.sort(SmbEntry.BROWSER_ORDER);
        boolean separated = false;
        for (SmbEntry entry : sorted) {
            if (!separated && entry.getType() == SmbEntry.Type.FILE) {
                if (!rows.isEmpty()) {
                    rows.add(null);
                }
                separated = true;
            }
            rows.add(entry);
        }
    }

    public int size() {
        return rows.size();
    }

    public SmbEntry get(int position) {
        return rows.get(position);
    }

    public String anchorAt(int position) {
        SmbEntry entry = get(position);
        return entry == null ? SEPARATOR_ANCHOR : entry.location.getUrl();
    }

    public int indexOf(String anchor) {
        for (int index = 0; index < rows.size(); index++) {
            if (anchorAt(index).equals(anchor)) {
                return index;
            }
        }
        return -1;
    }
}