package com.archos.mediacenter.video.browser;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.mappers.CompatibleCursorMapper;
import com.archos.mediacenter.video.browser.adapters.mappers.TvshowCursorMapper;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.loader.AllTvshowsLoader;
import com.archos.mediacenter.video.browser.loader.MoviesLoader;
import android.net.Uri;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Movies / TV shows page: poster cards in a 3 column grid, with filter chips (All, In progress, Watched, Unwatched)
 * and a search box. No action bar. Opened from the Library page (see BrowserCategory.openPosterGrid).
 */
public class PosterGridFragment extends Fragment {
    private static final String TAG = "PosterGridFragment";
    public static final String ARG_TV = "tv";
    /** Embedded in the Library page above the cards: no title, no insets, no action bar changes, grows with its content. */
    public static final String ARG_EMBED = "embed";
    /** Movies and TV shows together (the Home grid). */
    public static final String ARG_BOTH = "both";
    private static final int EMBED_MAX = 200;
    private static final int LOADER_ID = 9201;
    private static final int FILTER_ALL = 0, FILTER_PROGRESS = 1, FILTER_WATCHED = 2, FILTER_UNWATCHED = 3;

    private boolean mTv;
    private boolean mEmbed;
    private boolean mBoth;
    private TextView mCount;
    private final List<HomePosterAdapter.Poster> mMoviesList = new ArrayList<>();
    private final List<HomePosterAdapter.Poster> mShowsList = new ArrayList<>();
    private int mFilter = FILTER_ALL;
    private String mQuery = "";
    private final List<HomePosterAdapter.Poster> mAll = new ArrayList<>();
    private final List<HomePosterAdapter.Poster> mShown = new ArrayList<>();
    private RecyclerView.Adapter<CardHolder> mAdapter;
    private TextView[] mChips;
    private TextView mEmpty;
    private RecyclerView mGrid;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final Context ctx = requireContext();
        mTv = getArguments() != null && getArguments().getBoolean(ARG_TV, false);
        mEmbed = getArguments() != null && getArguments().getBoolean(ARG_EMBED, false);
        mBoth = getArguments() != null && getArguments().getBoolean(ARG_BOTH, false);
        int bg = PreferenceManager.getDefaultSharedPreferences(ctx).getInt("classic_home_background_color", 0xFF0B1220);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        if (!mEmbed) {
            root.setBackgroundColor(bg);   // the action bar (title + list view button) sits above the page
        }

