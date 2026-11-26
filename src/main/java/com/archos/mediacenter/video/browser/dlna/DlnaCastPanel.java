package com.archos.mediacenter.video.browser.dlna;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.archos.mediacenter.video.R;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Cast on TV" of the movie detail page: finds the TVs of the network, sends them the movie
 * (served by our own DLNA server) and shows play / pause / seek / volume controls in the page.
 * Everything that touches the network runs on background threads.
 */
public final class DlnaCastPanel {
    private final Context mContext;
    private final LinearLayout mPanel;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final ExecutorService mControlThread = Executors.newSingleThreadExecutor();   // button presses
    private final ExecutorService mPollThread = Executors.newSingleThreadExecutor();      // status requests
    private Runnable mOnStop;
    private java.util.function.Consumer<String> mOnFail;
    private TextView mError;
    private long mErrorUntil;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();

    private DlnaRendererControl mTv;
    private TextView mStatus;
    private TextView mTimes;
    private TextView mDebug;
    private SeekBar mSeek;
    private ImageButton mPlay;
    private boolean mDragging;
    private boolean mPlaying;
    private boolean mBusy;
    private boolean mReleased;
    private int mPositionSec;
    private int mDurationSec;
    private int mTick;
    private boolean mBuilt;
    private volatile Boolean mSsapWorks;      // null: not tried yet

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (mReleased || mTv == null) return;
            poll();
            mUi.postDelayed(this, 1000);
        }
    };

    /** @param panel an empty vertical LinearLayout (hidden) in the page */
    public DlnaCastPanel(Context context, LinearLayout panel) {
        mContext = context;
        mPanel = panel;
    }

    /** Called when the cast ends by "Stop casting". */
    public void setOnStop(Runnable r) {
        mOnStop = r;
    }

    /** Called when no cast could be started (no TV, refused, nothing chosen). */
    public void setOnFail(java.util.function.Consumer<String> r) {
        mOnFail = r;
    }

    /** Stops the DLNA server when this cast started it (a server the user switched on stays on). */
    private void stopServerStartedByCast() {
        if (DlnaCastSession.takeServerStartedByCast()) DlnaServerService.stop(mContext);
    }

    private void fail(String message) {
        if (!DlnaCastSession.isActive()) stopServerStartedByCast();      // nothing is being cast: the server is not needed
        if (mOnFail != null) mOnFail.accept(message);
        else toast(message);
    }

    /** Starts casting the file; a TV is chosen first when several answer. */
    public void cast(Uri file, String title) {
        if (file == null || (!"file".equals(file.getScheme()) && !"content".equals(file.getScheme()))) {
            fail("Only videos stored on this phone can be cast");
            return;
        }
        final List<DlnaRendererControl> found = new java.util.ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(mContext, android.R.layout.simple_list_item_1, new java.util.ArrayList<String>());
        final boolean[] chosen = {false};
        final AlertDialog dialog = new AlertDialog.Builder(mContext).setTitle("Searching for TVs...")
                .setOnCancelListener(d -> { if (!chosen[0]) fail("No TV chosen"); })
                .setAdapter(adapter, (d, which) -> {
                    chosen[0] = true;
                    if (which < found.size()) send(found.get(which), file, title);
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> { if (!chosen[0]) fail("No TV chosen"); }).create();
        dialog.show();
        mWorker.execute(() -> {
            if (!DlnaServerService.isRunning()) {
                DlnaCastSession.setServerStartedByCast(true);
                DlnaServerService.start(mContext);
                for (int i = 0; i < 40 && DlnaServerService.getUrl() == null; i++) sleep(250);
            }
            if (DlnaServerService.getUrl() == null) {
                post(() -> {
                    chosen[0] = true;
                    dialog.dismiss();
                    fail("The DLNA server could not start: " + DlnaServerService.getError());
                });
                return;
            }
            final List<DlnaRendererControl> tvs = DlnaRendererControl.discoverAll(tv -> post(() -> {
                if (!dialog.isShowing()) return;
                found.add(tv);
                adapter.add(tv.getName() + "  (" + tv.getAddress() + ")");
                dialog.setTitle("Choose a TV to cast on");
            }));
            post(() -> {
                if (tvs.isEmpty() && dialog.isShowing()) {
                    chosen[0] = true;
                    dialog.dismiss();
                    fail("No TV found on this Wi-Fi network");
                }
            });
        });
    }

    private void send(DlnaRendererControl tv, Uri file, String title) {
        mWorker.execute(() -> {
            String name = title == null || title.isEmpty() ? "Video" : title;
            String ext = MimeTypeMap.getFileExtensionFromUrl(file.toString());
            String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            if (mime == null || !mime.startsWith("video/")) mime = "video/mp4";
            long size = -1;
            String path = DlnaCast.registerPublic(file, name, mime, size);
            String url = DlnaServerService.getUrl() + path;
            String error = tv.cast(url, name, mime);
            post(() -> {
                if (error != null) {
                    fail(tv.getName() + " refused: " + error);
                    return;
                }
                mTv = tv;
                mPositionSec = 0;
                mDurationSec = 0;
                DlnaCastSession.begin(tv, name);
                show();
            });
        });
    }

    /** Shows the controls for the cast that is already running. */
    public void resume(DlnaRendererControl tv) {
        mTv = tv;
        show();
    }

    private void show() {
        if (!mBuilt) build();
        mPanel.setVisibility(View.VISIBLE);
        mStatus.setText("Playing on " + mTv.getName());
        mError.setVisibility(View.GONE);
        mPlaying = true;
        mPlay.setImageResource(R.drawable.mobile_ic_pause);
        mUi.removeCallbacks(mPoll);
        mUi.postDelayed(mPoll, 1500);
    }

    private void build() {
        mBuilt = true;
        int pad = dp(12);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(pad, pad, pad, pad);

        mStatus = new TextView(mContext);
        mStatus.setTextColor(0xFFFFFFFF);
        mStatus.setTextSize(14);
        mStatus.setGravity(Gravity.CENTER);
        mPanel.addView(mStatus);

        mDebug = new TextView(mContext);
        mDebug.setTextColor(0xFF9FB3C8);
        mDebug.setTextSize(11);
        mDebug.setGravity(Gravity.CENTER);
        mPanel.addView(mDebug);

        mError = new TextView(mContext);
        mError.setTextColor(0xFFFFB4A9);
        mError.setTextSize(12);
        mError.setGravity(Gravity.CENTER);
        mError.setVisibility(View.GONE);
        mPanel.addView(mError);

        mSeek = new SeekBar(mContext);
        mSeek.setMax(0);
        // flat look: square track, a thin bar as thumb (no circle), taller than the default
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        track.setColor(0x44FFFFFF);
        track.setSize(-1, dp(12));
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        fill.setColor(0xFF4FC3F7);
        fill.setSize(-1, dp(12));
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        mSeek.setProgressDrawable(layers);
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        thumb.setColor(0xFFFFFFFF);
        thumb.setSize(dp(4), dp(32));
        mSeek.setThumb(thumb);
        mSeek.setThumbOffset(0);
        mSeek.setSplitTrack(false);
        mSeek.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, dp(44));
        seekParams.topMargin = dp(8);
        mPanel.addView(mSeek, seekParams);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) mTimes.setText(format(progress) + " / " + format(mDurationSec));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                mDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mDragging = false;
                jumpTo(bar.getProgress());
            }
        });

        mTimes = new TextView(mContext);
        mTimes.setTextColor(0xFFFFFFFF);
        mTimes.setTextSize(12);
        mTimes.setGravity(Gravity.CENTER);
        mPanel.addView(mTimes);

        LinearLayout row = new LinearLayout(mContext);
        row.setGravity(Gravity.CENTER);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mPanel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        int size = dp(76);
        ImageButton back = button(row, R.drawable.mobile_ic_rewind, size);
        mPlay = button(row, R.drawable.mobile_ic_pause, size);
        ImageButton forward = button(row, R.drawable.mobile_ic_forward, size);

        // volume on its own row below
        LinearLayout volumeRow = new LinearLayout(mContext);
        volumeRow.setGravity(Gravity.CENTER);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(-1, -2);
        volumeParams.topMargin = dp(8);
        mPanel.addView(volumeRow, volumeParams);
        int volumeSize = dp(64);
        ImageButton volDown = button(volumeRow, R.drawable.ic_dlna_volume_down, volumeSize);
        ImageButton volUp = button(volumeRow, R.drawable.ic_dlna_volume_up, volumeSize);

        volDown.setOnClickListener(v -> changeVolume(false));
        volUp.setOnClickListener(v -> changeVolume(true));
        back.setOnClickListener(v -> jumpTo(Math.max(0, mPositionSec - 10)));
        forward.setOnClickListener(v -> jumpTo(mDurationSec > 0 ? Math.min(mDurationSec, mPositionSec + 10) : mPositionSec + 10));
        mPlay.setOnClickListener(v -> {
            final boolean wasPlaying = mPlaying;
            mPlaying = !wasPlaying;
            mPlay.setImageResource(mPlaying ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play);
            run(() -> {
                if (wasPlaying) mTv.pause();
                else mTv.play();
            });
        });

        TextView stop = new TextView(mContext);
        stop.setText("Stop casting");
        stop.setTextColor(0xFFFFFFFF);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(pad, pad, pad, pad);
        stop.setOnClickListener(v -> {
            final DlnaRendererControl tv = mTv;
            release(false);
            mControlThread.execute(tv::stop);
            DlnaCastSession.end();
            stopServerStartedByCast();               // the DLNA server that the cast started stops with it
            mPanel.setVisibility(View.GONE);
            if (mOnStop != null) mOnStop.run();
        });
        mPanel.addView(stop, new LinearLayout.LayoutParams(-1, -2));
    }

    private ImageButton button(LinearLayout parent, int icon, int size) {
        ImageButton b = new ImageButton(mContext);
        b.setImageResource(icon);
        b.setColorFilter(0xFFFFFFFF);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setPadding(size / 5, size / 5, size / 5, size / 5);
        b.setBackgroundResource(R.drawable.mobile_press_circle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(dp(10), 0, dp(10), 0);
        parent.addView(b, lp);
        return b;
    }

    /**
     * Volume: the TV's own remote service first (it also reaches a soundbar on HDMI ARC), then the
     * standard DLNA volume as a fallback for other makes.
     */
    private void changeVolume(final boolean up) {
        final DlnaRendererControl tv = mTv;
        if (tv == null) return;
        mControlThread.execute(() -> {
            if (mSsapWorks != Boolean.FALSE && !tv.isGoogleCast()) {
                String error = new DlnaTvRemote(mContext, tv.getAddress()).volumeStep(up, () -> post(() -> {
                    mError.setText("Accept the question shown on the TV (use the TV remote) to allow volume control.");
                    mError.setVisibility(View.VISIBLE);
                    mErrorUntil = System.currentTimeMillis() + 40_000;
                }));
                if (error == null) {
                    mSsapWorks = Boolean.TRUE;
                    post(() -> mError.setVisibility(View.GONE));
                    return;
                }
                if (mSsapWorks == null) mSsapWorks = Boolean.FALSE;      // not an LG webOS TV: do not retry
                else post(() -> {
                    mError.setText("TV volume: " + error);
                    mError.setVisibility(View.VISIBLE);
                    mErrorUntil = System.currentTimeMillis() + 10_000;
                });
            }
            tv.changeVolume(up ? 5 : -5);
        });
    }

    private void jumpTo(int sec) {
        mPositionSec = sec;
        mSeek.setProgress(sec);
        run(() -> mTv.seekTo(sec));
    }

    private void run(Runnable r) {
        if (mTv == null) return;
        mControlThread.execute(() -> {
            final DlnaRendererControl tv = mTv;
            r.run();
            final String err = tv != null ? tv.getLastError() : "";
            if (err != null && !err.isEmpty()) {
                post(() -> {
                    mError.setText("The TV did not accept the command: " + err);
                    mError.setVisibility(View.VISIBLE);
                    mErrorUntil = System.currentTimeMillis() + 10_000;
                });
            }
        });
    }

    private void poll() {
        if (mBusy) return;
        mBusy = true;
        final boolean withVolume = mTick++ % 3 == 0;
        final DlnaRendererControl tv = mTv;
        mPollThread.execute(() -> {
            DlnaRendererControl.State s = tv.poll(withVolume);
            final String pollError = tv.getLastError();
            post(() -> {
                if (mError.getVisibility() == View.VISIBLE && System.currentTimeMillis() > mErrorUntil) mError.setVisibility(View.GONE);
                mBusy = false;
                if (mReleased || tv != mTv) return;
                mDebug.setText(DlnaCast.report());
                if (s.transport.isEmpty() && s.positionSec < 0) {
                    mStatus.setText(tv.getName() + " - no reply (" + pollError + ")");
                    return;
                }
                mPlaying = "PLAYING".equals(s.transport) || "TRANSITIONING".equals(s.transport);
                mPlay.setImageResource(mPlaying ? R.drawable.mobile_ic_pause : R.drawable.mobile_ic_play);
                mStatus.setText(tv.getName() + (s.transport.isEmpty() ? ""
                        : " - " + s.transport.toLowerCase(Locale.ROOT).replace('_', ' '))
                        + (s.volume >= 0 ? "  -  volume " + s.volume : ""));
                if (s.durationSec > 0) {
                    mDurationSec = s.durationSec;
                    mSeek.setMax(s.durationSec);
                }
                if (s.positionSec >= 0 && !mDragging) {
                    mPositionSec = s.positionSec;
                    mSeek.setProgress(s.positionSec);
                    mTimes.setText(format(s.positionSec) + " / " + format(mDurationSec));
                }
            });
        });
    }

    /** Stops polling; call when the page goes away (the TV keeps playing). */
    public void release(boolean destroy) {
        mUi.removeCallbacks(mPoll);
        mTv = null;
        if (destroy) {
            mReleased = true;
            mControlThread.shutdown();
            mPollThread.shutdown();
            mWorker.shutdown();
        }
    }

    private void post(Runnable r) {
        mUi.post(() -> {
            if (!mReleased) r.run();
        });
    }

    private void toast(String text) {
        Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private int dp(int v) {
        return Math.round(v * mContext.getResources().getDisplayMetrics().density);
    }

    private static String format(int sec) {
        sec = Math.max(0, sec);
        return sec >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                : String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }
}
