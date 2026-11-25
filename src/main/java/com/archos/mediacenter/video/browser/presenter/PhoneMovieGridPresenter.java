package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;

import com.archos.mediacenter.utils.ThumbnailEngine;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Video;

/** Touch-sized adaptation of the TV movie poster cards. */
public final class PhoneMovieGridPresenter extends VideoGridPresenter {
    public PhoneMovieGridPresenter(Context context, ExtendedClickListener listener) {
        super(context, listener, null);
    }

    @Override
    public View getView(ViewGroup parent, Object object, View recycled) {
        View card = recycled;
        if (card == null || card.findViewById(R.id.phone_movie_card) == null) {
            card = LayoutInflater.from(mContext).inflate(R.layout.phone_movie_grid_item, parent, false);
            ViewHolder holder = new ViewHolder();
            holder.thumbnail = card.findViewById(R.id.thumbnail);
            holder.name = card.findViewById(R.id.name);
            holder.info = card.findViewById(R.id.info);
            holder.resume = card.findViewById(R.id.resume_notif);
            holder.watchedIcon = card.findViewById(R.id.watched_status);
            card.setTag(holder);
        }
        float density = mContext.getResources().getDisplayMetrics().density;
        int screenWidth = mContext.getResources().getConfiguration().screenWidthDp;
        int columns = Math.max(2, screenWidth / 180);
        int width = Math.round((screenWidth - 32 - (columns - 1) * 12) * density / columns);
        if (parent instanceof GridView && ((GridView) parent).getColumnWidth() > 0)
            width = ((GridView) parent).getColumnWidth();
        View poster = card.findViewById(R.id.phone_movie_poster);
        ViewGroup.LayoutParams size = poster.getLayoutParams();
        size.height = Math.round(width * 1.5f);
        poster.setLayoutParams(size);
        return card;
    }

    @Override
    public View bindView(View card, Object object, ThumbnailEngine.Result result, int position) {
        super.bindView(card, object, result, position);
        Video video = (Video) object;
        ViewHolder holder = (ViewHolder) card.getTag();
        holder.watchedIcon.setVisibility(video.isWatched() ? View.VISIBLE : View.GONE);
        card.findViewById(R.id.pinned_icon).setVisibility(
                video instanceof Movie && ((Movie) video).isPinned() ? View.VISIBLE : View.GONE);
        if (video instanceof Movie && ((Movie) video).getYear() > 0) {
            String duration = video.getDurationMs() > 0 ? holder.info.getText().toString() : "";
            holder.info.setText(((Movie) video).getYear() + (duration.isEmpty() ? "" : " · " + duration));
            holder.info.setVisibility(View.VISIBLE);
        }
        return card;
    }
}
