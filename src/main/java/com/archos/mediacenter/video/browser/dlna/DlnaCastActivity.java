package com.archos.mediacenter.video.browser.dlna;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Casting - now playing" page, laid out like the movie detail page: poster and title on top, the
 * TV controls (seek bar, play / pause, seek, volume, stop) in a card, then the video information.
 */
public class DlnaCastActivity extends AppCompatActivity {
    private static final String EXTRA_VIDEO = "video";

    private DlnaCastPanel mPanel;
    private CardView mErrorCard;
    private TextView mMediaText;
    private final java.util.concurrent.ExecutorService mInfoThread = java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView mErrorText;

    public static void start(Context context, Video video) {
        context.startActivity(new Intent(context, DlnaCastActivity.class).putExtra(EXTRA_VIDEO, video));
    }

    /** Opens the page of the cast that is running (the "DLNA Now" tile). */
    public static void open(Context context) {
        if (DlnaCastSession.isActive()) context.startActivity(new Intent(context, DlnaCastActivity.class));
    }

    @SuppressWarnings("deprecation")
    private Video readVideo() {
        if (android.os.Build.VERSION.SDK_INT >= 33) return getIntent().getSerializableExtra(EXTRA_VIDEO, Video.class);
        return (Video) getIntent().getSerializableExtra(EXTRA_VIDEO);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();      // no title bar: the page has its own heading
        Video video = readVideo();
        final boolean newCast = video != null;
        if (newCast) DlnaCastSession.setVideo(video);
        else if (DlnaCastSession.isActive()) video = DlnaCastSession.getVideo();
        if (video == null) {
            finish();
            return;
        }
        String title = video.getName();
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12161C);
        root.setPadding(pad, pad, pad, pad);

        // errors of the cast are shown here, they never close the page
        mErrorCard = card(pad);
        mErrorCard.setCardBackgroundColor(0xFF4A1F1F);
        LinearLayout errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setPadding(pad, pad, pad, pad);
        errorBox.addView(text(getString(R.string.dlna_cast_error), 12, 0xFFFFB4A9, true));
        mErrorText = text("", 14, 0xFFFFFFFF, false);
        mErrorText.setPadding(0, dp(6), 0, dp(6));
        errorBox.addView(mErrorText);
        TextView close = text(getString(R.string.dlna_cast_close), 14, 0xFFFFFFFF, true);
        close.setPadding(0, dp(8), 0, 0);
        close.setOnClickListener(v -> finish());
        errorBox.addView(close);
        mErrorCard.addView(errorBox);
        mErrorCard.setVisibility(View.GONE);
        root.addView(mErrorCard);

        // controls card
        CardView controls = card(pad);
        LinearLayout panel = new LinearLayout(this);
        panel.setVisibility(View.GONE);
        controls.addView(panel);

