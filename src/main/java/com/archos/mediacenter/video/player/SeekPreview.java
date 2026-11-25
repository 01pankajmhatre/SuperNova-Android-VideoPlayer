package com.archos.mediacenter.video.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thumbnail shown above the playhead while the user drags the seek bar: a frame of the video at the
 * position being scrubbed, with its time below. Frames are decoded on a background thread (nearest
 * key frame, so it stays fast) and cached per second. If the source cannot be decoded (e.g. some
 * network protocols) only the time is shown.
 */
final class SeekPreview {
    private static final int THUMB_WIDTH_DP = 160;
    private static final int EDGE_MARGIN_DP = 8;

    private final Context context;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SeekPreview");
        t.setDaemon(true);
        return t;
    });
    private final LruCache<Long, Bitmap> cache = new LruCache<>(24);

    private PopupWindow popup;
    private ImageView image;
    private TextView timeView;
    private int popupWidth, popupHeight;
    private boolean decodable = true;

    // Touched by the executor thread; guarded by "this".
    private MediaMetadataRetriever retriever;
    private boolean retrieverFailed;
    private long latestRequestMs = -1;
    private boolean decoding;
    private Uri sourceUri;
    private Map<String, String> sourceHeaders;

    SeekPreview(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /** Starts a scrub session for the given source. */
    void begin(Uri uri, Map<String, String> headers, int videoWidth, int videoHeight) {
        hide();
        cache.evictAll();
        synchronized (this) {
            sourceUri = uri;
            sourceHeaders = headers;
            retrieverFailed = uri == null;
            latestRequestMs = -1;
        }
        decodable = uri != null;

        int w = dp(THUMB_WIDTH_DP);
        int h = (videoWidth > 0 && videoHeight > 0)
                ? Math.max(dp(60), Math.min(dp(120), Math.round(w * (float) videoHeight / videoWidth)))
                : dp(90);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout frame = new FrameLayout(context);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xFF111111);
        border.setStroke(dp(1.5f), 0xFFEEEEEE);
        border.setCornerRadius(dp(3));
        frame.setBackground(border);
        frame.setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(w, h));
        frame.setVisibility(decodable ? View.VISIBLE : View.GONE);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        timeView = new TextView(context);
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(12);
        timeView.setShadowLayer(dp(2), 0, 0, Color.BLACK);
        timeView.setSingleLine(true);
        timeView.setGravity(Gravity.CENTER);
        timeView.setPadding(0, dp(4), 0, dp(2));
        root.addView(timeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popupWidth = Math.max(root.getMeasuredWidth(), w + dp(3));
        popupHeight = root.getMeasuredHeight() + dp(12);

        popup = new PopupWindow(root, popupWidth, popupHeight, false);
        popup.setTouchable(false);          // never steal the drag from the seek bar
        popup.setFocusable(false);
        popup.setClippingEnabled(false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    /**
     * Shows or moves the thumbnail above the playhead of {@code seekBar}.
     * @param progress seek bar progress (0..max)
     */
    void update(View windowAnchor, android.widget.SeekBar seekBar, int progress, long positionMs, CharSequence timeText) {
        if (popup == null || windowAnchor == null || seekBar == null || seekBar.getWidth() == 0) return;
        timeView.setText(timeText);

        int[] location = new int[2];
        seekBar.getLocationInWindow(location);
        int trackWidth = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
        float fraction = seekBar.getMax() > 0 ? progress / (float) seekBar.getMax() : 0f;
        float playheadX = location[0] + seekBar.getPaddingLeft() + trackWidth * fraction;
        int windowWidth = windowAnchor.getWidth();
        int x = Math.round(playheadX - popupWidth / 2f);
        x = Math.max(dp(EDGE_MARGIN_DP), Math.min(windowWidth - popupWidth - dp(EDGE_MARGIN_DP), x));
        // Bottom of the preview sits just above the track line.
        int y = location[1] + seekBar.getPaddingTop() - popupHeight - dp(4);
        y = Math.max(dp(EDGE_MARGIN_DP), y);

        if (popup.isShowing()) {
            popup.update(x, y, popupWidth, popupHeight);
        } else {
            popup.showAtLocation(windowAnchor, Gravity.TOP | Gravity.START, x, y);
        }
        if (decodable) requestFrame(positionMs);
    }

    void hide() {
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (RuntimeException ignored) {
                // window already gone
            }
            popup = null;
        }
        image = null;
        timeView = null;
        synchronized (this) {
            latestRequestMs = -1;
        }
        executor.execute(this::closeRetriever);
    }

    void release() {
        hide();
        executor.shutdown();
        cache.evictAll();
    }

    // ---- frame decoding -------------------------------------------------------------------

    private void requestFrame(long positionMs) {
        long key = positionMs / 1000;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            if (image != null) image.setImageBitmap(cached);
            return;
        }
        synchronized (this) {
            latestRequestMs = positionMs;
            if (decoding) return;
            decoding = true;
        }
        try {
            executor.execute(this::decodeLoop);
        } catch (RuntimeException rejected) {
            synchronized (this) {
                decoding = false;
            }
        }
    }

    private void decodeLoop() {
        while (true) {
            long position;
            synchronized (this) {
                position = latestRequestMs;
                if (position < 0) {
                    decoding = false;
                    return;
                }
                latestRequestMs = -1;
            }
            Bitmap frame = decode(position);
            final long key = position / 1000;
            if (frame != null) {
                cache.put(key, frame);
                final Bitmap shown = frame;
                main.post(() -> {
                    synchronized (SeekPreview.this) {
                        // Only show it if no newer position has been asked for meanwhile.
                        if (latestRequestMs >= 0 && latestRequestMs / 1000 != key) return;
                    }
                    if (image != null) image.setImageBitmap(shown);
                });
            } else if (retrieverFailed) {
                main.post(() -> {
                    decodable = false;
                    if (image != null && image.getParent() instanceof View) {
                        ((View) image.getParent()).setVisibility(View.GONE);
                        if (popup != null) {
                            popupHeight = Math.max(dp(24), popupHeight - ((View) image.getParent()).getHeight());
                        }
                    }
                });
            }
        }
    }

    private Bitmap decode(long positionMs) {
        try {
            MediaMetadataRetriever r;
            synchronized (this) {
                if (retrieverFailed) return null;
                r = retriever;
            }
            if (r == null) {
                r = new MediaMetadataRetriever();
                Uri uri;
                Map<String, String> headers;
                synchronized (this) {
                    uri = sourceUri;
                    headers = sourceHeaders;
                }
                try {
                    String scheme = uri.getScheme();
                    if ("http".equals(scheme) || "https".equals(scheme)) {
                        r.setDataSource(uri.toString(), headers != null ? headers : new HashMap<String, String>());
                    } else {
                        r.setDataSource(context, uri);
                    }
                } catch (Exception e) {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                        // nothing to do
                    }
                    synchronized (this) {
                        retrieverFailed = true;
                    }
                    return null;
                }
                synchronized (this) {
                    retriever = r;
                }
            }
            int w = dp(THUMB_WIDTH_DP);
            int h = dp(120);
            Bitmap frame;
            long us = positionMs * 1000L;
            if (Build.VERSION.SDK_INT >= 27) {
                frame = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            } else {
                frame = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null && frame.getWidth() > w) {
                    int sh = Math.max(1, Math.round(frame.getHeight() * (w / (float) frame.getWidth())));
                    frame = Bitmap.createScaledBitmap(frame, w, sh, true);
                }
            }
            return frame;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void closeRetriever() {
        MediaMetadataRetriever r;
        synchronized (this) {
            r = retriever;
            retriever = null;
        }
        if (r != null) {
            try {
                r.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }
}
