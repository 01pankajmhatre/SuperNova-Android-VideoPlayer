// Copyright 2026 Courville Software
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

package com.archos.mediacenter.video.browser.BrowserByIndexedVideos;

import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import com.archos.mediacenter.video.browser.presenter.Presenter;
import com.archos.mediacenter.video.browser.presenter.PhoneMovieGridPresenter;
import com.archos.mediacenter.video.browser.presenter.BannerPresenter;
import com.archos.mediacenter.video.browser.BannerListMenu;
import com.archos.mediacenter.video.browser.adapters.PresenterAdapterInterface;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.utils.ThemeManager;

import androidx.core.view.MenuProvider;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import com.archos.mediacenter.utils.ActionBarSubmenu;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.browser.Browser;
import com.archos.mediacenter.video.browser.loader.VideosSelectionLoader;
import com.archos.mediacenter.video.utils.SortOrder;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;
import com.archos.mediacenter.video.utils.VideoUtils;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediaprovider.video.VideoStore.MediaColumns;
import com.archos.mediaprovider.video.VideoStore.Video.VideoColumns;

public class BrowserByVideoSelection extends CursorBrowserByVideo {

    private boolean usesPhoneMovieGrid() {
        return (this instanceof BrowserAllMovies || getClass() == BrowserByVideoSelection.class)
                && ThemeManager.getInstance(requireContext()).isPhoneMode(requireContext());
    }

    /** Banner style list view (list view button in the action bar), instead of the phone poster grid. */
    private boolean usesBannerList() {
        return usesPhoneMovieGrid() && BannerListMenu.isBanner(requireContext());
    }

    @Override
    protected void setViewMode(int mode) {
        super.setViewMode(usesBannerList() ? VideoUtils.VIEW_MODE_LIST : usesPhoneMovieGrid() ? VideoUtils.VIEW_MODE_GRID : mode);
        if (usesBannerList()) {
            float density = getResources().getDisplayMetrics().density;
            mArchosGridView.setPadding(0, 0, 0, Math.round(24 * density));
            mArchosGridView.setClipToPadding(false);
            mArchosGridView.setSelector(android.R.color.transparent);
            if (mArchosGridView instanceof android.widget.ListView) {
                ((android.widget.ListView) mArchosGridView).setDivider(null);
                ((android.widget.ListView) mArchosGridView).setDividerHeight(0);
            } else if (mArchosGridView instanceof android.widget.GridView) {
                ((android.widget.GridView) mArchosGridView).setVerticalSpacing(0);
                ((android.widget.GridView) mArchosGridView).setHorizontalSpacing(0);
            }
            mRootView.setBackgroundColor(ThemeManager.getInstance(requireContext()).getLeanbackBackgroundColor());
        } else if (usesPhoneMovieGrid()) {
            android.widget.GridView grid = (android.widget.GridView) mArchosGridView;
            float density = getResources().getDisplayMetrics().density;
            int columns = Math.max(2, getResources().getConfiguration().screenWidthDp / 180);
            grid.setNumColumns(columns);
            grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(Math.round(12 * density));
            grid.setVerticalSpacing(Math.round(20 * density));
            grid.setPadding(Math.round(16 * density), Math.round(16 * density),
                    Math.round(16 * density), Math.round(24 * density));
            grid.setClipToPadding(false);
            grid.setSelector(android.R.color.transparent);
            mRootView.setBackgroundColor(ThemeManager.getInstance(requireContext()).getLeanbackBackgroundColor());
        }
    }

    @Override
    protected void setupAdapter(boolean createNewAdapter) {
        super.setupAdapter(createNewAdapter);
        if (usesBannerList()) {
            PresenterAdapterInterface adapter = (PresenterAdapterInterface) mBrowserAdapter;
            Presenter presenter = new BannerPresenter(requireContext());
            adapter.setPresenter(Video.class, presenter);
            adapter.setPresenter(Movie.class, presenter);
        } else if (usesPhoneMovieGrid()) {
            PresenterAdapterInterface adapter = (PresenterAdapterInterface) mBrowserAdapter;
            Presenter presenter = new PhoneMovieGridPresenter(requireContext(), this);
            adapter.setPresenter(Video.class, presenter);
            adapter.setPresenter(Movie.class, presenter);
        }
    }

    private static final boolean DBG = false;
    private static final String TAG = "BrowserByVideoSelection";

	public static final String SELECTION_ALL_MOVIES = VideoStore.Video.VideoColumns.SCRAPER_MOVIE_ID + " IS NOT NULL";

	public static final String DEFAULT_SORT = "name COLLATE LOCALIZED";

	static final String SORT_PARAM_KEY = BrowserByVideoSelection.class.getName()+"_SORT";

