package com.archos.mediacenter.video.utils;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediascraper.*;
import com.archos.mediascraper.xml.MovieScraper3;
import com.archos.mediascraper.xml.ShowScraper4;

/** Explicit refresh of the current match, without re-identifying other videos. */
public final class PhoneMetadataRefresh {
    private PhoneMetadataRefresh() {}
    public static void start(Activity activity, Video video, View button) {
        start(activity, video, button, null);
    }

    /** {@code onRefreshed}, when given, runs on success instead of recreating the activity. */
    public static void start(Activity activity, Video video, View button, Runnable onRefreshed) {
        Context context = activity.getApplicationContext();
        button.setEnabled(false);
        Toast.makeText(context, R.string.rescrap_in_progress, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean success = false;
            try {
                SearchResult search;
                Bundle options = new Bundle();
                if (video instanceof Episode) {
                    Episode episode = (Episode) video;
                    search = new SearchResult(SearchResult.tvshow, episode.getShowName(), (int) episode.getOnlineId());
                    search.setScraper(new ShowScraper4(context));
                    search.setOriginalTitle(episode.getShowName());
                    search.setLanguage(Scraper.getLanguage(context));
                    Bundle extra = new Bundle();
                    extra.putString(ShowUtils.SEASON, String.valueOf(episode.getSeasonNumber()));
                    extra.putString(ShowUtils.EPNUM, String.valueOf(episode.getEpisodeNumber()));
                    search.setExtra(extra);
                    options.putInt(Scraper.ITEM_REQUEST_BASIC_VIDEO, 1);
                    options.putInt(Scraper.ITEM_REQUEST_SEASON, episode.getSeasonNumber());
                    options.putInt(Scraper.ITEM_REQUEST_EPISODE, episode.getEpisodeNumber());
                    options.putBoolean(Scraper.ITEM_REQUEST_REFRESH_SHOW_METADATA, true);
                } else {
                    Movie movie = (Movie) video;
                    search = new SearchResult(SearchResult.movie, video.getName(), (int) movie.getOnlineId());
                    search.setScraper(new MovieScraper3(context));
                }
                search.setFile(video.getFileUri());
                ScrapeDetailResult result = Scraper.getDetails(search, options);
                if (result != null && result.tag != null) {
                    result.tag.downloadAllImages(context);
                    success = result.tag.save(context, video.getId()) != -1;
                }
            } catch (Exception e) {
                android.util.Log.w("PhoneMetadataRefresh", "Refresh failed", e);
            }
            final boolean refreshed = success;
            new Handler(Looper.getMainLooper()).post(() -> {
                button.setEnabled(true);
                Toast.makeText(context, refreshed ? R.string.phone_refresh_metadata_done
                        : R.string.phone_refresh_metadata_failed, Toast.LENGTH_SHORT).show();
                if (refreshed && onRefreshed != null) onRefreshed.run();
                else if (refreshed && !activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
            });
        }, "phone-metadata-refresh").start();
    }
}
