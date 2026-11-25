package com.archos.mediacenter.video.leanback.details;

import android.os.Bundle;
import android.view.KeyEvent;

import androidx.fragment.app.Fragment;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.TorrentObserverService;
import com.archos.mediacenter.video.leanback.LeanbackActivity;

/**
 * Phone page that shows the TV details screen (rows: sources, subtitles, file info, plot, trailers, posters,
 * backdrops, links). It is a separate copy of VideoDetailsActivity, so the TV UI is not changed.
 */
public class PhoneTvLayoutDetailsActivity extends LeanbackActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phone_tv_layout_details_activity);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getIntent().getBooleanExtra(VideoDetailsFragment.EXTRA_LAUNCHED_FROM_PLAYER, false))
            TorrentObserverService.paused(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getIntent().getBooleanExtra(VideoDetailsFragment.EXTRA_LAUNCHED_FROM_PLAYER, false))
            TorrentObserverService.resumed(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_browse_fragment);
                if (fragment instanceof VideoDetailsFragment) {
                    ((VideoDetailsFragment) fragment).onKeyDown(keyCode);
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}