	// To be put in args to select only a subset of the movies
	static final public String LIST_OF_IDS = "ListOfMovieIds";

	protected String mSortOrder = DEFAULT_SORT;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (savedInstanceState!=null) {
			mSortOrder = savedInstanceState.getString(SORT_PARAM_KEY);
		}
        else if (getArguments()!=null && getArguments().getString(SORT_PARAM_KEY, null)!=null){
            mSortOrder = getArguments().getString(SORT_PARAM_KEY, null);
        }
		else {
			mSortOrder = mPreferences.getString(SORT_PARAM_KEY, DEFAULT_SORT);
		}

		mTitle = null; // no default title because there may be the NAVIGATION_MODE_LIST list at this place instead
		Bundle args = getArguments();
		if (args != null) {
		    mTitle = args.getString(CursorBrowserByVideo.SUBCATEGORY_NAME);
		}
		mHideOption = true;
        mHideWatched = mPreferences.getBoolean(VideoPreferencesCommon.KEY_HIDE_WATCHED,false);
	}

	@Override
	public void onDestroy() {
		// Save the sort mode
		mPreferences.edit()
		.putString(SORT_PARAM_KEY, mSortOrder)
		.apply();

		super.onDestroy();
	}

	@Override
	public void onSaveInstanceState(Bundle state) {
		super.onSaveInstanceState(state);
		state.putString(SORT_PARAM_KEY, mSortOrder);
	}

	@Override
	public int getDefaultViewMode() {
		return VideoUtils.VIEW_MODE_GRID;
	}

	@Override
	public int getEmptyMessage() {
		return R.string.scraper_no_movie_text;
	}

	@Override
	public int getEmptyViewButtonLabel() {
		return R.string.scraper_no_movie_button_label;
	}

	public boolean showEmptyViewButton() {
		return true;
	}

	@Override
	protected String getActionBarTitle() {
	    return mTitle;
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		requireActivity().addMenuProvider(new MenuProvider() {
			@Override
			public void onCreateMenu(Menu menu, MenuInflater menuInflater) {
				menu.removeItem(Browser.MENU_VIEW_MODE);
				if (usesPhoneMovieGrid() && mBrowserAdapter != null && !mBrowserAdapter.isEmpty()) {
					BannerListMenu.addItem(menu, usesBannerList());   // list view button, left of the sort button
				}
				if (mBrowserAdapter != null && !mBrowserAdapter.isEmpty() && mSortModeSubmenu != null) {
					MenuItem sortMenuItem = menu.add(Browser.MENU_VIEW_MODE_GROUP, Browser.MENU_VIEW_MODE, Menu.NONE, R.string.sort_mode);
					sortMenuItem.setIcon(R.drawable.ic_menu_sort);
					sortMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
					mSortModeSubmenu.attachMenuItem(sortMenuItem);
					mSortModeSubmenu.clear();
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_name_asc,       MENU_ITEM_SORT+MENU_ITEM_NAME    +MENU_ITEM_ASC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_name_desc,      MENU_ITEM_SORT+MENU_ITEM_NAME    +MENU_ITEM_DESC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_year_asc,       MENU_ITEM_SORT+MENU_ITEM_YEAR    +MENU_ITEM_ASC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_year_desc,      MENU_ITEM_SORT+MENU_ITEM_YEAR    +MENU_ITEM_DESC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_duration_asc,   MENU_ITEM_SORT+MENU_ITEM_DURATION+MENU_ITEM_ASC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_duration_desc,  MENU_ITEM_SORT+MENU_ITEM_DURATION+MENU_ITEM_DESC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_rating_asc,     MENU_ITEM_SORT+MENU_ITEM_RATING  +MENU_ITEM_DESC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_date_added_desc, MENU_ITEM_SORT+MENU_ITEM_ADDED+MENU_ITEM_DESC);
					mSortModeSubmenu.addSubmenuItem(0, R.string.sort_by_date_added_asc,  MENU_ITEM_SORT+MENU_ITEM_ADDED+MENU_ITEM_ASC);
					int initId = sortorder2itemid(mSortOrder);
					if (initId == -1) {
						mSortModeSubmenu.selectSubmenuItem(0);
					} else {
						int position = mSortModeSubmenu.getPosition(initId);
						mSortModeSubmenu.selectSubmenuItem(position < 0 ? 0 : position);
					}
				}
			}
			@Override
			public void onPrepareMenu(Menu menu) {
				MenuItem item = menu.findItem(Browser.MENU_VIEW_MODE);
				if (item != null) item.setIcon(R.drawable.ic_menu_sort);
				MenuItem listItem = menu.findItem(BannerListMenu.MENU_ID);
				if (listItem != null) BannerListMenu.updateItem(listItem, usesBannerList());
			}
			@Override
			public boolean onMenuItemSelected(MenuItem item) {
				if (item.getItemId() == BannerListMenu.MENU_ID) {
					BannerListMenu.toggle(requireContext());
					applySelectedViewMode(usesBannerList() ? VideoUtils.VIEW_MODE_LIST : VideoUtils.VIEW_MODE_GRID);
					requireActivity().invalidateOptionsMenu();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner());
	}
	@Override
	public void onSubmenuItemSelected(ActionBarSubmenu submenu, int position, long itemId) {
		if (submenu==mSortModeSubmenu) {
			if ((itemId & MENU_ITEM_SORT_MASK)==MENU_ITEM_SORT) {
				mSortOrder = itemid2sortorder((int)itemId);
				// It's not enough to call notifyDataSetChanged() here to have the sort mode changed, must reset at Loader level. 
				LoaderManager.getInstance(this).restartLoader(0, null, this);
			}
		}
		else {
			super.onSubmenuItemSelected(submenu, position, itemId);
		}
	}

	private static String itemid2sortorder(int itemid) {

		String sortOrder = DEFAULT_SORT;
        boolean parseOrderAfterType = true;
        boolean isDesc = (itemid & MENU_ITEM_SORT_ORDER_MASK) == MENU_ITEM_DESC;

		switch (itemid & MENU_ITEM_SORT_TYPE_MASK) {
		// What is sorted
		case MENU_ITEM_NAME:
			sortOrder = "name COLLATE LOCALIZED";
			break;
		case MENU_ITEM_YEAR:
			sortOrder = VideoColumns.SCRAPER_M_YEAR + (isDesc ? " DESC, " : " ASC, ") + VideoColumns.SCRAPER_M_RELEASE_DATE + (isDesc ? " DESC" : " ASC");
			parseOrderAfterType = false;
			break;
		case MENU_ITEM_DURATION:
		    sortOrder = SortOrder.DURATION.get(isDesc);
		    parseOrderAfterType = false;
			break;
		case MENU_ITEM_RATING:
		    sortOrder = SortOrder.SCRAPER_M_RATING.get(isDesc);
		    parseOrderAfterType = false;
			break;
		case MENU_ITEM_ADDED:
			sortOrder = MediaColumns.DATE_ADDED;
			break;
		}

		if (parseOrderAfterType) {
		    // Order of the sort
		    switch (itemid & MENU_ITEM_SORT_ORDER_MASK) {
		        case MENU_ITEM_ASC:
		            sortOrder += " ASC";
		            break;
		        case MENU_ITEM_DESC:
		            sortOrder += " DESC";
		            break;
		    }
		}

		if (DBG) Log.d(TAG, "itemid2sortorder: sortOrder="+sortOrder);
		return sortOrder;
	}

	/**
	 * Returns -1 if given sortOrder can't be found in the menuid list
	 * @param sortOrder
	 * @return
	 */
	private static int sortorder2itemid(String sortOrder) {
		int itemId = MENU_ITEM_SORT;

		if (sortOrder.contains("name")) {
			itemId |= MENU_ITEM_NAME;
		}
		else if (sortOrder.contains(VideoColumns.SCRAPER_M_YEAR)) {
			itemId |= MENU_ITEM_YEAR;
		}
		else if (sortOrder.contains(VideoColumns.DURATION)) {
			itemId |= MENU_ITEM_DURATION;
		}
		else if (sortOrder.contains(VideoColumns.SCRAPER_M_RATING)) {
			itemId |= MENU_ITEM_RATING;
		}
		else if (sortOrder.contains(MediaColumns.DATE_ADDED)) {
			itemId |= MENU_ITEM_ADDED;
		}
		else {
			return -1; // better return an error in case we don't manage to find what is the current settings (it may be not supported anymore)
		}

		if (sortOrder.contains("ASC")) {
			itemId |= MENU_ITEM_ASC;
		} else if (sortOrder.contains("DESC")) {
			itemId |= MENU_ITEM_DESC;
		} else {
			return -1; // better return an error in case we don't manage to find what is the current settings (it may be not supported anymore)
		}

		return itemId;

	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args2) {
		if(getArguments()!=null){
			String listOfMoviesIds = getArguments().getString(BrowserByVideoSelection.LIST_OF_IDS);
			if (listOfMoviesIds != null)
				return new VideosSelectionLoader(getContext(), listOfMoviesIds, mSortOrder).getV4CursorLoader(true, mPreferences.getBoolean(VideoPreferencesCommon.KEY_HIDE_WATCHED, false));
		}
		return null;
	}
}
