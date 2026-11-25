package com.archos.mediacenter.video.browser;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.utils.MediaUtils;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.loader.AllVideosLoader;
import com.archos.mediacenter.video.browser.loader.LastAddedLoader;
import com.archos.mediacenter.video.browser.loader.LastPlayedLoader;
import com.archos.mediaprovider.video.VideoStore;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Library page: All videos / Recently added / Played as a list. Thumbnail on the left; on the right the title,
 * then size, resolution and codec, then a line that depends on the page (resume point, year and rating, date added).
 * Embedded above the Library sources row.
 */
public class VideoListFragment extends Fragment {
    private static final String TAG = "VideoListFragment";
    public static final String ARG_MODE = "mode";
    public static final int MODE_ALL = 0;
    public static final int MODE_ADDED = 1;
    public static final int MODE_PLAYED = 2;
    private static final int LOADER_ID = 9210;
    private static final int MAX_ITEMS = 3000;

    private static class Row {
        final HomePosterAdapter.Poster poster;
        final Video video;
        final String line1, line2;
        String line3;
        Row(HomePosterAdapter.Poster poster, Video video, String line1, String line2, String line3) {
            this.poster = poster;
            this.video = video;
            this.line1 = line1;
            this.line2 = line2;
            this.line3 = line3;
        }
    }