        // header: poster + title + short facts
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF1E2630);
        header.addView(poster, new LinearLayout.LayoutParams(dp(110), dp(165)));
        Uri posterUri = video.getPosterUri();
        if (posterUri != null) {
            try {
                Picasso.get().load(posterUri).resize(330, 495).centerCrop().into(poster);
            } catch (RuntimeException ignored) {
                // no poster
            }
        }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(pad, 0, 0, 0);
        TextView heading = text(getString(R.string.dlna_cast_now_playing), 12, 0xFF9FB3C8, false);
        titles.addView(heading);
        TextView name = text(title, 22, 0xFFFFFFFF, true);
        name.setPadding(0, dp(4), 0, dp(4));
        titles.addView(name);
        String facts = facts(video);
        if (!facts.isEmpty()) titles.addView(text(facts, 13, 0xFFDFE7F2, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
        headerParams.topMargin = pad;
        root.addView(header, headerParams);

        // media information (codecs, resolution...), filled in the background
        CardView mediaCard = infoCard(getString(R.string.dlna_cast_media_info), getString(R.string.dlna_cast_media_loading), pad);
        mMediaText = (TextView) ((LinearLayout) mediaCard.getChildAt(0)).getChildAt(1);
        root.addView(mediaCard);
        loadMediaInfo(video);

        // controls below the media information
        root.addView(controls);

        // information cards, after the controls
        String plot = video.getDescriptionBody();
        if (plot != null && !plot.trim().isEmpty()) root.addView(infoCard(getString(R.string.dlna_cast_plot), plot.trim(), pad));
        String details = details(video);
        if (!details.isEmpty()) root.addView(infoCard(getString(R.string.dlna_cast_details), details, pad));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        // keep the page out of the status bar and the navigation bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            root.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(scroll);

        mPanel = new DlnaCastPanel(this, panel);
        mPanel.setOnStop(this::finish);
        mPanel.setOnFail(message -> {
            mErrorText.setText(message);
            mErrorCard.setVisibility(View.VISIBLE);
        });
        if (newCast) {
            if (savedInstanceState == null) mPanel.cast(video.getFileUri(), title);
            else if (DlnaCastSession.isActive()) mPanel.resume(DlnaCastSession.getTv());
        } else if (DlnaCastSession.isActive() && DlnaCastSession.getTv() != null) {
            mPanel.resume(DlnaCastSession.getTv());
        } else {
            finish();
        }
    }

    private void loadMediaInfo(final Video video) {
        final android.content.Context app = getApplicationContext();
        mInfoThread.execute(() -> {
            String text;
            try {
                com.archos.mediacenter.video.utils.VideoMetadata m = video.getMetadata();
                if (m == null || m.getVideoTrack() == null) {
                    m = com.archos.mediacenter.video.info.VideoInfoCommonClass.retrieveMetadata(video, app, null);
                }
                text = describe(m);
            } catch (RuntimeException e) {
                text = "";
            }
            final String result = text.isEmpty() ? getString(R.string.dlna_cast_media_none) : text;
            runOnUiThread(() -> {
                if (!isFinishing() && mMediaText != null) mMediaText.setText(result);
            });
        });
    }

    private static String describe(com.archos.mediacenter.video.utils.VideoMetadata m) {
        if (m == null) return "";
        StringBuilder b = new StringBuilder();
        com.archos.mediacenter.video.utils.VideoMetadata.VideoTrack v = m.getVideoTrack();
        if (v != null) {
            b.append("Video: ");
            if (v.format != null && !v.format.isEmpty()) b.append(v.format);
            if (v.profile > 0) b.append(" (profile ").append(v.profile).append(')');
            if (m.getVideoWidth() > 0) b.append("  ").append(m.getVideoWidth()).append('x').append(m.getVideoHeight());
            if (v.fps > 0) b.append("  ").append(v.fps).append(" fps");
            if (v.bitRate > 0) b.append("  ").append(v.bitRate / 1000).append(" kb/s");
            b.append('\n');
        }
        for (int i = 0; i < m.getAudioTrackNb(); i++) {
            com.archos.mediacenter.video.utils.VideoMetadata.AudioTrack a = m.getAudioTrack(i);
            if (a == null) continue;
            b.append("Audio ").append(i + 1).append(": ");
            if (a.format != null && !a.format.isEmpty()) b.append(a.format);
            if (a.channels != null && !a.channels.isEmpty()) b.append("  ").append(a.channels);
            if (a.sampleRate > 0) b.append("  ").append(a.sampleRate).append(" Hz");
            if (a.bitRate > 0) b.append("  ").append(a.bitRate / 1000).append(" kb/s");
            if (a.language != null && !a.language.isEmpty()) b.append("  ").append(a.language);
            b.append('\n');
        }
        if (m.getSubtitleTrackNb() > 0) {
            b.append("Subtitles: ");
            for (int i = 0; i < m.getSubtitleTrackNb(); i++) {
                com.archos.mediacenter.video.utils.VideoMetadata.SubtitleTrack t = m.getSubtitleTrack(i);
                if (t == null) continue;
                if (i > 0) b.append(", ");
                b.append(t.language != null && !t.language.isEmpty() ? t.language : "#" + (i + 1));
            }
            b.append('\n');
        }
        if (m.getDuration() > 0) b.append("Duration: ").append(duration(m.getDuration())).append('\n');
        return b.toString().trim();
    }

    private static String facts(Video v) {
        ArrayList<String> parts = new ArrayList<>();
        if (v instanceof Movie) {
            Movie m = (Movie) v;
            if (m.getYear() > 0) parts.add(String.valueOf(m.getYear()));
            if (m.getContentRating() != null && !m.getContentRating().isEmpty()) parts.add(m.getContentRating());
            if (m.getRating() > 0) parts.add(String.format(Locale.ROOT, "%.1f / 10", m.getRating()));
        } else if (v instanceof Episode) {
            Episode e = (Episode) v;
            if (e.getShowName() != null && !e.getShowName().isEmpty()) parts.add(e.getShowName());
            parts.add(String.format(Locale.ROOT, "S%02dE%02d", e.getSeasonNumber(), e.getEpisodeNumber()));
            if (e.getEpisodeRating() > 0) parts.add(String.format(Locale.ROOT, "%.1f / 10", e.getEpisodeRating()));
        }
        if (v.getDurationMs() > 0) parts.add(duration(v.getDurationMs()));
        return android.text.TextUtils.join("  -  ", parts);
    }

    private static String details(Video v) {
        StringBuilder b = new StringBuilder();
        if (v instanceof Episode) {
            Episode e = (Episode) v;
            if (e.getEpisodeName() != null && !e.getEpisodeName().isEmpty()) b.append("Episode: ").append(e.getEpisodeName()).append('\n');
            String date = e.getEpisodeDateFormatted();
            if (date != null && !date.isEmpty()) b.append("Aired: ").append(date).append('\n');
        }
        if (v.getDurationMs() > 0) b.append("Duration: ").append(duration(v.getDurationMs())).append('\n');
        if (v.getVideoFormat() != null && !v.getVideoFormat().isEmpty()) b.append("Video: ").append(v.getVideoFormat()).append('\n');
        if (v.getSize() > 0) b.append("Size: ").append(String.format(Locale.ROOT, "%.2f GB", v.getSize() / 1073741824.0)).append('\n');
        if (v.getFileNameWithExtension() != null) b.append("File: ").append(v.getFileNameWithExtension());
        return b.toString().trim();
    }

    private static String duration(int ms) {
        int min = ms / 60000;
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private CardView card(int pad) {
        CardView c = new CardView(this);
        c.setCardBackgroundColor(0xFF1E2630);
        c.setRadius(dp(8));
        c.setCardElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = pad;
        c.setLayoutParams(lp);
        return c;
    }

    private CardView infoCard(String heading, String body, int pad) {
        CardView c = card(pad);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.addView(text(heading, 12, 0xFF9FB3C8, true));
        TextView t = text(body, 14, 0xFFDFE7F2, false);
        t.setPadding(0, dp(6), 0, 0);
        t.setLineSpacing(0, 1.15f);
        box.addView(t);
        c.addView(box);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        mInfoThread.shutdownNow();
        if (mPanel != null) mPanel.release(true);
        super.onDestroy();
    }
}
