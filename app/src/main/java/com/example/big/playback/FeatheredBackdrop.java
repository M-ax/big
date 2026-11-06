package com.example.big.playback;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/** Samples only the video, never the label or the other player controls. */
final class FeatheredBackdrop {
    private static final String PROGRAM =
            "uniform shader frame; uniform float2 size; uniform float4 imageBounds;"
            + "uniform float radius; uniform float feather; uniform float blur; uniform float hasFrame;"
            + "half4 main(float2 p) {"
            + " float2 q = abs(p - size * 0.5) - (size * 0.5 - radius);"
            + " float inside = -(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius);"
            + " float fade = smoothstep(0.0, feather, inside);"
            + " if (hasFrame < 0.5) { return half4(0.0, 0.0, 0.0, 0.12 * fade); }"
            + " float strength = blur * fade;"
            + " half4 color = half4(0.0); float weights = 0.0;"
            + " for (int y = -1; y <= 1; y++) { for (int x = -1; x <= 1; x++) {"
            + "   float weight = float((2 - x*x) * (2 - y*y));"
            + "   color += frame.eval(p + float2(float(x), float(y)) * strength) * weight;"
            + "   weights += weight;"
            + " } }"
            + " color /= weights;"
            + " float covered = step(imageBounds.x, p.x) * step(imageBounds.y, p.y)"
            + "   * step(p.x, imageBounds.z) * step(p.y, imageBounds.w) * hasFrame;"
            + " float alpha = mix(0.12, 1.0, covered) * fade;"
            + " return half4(color.rgb * 0.62 * covered * alpha, alpha);"
            + "}";
    private final RuntimeShader shader = new RuntimeShader(PROGRAM);
    private final Bitmap empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean captured;

    FeatheredBackdrop(float density) {
        this.density = density;
        paint.setShader(shader);
        clear();
    }

    void setFrame(Bitmap bitmap, RectF bounds) {
        BitmapShader frame = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        frame.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        Matrix matrix = new Matrix();
        matrix.setRectToRect(new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight()), bounds, Matrix.ScaleToFit.FILL);
        frame.setLocalMatrix(matrix);
        shader.setInputShader("frame", frame);
        shader.setFloatUniform("imageBounds", bounds.left, bounds.top, bounds.right, bounds.bottom);
        shader.setFloatUniform("hasFrame", 1f);
        captured = true;
    }

    void clear() {
        // Keep the child shader type stable so showing a frame needs no new GPU program.
        shader.setInputShader("frame", new BitmapShader(empty, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        shader.setFloatUniform("imageBounds", 0f, 0f, 0f, 0f);
        shader.setFloatUniform("hasFrame", 0f);
        captured = false;
    }

    boolean hasFrame() {
        return captured;
    }

    void draw(Canvas canvas, int width, int height) {
        if (!canvas.isHardwareAccelerated()) {
            return;
        }
        shader.setFloatUniform("size", (float) width, (float) height);
        shader.setFloatUniform("radius", Math.min(24f * density, Math.min(width, height) / 2f));
        shader.setFloatUniform("feather", Math.min(24f * density, Math.min(width, height) / 2f));
        shader.setFloatUniform("blur", 16f * density);
        canvas.drawRect(0, 0, width, height, paint);
    }
}