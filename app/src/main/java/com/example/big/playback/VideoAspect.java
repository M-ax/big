package com.example.big.playback;

/** Display geometry, independent of decoder padding and preview bitmap resolution. */
public final class VideoAspect {
    private VideoAspect() { }

    /**
     * Returns an exact reduced DAR for VLC, or null for invalid/unsafe metadata (no override).
     * Dimensions are the visible source dimensions; SAR must be positive. No rotation is applied.
     */
    public static String vlcAspectRatio(int width, int height, int sarNum, int sarDen) {
        if (width <= 0 || height <= 0 || sarNum <= 0 || sarDen <= 0) return null;
        long num = (long) width * sarNum;
        long den = (long) height * sarDen;
        long divisor = gcd(num, den);
        num /= divisor;
        den /= divisor;

        // VLC 3 src/video_output/display.c multiplies DAR by source dimensions before reducing.
        // Keep either axis product within signed 32-bit range, conservatively below unsigned max.
        long limit = Integer.MAX_VALUE / (long) Math.max(width, height);
        if (num > limit || den > limit) return null;

        // vout_display_PlacePicture also multiplies source dimensions by the reduced SAR.
        long sarDivisor = gcd(sarNum, sarDen);
        if ((long) width * (sarNum / sarDivisor) > Integer.MAX_VALUE
                || (long) height * (sarDen / sarDivisor) > Integer.MAX_VALUE) return null;
        return num + ":" + den;
    }

    static double displayAspect(int visibleWidth, int visibleHeight, int sarNum, int sarDen,
                                boolean swapped) {
        if (visibleWidth <= 0 || visibleHeight <= 0) return 0;
        double aspect = (double) visibleWidth / visibleHeight;
        if (sarNum > 0 && sarDen > 0) aspect *= (double) sarNum / sarDen;
        return swapped ? 1 / aspect : aspect;
    }

    static double previewAspect(double displayAspect, int bitmapWidth, int bitmapHeight) {
        if (valid(displayAspect)) return displayAspect;
        return bitmapWidth > 0 && bitmapHeight > 0 ? (double) bitmapWidth / bitmapHeight : 0;
    }

    static float width(int outputWidth, int outputHeight, double aspect, int fit) {
        if (fit == 2 || !valid(aspect)) return outputWidth;
        return (float) (fit == 1 ? Math.max(outputWidth, outputHeight * aspect)
                : Math.min(outputWidth, outputHeight * aspect));
    }

    static float height(int outputWidth, int outputHeight, double aspect, int fit) {
        if (fit == 2 || !valid(aspect)) return outputHeight;
        return (float) (fit == 1 ? Math.max(outputWidth / aspect, outputHeight)
                : Math.min(outputWidth / aspect, outputHeight));
    }

    static float cropZoom(int outputWidth, int outputHeight, double aspect) {
        if (outputWidth <= 0 || outputHeight <= 0 || !valid(aspect)) return 1;
        double screenAspect = (double) outputWidth / outputHeight;
        return (float) Math.max(screenAspect / aspect, aspect / screenAspect);
    }

    private static long gcd(long first, long second) {
        while (second != 0) {
            long remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }

    private static boolean valid(double aspect) {
        return Double.isFinite(aspect) && aspect > 0;
    }
}