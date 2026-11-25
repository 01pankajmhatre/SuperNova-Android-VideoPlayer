package com.archos.mediacenter.video.player;

import android.net.Uri;
import android.os.Build;
import android.service.notification.ConditionProviderService;

import androidx.annotation.RequiresApi;

@RequiresApi(Build.VERSION_CODES.Q)
public final class PlaybackNotificationConditionProvider extends ConditionProviderService {
    @Override
    public void onConnected() {}

    @Override
    public void onSubscribe(Uri conditionId) {
        if (PlaybackNotificationSettings.conditionId(this).equals(conditionId)) {
            // Recompute on reconnect so a restarted process never reports a player it no longer owns.
            notifyCondition(PlaybackNotificationSettings.playbackCondition(
                    this, PlaybackNotificationSettings.shouldFilter(this)));
        }
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {}
}
