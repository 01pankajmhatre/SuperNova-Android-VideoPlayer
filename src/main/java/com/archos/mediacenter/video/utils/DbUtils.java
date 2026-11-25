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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import androidx.preference.PreferenceManager;
import android.util.Log;
import com.archos.mediacenter.video.browser.Delete;
import com.archos.mediacenter.video.browser.adapters.object.Base;
import com.archos.mediacenter.video.browser.adapters.object.Collection;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Season;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediaprovider.video.ScraperStore;
import com.archos.mediaprovider.video.VideoStore;

import java.util.ArrayList;

/**
 * Created by vapillon on 13/05/15.
 */
public class DbUtils {
    private static final String TAG = "DbUtils";
    private static final Boolean DBG = false;

    private static final String KEY_NETWORK_BOOKMARKS = "network_bookmarks";

    static public void markAsRead(final Context context, final Video video) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsRead: " + video.getFilePath());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean networkBookmarksEnabled = prefs.getBoolean(KEY_NETWORK_BOOKMARKS, true);  //TODO networkBookmarks

        if (!video.isIndexed()) {
            return;
        }

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_END);

        
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.WATCHED);
        

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values,
                VideoStore.Video.VideoColumns._ID + " =?",
                new String[]{Long.toString(video.getId())});

        
    }

    static public  void markAsNotRead(final Context context, final Video video) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsNotRead: " + video.getFilePath());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean networkBookmarksEnabled = prefs.getBoolean(KEY_NETWORK_BOOKMARKS, true);  //TODO networkBookmarks

        if (!video.isIndexed()) {
            return;
        }

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_UNKNOWN);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.UNWATCHED);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, 0);

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values,
                VideoStore.Video.VideoColumns._ID + " =?",
                new String[]{Long.toString(video.getId())});

        
    }



    public static void deleteScraperInfo(Context context,Video video) {
        // Reset the scraper fields for this item in the medialib
        // (set them to -1 because there is no need to search it again when running the automated task)
        // this also deletes the scraper data
        ContentValues values = new ContentValues(2);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_ID, "-1");
        values.put(VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE, "-1");
        final String selection = VideoStore.MediaColumns._ID + "=?";
        final String[] selectionArgs =new String[]{Long.toString(video.getId())};
        context.getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, selection, selectionArgs);
        // delete nfo files and posters: no need in theory to do the API30 gazplant
        // TODO check and provide option to disable Q way here
        Delete delete = new Delete(null,context);
        delete.deleteAssociatedNfoFiles(video.getFileUri());
    }


    public static void markAsHiddenByUser(Context context, Video video) {
        markHiddenValue(context, new Long[]{video.getId()}, 1);
    }
    public static void markAsNotHiddenByUser(Context context, Video video) {
        markHiddenValue(context, new Long[]{video.getId()}, 0);
    }

    public static void markHiddenValue(Context context, Long[] videoIds, int value) {
        if (DBG) Log.d(TAG, "markHiddenValue: "+videoIds+" "+value);
        final ContentResolver cr = context.getContentResolver();
        final ContentValues values = new ContentValues(1);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_HIDDEN_BY_USER, value);
        String whereString ="";
        String [] whereArg = new String[videoIds.length];
        int i = 0;
        for (long id : videoIds) {
            if(!whereString.isEmpty())
                whereString+= " OR ";
            whereString += VideoStore.Video.VideoColumns._ID+ " = ?";
            whereArg[i] = Long.toString(id);
            i++;
        }
        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values,
                whereString,
                whereArg);
    }

    /**
     * Marke all the episodes of a season as Watched
     * @param context
     * @param season
     */
    static public void markAsRead(final Context context, final Season season) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsRead: " + season.getName()+" S"+season.getSeasonNumber());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_END);

        
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.WATCHED);
        

        final String where = "_id IN (SELECT video_id FROM episode e JOIN show s ON e.show_episode=s._id WHERE s._id=? AND e.season_episode=?)";
        final String[] selectionArgs = new String[]{Long.toString(season.getShowId()), Integer.toString(season.getSeasonNumber())};

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, where, selectionArgs);

        
    }

    /**
     * Marke all the episodes of a season as not read
     * @param context
     * @param season
     */
    static public void markAsNotRead(final Context context, final Season season) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsNotRead: " + season.getName()+" S"+season.getSeasonNumber());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_UNKNOWN);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.UNWATCHED);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, 0);

        final String where = "_id IN (SELECT video_id FROM episode e JOIN show s ON e.show_episode=s._id WHERE s._id=? AND e.season_episode=?)";
        final String[] selectionArgs = new String[]{Long.toString(season.getShowId()), Integer.toString(season.getSeasonNumber())};

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, where, selectionArgs);

        
    }

    /**
     * Marke all the movies of a collection as Watched
     * @param context
     * @param collection
     */
    static public void markAsRead(final Context context, final Collection collection) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsRead: " + collection.getName());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_END);

        
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.WATCHED);
        

        final String where = "_id IN (SELECT video_id FROM movie m WHERE m.m_coll_id=?)";
        final String[] selectionArgs = new String[]{Long.toString(collection.getCollectionId())};

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, where, selectionArgs);

        
    }

    /**
     * Marke all the movies of a collection as not read
     * @param context
     * @param collection
     */
    static public void markAsNotRead(final Context context, final Collection collection) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsNotRead: " + collection.getName());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_UNKNOWN);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.UNWATCHED);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, 0);

        final String where = "_id IN (SELECT video_id FROM movie m WHERE m.m_coll_id=?)";

        final String[] selectionArgs = new String[]{Long.toString(collection.getCollectionId())};

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, where, selectionArgs);

        
    }
    
    public static void markAsHiddenByUser(final Context context, final Season season) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markHiddenValue: " + season.getName()+" S"+season.getSeasonNumber());
        
        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.ARCHOS_HIDDEN_BY_USER, 1);

        final String where = "_id IN (SELECT video_id FROM episode e JOIN show s ON e.show_episode=s._id WHERE s._id=? AND e.season_episode=?)";
        final String[] selectionArgs = new String[]{Long.toString(season.getShowId()), Integer.toString(season.getSeasonNumber())};
        
        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, where, selectionArgs);
    }

    public static void markAsHiddenByUser(final Context context, final Collection collection) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markHiddenValue: " + collection.getName());

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.ARCHOS_HIDDEN_BY_USER, 1);

        final String where = "_id IN (SELECT video_id FROM movie m WHERE m.m_coll_id=?)";

        final String[] selectionArgs = new String[]{Long.toString(collection.getCollectionId())};

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, where, selectionArgs);
    }
    
    public static ArrayList<String> getFilePaths(final Context context, final Season season) {
        ArrayList<String> filePaths = new ArrayList<String>();
        final Uri uri = VideoStore.Video.Media.EXTERNAL_CONTENT_URI;
        final String[] projection = new String[] { VideoStore.MediaColumns.DATA };
        final String selection = VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID + "=? AND " + VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + "=?";
        final String[] selectionArgs = new String[] { String.valueOf(season.getShowId()), String.valueOf(season.getSeasonNumber()) };
        final String sortOrder = VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + ", " + VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE;
        Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
        final int filePathColumn = c.getColumnIndex(VideoStore.MediaColumns.DATA);
        
        c.moveToFirst();
        while (!c.isAfterLast()) {
            String filePath = c.getString(filePathColumn);
            filePaths.add(filePath);
            c.moveToNext();
        }
        c.close();

        return filePaths;
    }

    public static ArrayList<String> getFilePaths(final Context context, final Collection collection) {
        ArrayList<String> filePaths = new ArrayList<String>();
        final Uri uri = VideoStore.Video.Media.EXTERNAL_CONTENT_URI;
        final String[] projection = new String[] { VideoStore.MediaColumns.DATA };
        final String selection = VideoStore.Video.VideoColumns.SCRAPER_C_ID + "=?";
        final String[] selectionArgs = new String[] { String.valueOf(collection.getCollectionId()) };
        final String sortOrder = VideoStore.Video.VideoColumns.SCRAPER_M_NAME;
        Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
        final int filePathColumn = c.getColumnIndex(VideoStore.MediaColumns.DATA);

        c.moveToFirst();
        while (!c.isAfterLast()) {
            String filePath = c.getString(filePathColumn);
            filePaths.add(filePath);
            c.moveToNext();
        }
        c.close();

        return filePaths;
    }

    public static void markAsNotRead(final Context context) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsNotRead");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.BOOKMARK, PlayerActivity.LAST_POSITION_UNKNOWN);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, LocalWatchState.UNWATCHED);
        values.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, 0);

        cr.update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, null, null);

        
    }



    static public void markAsPinned(final Context context, final Base base) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsPinned: " + base.getName());

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.NOVA_PINNED, (long)(System.currentTimeMillis() / 1000));

        if (base instanceof Movie) {
            cr.update(ScraperStore.Movie.URI.BASE, values,
                    VideoStore.Video.VideoColumns._ID + " =?",
                    new String[]{Long.toString(((Movie)base).getMovieId())});
        }
        else if (base instanceof Tvshow) {
            cr.update(ScraperStore.Show.URI.BASE, values,
                    VideoStore.Video.VideoColumns._ID + " =?",
                    new String[]{Long.toString(((Tvshow)base).getTvshowId())});
        }
    }

    static public  void markAsNotPinned(final Context context, final Base base) {
        final ContentResolver cr = context.getContentResolver();
        if (DBG) Log.d(TAG, "markAsNotPinned: " + base.getName());

        final ContentValues values = new ContentValues();
        values.put(VideoStore.Video.VideoColumns.NOVA_PINNED, 0);

        if (base instanceof Movie) {
            cr.update(ScraperStore.Movie.URI.BASE, values,
                    VideoStore.Video.VideoColumns._ID + " =?",
                    new String[]{Long.toString(((Movie)base).getMovieId())});
        }
        else if (base instanceof Tvshow) {
            cr.update(ScraperStore.Show.URI.BASE, values,
                    VideoStore.Video.VideoColumns._ID + " =?",
                    new String[]{Long.toString(((Tvshow)base).getTvshowId())});
        }
    }
}
