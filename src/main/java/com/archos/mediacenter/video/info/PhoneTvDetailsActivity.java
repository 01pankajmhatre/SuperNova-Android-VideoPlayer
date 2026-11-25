package com.archos.mediacenter.video.info;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.utils.MediaUtils;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.leanback.BackdropTask;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.utils.DbUtils;
import com.archos.mediacenter.video.utils.ExternalPlayerWithResultStarter;
import com.archos.mediacenter.video.utils.PlayUtils;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediascraper.BaseTags;
import com.archos.mediascraper.VideoTags;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone UI movie details page that looks like the TV UI details page: full-screen backdrop, poster,
 * title, year / duration / rating, badges, plot, genres, action buttons and cast.
 *
 * This is a page of its own (it does not use any TV UI screen), so it can be changed freely without any
 * effect on the TV UI. It is used instead of the standard phone details page when the user turns on
 * Settings > Movie details page > TV-style page. "More info" opens the standard phone page.
 */
public class PhoneTvDetailsActivity extends AppCompatActivity implements ExternalPlayerWithResultStarter {

    /** Boolean preference: show movie details with this page instead of the standard phone page. */
    public static final String PREF_TV_STYLE_DETAILS = "phone_tv_style_details";

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_TV_STYLE_DETAILS, false);
    }

    private static final int LOADER_VIDEO = 1;

    private Video mVideo;
    private ArrayList<Uri> mPaths = new ArrayList<>();
    private long mVideoId = -1;

    private ImageView mBackdrop, mPoster, mWatched;
    private TextView mTitle, mYear, mDuration, mRating, mChipContent, mChipResolution, mChipAudio, mGenres, mPlot;
    private TextView mResume, mPlay, mWatchedToggle, mMore, mCastTitle, mCast, mDirector;

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private BackdropTask mBackdropTask;
    private long mTagsLoadedFor = Long.MIN_VALUE;
    private boolean mFocusSet = false;
    private boolean mPosterAsBackdrop = false;

    @SuppressWarnings({"deprecation", "unchecked"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_tv_details);

        mBackdrop = findViewById(R.id.tvd_backdrop);
        mPoster = findViewById(R.id.tvd_poster);
        mWatched = findViewById(R.id.tvd_watched);
        mTitle = findViewById(R.id.tvd_title);
        mYear = findViewById(R.id.tvd_year);
        mDuration = findViewById(R.id.tvd_duration);
        mRating = findViewById(R.id.tvd_rating);
        mChipContent = findViewById(R.id.tvd_chip_content);
        mChipResolution = findViewById(R.id.tvd_chip_resolution);
        mChipAudio = findViewById(R.id.tvd_chip_audio);
        mGenres = findViewById(R.id.tvd_genres);
        mPlot = findViewById(R.id.tvd_plot);
        mResume = findViewById(R.id.tvd_resume);
        mPlay = findViewById(R.id.tvd_play);
        mWatchedToggle = findViewById(R.id.tvd_watched_toggle);
        mMore = findViewById(R.id.tvd_more);
        mCastTitle = findViewById(R.id.tvd_cast_title);
        mCast = findViewById(R.id.tvd_cast);
        mDirector = findViewById(R.id.tvd_director);

        Intent intent = getIntent();
        if (intent.hasExtra(VideoInfoActivityFragment.EXTRA_VIDEO)) {
            mVideo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getSerializableExtra(VideoInfoActivityFragment.EXTRA_VIDEO, Video.class)
                    : (Video) intent.getSerializableExtra(VideoInfoActivityFragment.EXTRA_VIDEO);
        }
        if (intent.hasExtra(VideoInfoActivity.EXTRA_VIDEO_PATHS)) {
            ArrayList<Uri> paths = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getSerializableExtra(VideoInfoActivity.EXTRA_VIDEO_PATHS, ArrayList.class)
                    : (ArrayList<Uri>) intent.getSerializableExtra(VideoInfoActivity.EXTRA_VIDEO_PATHS);
            if (paths != null) mPaths = paths;
        }
        mVideoId = intent.getLongExtra(VideoInfoActivity.EXTRA_VIDEO_ID, -1);

        findViewById(R.id.tvd_back).setOnClickListener(v -> finish());
        mResume.setOnClickListener(v -> play(PlayerActivity.RESUME_FROM_LAST_POS));
        mPlay.setOnClickListener(v -> play(PlayerActivity.RESUME_NO));
        mWatchedToggle.setOnClickListener(v -> toggleWatched());
        mMore.setOnClickListener(v -> openStandardPage());
        mPlot.setOnClickListener(v -> mPlot.setMaxLines(mPlot.getMaxLines() > 6 ? 6 : Integer.MAX_VALUE));

        if (mVideo != null) bindVideo(mVideo); // show what we already know at once, the loader refreshes it
        LoaderManager.getInstance(this).initLoader(LOADER_VIDEO, null, mLoaderCallbacks);
    }

    // ------------------------------------------------------------------ data

    private final LoaderManager.LoaderCallbacks<Cursor> mLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @NonNull
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            if (mVideo != null) {
                return mVideo.isIndexed() ? new MultipleVideoLoader(PhoneTvDetailsActivity.this, mVideo.getId())
                        : new MultipleVideoLoader(PhoneTvDetailsActivity.this, mVideo.getFilePath());
            }
            if (mVideoId >= 0) return new MultipleVideoLoader(PhoneTvDetailsActivity.this, mVideoId);
            return new MultipleVideoLoader(PhoneTvDetailsActivity.this, mPaths.isEmpty() ? "" : mPaths.get(0).toString());
        }

        @Override
        public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
            if (cursor == null || cursor.isClosed() || !cursor.moveToFirst()) return;
            try {
                VideoCursorMapper mapper = new VideoCursorMapper();
                mapper.bindColumns(cursor);
                Object o = mapper.bind(cursor);
                if (o instanceof Video) {
                    mVideo = (Video) o;
                    bindVideo(mVideo);
                }
            } catch (RuntimeException e) {
                // keep showing the video we already have
            }
        }

        @Override
        public void onLoaderReset(@NonNull Loader<Cursor> loader) { }
    };

    private static void setText(TextView view, CharSequence text) {
        boolean has = text != null && text.length() > 0;
        view.setText(has ? text : "");
        view.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private void bindVideo(Video v) {
        mTitle.setText(v.getName());
        Movie m = v instanceof Movie ? (Movie) v : null;
        setText(mYear, m != null && m.getYear() > 0 ? String.valueOf(m.getYear()) : null);
        int ms = v.getDurationMs();
        if (ms > 0) {
            int minutes = ms / 60000;
            setText(mDuration, minutes >= 60 ? String.format(Locale.US, "%dh %02dm", minutes / 60, minutes % 60)
                    : String.format(Locale.US, "%dm", Math.max(1, minutes)));
        } else {
            setText(mDuration, null);
        }
        setText(mRating, m != null && m.getRating() > 0 ? String.format(Locale.US, "%.1f", m.getRating()) : null);
        setText(mChipContent, m != null ? m.getContentRating() : null);
        mWatched.setVisibility(v.isWatched() ? View.VISIBLE : View.GONE);
        if (mPlot.getText().length() == 0) setText(mPlot, v.getDescriptionBody());

        Uri poster = v.getPosterUri();
        if (poster != null) {
            Picasso.get().load(poster).fit().centerCrop().into(mPoster);
            if (!mPosterAsBackdrop) { // until the real backdrop arrives
                mPosterAsBackdrop = true;
                Picasso.get().load(poster).fit().centerCrop().into(mBackdrop);
            }
        }
        bindBadges(v);
        bindActions(v);
        loadTags(v);
    }

    private void bindBadges(Video v) {
        String resolution = null;
        switch (v.getNormalizedDefinition()) {
            case VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_4K: resolution = getString(R.string.resolution_4k); break;
            case VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_1080P: resolution = getString(R.string.resolution_1080p); break;
            case VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_720P: resolution = getString(R.string.resolution_720p); break;
            case VideoStore.Video.VideoColumns.ARCHOS_DEFINITION_SD: resolution = getString(R.string.resolution_SD); break;
            default: break; // unknown: show nothing
        }
        setText(mChipResolution, resolution);

        String audio = null;
        if (v.getMetadata() != null) {
            int best = -1;
            for (int n = 0; n < v.getMetadata().getAudioTrackNb(); n++) {
                String ch = v.getMetadata().getAudioTrack(n).channels;
                if (ch == null) continue;
                int c = ch.startsWith("7.1") ? 7 : ch.startsWith("5.1") ? 5 : ch.startsWith("Stereo") ? 2 : -1;
                if (c > best) best = c;
            }
            if (best == 7) audio = getString(R.string.audio_7_1);
            else if (best == 5) audio = getString(R.string.audio_5_1);
            else if (best == 2) audio = getString(R.string.audio_2_0);
        }
        setText(mChipAudio, audio);
    }

    private void bindActions(Video v) {
        int resume = v.getResumeMs();
        if (resume > 0) {
            mResume.setText(getString(R.string.resume) + "  " + MediaUtils.formatTime(resume));
            mResume.setVisibility(View.VISIBLE);
            mPlay.setText(R.string.play_from_beginning);
        } else {
            mResume.setVisibility(View.GONE);
            mPlay.setText(R.string.play_selection);
        }
        mWatchedToggle.setText(v.isWatched() ? R.string.mark_as_not_watched : R.string.mark_as_watched);
        if (!mFocusSet) { // remote: start on the main action (no effect in touch mode)
            mFocusSet = true;
            (resume > 0 ? mResume : mPlay).requestFocus();
        }
    }

    /** Plot, genres, cast, director and the real backdrop come from the scraper tags (read off the main thread). */
    private void loadTags(final Video v) {
        final long key = v.getId();
        if (mTagsLoadedFor == key) return;
        mTagsLoadedFor = key;
        mWorker.execute(() -> {
            BaseTags tags = null;
            try {
                tags = v.getFullScraperTags(getApplicationContext());
            } catch (RuntimeException ignored) { /* scraped data is optional */ }
            final BaseTags result = tags;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || result == null) return;
                applyTags(result);
            });
        });
    }

    private void applyTags(BaseTags tags) {
        String plot = tags.getPlot();
        if (plot != null && !plot.isEmpty()) setText(mPlot, plot);
        if (tags instanceof VideoTags) {
            String genres = ((VideoTags) tags).getGenresFormatted(this);
            setText(mGenres, genres);
        }
        String cast = tags.getActorsFormatted();
        boolean hasCast = cast != null && !cast.isEmpty();
        mCastTitle.setVisibility(hasCast ? View.VISIBLE : View.GONE);
        setText(mCast, hasCast ? cast : null);
        String directors = tags.getDirectorsFormatted();
        setText(mDirector, directors == null || directors.isEmpty() ? null : getString(R.string.tvdetails_director, directors));
        if (mBackdropTask != null) mBackdropTask.cancelTaskOnly();
        mBackdropTask = new BackdropTask(this, mBackdrop, 0xFF000000);
        mBackdropTask.execute(tags);
    }

    // ------------------------------------------------------------------ actions

    private void play(int resumeMode) {
        if (mVideo == null) return;
        PlayUtils.startVideo(this, mVideo, resumeMode, true, -1, this, -1);
    }

    private void toggleWatched() {
        if (mVideo == null) return;
        if (mVideo.isWatched()) DbUtils.markAsNotRead(this, mVideo); else DbUtils.markAsRead(this, mVideo);
        setResult(RESULT_OK); // the loader refreshes this page when the database changes
    }

    /** The standard phone details page stays reachable (delete, subtitles, lists, file details...). */
    private void openStandardPage() {
        if (mVideo == null) return;
        ArrayList<Uri> paths = new ArrayList<>(mPaths);
        if (paths.isEmpty()) paths.add(Uri.parse(mVideo.getFilePath()));
        startActivity(VideoInfoActivity.buildStandardIntent(this, mVideo, paths, mVideoId));
    }

    @Override
    public void startActivityWithResultListener(Intent intent) {
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBackdropTask != null) mBackdropTask.cancelTaskOnly();
        mWorker.shutdownNow();
    }
}
