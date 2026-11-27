package com.archos.mediacenter.video.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.archos.mediacenter.video.R;

import java.text.NumberFormat;
import java.util.Date;

/** Phone player title row, right side: system time with the battery percentage below it. */
public final class MobileTitleStatusView extends LinearLayout {
    private final TextView clock;
    private final TextView battery;
    private boolean listening;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                updateBattery(intent);
            } else {
                updateClock();
            }
        }
    };

    public MobileTitleStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        clock = addLabel(context, 14);
        battery = addLabel(context, 11);
        battery.setVisibility(GONE);
        if (!getResources().getBoolean(R.bool.mobile_title_status_enabled)) setVisibility(GONE);
    }

    private TextView addLabel(Context context, int sp) {
        TextView label = new TextView(context);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.END);
        label.setTextColor(Color.WHITE);
        label.setTextSize(sp);
        label.setShadowLayer(3, 0, 0, 0x99000000);
        addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return label;
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
        Intent status = ContextCompat.registerReceiver(getContext(), updates, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        listening = true;
        if (status != null) updateBattery(status);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (listening) {
            getContext().unregisterReceiver(updates);
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
        battery.setVisibility(VISIBLE);
    }
}
