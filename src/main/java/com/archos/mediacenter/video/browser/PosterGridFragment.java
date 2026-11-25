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
    /** Library Movies / TV shows pages: banner rows (backdrop with the date and title on it) instead of the poster grid. */
    public static final String PREF_BANNER_LIST = "library_banner_list";
    private static final int EMBED_MAX = 200;
    private static final int LOADER_ID = 9201;
    private static final int MENU_LIST_VIEW = 9301;
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
    /** Banner text position along the bottom: left, center or right. */
    public static final String PREF_BANNER_TEXT_ALIGN = "library_banner_text_align";
    private RecyclerView mGrid;
    private boolean mBanner;
    private String mBannerAlign = "center";

    private String wantBannerAlign() {
        return PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(PREF_BANNER_TEXT_ALIGN, "center");
    }

    private void alignBannerText(CardHolder h) {
        String a = mBannerAlign;
        int g = "left".equals(a) ? Gravity.START : "right".equals(a) ? Gravity.END : Gravity.CENTER_HORIZONTAL;
        h.name.setGravity(g);
        h.meta.setGravity(g);
        if (h.name.getParent() instanceof LinearLayout) ((LinearLayout) h.name.getParent()).setGravity(g | Gravity.BOTTOM);
    }

    private boolean wantBanner() {
        return !mBoth && PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(PREF_BANNER_LIST, false);
    }

    private void applyLayoutMode() {
        if (mGrid == null) return;
        boolean want = wantBanner();
        String align = wantBannerAlign();
        if (want == mBanner && align.equals(mBannerAlign) && mGrid.getLayoutManager() != null) return;
        boolean modeChanged = want != mBanner || mGrid.getLayoutManager() == null;
        mBannerAlign = align;
        mBanner = want;
        if (!modeChanged) { if (mAdapter != null) mAdapter.notifyDataSetChanged(); return; }
        mGrid.setLayoutManager(want ? new androidx.recyclerview.widget.LinearLayoutManager(requireContext())
                : new GridLayoutManager(requireContext(), 3));
        mGrid.setPadding(dp(want ? 0 : 8), dp(4), dp(want ? 0 : 8), dp(16));
        if (mAdapter != null) mAdapter.notifyDataSetChanged();   // view types changed
    }

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
        mBanner = wantBanner();
        mBannerAlign = wantBannerAlign();
        grid.setLayoutManager(mBanner ? new androidx.recyclerview.widget.LinearLayoutManager(ctx) : new GridLayoutManager(ctx, 3));
        grid.setClipToPadding(false);
        grid.setPadding(dp(mBanner ? 0 : 8), dp(4), dp(mBanner ? 0 : 8), dp(16));
        mAdapter = new RecyclerView.Adapter<CardHolder>() {
            @NonNull @Override public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new CardHolder(viewType == 1 ? newBanner(parent.getContext()) : newCard(parent.getContext()));
            }
            @Override public int getItemViewType(int position) { return mBanner ? 1 : 0; }
            @Override public void onBindViewHolder(@NonNull CardHolder h, int position) {
                final HomePosterAdapter.Poster p = mShown.get(position);
                if (mBanner) alignBannerText(h);
                h.name.setText(p.title);
                h.meta.setText(p.meta);
                h.meta.setVisibility(TextUtils.isEmpty(p.meta) ? View.GONE : View.VISIBLE);
                Uri src = mBanner && p.backdrop != null ? p.backdrop : p.poster;
                if (src != null && mBanner) Picasso.get().load(src).resize(800, 0).onlyScaleDown().into(h.poster);   // whole picture, top aligned by the view
                else if (src != null) Picasso.get().load(src).resize(300, 450).centerCrop().into(h.poster);
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
        pbg.setCornerRadius(dp(10));
        poster.setBackground(pbg);
        poster.setClipToOutline(true);
        card.addView(poster, new LinearLayout.LayoutParams(-1, -2));
        TextView name = new TextView(ctx);
        name.setTextColor(Color.WHITE);
        name.setTextSize(12);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(2);
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

    /** Banner row: backdrop darkened with the date / year line (accent colour) and the title centred on it. */
    private View newBanner(Context ctx) {
        android.widget.FrameLayout card = new android.widget.FrameLayout(ctx);
        card.setFocusable(true);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(120));
        lp.setMargins(0, 0, 0, dp(2));
        card.setLayoutParams(lp);
        ImageView image = new TopCropImageView(ctx);   // shows the top part of the backdrop
        image.setBackgroundColor(0xFF1F2B44);
        card.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x55000000, 0xAA000000}));
        card.addView(shade, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);   // text sits at the bottom of the banner
        texts.setPadding(dp(16), 0, dp(16), dp(10));
        TextView meta = new TextView(ctx);
        meta.setTextColor(0xFFF5C542);
        meta.setTextSize(12);
        meta.setTypeface(null, Typeface.BOLD);
        meta.setGravity(Gravity.CENTER);
        meta.setSingleLine(true);
        meta.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
        texts.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        TextView name = new TextView(ctx);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        android.widget.FrameLayout.LayoutParams tlp = new android.widget.FrameLayout.LayoutParams(-1, -1);
        card.addView(texts, tlp);
        card.setTag(new View[]{image, name, meta});
        return card;
    }

    /** Fills the view width and keeps the top of the picture (backdrops lose their bottom, not their top). */
    private static class TopCropImageView extends androidx.appcompat.widget.AppCompatImageView {
        TopCropImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
        }
        @Override protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            applyTopCrop();
            return changed;
        }
        @Override public void setImageDrawable(@Nullable android.graphics.drawable.Drawable d) {
            super.setImageDrawable(d);
            applyTopCrop();
        }
        private void applyTopCrop() {
            android.graphics.drawable.Drawable d = getDrawable();
            int vw = getWidth(), vh = getHeight();
            if (d == null || vw == 0 || vh == 0) return;
            int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            float scale = Math.max(vw / (float) dw, vh / (float) dh);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(scale, scale);
            m.postTranslate((vw - dw * scale) / 2f, 0f);
            setImageMatrix(m);
        }
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
        if (!mEmbed && !mBoth) {
            // Action bar: list view button (banner list of the Library Movies / TV shows pages <-> poster grid)
            requireActivity().addMenuProvider(new MenuProvider() {
                @Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                    MenuItem item = menu.add(0, MENU_LIST_VIEW, Menu.NONE, R.string.grid_list_view);
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    androidx.core.view.MenuItemCompat.setIconTintList(item,
                            android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                    updateListViewItem(item);
                }
                @Override public void onPrepareMenu(@NonNull Menu menu) {
                    MenuItem item = menu.findItem(MENU_LIST_VIEW);
                    if (item != null) updateListViewItem(item);
                }
                @Override public boolean onMenuItemSelected(@NonNull MenuItem item) {
                    if (item.getItemId() != MENU_LIST_VIEW) return false;
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putBoolean(PREF_BANNER_LIST, !mBanner).apply();   // same setting as the Library pages
                    applyLayoutMode();
                    requireActivity().invalidateOptionsMenu();
                    return true;
                }
            }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        }
        LoaderManager lm = LoaderManager.getInstance(this);
        if (mBoth || !mTv) lm.initLoader(LOADER_ID, null, callbacks(false));
        if (mBoth || mTv) lm.initLoader(LOADER_ID + 1, null, callbacks(true));
    }

    /** The button shows what a tap switches to: the list icon on the poster grid, the grid icon on the banner list. */
    private void updateListViewItem(MenuItem item) {
        item.setIcon(mBanner ? R.drawable.mobile_ic_grid : R.drawable.mobile_ic_list);
        item.setTitle(mBanner ? R.string.grid_grid_view : R.string.grid_list_view);
    }

    @Override
    public void onStart() {
        super.onStart();
        applyLayoutMode();   // the banner list setting may have changed
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
