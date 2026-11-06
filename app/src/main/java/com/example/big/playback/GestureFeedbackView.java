package com.example.big.playback;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.appcompat.widget.AppCompatTextView;

/** Sharp text above the video filter; only unavailable video needs a local tint. */
public final class GestureFeedbackView extends AppCompatTextView {
    private final FeatheredBackdrop backdrop;
    private GlVideoView videoSurface;
    private final ViewTreeObserver.OnPreDrawListener placement = () -> {
        updateBounds();
        return true;
    };

    public GestureFeedbackView(Context context, AttributeSet attributes) {
        super(context, attributes);
        backdrop = new FeatheredBackdrop(getResources().getDisplayMetrics().density);
    }

    public void setVideoSurface(GlVideoView surface) {
        if (videoSurface != null) {
            videoSurface.setFeedbackBounds(new RectF());
        }
        videoSurface = surface;
        updateBounds();
    }

    private void updateBounds() {
        if (videoSurface == null) {
            return;
        }
        RectF bounds = new RectF();
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            int[] labelPosition = new int[2];
            int[] sourcePosition = new int[2];
            getLocationInWindow(labelPosition);
            videoSurface.getLocationInWindow(sourcePosition);
            float x = labelPosition[0] - sourcePosition[0];
            float y = labelPosition[1] - sourcePosition[1];
            bounds.set(x, y, x + getWidth(), y + getHeight());
        }
        videoSurface.setFeedbackBounds(bounds);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (videoSurface == null || videoSurface.getVideoFrameCount() == 0) {
            backdrop.draw(canvas, getWidth(), getHeight());
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(placement);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(placement);
        if (videoSurface != null) {
            videoSurface.setFeedbackBounds(new RectF());
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateBounds();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateBounds();
    }

    boolean hasVideoBackdrop() {
        return isShown() && videoSurface != null && videoSurface.isFeedbackRendered();
    }
}