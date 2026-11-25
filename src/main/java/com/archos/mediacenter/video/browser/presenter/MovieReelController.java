package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.util.LruCache;

import com.archos.mediacenter.utils.ThumbnailEngine;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediascraper.BaseTags;
import com.archos.mediascraper.VideoTags;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/** Phone movie reel. Thumbnail loading and movie actions stay with the browser. */
public final class MovieReelController {
    private final Context context;
    private final ListView list;
    private final ImageView background;
    private final Runnable refresh;
    private final IntConsumer play;
    private final IntConsumer info;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Long, String> genres = new LruCache<>(64);
    private volatile long selectedId;
    private volatile boolean closed;
    private long loadingGenre = -1;
    private Bitmap backgroundSource;

    public MovieReelController(Context context, ListView list, long selectedId,
            Runnable refresh, IntConsumer play, IntConsumer info) {
        this.context = context.getApplicationContext();
        this.list = list;
        this.selectedId = selectedId;
        this.refresh = refresh;
        this.play = play;
        this.info = info;
        background = new ImageView(context);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        background.setAlpha(0.7f);
        list.setPadding(dp(24), dp(12), dp(16), dp(80));
        list.setClipToPadding(false);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!list.isInTouchMode()) select(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }

    public View wrap(View content) {
        FrameLayout root = new FrameLayout(content.getContext());
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF008C91, 0xFF004A52, 0xFF071C27}));
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(content.getContext());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33001219, 0x66000D18, 0xBB001019}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        content.setBackgroundColor(Color.TRANSPARENT);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    public long getSelectedId() { return selectedId; }

    public boolean isSelected(long id) { return selectedId == id; }

    public void ensureSelection() {
        if (closed || list.getAdapter() == null) return;
        if (list.getCount() == 0) {
            selectedId = -1;
            backgroundSource = null;
            background.animate().cancel();
            background.setImageDrawable(null);
            return;
        }
        for (int i = 0; i < list.getCount(); i++)
            if (list.getAdapter().getItemId(i) == selectedId) return;
        select(0);
    }

    public void select(int position) {
        if (closed || list.getAdapter() == null || position < 0 || position >= list.getCount()) return;
        Object item = list.getItemAtPosition(position);
        if (!(item instanceof Video)) return;
        long id = ((Video) item).getId();
        if (selectedId == id) return;
        selectedId = id;
        backgroundSource = null;
        background.animate().cancel();
        background.setImageDrawable(null);
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int offset = top == null ? list.getPaddingTop() : top.getTop();
        refresh.run();
        list.setSelectionFromTop(first, offset);
    }

    public void onScrollIdle() {
        if (closed || list.getChildCount() == 0) return;
        float target = list.getHeight() * 0.42f;
        int nearest = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            float d = Math.abs((child.getTop() + child.getBottom()) / 2f - target);
            if (d < distance) { nearest = i; distance = d; }
        }
        select(list.getFirstVisiblePosition() + nearest);
    }

    public Presenter presenter(Context themedContext, CommonPresenter.ExtendedClickListener listener) {
        return new VideoListPresenter(themedContext, listener, null) {
            @Override public View getView(ViewGroup parent, Object object, View recycled) {
                View row = recycled;
                if (row == null || row.findViewById(R.id.movie_reel_details) == null) {
                    row = LayoutInflater.from(themedContext).inflate(R.layout.movie_reel_row, parent, false);
                    ViewHolder holder = new ViewHolder();
                    holder.thumbnail = row.findViewById(R.id.thumbnail);
                    holder.name = row.findViewById(R.id.name);
                    row.setTag(holder);
                }
                return row;
            }

            @Override public View bindView(View row, Object object, ThumbnailEngine.Result result, int position) {
                super.bindView(row, object, result, position);
                Video video = (Video) object;
                if (selectedId < 0 && position == 0) selectedId = video.getId();
                boolean selected = isSelected(video.getId());
                ViewHolder holder = (ViewHolder) row.getTag();
                ViewGroup.LayoutParams posterSize = holder.thumbnail.getLayoutParams();
                posterSize.width = dp(selected ? 120 : 88);
                posterSize.height = dp(selected ? 180 : 132);
                holder.thumbnail.setLayoutParams(posterSize);
                View details = row.findViewById(R.id.movie_reel_details);
                details.setVisibility(selected ? View.VISIBLE : View.GONE);
                row.setContentDescription(video.getName());
                row.setActivated(selected);
                TextView meta = row.findViewById(R.id.movie_reel_meta);
                ArrayList<String> fields = new ArrayList<>();
                if (video instanceof Movie && ((Movie) video).getYear() > 0)
                    fields.add(String.valueOf(((Movie) video).getYear()));
                if (video.getDurationMs() > 0)
                    fields.add(android.text.format.DateUtils.formatElapsedTime(video.getDurationMs() / 1000));
                String genre = genres.get(video.getId());
                if (genre != null && !genre.isEmpty()) fields.add(genre);
                meta.setText(android.text.TextUtils.join(" · ", fields));
                meta.setVisibility(fields.isEmpty() ? View.GONE : View.VISIBLE);
                row.findViewById(R.id.movie_reel_play).setOnClickListener(v -> play.accept(position));
                row.findViewById(R.id.movie_reel_info).setOnClickListener(v -> info.accept(position));
                if (selected) {
                    loadGenre(video);
                    Bitmap bitmap = result == null ? null : result.getThumbnail();
                    updateBackground(bitmap, video.getId());
                }
                return row;
            }
        };
    }

    private void loadGenre(Video video) {
        if (closed || !(video instanceof Movie) || genres.get(video.getId()) != null || loadingGenre == video.getId()) return;
        loadingGenre = video.getId();
        worker.execute(() -> {
            if (closed) return;
            String genre = "";
            try {
                BaseTags tags = video.getFullScraperTags(context);
                if (tags instanceof VideoTags) {
                    String value = ((VideoTags) tags).getGenresFormatted(context);
                    if (value != null) genre = value;
                }
            } catch (RuntimeException ignored) { /* Missing scraper data is optional. */ }
            String value = genre;
            main.post(() -> {
                if (closed) return;
                genres.put(video.getId(), value);
                if (selectedId == video.getId()) refresh.run();
            });
        });
    }

    private void updateBackground(Bitmap bitmap, long id) {
        if (closed || bitmap == null || bitmap == backgroundSource) return;
        backgroundSource = bitmap;
        worker.execute(() -> {
            if (closed || selectedId != id || bitmap.isRecycled()) return;
            Bitmap blurred = blur(bitmap);
            main.post(() -> {
                if (closed || selectedId != id || backgroundSource != bitmap) return;
                background.setAlpha(0f);
                background.setImageBitmap(blurred);
                background.animate().alpha(0.7f).setDuration(240).start();
            });
        });
    }

    private static Bitmap blur(Bitmap source) {
        final int width = 72, height = 108, radius = 5, diameter = radius * 2 + 1;
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        int[] out = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 3; pass++) {
            for (int axis = 0; axis < 2; axis++) {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int red = 0, green = 0, blue = 0;
                    for (int d = -radius; d <= radius; d++) {
                        int xx = axis == 0 ? Math.max(0, Math.min(width - 1, x + d)) : x;
                        int yy = axis == 1 ? Math.max(0, Math.min(height - 1, y + d)) : y;
                        int color = pixels[yy * width + xx];
                        red += Color.red(color); green += Color.green(color); blue += Color.blue(color);
                    }
                    out[y * width + x] = Color.rgb(red / diameter, green / diameter, blue / diameter);
                }
                int[] swap = pixels; pixels = out; out = swap;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int dp(int value) { return Math.round(value * list.getResources().getDisplayMetrics().density); }

    public void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        background.animate().cancel();
        background.setImageDrawable(null);
        backgroundSource = null;
    }
}
