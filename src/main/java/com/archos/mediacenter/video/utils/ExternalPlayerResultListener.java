// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.utils;

import com.archos.mediacenter.utils.LocalWatchState;
import android.annotation.SuppressLint;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.archos.environment.ArchosUtils;
import com.archos.mediacenter.utils.videodb.IndexHelper;
import com.archos.mediacenter.utils.videodb.VideoDbInfo;
import com.archos.mediacenter.video.player.ExternalPlayerService;
import com.archos.mediacenter.video.player.PrivateMode;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediascraper.ScrapeDetailResult;

import static com.archos.filecorelibrary.FileUtils.removeFileSlashSlash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by alexandre on 19/09/16.
 */
public class ExternalPlayerResultListener implements ExternalPlayerWithResultStarter.ResultListener, IndexHelper.Listener {

    private static final Logger log = LoggerFactory.getLogger(ExternalPlayerResultListener.class);

    @SuppressLint("StaticFieldLeak")
    private static ExternalPlayerResultListener sExternalPlayerResultListener;
    private Context mContext;




    private Uri mContentUri;
    private Uri mPlayerUri;
    private IndexHelper mIndexHelper;
    private VideoDbInfo mVideoDbInfo;
    private int mDuration;

    public static class ExternalPositionExtra{
        public static String VLC_JUSTPLAYER_ACTION_EXTRA_position = "position";
        public static String VLC_RESULT_EXTRA_position = "extra_position";
        public static String JUSTPLAYER_RESULT_EXTRA_position = "position"; // works for mxplayer too
        public static String JUSTPLAYER_RESULT_EXTRA_end_by = "end_by"; // works for mxplayer too
        public static String JUSTPLAYER_RESULT_EXTRA_duration = "duration"; // works for mxplayer too
        public static String VLC_RESULT_EXTRA_duration = "extra_duration";

        public static void setAllPositionExtras(Intent intent, int position){
            intent.putExtra(VLC_JUSTPLAYER_ACTION_EXTRA_position, position);
            intent.putExtra("return_result",true);//for mxplayer
        }
    }

    public static ExternalPlayerResultListener getInstance(){
        if(sExternalPlayerResultListener==null)
            sExternalPlayerResultListener = new ExternalPlayerResultListener();
        return sExternalPlayerResultListener;
    }

    public void init(Context context, Uri contentUri, Uri playerUri, VideoDbInfo videoDbInfo){
        mContext = context != null ? context.getApplicationContext() : ArchosUtils.getGlobalContext();
        mContentUri = contentUri;
        mPlayerUri = playerUri;
        if (log.isDebugEnabled()) log.debug("init: playerUri={}, contentUri={}", playerUri, contentUri);
        mContentUri = Uri.parse(removeFileSlashSlash(mContentUri.toString())); // we need to remove "file://"
        
        //get video info, useful to save video state
        mIndexHelper = new IndexHelper(mContext, null, 0);
        if(videoDbInfo!=null){
            mVideoDbInfo = videoDbInfo;
        }
        else {
            mIndexHelper.requestVideoDb(mContentUri, -1,
                    null,
                    this, false, true);
        }
    }

    @SuppressWarnings("deprecation") // bundle.get: debug dumping only
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (log.isDebugEnabled()) log.debug("onActivityResult: requestCode={}, resultCode={}, mVideoDbInfo!=null {}, mPlayerUri {}", requestCode, resultCode, (mVideoDbInfo!=null), mPlayerUri        );

        ExternalPlayerService.stopService(mContext != null ? mContext : ArchosUtils.getGlobalContext());

