package com.archos.mediacenter.video.browser;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.GridView;

/**
 * GridView that is as tall as all its rows, so it can sit inside a vertical ScrollView
 * (the phone home page scrolls as a whole: tiles first, then the poster rows).
 */
public class ExpandedGridView extends GridView {

    public ExpandedGridView(Context context) { super(context); }

    public ExpandedGridView(Context context, AttributeSet attrs) { super(context, attrs); }

    public ExpandedGridView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST));
    }
}
