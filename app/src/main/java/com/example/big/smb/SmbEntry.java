package com.example.big.smb;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SmbEntry {
    public enum Type { DIRECTORY, VIDEO, AUDIO, FILE }

    public static final Comparator<SmbEntry> BROWSER_ORDER = (first, second) -> {
        int group = Integer.compare(first.sortGroup(), second.sortGroup());
        if (group != 0) {
            return group;
        }
        int order = compareNaturalNames(first.name, second.name);
        return order != 0 ? order : first.name.compareTo(second.name);
    };

    private static final Set<String> PLAYABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "3g2", "3gp", "aac", "ac3", "aiff", "alac", "amr", "ape", "asf", "avi",
            "divx", "dts", "dv", "f4v", "flac", "flv", "m2t", "m2ts", "m2v", "m4a",
            "m4b", "m4v", "mka", "mkv", "mov", "mp2", "mp3", "mp4", "mpeg", "mpg",
            "mts", "mxf", "oga", "ogg", "ogm", "ogv", "opus", "rm", "rmvb", "ts",
            "vob", "wav", "webm", "wma", "wmv", "wv"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "aac", "ac3", "aiff", "alac", "amr", "ape", "dts", "flac", "m4a", "m4b",
            "mka", "mp2", "mp3", "oga", "ogg", "opus", "wav", "wma", "wv"));

    public final String name;
    public final boolean directory;
    public final SmbLocation location;
    public final long size;

    public SmbEntry(String name, boolean directory, SmbLocation location, long size) {
        this.name = name;
        this.directory = directory;
        this.location = location;
        this.size = size;
    }

    public Type getType() {
        if (directory) {
            return Type.DIRECTORY;
        }
        if (AUDIO_EXTENSIONS.contains(getExtension().toLowerCase(Locale.ROOT))) {
            return Type.AUDIO;
        }
        return isPlayable(name) ? Type.VIDEO : Type.FILE;
    }

    public String getExtension() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    private int sortGroup() {
        return directory ? 0 : isPlayable(name) ? 1 : 2;
    }

    private static int compareNaturalNames(String first, String second) {
        int left = 0;
        int right = 0;
        int paddingOrder = 0;
        while (left < first.length() && right < second.length()) {
            int a = first.codePointAt(left);
            int b = second.codePointAt(right);
            if (isDigit(a) && isDigit(b)) {
                int leftEnd = left;
                int rightEnd = right;
                while (leftEnd < first.length() && isDigit(first.charAt(leftEnd))) leftEnd++;
                while (rightEnd < second.length() && isDigit(second.charAt(rightEnd))) rightEnd++;
                int leftSignificant = left;
                int rightSignificant = right;
                while (leftSignificant < leftEnd && first.charAt(leftSignificant) == '0') leftSignificant++;
                while (rightSignificant < rightEnd && second.charAt(rightSignificant) == '0') rightSignificant++;

                // Compare digit runs without parsing: even numbers larger than long cannot overflow.
                int order = Integer.compare(leftEnd - leftSignificant, rightEnd - rightSignificant);
                if (order != 0) return order;
                while (leftSignificant < leftEnd) {
                    order = Character.compare(first.charAt(leftSignificant++), second.charAt(rightSignificant++));
                    if (order != 0) return order;
                }
                // Prefer fewer leading zeros only when the rest of the natural name is equal.
                if (paddingOrder == 0) {
                    paddingOrder = Integer.compare(leftEnd - left, rightEnd - right);
                }
                left = leftEnd;
                right = rightEnd;
            } else {
                int order = Integer.compare(Character.toLowerCase(Character.toUpperCase(a)),
                        Character.toLowerCase(Character.toUpperCase(b)));
                if (order != 0) return order;
                left += Character.charCount(a);
                right += Character.charCount(b);
            }
        }
        int order = Integer.compare(first.length() - left, second.length() - right);
        return order != 0 ? order : paddingOrder;
    }

    private static boolean isDigit(int character) {
        return character >= '0' && character <= '9';
    }

    public static boolean isPlayable(String name) {
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && PLAYABLE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}