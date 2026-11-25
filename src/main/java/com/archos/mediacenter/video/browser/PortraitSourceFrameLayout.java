package com.archos.mediacenter.video.browser;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Source artwork uses the same 2:3 aspect ratio as home movie posters. */
public class PortraitSourceFrameLayout extends FrameLayout {
    public PortraitSourceFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = Math.round(getMeasuredWidth() * 1.5f);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
