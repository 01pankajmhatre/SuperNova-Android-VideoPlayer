package com.archos.mediacenter.video.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatTextView;

public final class StandardPlaybackTitleView extends AppCompatTextView {
    private static final ColorFilter OUTLINE_COLOR =
            new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);

    private boolean outlined;

    public StandardPlaybackTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setAppearance(int sizeSp, boolean outline, boolean shadow) {
        setTextSize(sizeSp);
        outlined = outline;
        applyPlaybackShadow(this, shadow);
        invalidate();
    }

    static void applyPlaybackShadow(TextView label, boolean enabled) {
        float density = label.getResources().getDisplayMetrics().density;
        label.setShadowLayer(enabled ? 3 * density : 0, 0, density, Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (outlined) {
            Paint paint = getPaint();
            Paint.Style previousStyle = paint.getStyle();
            ColorFilter previousFilter = paint.getColorFilter();
            float previousWidth = paint.getStrokeWidth();
            try {
                // Reuse TextView's layout and marquee so the stroke stays aligned as the title scrolls.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
                paint.setColorFilter(OUTLINE_COLOR);
                super.onDraw(canvas);
            } finally {
                paint.setStyle(previousStyle);
                paint.setStrokeWidth(previousWidth);
                paint.setColorFilter(previousFilter);
            }
        }
        super.onDraw(canvas);
    }
}
