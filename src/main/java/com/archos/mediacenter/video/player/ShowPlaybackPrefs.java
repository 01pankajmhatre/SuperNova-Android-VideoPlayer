package com.archos.mediacenter.video.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.archos.mediacenter.utils.videodb.VideoDbInfo;

/**
 * Remembers the playback choices made on one episode of a TV show (audio track, subtitle track,
 * subtitle delay/ratio, audio delay, video format) so they are applied to the other episodes of
 * the same show. Tracks are remembered by language/name, never by index, because indexes differ
 * from one episode file to another.
 */
final class ShowPlaybackPrefs {
    static final String SUBTITLE_NONE = "none";

    private static final String FILE = "show_playback_prefs";
    private final SharedPreferences mPrefs;

    ShowPlaybackPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Show identifier for an episode, or null when the video is not a scraped TV show episode. */
    @Nullable
    static String keyOf(@Nullable VideoDbInfo info) {
        if (info == null || !info.isShow || info.scraperShowId == null) return null;
        String id = info.scraperShowId.trim();
        if (id.isEmpty() || "0".equals(id) || "-1".equals(id)) return null;
        return id;
    }

    void saveAudio(String key, @Nullable String language, @Nullable String name) {
        mPrefs.edit()
                .putString(key + ".audio_lang", language == null ? "" : language)
                .putString(key + ".audio_name", name == null ? "" : name)
                .apply();
    }

    @Nullable String getAudioLanguage(String key) { return mPrefs.getString(key + ".audio_lang", null); }
    @Nullable String getAudioName(String key) { return mPrefs.getString(key + ".audio_name", null); }

    void saveSubtitle(String key, String language, @Nullable String name) {
        mPrefs.edit()
                .putString(key + ".sub_lang", language)
                .putString(key + ".sub_name", name == null ? "" : name)
                .apply();
    }

    @Nullable String getSubtitleLanguage(String key) { return mPrefs.getString(key + ".sub_lang", null); }
    @Nullable String getSubtitleName(String key) { return mPrefs.getString(key + ".sub_name", null); }

    void saveSubtitleTiming(String key, int delay, int ratio) {
        mPrefs.edit().putInt(key + ".sub_delay", delay).putInt(key + ".sub_ratio", ratio).apply();
    }

    @Nullable Integer getSubtitleDelay(String key) {
        return mPrefs.contains(key + ".sub_delay") ? mPrefs.getInt(key + ".sub_delay", 0) : null;
    }

    @Nullable Integer getSubtitleRatio(String key) {
        return mPrefs.contains(key + ".sub_ratio") ? mPrefs.getInt(key + ".sub_ratio", -1) : null;
    }

    void saveAudioDelay(String key, int delay) {
        mPrefs.edit().putInt(key + ".audio_delay", delay).apply();
    }

    @Nullable Integer getAudioDelay(String key) {
        return mPrefs.contains(key + ".audio_delay") ? mPrefs.getInt(key + ".audio_delay", 0) : null;
    }

    void saveVideoFormat(String key, int format) {
        mPrefs.edit().putInt(key + ".video_format", format).apply();
    }

    @Nullable Integer getVideoFormat(String key) {
        return mPrefs.contains(key + ".video_format") ? mPrefs.getInt(key + ".video_format", 0) : null;
    }
}