        // Some external video player api specs:
        // vlc https://wiki.videolan.org/Android_Player_Intents/ https://wiki.videolan.org/MediaControlAPI
        // justplayer https://github.com/moneytoo/Player/issues/203
        // mxplayer https://mx.j2inter.com/api https://sites.google.com/site/mxvpen/api
        // mpv http://mpv-android.github.io/mpv-android/intent.html
        // vimu https://www.vimu.tv/player-api
        if (data != null) {
            Bundle bundle = data.getExtras();
            if (log.isDebugEnabled()) log.debug("onActivityResult: data.getData()={}", bundle);
            if (log.isDebugEnabled()) {
                if (bundle != null) {
                    for (String key : bundle.keySet()) {
                        log.debug("onActivityResult: data {}  : {}", key, (bundle.get(key) != null ? bundle.get(key) : "NULL"));
                    }
                }
            }
        } else {
            // data = null, could be vlc (not following its spec) or hitting back before selecting external player
            if (log.isDebugEnabled()) log.debug("onActivityResult: data is null!");
            // do nothing
            return;
        }
        if (!PrivateMode.isActive() && resultCode == Activity.RESULT_OK && mVideoDbInfo != null) {
            int position = 0;
            boolean isFinished = false;
            if (data != null) {
                if (data.getExtras() != null) {
                    if (log.isDebugEnabled()) log.debug("onActivityResult: JUSTPLAYER_RESULT_EXTRA_end_by={}, VLC_RESULT_EXTRA_position={}, JUSTPLAYER_RESULT_EXTRA_duration={}, VLC_RESULT_EXTRA_duration={}, requestCode={}, resultCode={}", data.getStringExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_end_by), data.getLongExtra(ExternalPositionExtra.VLC_RESULT_EXTRA_position, -1), data.getIntExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_duration, -1), data.getLongExtra(ExternalPositionExtra.VLC_RESULT_EXTRA_duration, -1), requestCode, resultCode);
                    if (data.getIntExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_position, -1) != -1) {// justplayer/mxplayer
                        position = data.getIntExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_position, -1);
                    } else if (data.getLongExtra(ExternalPositionExtra.VLC_RESULT_EXTRA_position, -1) != -1) {// vlc
                        position = (int) data.getLongExtra(ExternalPositionExtra.VLC_RESULT_EXTRA_position, -1);
                    }
                    if (data.getIntExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_duration, -1) > 0) {
                        mDuration = data.getIntExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_duration, -1);
                    } else if (data.getLongExtra(ExternalPositionExtra.VLC_RESULT_EXTRA_duration, -1) > 0) {
                        mDuration = (int) data.getLongExtra(ExternalPositionExtra.VLC_RESULT_EXTRA_duration, -1);
                    }
                    String externalPositionExtra = data.getStringExtra(ExternalPositionExtra.JUSTPLAYER_RESULT_EXTRA_end_by);
                    if (externalPositionExtra != null && externalPositionExtra.equals("playback_completion")) { // justplayer video completion by playback complete has duration 0
                        if (log.isDebugEnabled()) log.debug("onActivityResult: video finished until the end");
                        isFinished = true;
                        position = mDuration;
                    }
                } else {
                    // data.getExtra() null means for mpv-android video watched completely when played till the end
                    isFinished = true;
                    position = mVideoDbInfo.duration;
                }
                if (log.isDebugEnabled()) log.debug("onActivityResult: position={}, duration={}", position, mDuration);
                mVideoDbInfo.lastTimePlayed = Long.valueOf(System.currentTimeMillis() / 1000L);
                if (position != -1) {
                    mVideoDbInfo.resume = position;
                    if (mDuration > 0)
                        mVideoDbInfo.duration = mDuration;
                    mIndexHelper.writeVideoInfo(mVideoDbInfo, true);
                }
                if (isFinished) markWatchedIfComplete(100);
                else markWatchedIfComplete(getPercentProgress(position));
            } else { // case already treated by return
                // data = null, could be vlc (not following its spec) or hitting back before selecting external player
                if (log.isDebugEnabled()) log.debug("onActivityResult: data is null!");
            }
        }
    }

    private int getPercentProgress(int position) {
        int progress = 0;
        int duration = mDuration!=-1?mDuration : mVideoDbInfo.duration;
        if (position >= 0 && duration > 0 && position <= duration)
            progress = (int) (position / (double) duration * 100);
        return progress;
    }

    private void markWatchedIfComplete(int percentProgress) {
        if (mVideoDbInfo == null || mVideoDbInfo.id < 0 || PrivateMode.isActive()
                || !LocalWatchState.shouldMarkWatched(percentProgress)) return;
        ContentValues values = new ContentValues(1);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.WATCHED);
        mContext.getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values,
                VideoStore.Video.VideoColumns._ID + " = ?", new String[]{Long.toString(mVideoDbInfo.id)});
    }

    @Override
    public void onVideoDb(VideoDbInfo info, VideoDbInfo remoteInfo) {
        mVideoDbInfo = info;
    }

    @Override
    public void onScraped(ScrapeDetailResult result) {}
}
