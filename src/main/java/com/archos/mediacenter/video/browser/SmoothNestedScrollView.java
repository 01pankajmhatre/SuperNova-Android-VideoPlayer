package com.archos.mediacenter.video.browser;

import android.content.Context;
import android.util.AttributeSet;

import androidx.core.widget.NestedScrollView;

/**
 * The scroll view of the Home page. With a remote (TV) a focus change makes the default view jump to the
 * focused row: here that move is animated instead.
 */
public class SmoothNestedScrollView extends NestedScrollView {
    private boolean mSmoothFocusScroll;

    public SmoothNestedScrollView(Context context) {
        super(context);
    }

    public SmoothNestedScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SmoothNestedScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSmoothFocusScroll(boolean smooth) {
        mSmoothFocusScroll = smooth;
    }

    /** Used for the "scroll to the focused child" and D-pad arrow scrolls; touch scrolling does not come here. */
    @Override
    public void scrollBy(int x, int y) {
        if (mSmoothFocusScroll && x == 0 && y != 0 && isLaidOut()) {
            smoothScrollBy(0, y);
        } else {
            super.scrollBy(x, y);
        }
    }
}
