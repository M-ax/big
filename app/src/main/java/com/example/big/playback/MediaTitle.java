package com.example.big.playback;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

public final class MediaTitle {
    private MediaTitle() {
    }

    public static String resolve(ContentResolver resolver, Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    String name = column < 0 ? null : clean(cursor.getString(column));
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            } catch (RuntimeException ignored) {
                // A provider may not expose a display name or may have revoked access.
            }
        }
        return fallback(uri);
    }

    public static String fallback(Uri uri) {
        String name = clean(uri.getLastPathSegment());
        if (name == null || name.isEmpty()) {
            name = clean(uri.getHost());
        }
        return name == null ? "" : name;
    }

    private static String clean(String value) {
        return value == null ? null : value.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}