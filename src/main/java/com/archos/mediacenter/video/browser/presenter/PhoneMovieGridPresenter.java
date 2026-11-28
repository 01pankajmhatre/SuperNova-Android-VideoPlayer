package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
        PhoneCardStyle.sizePoster(card, parent);
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
        final String duration = video.getDurationMs() > 0 && holder.info != null ? holder.info.getText().toString() : "";
        final int year = video instanceof Movie ? ((Movie) video).getYear() : 0;
        PhoneCardStyle.setRow(card, R.string.phone_card_year, year > 0 ? String.valueOf(year) : "",
                R.string.phone_card_duration, duration);
        PhoneCardStyle.tint(card, result == null ? null : result.getThumbnail());
        return card;
    }
}
