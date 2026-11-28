package com.archos.mediacenter.video.player;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.archos.medialib.LibAvos;
import com.archos.mediacenter.video.R;

/** Owns the PCM controls and preserves their values across player recreation. */
final class AudioChannelsController {
    private final Activity activity;
    private final Runnable refreshAudio;
    private final Runnable onClosed;
    private final SharedPreferences preferences;
    private final AudioChannelSettings settings = new AudioChannelSettings();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View[] rows = new View[AudioChannelSettings.COUNT];
    private final AppCompatSeekBar[] sliders = new AppCompatSeekBar[AudioChannelSettings.COUNT];
    private final TextView[] percentages = new TextView[AudioChannelSettings.COUNT];
    private AlertDialog dialog;
    private TextView layoutLabel;
    private TextView surroundLabel;
    private int lastLayout = -1;
    private boolean combineSurround;

    AudioChannelsController(Activity activity, Runnable refreshAudio, Runnable onClosed) {
        this.activity = activity;
        this.refreshAudio = refreshAudio;
        this.onClosed = onClosed;
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        for (int i = 0; i < AudioChannelSettings.COUNT; i++) {
            try {
                settings.setGain(i, preferences.getInt("pcm_channel_gain_" + i, 100));
            } catch (ClassCastException ignored) {
                settings.setGain(i, 100);
            }
        }
        settings.setDownmix(preferences.getBoolean("pcm_channel_downmix", false));
        combineSurround = preferences.getBoolean("pcm_channel_combine_surround", false);
    }

    boolean isProcessingActive() {
        return LibAvos.isAvailable()
                && ((dialog != null && dialog.isShowing()) || settings.requiresProcessing());
    }

    void apply() {
        if (!LibAvos.isAvailable()) return;
        if (LibAvos.setChannelGains(settings.snapshot(), isProcessingActive(), settings.isDownmix())) {
            refreshAudio.run();
        }
    }

