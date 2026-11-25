package com.archos.mediacenter.video.player;

import android.app.Activity;
import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenPolicy;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class PlaybackNotificationSettings {
    private static final String TAG = "PlaybackNotifications";
    private static final String SHOW_BANNERS = "playback_show_notification_banners";
    private static final String SILENCE = "playback_silence_notifications";
    private static final Set<Activity> foregroundPlayers =
            Collections.newSetFromMap(new WeakHashMap<>());

    private PlaybackNotificationSettings() {}

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    public static boolean isSupported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    public static void show(Activity activity) {
        if (!isSupported(activity)) return;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        if (manager == null) return;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);

        AppCompatCheckBox show = new AppCompatCheckBox(activity);
        show.setText(R.string.playback_show_notification_banners);
        show.setChecked(showBanners(preferences));
        content.addView(show);

        AppCompatCheckBox silence = new AppCompatCheckBox(activity);
        silence.setText(R.string.playback_silence_notifications);
        silence.setChecked(!show.isChecked() || preferences.getBoolean(SILENCE, false));
        silence.setEnabled(show.isChecked());
        content.addView(silence);
        show.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) silence.setChecked(true);
            silence.setEnabled(checked);
        });

        TextView explanation = new TextView(activity);
        explanation.setText(R.string.playback_notifications_summary);
        content.addView(explanation);
        if (!manager.isNotificationPolicyAccessGranted()) {
            TextView permission = new TextView(activity);
            permission.setText(R.string.playback_notifications_access_required);
            permission.setPadding(0, padding / 2, 0, 0);
            content.addView(permission);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.notification_mode)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    preferences.edit().putBoolean(SHOW_BANNERS, show.isChecked())
                            .putBoolean(SILENCE, silence.isChecked()).apply();
                    updateRule(activity);
                    if (filterRequested(preferences) && !manager.isNotificationPolicyAccessGranted()) {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION));
                        } catch (ActivityNotFoundException error) {
                            Toast.makeText(activity, R.string.playback_notifications_access_required,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    static void enterPlayer(Activity activity) {
        if (!isSupported(activity)) return;
        foregroundPlayers.add(activity);
        updateRule(activity);
    }

    static void leavePlayer(Activity activity) {
        if (foregroundPlayers.remove(activity) && isSupported(activity)) updateRule(activity);
    }

    private static boolean showBanners(SharedPreferences preferences) {
        boolean legacyAllowsBanners = preferences.getInt(PlayerActivity.KEY_NOTIFICATIONS_MODE, 0)
                != PlayerActivity.NOTIFICATION_MODE_NONE;
        return preferences.getBoolean(SHOW_BANNERS, legacyAllowsBanners);
    }

    private static boolean filterRequested(SharedPreferences preferences) {
        return !showBanners(preferences) || preferences.getBoolean(SILENCE, false);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    static Uri conditionId(Context context) {
        return Condition.newId(context).appendPath("video-playback").build();
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    static Condition playbackCondition(Context context, boolean active) {
        return new Condition(conditionId(context), context.getString(R.string.playback_notifications_rule),
                active ? Condition.STATE_TRUE : Condition.STATE_FALSE);
    }

    static boolean shouldFilter(Context context) {
        return !foregroundPlayers.isEmpty()
                && filterRequested(PreferenceManager.getDefaultSharedPreferences(context));
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void updateRule(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.isNotificationPolicyAccessGranted()) return;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean active = shouldFilter(context);
        Uri condition = conditionId(context);
        ComponentName owner = new ComponentName(context, PlaybackNotificationConditionProvider.class);
        try {
            String ruleId = null;
            AutomaticZenRule existing = null;
            for (var entry : manager.getAutomaticZenRules().entrySet()) {
                AutomaticZenRule rule = entry.getValue();
                if (owner.equals(rule.getOwner()) && condition.equals(rule.getConditionId())) {
                    ruleId = entry.getKey();
                    existing = rule;
                    break;
                }
            }

            if (!active) {
                if (ruleId != null) manager.setAutomaticZenRuleState(ruleId, playbackCondition(context, false));
                return;
            }
            if (existing != null && !existing.isEnabled()) return;

            boolean banners = showBanners(preferences);
            ZenPolicy policy = new ZenPolicy.Builder()
                    .disallowAllSounds()
                    .allowAlarms(true)
                    .allowMedia(true)
                    .allowSystem(true)
                    .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
                    .showAllVisualEffects()
                    .showPeeking(banners)
                    .showFullScreenIntent(banners)
                    .build();
            if (existing == null) {
                AutomaticZenRule rule = new AutomaticZenRule(
                        context.getString(R.string.playback_notifications_rule), owner, null, condition,
                        policy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
                ruleId = manager.addAutomaticZenRule(rule);
            } else if (!policy.equals(existing.getZenPolicy())) {
                existing.setZenPolicy(policy);
                if (!manager.updateAutomaticZenRule(ruleId, existing)) return;
            }
            if (ruleId != null) manager.setAutomaticZenRuleState(ruleId, playbackCondition(context, true));
        } catch (IllegalArgumentException | SecurityException error) {
            Log.w(TAG, "Could not update playback Do Not Disturb rule", error);
        }
    }
}
