package com.archos.mediacenter.video.info;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Simple wrapping row layout: children sit next to each other, overflow moves to the next row. */
public class FlowLayout extends ViewGroup {
    private final int mGap;

    public FlowLayout(Context context) { this(context, null); }

    public FlowLayout(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public FlowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mGap = 0; // buttons sit flush inside the action bar, like the original page
    }

    private static boolean isSkipped(View c) {
        if (c.getVisibility() == GONE) return true;
        if (c instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) c;
            if (g.getChildCount() == 0) return false;
            for (int i = 0; i < g.getChildCount(); i++)
                if (g.getChildAt(i).getVisibility() != GONE) return false;
            return true;
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0, usedW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            final View c = getChildAt(i);
            if (isSkipped(c)) continue;
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            final int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + mGap; rowH = 0; }
            x += cw + mGap;
            rowH = Math.max(rowH, ch);
            usedW = Math.max(usedW, x - mGap);
        }
        final int h = y + rowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(usedW + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            final View c = getChildAt(i);
            if (isSkipped(c)) continue;
            final int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + mGap; rowH = 0; }
            final int left = getPaddingLeft() + x, top = getPaddingTop() + y;
            c.layout(left, top, left + cw, top + ch);
            x += cw + mGap;
            rowH = Math.max(rowH, ch);
        }
    }
}
