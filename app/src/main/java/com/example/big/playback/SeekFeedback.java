package com.example.big.playback;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.Locale;

public final class SeekFeedback {
    private SeekFeedback() {
    }

    public static CharSequence format(long targetMs, long startMs) {
        return format(targetMs, startMs, false);
    }

    public static CharSequence format(long targetMs, long startMs, boolean precise) {
        long target = Math.max(0, targetMs);
        long delta = target - Math.max(0, startMs);
        String absolute = formatTime(target, precise);
        String change = (delta < 0 ? "−" : "+") + formatTime(Math.abs(delta), precise);
        SpannableString text = new SpannableString(absolute + "  " + change);
        text.setSpan(new ForegroundColorSpan(Color.LTGRAY), 0, absolute.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.WHITE), absolute.length() + 2, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static String formatTime(long timeMs, boolean precise) {
        return PlaybackInput.formatTime(timeMs)
                + (precise ? String.format(Locale.ROOT, ".%03d", timeMs % 1000) : "");
    }
}