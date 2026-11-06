package com.example.big.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

public class ScrollingTitleView extends AppCompatTextView {
    private ValueAnimator animator;
    private boolean visibleToUser;
    private final Runnable updateScroll = this::startScroll;

    public ScrollingTitleView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setSingleLine(true);
        setEllipsize(null);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed || animator == null) {
            restartScroll();
        }
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (updateScroll != null) {
            restartScroll();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visibleToUser = isVisible;
        restartScroll();
    }

    @Override
    protected void onDetachedFromWindow() {
        visibleToUser = false;
        stopScroll();
        super.onDetachedFromWindow();
    }

    private void restartScroll() {
        stopScroll();
        if (visibleToUser && isAttachedToWindow()) {
            post(updateScroll);
        }
    }

    private void startScroll() {
        if (!visibleToUser || !isAttachedToWindow() || getLayout() == null) {
            return;
        }
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        int overflow = (int) Math.ceil(getLayout().getLineWidth(0)) - available;
        if (available <= 0 || overflow <= 0 || !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        animator = ValueAnimator.ofInt(0, 0, overflow, overflow, 0, 0);
        animator.setDuration(4500);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(animation -> scrollTo((int) animation.getAnimatedValue(), 0));
        animator.start();
    }

    private void stopScroll() {
        removeCallbacks(updateScroll);
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        scrollTo(0, 0);
    }
}