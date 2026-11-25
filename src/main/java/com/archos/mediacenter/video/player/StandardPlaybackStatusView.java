package com.archos.mediacenter.video.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.archos.mediacenter.video.R;

import java.text.NumberFormat;
import java.util.Date;

public final class StandardPlaybackStatusView extends LinearLayout {
    private final StandardPlaybackTitleView clock;
    private final StandardPlaybackTitleView battery;
    private boolean listening;

    private final BroadcastReceiver statusUpdates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                updateBattery(intent);
            } else {
                updateClock();
            }
        }
    };

    public StandardPlaybackStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        battery = addLabel(context);
        clock = addLabel(context);
        int gap = Math.round(6 * getResources().getDisplayMetrics().density);
        LayoutParams clockParams = (LayoutParams) clock.getLayoutParams();
        clockParams.setMarginStart(gap);
        clock.setLayoutParams(clockParams);
    }

    private StandardPlaybackTitleView addLabel(Context context) {
        StandardPlaybackTitleView line = new StandardPlaybackTitleView(context, null);
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        line.setTextColor(Color.WHITE);
        addView(line, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return line;
    }

    void setAppearance(boolean outline, boolean shadow) {
        clock.setAppearance(11, outline, shadow);
        battery.setAppearance(11, outline, shadow);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == GONE) return;
        updateClock();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        Intent batteryStatus = ContextCompat.registerReceiver(getContext(), statusUpdates, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        listening = true;
        if (batteryStatus != null) updateBattery(batteryStatus);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (listening) {
            getContext().unregisterReceiver(statusUpdates);
            listening = false;
        }
        super.onDetachedFromWindow();
    }

    private void updateClock() {
        clock.setText(DateFormat.getTimeFormat(getContext()).format(new Date()));
    }

    private void updateBattery(Intent status) {
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
        if (level < 0 || scale <= 0) return;
        String percent = NumberFormat.getPercentInstance().format((double) level / scale);
        boolean charging = status.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                == BatteryManager.BATTERY_STATUS_CHARGING;
        battery.setText(charging ? getResources().getString(R.string.phone_standard_title_charging, percent) : percent);
        battery.setContentDescription(getResources().getString(charging
                ? R.string.phone_standard_title_battery_charging : R.string.phone_standard_title_battery, percent));
    }
}
