// "List view" button of the Movies / TV Shows action bar: switches the page between the poster grid
// and the banner style list view (same setting as the Library pages).

package com.archos.mediacenter.video.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.view.Menu;
import android.view.MenuItem;

import androidx.core.view.MenuItemCompat;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.ThemeManager;

public final class BannerListMenu {

    public static final int MENU_ID = 9301;

    private BannerListMenu() {}

    /** The banner list only exists on the phone UI. */
    public static boolean isPhone(Context context) {
        return ThemeManager.getInstance(context).isPhoneMode(context);
    }

    /** True when the banner list view is on (shared with the Library Movies / TV shows pages). */
    public static boolean isBanner(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PosterGridFragment.PREF_BANNER_LIST, false);
    }

    public static void toggle(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PosterGridFragment.PREF_BANNER_LIST,
                !prefs.getBoolean(PosterGridFragment.PREF_BANNER_LIST, false)).apply();
    }

    /** Adds the list view button to the action bar. */
    public static void addItem(Menu menu, boolean banner) {
        if (menu.findItem(MENU_ID) != null) return;
        MenuItem item = menu.add(0, MENU_ID, Menu.NONE, R.string.grid_list_view);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(0xFFFFFFFF));
        updateItem(item, banner);
    }

    /** The button shows what a tap switches to: the list icon on the grid, the grid icon on the banner list. */
    public static void updateItem(MenuItem item, boolean banner) {
        item.setIcon(banner ? R.drawable.mobile_ic_grid : R.drawable.mobile_ic_list);
        item.setTitle(banner ? R.string.grid_grid_view : R.string.grid_list_view);
    }
}