    private void save() {
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < AudioChannelSettings.COUNT; i++)
            editor.putInt("pcm_channel_gain_" + i, settings.getGain(i));
        editor.putBoolean("pcm_channel_downmix", settings.isDownmix())
                .putBoolean("pcm_channel_combine_surround", combineSurround).apply();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    void show(boolean supported) {
        if (dialog != null && dialog.isShowing()) return;
        if (!supported || !LibAvos.isAvailable()) {
            Toast.makeText(activity, R.string.audio_channels_unavailable, Toast.LENGTH_LONG).show();
            onClosed.run();
            return;
        }

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(4));
        // Back (previous popup) at the top
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label(R.string.mobile_player_back, 14, Color.WHITE);
        back.setMinHeight(dp(44));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setFocusable(true);
        back.setClickable(true);
        android.util.TypedValue backBackground = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, backBackground, true)) {
            back.setBackgroundResource(backBackground.resourceId);
        }
        back.setOnClickListener(view -> { if (dialog != null) dialog.dismiss(); });
        header.addView(back);
        TextView title = label(R.string.audio_channels_title, 14, 0xFF9FB3D1);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(header);
        layoutLabel = label(R.string.audio_channels_detecting, 12, 0xFFC3CEDD);
        content.addView(layoutLabel);
        AppCompatCheckBox combine = new AppCompatCheckBox(activity);
        combine.setText(R.string.audio_channels_combine);
        combine.setTextSize(12);
        combine.setTextColor(Color.WHITE);
        combine.setPadding(0, 0, 0, 0);
        combine.setButtonTintList(ContextCompat.getColorStateList(activity, R.color.audio_channels_slider));
        combine.setChecked(combineSurround);
        combine.setOnCheckedChangeListener((button, checked) -> {
            combineSurround = checked;
            if (checked) {
                syncSurroundGains(settings.getGain(1));
                apply();
            }
            updateChannelRows();
            save();
        });
        // Reset sits next to Combine Channels
        TextView reset = label(R.string.audio_channels_reset, 12, Color.WHITE);
        reset.setMinHeight(dp(36));
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(12), 0, dp(12), 0);
        reset.setFocusable(true);
        reset.setClickable(true);
        android.util.TypedValue resetBackground = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, resetBackground, true)) {
            reset.setBackgroundResource(resetBackground.resourceId);
        }
        reset.setOnClickListener(button -> {
            for (int i = 1; i < sliders.length; i++) {
                settings.setGain(i, 100);
                if (sliders[i] != null) sliders[i].setProgress(100);
                if (percentages[i] != null) percentages[i].setText(activity.getString(R.string.audio_channels_percent, 100));
            }
            apply();
            save();
        });
        LinearLayout combineRow = new LinearLayout(activity);
        combineRow.setGravity(Gravity.CENTER_VERTICAL);
        combineRow.addView(combine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        combineRow.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(combineRow);

        int[] names = {R.string.audio_channels_master, R.string.audio_channels_front,
                R.string.audio_channels_center, R.string.audio_channels_lfe,
                R.string.audio_channels_rear, R.string.audio_channels_side};
        for (int i = 1; i < names.length; i++) addGainRow(content, i, names[i]);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        int available = activity.getResources().getDisplayMetrics().heightPixels - dp(140);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(120), Math.min(dp(500), available))));

        dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .create();
        dialog.setOnDismissListener(value -> {
            handler.removeCallbacks(pollLayout);
            save();
            dialog = null;
            apply();
            onClosed.run();
        });
        dialog.show();
        if (dialog.getWindow() != null) PlayerController.applyNightModeTo(activity, dialog.getWindow().getDecorView());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.mobile_controls_pill);
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); // no background dimming
            android.view.WindowManager.LayoutParams position = window.getAttributes();
            position.x = 0;
            position.y = dp(16);
            window.setAttributes(position);
            int width = Math.min(dp(300), activity.getResources().getDisplayMetrics().widthPixels - dp(32));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lastLayout = -1;
        apply();
        handler.post(pollLayout);
    }

    private void syncSurroundGains(int gain) {
        for (int group : new int[]{1, 4, 5}) {
            settings.setGain(group, gain);
            if (sliders[group] != null) sliders[group].setProgress(gain);
            if (percentages[group] != null) percentages[group].setText(
                    activity.getString(R.string.audio_channels_percent, gain));
        }
    }

    View createAudioPopupControls(boolean supported, Runnable onShowChannels) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), 0);
        View row = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.menu_track_settings_view, content, false);
        row.setMinimumHeight(dp(32));
        row.setPaddingRelative(0, dp(2), 0, dp(2));
        TextView name = row.findViewById(R.id.name);
        name.setText(R.string.audio_channels_downmix);
        name.setTextSize(12);
        name.setEnabled(supported);
        androidx.appcompat.widget.SwitchCompat downmix = row.findViewById(R.id.track_settings_switch);
        downmix.setVisibility(View.VISIBLE);
        downmix.setMinimumHeight(0);
        downmix.setScaleX(0.92f);
        downmix.setScaleY(0.92f);
        downmix.setChecked(settings.isDownmix());
        downmix.setEnabled(supported);
        downmix.setClickable(true);
        downmix.setFocusable(true);
        downmix.setContentDescription(name.getText());
        final TextView[] showChannels = new TextView[1];
        downmix.setOnCheckedChangeListener((button, checked) -> {
            settings.setDownmix(checked);
            apply();
            save();
            // the channel controls only exist while downmix is off
            if (showChannels[0] != null) showChannels[0].setVisibility(checked ? View.GONE : View.VISIBLE);
        });
        row.setOnClickListener(view -> downmix.toggle());
        row.setEnabled(supported);
        content.addView(row);
        addGainRow(content, 0, R.string.audio_channels_master).setEnabled(supported);

        // below master gain: opens the audio channels sub popup (only when downmix is disabled)
        TextView channels = new TextView(activity);
        channels.setText(activity.getString(R.string.audio_channels_show) + "  \u203A");
        channels.setTextColor(Color.WHITE);
        channels.setTextSize(12);
        channels.setMinHeight(dp(36));
        channels.setGravity(Gravity.CENTER_VERTICAL);
        channels.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.util.TypedValue background = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
            channels.setBackgroundResource(background.resourceId);
        }
        channels.setEnabled(supported);
        channels.setAlpha(supported ? 1f : 0.45f);
        channels.setFocusable(supported);
        channels.setClickable(supported);
        channels.setOnClickListener(view -> { if (onShowChannels != null) onShowChannels.run(); });
        channels.setVisibility(settings.isDownmix() ? View.GONE : View.VISIBLE);
        showChannels[0] = channels;
        content.addView(channels, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return content;
    }

    private SeekBar addGainRow(LinearLayout content, int group, int name) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameLabel = label(name, 12, Color.WHITE);
        if (group == 0) row.setPaddingRelative(dp(8), 0, 0, 0);
        if (group == 1) surroundLabel = nameLabel;
        row.addView(nameLabel, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView percent = new TextView(activity);
        percent.setTextColor(Color.WHITE);
        percent.setTextSize(12);
        percent.setGravity(Gravity.END);
        percent.setText(activity.getString(R.string.audio_channels_percent, settings.getGain(group)));

        AppCompatSeekBar slider = new AppCompatSeekBar(activity);
        slider.setPadding(dp(6), 0, dp(6), 0);
        slider.setThumb(activity.getDrawable(R.drawable.audio_channels_thumb));
        slider.setThumbOffset(dp(3));
        slider.setMax(200);
        slider.setProgress(settings.getGain(group));
        slider.setContentDescription(activity.getString(name));
        slider.setMinimumHeight(dp(40));
        ColorStateList tint = ContextCompat.getColorStateList(activity, R.color.audio_channels_slider);
        slider.setProgressTintList(tint);
        slider.setThumbTintList(tint);
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                settings.setGain(group, progress);
                if (combineSurround && (group == 1 || group == 4 || group == 5)) syncSurroundGains(progress);
                percent.setText(activity.getString(R.string.audio_channels_percent, progress));
                apply();
                if (group == 0 && !bar.isPressed()) save();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { save(); }
        });
        row.addView(slider, new LinearLayout.LayoutParams(0, dp(40), 1));
        row.addView(percent, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(row);
        if (group == 0) return slider;
        rows[group] = row;
        sliders[group] = slider;
        percentages[group] = percent;
        row.setVisibility(View.GONE);
        return slider;
    }

    private void updateChannelRows() {
        if (surroundLabel == null) return;
        surroundLabel.setText(combineSurround ? R.string.audio_channels_combined
                : R.string.audio_channels_front);
        sliders[1].setContentDescription(activity.getString(combineSurround
                ? R.string.audio_channels_combined : R.string.audio_channels_front));
        for (int i = 1; i < rows.length; i++) {
            boolean present = lastLayout != -1 && (lastLayout & (1 << (i - 1))) != 0;
            if (combineSurround) {
                if (i == 1) present = lastLayout != -1 && (lastLayout & 0x19) != 0;
                else if (i == 4 || i == 5) present = false;
            }
            rows[i].setVisibility(present ? View.VISIBLE : View.GONE);
        }
    }

    private final Runnable pollLayout = new Runnable() {
        @Override public void run() {
            if (dialog == null || !dialog.isShowing()) return;
            int layout = LibAvos.getChannelLayout();
            if (layout != lastLayout) {
                lastLayout = layout;
                int channels = layout >>> 16;
                layoutLabel.setText(channels == 0
                        ? activity.getString(R.string.audio_channels_detecting)
                        : activity.getString(R.string.audio_channels_layout, channels));

                updateChannelRows();
            }
            handler.postDelayed(this, 250);
        }
    };

    private TextView label(int text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