        TextView title = new TextView(ctx);
        title.setText(mBoth ? R.string.home_movies_shows : mTv ? R.string.all_tv_shows : R.string.movies);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(16), dp(12), dp(16), dp(4));
        if (mBoth) {
            // Home: same header as the other rows (name + count)
            title.setTextSize(16);
            title.setPadding(0, 0, 0, 0);
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(12), dp(12), dp(12), dp(2));
            header.addView(title);
            mCount = new TextView(ctx);
            mCount.setTextColor(0xFF9FB3D1);
            mCount.setTextSize(13);
            LinearLayout.LayoutParams clp0 = new LinearLayout.LayoutParams(-2, -2);
            clp0.setMarginStart(dp(12));
            header.addView(mCount, clp0);
            root.addView(header);
        }   // full page: the title is in the action bar

        EditText search = new EditText(ctx);
        search.setHint(R.string.grid_search_hint);
        search.setHintTextColor(0xFF9FB3D1);
        search.setTextColor(Color.WHITE);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xFF1F2B44);
        sbg.setCornerRadius(dp(20));
        search.setBackground(sbg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(dp(12), dp(4), dp(12), dp(4));
        if (!mBoth) root.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
        });

        HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        chipScroll.addView(chipRow);
        int[] labels = {R.string.grid_all, R.string.grid_in_progress, R.string.grid_watched, R.string.grid_unwatched};
        mChips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int filter = i;
            TextView chip = new TextView(ctx);
            chip.setText(labels[i]);
            chip.setTextSize(13);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setFocusable(true);
            chip.setOnClickListener(v -> { mFilter = filter; updateChips(); applyFilter(); });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(dp(8));
            chipRow.addView(chip, clp);
            mChips[i] = chip;
        }
        updateChips();
        if (!mBoth) root.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));

        mEmpty = new TextView(ctx);
        mEmpty.setTextColor(0xFF9FB3D1);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setText(R.string.grid_empty);
        mEmpty.setVisibility(View.GONE);
        mEmpty.setPadding(0, dp(24), 0, 0);
        root.addView(mEmpty, new LinearLayout.LayoutParams(-1, -2));

        RecyclerView grid = new RecyclerView(ctx);
        mGrid = grid;
        grid.setLayoutManager(new GridLayoutManager(ctx, 3));
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(4), dp(8), dp(16));
        mAdapter = new RecyclerView.Adapter<CardHolder>() {
            @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new CardHolder(newCard(parent.getContext()));
            }
            @Override public void onBindViewHolder(@NonNull CardHolder h, int position) {
                final HomePosterAdapter.Poster p = mShown.get(position);
                h.name.setText(p.title);
                h.meta.setText(TextUtils.isEmpty(p.meta) ? " " : p.meta);
                h.meta.setVisibility(View.VISIBLE);
                Uri src = p.poster;
                if (src != null) Picasso.get().load(src).resize(300, 450).centerCrop().into(h.poster);
                else { Picasso.get().cancelRequest(h.poster); h.poster.setImageDrawable(null); }
                h.itemView.setOnClickListener(v -> {
                    BrowserCategory owner = owner();
                    if (owner != null) owner.openPosterFromGrid(p);
                });
                h.itemView.setOnLongClickListener(v -> {
                    BrowserCategory owner = owner();
                    return owner != null && owner.longPosterFromGrid(p, v);
                });
            }
            @Override public int getItemCount() { return mShown.size(); }
        };
        grid.setAdapter(mAdapter);
        if (mEmbed) {
            grid.setNestedScrollingEnabled(false);   // the Library page scrolls
            root.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        } else {
            root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return root;
    }

    private BrowserCategory owner() {
        Fragment pf = getParentFragment();
        if (pf instanceof BrowserCategory) return (BrowserCategory) pf;
        Fragment f = getParentFragmentManager().findFragmentById(R.id.category);
        return f instanceof BrowserCategory ? (BrowserCategory) f : null;
    }

    /** Poster card: 2:3 poster with rounded corners, name and details below. */
    private View newCard(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(8));
        card.setLayoutParams(lp);
        ImageView poster = new androidx.appcompat.widget.AppCompatImageView(ctx) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(width * 1.5f), MeasureSpec.EXACTLY));
            }
        };
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFF1F2B44);
        pbg.setCornerRadius(0f);
        poster.setBackground(pbg);
        poster.setClipToOutline(true);
        card.addView(poster, new LinearLayout.LayoutParams(-1, -2));
        TextView name = new TextView(ctx);
        name.setTextColor(Color.WHITE);
        name.setTextSize(12);
        name.setTypeface(null, Typeface.BOLD);
        name.setLines(2);   // fixed height so rows never overlap
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        card.addView(name);
        TextView meta = new TextView(ctx);
        meta.setTextColor(0xFF9FB3D1);
        meta.setTextSize(10);
        meta.setSingleLine(true);
        meta.setPadding(dp(2), 0, dp(2), 0);
        card.addView(meta);
        card.setTag(new View[]{poster, name, meta});
        return card;
    }

    private static class CardHolder extends RecyclerView.ViewHolder {
        final ImageView poster; final TextView name, meta;
        CardHolder(View v) {
            super(v);
            View[] parts = (View[]) v.getTag();
            poster = (ImageView) parts[0];
            name = (TextView) parts[1];
            meta = (TextView) parts[2];
        }
    }

    private void updateChips() {
        for (int i = 0; i < mChips.length; i++) {
            boolean sel = i == mFilter;
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(18));
            d.setColor(sel ? 0xFFFFFFFF : 0xFF1F2B44);
            mChips[i].setBackground(d);
            mChips[i].setTextColor(sel ? 0xFF0B1220 : 0xFFDFE7F2);
        }
    }

    private void applyFilter() {
        mShown.clear();
        for (HomePosterAdapter.Poster p : mAll) {
            if (!mQuery.isEmpty() && (p.title == null || !p.title.toLowerCase(Locale.getDefault()).contains(mQuery))) continue;
            if (!matchesFilter(p)) continue;
            mShown.add(p);
            if (mEmbed && mShown.size() >= EMBED_MAX) break;   // all items are laid out inside the scrolling page: search / filters narrow the list
        }
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        if (mEmpty != null) mEmpty.setVisibility(mShown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean matchesFilter(HomePosterAdapter.Poster p) {
        if (mFilter == FILTER_ALL) return true;
        boolean watched, started;
        if (p.item instanceof Tvshow) {
            Tvshow t = (Tvshow) p.item;
            watched = t.isWatched();
            started = t.getEpisodeWatchedCount() > 0;
        } else if (p.item instanceof Video) {
            Video v = (Video) p.item;
            watched = v.isWatched();
            started = v.getResumeMs() > 0;
        } else {
            return true;
        }
        if (mFilter == FILTER_WATCHED) return watched;
        if (mFilter == FILTER_PROGRESS) return !watched && started;
        return !watched && !started;
    }

    private HomePosterAdapter.Poster toPoster(Object o) {
        if (o instanceof Tvshow) {
            Tvshow t = (Tvshow) o;
            String meta = t.getSeasonCount() > 1
                    ? getString(R.string.home_seasons_episodes, t.getSeasonCount(), t.getEpisodeCount())
                    : getString(R.string.home_season_episodes, t.getEpisodeCount());
            return new HomePosterAdapter.Poster(t.getName(), meta, t.getPosterUri(), t, 1);
        }
        if (o instanceof Video) {
            Video v = (Video) o;
            String meta = "";
            if (v instanceof Movie) {
                Movie m = (Movie) v;
                meta = m.getYear() > 0 ? String.valueOf(m.getYear()) : "";
                if (m.getRating() > 0)
                    meta += (meta.isEmpty() ? "" : "  ") + String.format(Locale.US, "★ %.1f", m.getRating());
            }
            return new HomePosterAdapter.Poster(v.getName(), meta, v.getPosterUri(), v, 1);
        }
        return null;
    }

    /** Wide movie backdrop from the cursor (file first, then the online url); null when the query has none. */
    private static Uri backdropOf(Cursor c) {
        int file = c.getColumnIndex(com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.SCRAPER_BACKDROP_LARGE_FILE);
        if (file >= 0) {
            String f = c.getString(file);
            if (f != null && !f.isEmpty()) return Uri.parse("file://" + f);
        }
        int url = c.getColumnIndex(com.archos.mediaprovider.video.VideoStore.Video.VideoColumns.SCRAPER_BACKDROP_LARGE_URL);
        if (url >= 0) {
            String u = c.getString(url);
            if (u != null && !u.isEmpty()) return Uri.parse(u);
        }
        return null;
    }

    private List<HomePosterAdapter.Poster> readPosters(Cursor c, boolean tv) {
        List<HomePosterAdapter.Poster> out = new ArrayList<>();
        if (c != null && !c.isClosed() && c.moveToFirst()) {
            try {
                CompatibleCursorMapper mapper = tv ? new TvshowCursorMapper() : new VideoCursorMapper();
                mapper.bindColumns(c);
                do {
                    HomePosterAdapter.Poster p = toPoster(mapper.bind(c));
                    if (p != null) {
                        p.backdrop = backdropOf(c);
                        out.add(p);
                    }
                } while (c.moveToNext());
            } catch (RuntimeException e) {
                Log.w(TAG, "readPosters: could not read the list", e);
            }
        }
        return out;
    }

    private void rebuildAll() {
        mAll.clear();
        mAll.addAll(mMoviesList);
        mAll.addAll(mShowsList);
        if (mCount != null) {
            StringBuilder sb = new StringBuilder();
            if (!mMoviesList.isEmpty()) sb.append(getString(R.string.home_count_movies, mMoviesList.size()));
            if (!mShowsList.isEmpty()) sb.append(sb.length() > 0 ? "  \u00B7  " : "").append(getString(R.string.home_count_series, mShowsList.size()));
            mCount.setText(sb);
        }
        applyFilter();
    }

    private LoaderManager.LoaderCallbacks<Cursor> callbacks(final boolean tv) {
        return new LoaderManager.LoaderCallbacks<Cursor>() {
            @NonNull @Override public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
                Context c = requireContext();
                return tv ? new AllTvshowsLoader(c).getV4CursorLoader(false, false)
                        : new MoviesLoader(c, MoviesLoader.DEFAULT_SORT, true, true, false, 0).getV4CursorLoader(true, false);
            }
            @Override public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor c) {
                (tv ? mShowsList : mMoviesList).clear();
                (tv ? mShowsList : mMoviesList).addAll(readPosters(c, tv));
                rebuildAll();
            }
            @Override public void onLoaderReset(@NonNull Loader<Cursor> loader) {
                (tv ? mShowsList : mMoviesList).clear();
                rebuildAll();
            }
        };
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LoaderManager lm = LoaderManager.getInstance(this);
        if (mBoth || !mTv) lm.initLoader(LOADER_ID, null, callbacks(false));
        if (mBoth || mTv) lm.initLoader(LOADER_ID + 1, null, callbacks(true));
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mEmbed) return;
        // full page: the action bar stays, titled Movies / TV shows
        if (getActivity() instanceof AppCompatActivity) {
            androidx.appcompat.app.ActionBar bar = ((AppCompatActivity) getActivity()).getSupportActionBar();
            if (bar != null) {
                bar.show();
                bar.setDisplayShowTitleEnabled(true);
                bar.setTitle(mBoth ? R.string.home_movies_shows : mTv ? R.string.all_tv_shows : R.string.movies);
            }
        }
        requireActivity().invalidateOptionsMenu();   // the list view button follows the current mode
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mEmbed || getActivity() == null) return;
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).updatePhoneHomeActionBar();
    }
}
