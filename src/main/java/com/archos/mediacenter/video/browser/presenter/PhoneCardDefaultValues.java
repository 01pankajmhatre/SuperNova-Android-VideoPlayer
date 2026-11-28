package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.adapters.AdapterDefaultValues;
import com.archos.mediacenter.video.browser.adapters.AdapterDefaultValuesGrid;

/** Grid defaults with the phone poster card layout. */
public enum PhoneCardDefaultValues implements AdapterDefaultValues {
    INSTANCE;

    @Override public int getDefaultDirectoryThumbnail() { return AdapterDefaultValuesGrid.INSTANCE.getDefaultDirectoryThumbnail(); }
    @Override public int getDefaultShortcutThumbnail() { return AdapterDefaultValuesGrid.INSTANCE.getDefaultShortcutThumbnail(); }
    @Override public int getDefaultVideoThumbnail() { return AdapterDefaultValuesGrid.INSTANCE.getDefaultVideoThumbnail(); }
    @Override public int getMediaSyncIcon(int state) { return AdapterDefaultValuesGrid.INSTANCE.getMediaSyncIcon(state); }
    @Override public int getLayoutId() { return R.layout.phone_movie_grid_item; }
    @Override public int getLayoutId(int itemType) { return R.layout.phone_movie_grid_item; }
    @Override public int[] getThumnailHeightWidth(Context context) { return AdapterDefaultValuesGrid.INSTANCE.getThumnailHeightWidth(context); }
    @Override public int getExpandedZone() { return AdapterDefaultValuesGrid.INSTANCE.getExpandedZone(); }
}
