package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.archos.mediacenter.utils.ThumbnailEngine;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;

/** TV shows grid card in the phone poster-card style. */
public final class PhoneTvshowGridPresenter extends TvshowGridPresenter {
    public PhoneTvshowGridPresenter(Context context, ExtendedClickListener listener) {
        super(context, PhoneCardDefaultValues.INSTANCE, listener);
    }

    @Override
    public View getView(ViewGroup parent, Object object, View recycled) {
        View card = super.getView(parent, object, recycled);
        PhoneCardStyle.sizePoster(card, parent);
        return card;
    }

    @Override
    public View bindView(View card, Object object, ThumbnailEngine.Result result, int position) {
        super.bindView(card, object, result, position);
        final Tvshow show = (Tvshow) object;
        PhoneCardStyle.setRow(card,
                R.string.phone_card_seasons, String.valueOf(Math.max(0, show.getSeasonCount())),
                R.string.phone_card_episodes, String.valueOf(Math.max(0, show.getEpisodeCount())));
        card.findViewById(R.id.pinned_icon).setVisibility(View.GONE);   // pinning only applies to movies
        PhoneCardStyle.tint(card, result == null ? null : result.getThumbnail());
        return card;
    }
}