    private int mMode;
    private final List<Row> mRows = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mFrameExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final java.util.Random mRandom = new java.util.Random();
    private RecyclerView mList;
    // one frame per video, kept: in memory while the page is open and on disk (cache folder) for next time
    private final android.util.LruCache<Long, Bitmap> mFrameCache = new android.util.LruCache<>(80);
    private final java.util.Set<Long> mNoFrame = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
    private int mGeneration;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        mMode = getArguments() != null ? getArguments().getInt(ARG_MODE, MODE_ALL) : MODE_ALL;
        final Context ctx = requireContext();
        RecyclerView list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx));
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(8));
        mAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(newRow(parent.getContext())) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                final Row row = mRows.get(position);
                LinearLayout item = (LinearLayout) h.itemView;
                ImageView thumb = (ImageView) item.getChildAt(0);
                LinearLayout texts = (LinearLayout) item.getChildAt(1);
                ((TextView) texts.getChildAt(0)).setText(row.line1);
                TextView l2 = (TextView) texts.getChildAt(1);
                l2.setText(row.line2);
                l2.setVisibility(TextUtils.isEmpty(row.line2) ? View.GONE : View.VISIBLE);
                TextView l3 = (TextView) texts.getChildAt(2);
                l3.setText(row.line3);
                l3.setVisibility(TextUtils.isEmpty(row.line3) ? View.GONE : View.VISIBLE);
                if (row.poster.poster != null) {
                    if (mMode == MODE_ALL) Picasso.get().load(row.poster.poster).resize(240, 240).centerCrop().into(thumb);
                    else Picasso.get().load(row.poster.poster).resize(240, 360).centerCrop().into(thumb);
                } else {
                    Picasso.get().cancelRequest(thumb);
                    thumb.setImageDrawable(null);
                }
                item.setTag(row.video.getId());
                if (mMode == MODE_ALL) loadFrame(item, row.video);
                item.setOnClickListener(v -> {
                    BrowserCategory owner = owner();
                    if (owner != null) owner.openPosterFromGrid(row.poster);
                });
                item.setOnLongClickListener(v -> {
                    BrowserCategory owner = owner();
                    return owner != null && owner.longPosterFromGrid(row.poster, v);
                });
            }

            @Override
            public int getItemCount() {
                return mRows.size();
            }
        };
        list.setAdapter(mAdapter);
        mList = list;
        // The list scrolls by itself (it only builds the rows that are on screen): it gets the height of the page
        // and keeps the touch, so the page around it does not take the vertical drag.
        list.setNestedScrollingEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setLayoutParams(new ViewGroup.LayoutParams(-1, dp(480)));
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN && rv.getParent() != null) {
                    rv.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });
        final View scroll = requireActivity().findViewById(R.id.home_classic);
        if (scroll != null) {
            final View.OnLayoutChangeListener fit = (v, l, t, rr, b, ol, ot, or, ob) -> {
                int h = b - t;
                ViewGroup.LayoutParams lp = list.getLayoutParams();
                if (h > 0 && lp.height != h) {
                    lp.height = h;
                    list.setLayoutParams(lp);
                }
            };
            scroll.addOnLayoutChangeListener(fit);
            list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(@NonNull View v) { }
                @Override public void onViewDetachedFromWindow(@NonNull View v) { scroll.removeOnLayoutChangeListener(fit); }
            });
            scroll.post(() -> fit.onLayoutChange(scroll, scroll.getLeft(), scroll.getTop(), scroll.getRight(), scroll.getBottom(), 0, 0, 0, 0));
        }
        return list;
    }

    private BrowserCategory owner() {
        Fragment pf = getParentFragment();
        return pf instanceof BrowserCategory ? (BrowserCategory) pf : null;
    }

    /** One row: thumbnail | title / size · resolution · codec / page specific line. */
    private View newRow(Context ctx) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setPadding(dp(12), dp(6), dp(12), dp(6));
        item.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1B2638);
        bg.setCornerRadius(dp(8));
        thumb.setBackground(bg);
        thumb.setClipToOutline(true);
        if (mMode == MODE_ALL) item.addView(thumb, new LinearLayout.LayoutParams(dp(84), dp(84)));   // square, shows frames of the video
        else item.addView(thumb, new LinearLayout.LayoutParams(dp(64), dp(96)));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMarginStart(dp(12));
        item.addView(texts, tlp);
        TextView l1 = new TextView(ctx);
        l1.setTextColor(Color.WHITE);
        l1.setTextSize(15);
        l1.setTypeface(Typeface.DEFAULT_BOLD);
        l1.setMaxLines(2);
        l1.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(l1);
        TextView l2 = new TextView(ctx);
        l2.setTextColor(0xFFB4C2DA);
        l2.setTextSize(12.5f);
        l2.setSingleLine(true);
        l2.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(l2);
        TextView l3 = new TextView(ctx);
        l3.setTextColor(0xFF9FB3D1);
        l3.setTextSize(12.5f);
        l3.setSingleLine(true);
        l3.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(l3);
        return item;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LoaderManager.getInstance(this).initLoader(LOADER_ID + mMode, null, new LoaderManager.LoaderCallbacks<Cursor>() {
            @NonNull @Override
            public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
                Context c = requireContext();
                if (mMode == MODE_ADDED) return new LastAddedLoader(c, false).getV4CursorLoader(false, false);
                if (mMode == MODE_PLAYED) return new LastPlayedLoader(c).getV4CursorLoader(false, false);
                return new AllVideosLoader(c).getV4CursorLoader(false, false);
            }

            @Override
            public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor c) {
                readRows(c);
            }

            @Override
            public void onLoaderReset(@NonNull Loader<Cursor> loader) {
                mRows.clear();
                mAdapter.notifyDataSetChanged();
            }
        });
    }

    private java.io.File frameFile(long id) {
        java.io.File dir = new java.io.File(requireContext().getCacheDir(), "list_frames");
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, id + ".jpg");
    }

    private void showFrame(final LinearLayout item, Object tag, Bitmap frame) {
        if (!isAdded() || !tag.equals(item.getTag())) return;
        ImageView thumb = (ImageView) item.getChildAt(0);
        Picasso.get().cancelRequest(thumb);
        thumb.setImageBitmap(frame);
    }

    /**
     * A random frame of the video, cropped square. It is taken once and then kept (memory + cache folder): the thumbnail
     * does not change afterwards. Local files only (no network reads).
     */
    private void loadFrame(final LinearLayout item, final Video v) {
        final Object tag = item.getTag();
        final long id = v.getId();
        Bitmap cached = mFrameCache.get(id);
        if (cached != null) {
            showFrame(item, tag, cached);
            return;
        }
        final String path = v.getFilePath();
        if (path == null || !path.startsWith("/") || mNoFrame.contains(id)) return;
        final int durationMs = v.getDurationMs();
        final java.io.File file = frameFile(id);
        try {
            mFrameExecutor.execute(() -> {
                if (tag == null || !tag.equals(item.getTag())) return;   // the row was recycled for another video
                Bitmap out = null;
                if (file.exists()) out = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                if (out == null) {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try {
                        r.setDataSource(path);
                        long dur = durationMs > 0 ? durationMs : 0;
                        if (dur <= 0) {
                            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            dur = d != null ? Long.parseLong(d) : 0;
                        }
                        long at = dur > 0 ? (long) (dur * (0.05 + 0.85 * mRandom.nextDouble())) * 1000L : 0;
                        Bitmap full = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (full != null) {
                            int side = Math.min(full.getWidth(), full.getHeight());
                            Bitmap square = Bitmap.createBitmap(full, (full.getWidth() - side) / 2, (full.getHeight() - side) / 2, side, side);
                            out = Bitmap.createScaledBitmap(square, 240, 240, true);
                            if (square != full && square != out) square.recycle();
                            if (full != out) full.recycle();
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                                out.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                            } catch (java.io.IOException e) {
                                Log.d(TAG, "loadFrame: could not cache the frame: " + e.getMessage());
                            }
                        }
                    } catch (RuntimeException e) {
                        Log.d(TAG, "loadFrame: no frame for " + path + ": " + e.getMessage());
                    } finally {
                        try { r.release(); } catch (Exception ignored) { }
                    }
                }
                final Bitmap frame = out;
                if (frame == null) {
                    mNoFrame.add(id);   // do not try again on every scroll
                    return;
                }
                mFrameCache.put(id, frame);
                mMain.post(() -> showFrame(item, tag, frame));
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "loadFrame: executor stopped", e);
        }
    }

    @Override
    public void onDestroyView() {
        mGeneration++;
        mFrameExecutor.shutdownNow();
        mExecutor.shutdownNow();
        super.onDestroyView();
    }

    private void readRows(Cursor c) {
        mRows.clear();
        final List<Long> ids = new ArrayList<>();
        if (c != null && !c.isClosed() && c.moveToFirst()) {
            try {
                VideoCursorMapper mapper = new VideoCursorMapper();
                mapper.bindColumns(c);
                do {
                    Object o = mapper.bind(c);
                    if (!(o instanceof Video)) continue;
                    Video v = (Video) o;
                    HomePosterAdapter.Poster poster = new HomePosterAdapter.Poster(v.getName(), "", v.getPosterUri(), v, 1);
                    mRows.add(new Row(poster, v, v.getName(), techLine(v), pageLine(v, null)));
                    ids.add(v.getId());
                } while (c.moveToNext() && mRows.size() < MAX_ITEMS);
            } catch (RuntimeException e) {
                Log.w(TAG, "readRows: could not read the list", e);
            }
        }
        mAdapter.notifyDataSetChanged();
        if (mMode == MODE_ADDED && !ids.isEmpty()) loadDatesAdded(ids);
    }

    /** size · resolution · codec */
    private String techLine(Video v) {
        List<String> parts = new ArrayList<>();
        if (v.getSize() > 0) parts.add(Formatter.formatShortFileSize(requireContext(), v.getSize()));
        int w = v.getKnownWidth(), h = v.getKnownHeight();
        if (w > 0 && h > 0) {
            parts.add(w + "\u00D7" + h);
        } else {
            int def = v.getNormalizedDefinition();
            if (def == VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_4K) parts.add("4K");
            else if (def == VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_1080P) parts.add("1080p");
            else if (def == VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_720P) parts.add("720p");
            else if (def == VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_SD) parts.add("SD");
        }
        String codec = v.getVideoFormat();
        if (TextUtils.isEmpty(codec)) codec = v.getGuessedVideoFormat();
        if (!TextUtils.isEmpty(codec)) parts.add(codec.toUpperCase(Locale.getDefault()));
        return TextUtils.join("  \u00B7  ", parts);
    }

    /** Third line: depends on the page. */
    private String pageLine(Video v, @Nullable Long dateAddedSeconds) {
        if (mMode == MODE_PLAYED) {
            if (v.getResumeMs() > 0) return getString(R.string.list_resume_at, MediaUtils.formatTime(v.getResumeMs()));
            return getString(R.string.list_played);
        }
        List<String> parts = new ArrayList<>();
        String yearRating = yearAndRating(v);
        if (!yearRating.isEmpty()) parts.add(yearRating);
        if (mMode == MODE_ADDED && dateAddedSeconds != null && dateAddedSeconds > 0) {
            parts.add(getString(R.string.list_added_on, DateFormat.getMediumDateFormat(requireContext()).format(new Date(dateAddedSeconds * 1000L))));
        }
        return TextUtils.join("  \u00B7  ", parts);
    }

    private String yearAndRating(Video v) {
        int year = 0;
        float rating = 0;
        if (v instanceof Movie) {
            year = ((Movie) v).getYear();
            rating = ((Movie) v).getRating();
        } else if (v instanceof Episode) {
            Episode e = (Episode) v;
            if (e.getEpisodeDate() > 0) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(e.getEpisodeDate());
                year = cal.get(java.util.Calendar.YEAR);
            }
            rating = e.getEpisodeRating();
        }
        StringBuilder sb = new StringBuilder();
        if (year > 0) sb.append(year);
        if (rating > 0) sb.append(sb.length() > 0 ? "  " : "").append(String.format(Locale.US, "\u2605 %.1f", rating));
        return sb.toString();
    }

    /** Date added is not in the list's columns: read it for the shown videos, off the main thread. */
    private void loadDatesAdded(final List<Long> ids) {
        final int generation = ++mGeneration;
        final ContentResolver resolver = requireContext().getContentResolver();
        try {
            mExecutor.execute(() -> {
                final Map<Long, Long> dates = new HashMap<>();
                for (int from = 0; from < ids.size(); from += 400) {
                    List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 400));
                    StringBuilder in = new StringBuilder();
                    for (Long id : chunk) in.append(in.length() > 0 ? "," : "").append(id);
                    try (Cursor dc = resolver.query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                            new String[]{VideoStore.Video.VideoColumns._ID, VideoStore.Video.VideoColumns.DATE_ADDED},
                            VideoStore.Video.VideoColumns._ID + " IN (" + in + ")", null, null)) {
                        while (dc != null && dc.moveToNext()) dates.put(dc.getLong(0), dc.getLong(1));
                    } catch (RuntimeException e) {
                        Log.w(TAG, "loadDatesAdded: query failed", e);
                    }
                }
                mMain.post(() -> {
                    if (generation != mGeneration || !isAdded()) return;
                    for (Row r : mRows) r.line3 = pageLine(r.video, dates.get(r.video.getId()));
                    mAdapter.notifyDataSetChanged();
                });
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "loadDatesAdded: executor stopped", e);
        }
    }
}
