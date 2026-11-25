package com.archos.mediacenter.video.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import com.archos.mediacenter.utils.seekbar.ArchosProgressSlider;

/** Draws a play/pause icon and the playback or scrub time above the actual seek thumb. */
public class TimeLabelSeekBar extends ArchosProgressSlider {
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF labelBounds = new RectF();
    private final float density;
    private String timeText = "0:00";
    private boolean paused;
    private boolean playbackShadow;

    public TimeLabelSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.LEFT);
        iconPaint.setColor(Color.WHITE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(1.2f * density);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        trackShadow.setColor(0x55000000);
        trackShadow.setStrokeWidth(3 * density);
        trackShadow.setStrokeCap(Paint.Cap.ROUND);
        trackShadow.setShadowLayer(1.2f * density, 0, 0.5f * density, 0x99000000);
    }

    /** Shows a pause icon in the floating label while the video is paused, a play icon otherwise. */
    public void setPaused(boolean paused) {
        if (this.paused != paused) {
            this.paused = paused;
            invalidate();
        }
    }

    void setPlaybackShadow(boolean enabled) {
        playbackShadow = enabled;
        float radius = enabled ? 1.2f * density : 0;
        textPaint.setShadowLayer(radius, 0, 0.5f * density, 0x99000000);
        iconPaint.setShadowLayer(radius, 0, 0.5f * density, 0x99000000);
        invalidate();
    }

    public void setTimeText(CharSequence text) {
        String value = text.toString();
        if (!timeText.equals(value)) {
            timeText = value;
            invalidate();
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        if (playbackShadow && getProgressDrawable() != null) {
            Rect track = getProgressDrawable().getBounds();
            if (!track.isEmpty()) {
                float y = getPaddingTop() + track.exactCenterY() + 0.5f * density;
                canvas.drawLine(getPaddingLeft() + track.left, y, getPaddingLeft() + track.right, y, trackShadow);
            }
        }
        super.onDraw(canvas);
        Drawable thumb = getThumb();
        if (timeText.isEmpty() || thumb == null || getWidth() == 0) return;

        Rect bounds = thumb.getBounds();
        float thumbCenter = getPaddingLeft() - getThumbOffset() + bounds.exactCenterX();
        // Keep the icon over the playhead and the time beside it.
        float iconSize = 10 * density;
        float iconGap = 8 * density;
        float textWidth = textPaint.measureText(timeText);
        float labelWidth = Math.min(getWidth(), iconSize + iconGap + textWidth + 8 * density);
        // At the start of the track, center the icon itself over the thumb.
        float contentInset = (labelWidth - (iconSize + iconGap + textWidth)) / 2;
        float alignedLeft = thumbCenter - iconSize / 2 - contentInset;
        float left = Math.max(0, Math.min(alignedLeft, getWidth() - labelWidth));
        float bottom = getPaddingTop() + bounds.top - 2 * density;
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float height = metrics.descent - metrics.ascent + 4 * density;
        labelBounds.set(left, Math.max(0, bottom - height), left + labelWidth, bottom);
        float contentLeft = labelBounds.left + contentInset;
        float cy = labelBounds.centerY();

        iconPath.reset();
        if (paused) {
            float barTop = cy - iconSize / 2 + 1 * density;
            float barBottom = cy + iconSize / 2 - 1 * density;
            float x1 = contentLeft + 2.5f * density;
            float x2 = contentLeft + iconSize - 2.5f * density;
            iconPath.moveTo(x1, barTop);
            iconPath.lineTo(x1, barBottom);
            iconPath.moveTo(x2, barTop);
            iconPath.lineTo(x2, barBottom);
        } else {
            iconPath.moveTo(contentLeft + 1.5f * density, cy - iconSize / 2 + 1 * density);
            iconPath.lineTo(contentLeft + iconSize - 1 * density, cy);
            iconPath.lineTo(contentLeft + 1.5f * density, cy + iconSize / 2 - 1 * density);
            iconPath.close();
        }
        canvas.drawPath(iconPath, iconPaint);

        float baseline = cy - (metrics.ascent + metrics.descent) / 2;
        canvas.drawText(timeText, contentLeft + iconSize + iconGap, baseline, textPaint);
    }
}
