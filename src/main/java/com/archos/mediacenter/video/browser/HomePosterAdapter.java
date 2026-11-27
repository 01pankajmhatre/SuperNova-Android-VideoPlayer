package com.archos.mediacenter.video.browser;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.archos.mediacenter.video.R;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

/** Poster cards of the phone home page rows (Recently Played, Movies, Series). */
public class HomePosterAdapter extends RecyclerView.Adapter<HomePosterAdapter.VH> {
    public static final String POSTER_RESUME = "classic_home_poster_resume";

    public static class Poster {
        public final String title;
        public final String meta;
        public final Uri poster;
        public final Object item;   // Video or Tvshow
        public final int season;
        /** Wide backdrop (banner list style); null when the item has none. */
        public Uri backdrop;

        public Poster(String title, String meta, Uri poster, Object item, int season) {
            this.title = title;
            this.meta = meta;
            this.poster = poster;
            this.item = item;
            this.season = season;
        }
    }

    public interface Listener {
        void onPosterClick(Poster poster);
    }

    /** Long press on a card; return true when a popup was shown. */
    public interface LongListener {
        boolean onPosterLongClick(Poster poster, View anchor);
    }

    private LongListener mLongListener;

    public void setLongClickListener(LongListener listener) {
        mLongListener = listener;
    }
    private final List<Poster> mItems = new ArrayList<>();
    private final Listener mListener;
    private final Listener mArtworkListener;
    private float mScale = 1f;
    private boolean mThreeRows;
    private boolean mSingleLineName;

    /** The name under the poster uses one row only. */
    public void setSingleLineName(boolean single) {
        mSingleLineName = single;
    }

    /** Size of the cards (1 = normal). */
    public void setScale(float scale) {
        mScale = scale;
        notifyDataSetChanged();
    }

    /** Text under the poster on three rows: name, then the details one per row. */
    public void setThreeRows(boolean threeRows) {
        mThreeRows = threeRows;
    }

    public HomePosterAdapter(Listener listener) {
        this(listener, listener);
    }

    public HomePosterAdapter(Listener listener, Listener artworkListener) {
        mListener = listener;
        mArtworkListener = artworkListener;
    }

    public void setItems(List<Poster> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView fallback, name, meta, overlayTitle;

        VH(View v) {
            super(v);
            image = v.findViewById(R.id.home_poster_image);
            fallback = v.findViewById(R.id.home_poster_fallback);
            name = v.findViewById(R.id.home_poster_name);
            overlayTitle = v.findViewById(R.id.home_poster_overlay_title);
            meta = v.findViewById(R.id.home_poster_meta);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.home_poster_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Poster p = mItems.get(position);
        final boolean square = false;                 // posters stay portrait
        View artwork = (View) h.image.getParent();
        ViewGroup.LayoutParams artworkParams = artwork.getLayoutParams();
        artworkParams.height = Math.round((square ? 84 : 124) * mScale * h.itemView.getResources().getDisplayMetrics().density);
        if (mScale != 1f) {
            int cardWidth = Math.round(h.itemView.getResources().getDimension(R.dimen.phone_home_card_width) * mScale);
            artworkParams.width = cardWidth;
            ViewGroup.LayoutParams cardParams = h.itemView.getLayoutParams();
            if (cardParams != null) cardParams.width = cardWidth;
            h.name.setTextSize(11 * mScale);
            h.meta.setTextSize(9 * mScale);
        }
        artwork.setLayoutParams(artworkParams);
        int color = androidx.preference.PreferenceManager.getDefaultSharedPreferences(h.itemView.getContext())
                .getInt(BrowserCategory.HOME_CARD_COLOR, 0xFF1F2B44);
        h.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        ((View) h.image.getParent()).setBackgroundColor(color);
        final boolean titleInside = false;            // the title stays below the poster
        h.overlayTitle.setText(p.title);
        h.overlayTitle.setVisibility(titleInside ? View.VISIBLE : View.GONE);
        h.name.setVisibility(titleInside ? View.GONE : View.VISIBLE);
        h.name.setText(p.title);
        h.name.setMaxLines(mSingleLineName ? 1 : 2);
        if (mThreeRows) {
            h.name.setMaxLines(1);
            h.meta.setMaxLines(2);
            h.meta.setText(p.meta == null ? "" : p.meta.replace("  ", "\n"));
        } else {
            h.meta.setText(p.meta);
        }
        h.meta.setVisibility(titleInside ? View.GONE : View.VISIBLE);
        h.fallback.setText(p.title);
        if (p.poster != null) {
            h.fallback.setVisibility(View.GONE);
            Picasso.get().load(p.poster).resize(264, square ? 264 : 396).centerCrop().into(h.image, new Callback() {
                @Override public void onSuccess() { h.fallback.setVisibility(View.GONE); }
                @Override public void onError(Exception e) { h.fallback.setVisibility(titleInside ? View.GONE : View.VISIBLE); }
            });
        } else {
            Picasso.get().cancelRequest(h.image);
            h.image.setImageDrawable(null);
            h.fallback.setVisibility(titleInside ? View.GONE : View.VISIBLE);
        }
        h.itemView.setOnClickListener(v -> { if (mListener != null) mListener.onPosterClick(p); });
        artwork.setOnClickListener(v -> { if (mArtworkListener != null) mArtworkListener.onPosterClick(p); });
        View.OnClickListener details = v -> { if (mListener != null) mListener.onPosterClick(p); };
        h.name.setOnClickListener(details);
        h.meta.setOnClickListener(details);
        h.overlayTitle.setOnClickListener(details);
        h.fallback.setOnClickListener(details);
        View.OnLongClickListener longClick = v -> mLongListener != null && mLongListener.onPosterLongClick(p, h.itemView);
        h.itemView.setOnLongClickListener(longClick);
        artwork.setOnLongClickListener(longClick);
        h.name.setOnLongClickListener(longClick);
        h.meta.setOnLongClickListener(longClick);
        h.overlayTitle.setOnLongClickListener(longClick);
        h.fallback.setOnLongClickListener(longClick);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }
}
