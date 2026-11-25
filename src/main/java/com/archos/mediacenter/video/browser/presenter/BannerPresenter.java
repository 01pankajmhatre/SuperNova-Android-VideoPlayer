// Banner style list row for the Movies / TV Shows pages: the backdrop darkened, with the year /
// rating (or seasons / episodes) line in the accent colour and the title centred at the bottom.
// Same look as the banner list of the Library Movies and TV shows pages.

package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.archos.mediacenter.utils.ThumbnailEngine;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Base;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.squareup.picasso.Picasso;

import java.util.Locale;

public class BannerPresenter implements Presenter {

    private static final int ROW_HEIGHT_DP = 120;
    private static final int ROW_GAP_DP = 2;

    private final Context mContext;
    private final float mDensity;

    public BannerPresenter(Context context) {
        mContext = context;
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    private static final class Holder {
        ImageView image;
        TextView name;
        TextView meta;
    }

    private int dp(int v) {
        return Math.round(v * mDensity);
    }

    @Override
    public View getView(ViewGroup parent, Object object, View recycled) {
        if (recycled != null && recycled.getTag() instanceof Holder) return recycled;

        FrameLayout card = new FrameLayout(mContext);
        // bottom margin is made with the row height: AbsListView rows can't carry margins
        card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP + ROW_GAP_DP)));
        card.setPadding(0, 0, 0, dp(ROW_GAP_DP));
        card.setClipToPadding(true);

        ImageView image = new TopCropImageView(mContext);   // shows the top part of the backdrop
        image.setBackgroundColor(0xFF1F2B44);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(mContext);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0xAA000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout texts = new LinearLayout(mContext);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        texts.setPadding(dp(16), 0, dp(16), dp(10));

        TextView meta = new TextView(mContext);
        meta.setTextColor(0xFFF5C542);
        meta.setTextSize(12);
        meta.setTypeface(null, Typeface.BOLD);
        meta.setGravity(Gravity.CENTER);
        meta.setSingleLine(true);
        meta.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
        texts.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        TextView name = new TextView(mContext);
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));

        card.addView(texts, new FrameLayout.LayoutParams(-1, -1));

        Holder holder = new Holder();
        holder.image = image;
        holder.name = name;
        holder.meta = meta;
        card.setTag(holder);
        return card;
    }

    @Override
    public View bindView(View view, Object object, ThumbnailEngine.Result thumbnailResult, int positionInAdapter) {
        Holder holder = (Holder) view.getTag();
        Base base = (Base) object;
        holder.name.setText(base.getName());

        String meta = "";
        if (object instanceof Tvshow) {
            Tvshow show = (Tvshow) object;
            meta = show.getSeasonCount() > 1
                    ? mContext.getString(R.string.home_seasons_episodes, show.getSeasonCount(), show.getEpisodeCount())
                    : mContext.getString(R.string.home_season_episodes, show.getEpisodeCount());
        } else if (object instanceof Movie) {
            Movie movie = (Movie) object;
            meta = movie.getYear() > 0 ? String.valueOf(movie.getYear()) : "";
            if (movie.getRating() > 0)
                meta += (meta.isEmpty() ? "" : "  ") + String.format(Locale.US, "\u2605 %.1f", movie.getRating());
        }
        holder.meta.setText(meta);
        holder.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

        Uri source = base.getBannerUri() != null ? base.getBannerUri() : base.getPosterUri();
        if (source != null) {
            Picasso.get().load(source).resize(800, 0).onlyScaleDown().into(holder.image);
        } else {
            Picasso.get().cancelRequest(holder.image);
            holder.image.setImageDrawable(null);
        }
        return view;
    }

    /** Fills the row width and keeps the top of the picture (backdrops lose their bottom, not their top). */
    private static final class TopCropImageView extends androidx.appcompat.widget.AppCompatImageView {
        TopCropImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
        }

        @Override
        protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            applyTopCrop();
            return changed;
        }

        @Override
        public void setImageDrawable(@Nullable Drawable d) {
            super.setImageDrawable(d);
            applyTopCrop();
        }

        private void applyTopCrop() {
            Drawable d = getDrawable();
            int vw = getWidth(), vh = getHeight();
            if (d == null || vw == 0 || vh == 0) return;
            int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            float scale = Math.max(vw / (float) dw, vh / (float) dh);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((vw - dw * scale) / 2f, 0f);
            setImageMatrix(m);
        }
    }
}
