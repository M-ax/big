package com.example.big.smb;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

/** Restores inside layout, not in a posted task after a frame at the top. */
public final class SmbBrowserListView extends ListView {
    private SmbScrollPosition pending;
    private Runnable restored;
    private View controls;
    private View placeholder;

    public SmbBrowserListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setQuickReturnHeader(View controls, View placeholder) {
        this.controls = controls;
        this.placeholder = placeholder;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (controls != null && controls.getParent() != placeholder) {
            // Reserve the panel's current height before measuring or restoring the header,
            // including the first traversal after rotation or a path/font-size change.
            controls.measure(MeasureSpec.makeMeasureSpec(Math.max(0,
                            MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight()),
                    MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            placeholder.setMinimumHeight(controls.getMeasuredHeight());
        } else if (placeholder != null) {
            placeholder.setMinimumHeight(0);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void restoreBeforeDraw(int position, int top, Runnable restored) {
        pending = new SmbScrollPosition(position, top, null, false);
        this.restored = restored;
        requestLayout();
    }

    public boolean isRestoring() {
        return pending != null;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (pending != null && getChildCount() > 0) {
            // Finish data-change reconciliation first, then lay out the anchor in the
            // same traversal. Neither intermediate layout can reach the screen.
            int position = pending.position;
            int top = pending.top;
            // Non-touch selection skips disabled rows. Offset the next selectable row so
            // headers and separators still land at their exact saved coordinates.
            while (position < getCount() && !getAdapter().isEnabled(position)) {
                top += rowHeight(position) + getDividerHeight();
                position++;
            }
            if (position < getCount()) {
                setSelectionFromTop(position, top);
                super.layoutChildren();
            }
            if (getCount() == getHeaderViewsCount()) {
                scrollListBy(getChildAt(0).getTop() - getPaddingTop() - pending.top);
            }
            pending = null;
            Runnable callback = restored;
            restored = null;
            callback.run();
        }
    }

    private int rowHeight(int position) {
        View row = getAdapter().getView(position, null, this);
        ViewGroup.LayoutParams params = row.getLayoutParams();
        int height = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height;
        row.measure(MeasureSpec.makeMeasureSpec(getWidth() - getPaddingLeft() - getPaddingRight(),
                        MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, height), height >= 0
                        ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
        return row.getMeasuredHeight();
    }
}