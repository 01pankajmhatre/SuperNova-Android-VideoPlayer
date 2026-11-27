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


package com.archos.mediacenter.video.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.archos.mediacenter.video.browser.BrowserByIndexedVideos.BrowserListOfEpisodes;
import com.archos.mediacenter.video.browser.BrowserByIndexedVideos.BrowserListOfSeasons;
import com.archos.mediacenter.video.browser.BrowserByIndexedVideos.CursorBrowserByVideo;
import com.archos.mediacenter.video.browser.adapters.mappers.CompatibleCursorMapper;
import com.archos.mediacenter.video.browser.adapters.mappers.TvshowCursorMapper;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.loader.AllTvshowsLoader;
import com.archos.mediacenter.video.browser.loader.LastPlayedLoader;
import com.archos.mediacenter.video.browser.loader.LastAddedLoader;
import com.archos.mediacenter.video.browser.loader.MoviesLoader;
import com.archos.mediaprovider.video.VideoStore;
import java.util.List;
import java.util.Locale;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.PreferenceManager;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.fragment.app.ListFragment;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.archos.environment.ArchosUtils;
import com.archos.filecorelibrary.ExtStorageManager;
import com.archos.filecorelibrary.ExtStorageReceiver;
import com.archos.mediacenter.video.BuildConfig;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.info.VideoInfoActivity;
import com.archos.mediacenter.video.player.PrivateMode;
import com.archos.mediacenter.video.utils.ThemeManager;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;
import com.archos.mediacenter.video.utils.WebUtils;
import com.archos.environment.NetworkState;
import com.archos.mediaprovider.video.NetworkAutoRefresh;
import com.archos.mediaprovider.ImportState;
import com.archos.mediaprovider.video.LoaderUtils;
import com.archos.mediaprovider.video.NetworkScannerReceiver;
import com.archos.mediascraper.AutoScrapeService;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ArrayList;

abstract public class BrowserCategory extends ListFragment {

    private static final String SELECTED_ID = "selectedId";
    private static final String SELECTED_TOP = "selectedTop";
    public static final String MOUNT_POINT = "mount_point";
    private static final String TAG = "BrowserCategory";
    private static final boolean DBG = false;
    private static final boolean DBG_LISTENER = false;

    private static final int[] mExternalIDs = {
            R.string.sd_card_storage, R.string.usb_host_storage, R.string.other_storage, R.string.network_shared_folders,R.string.network_shortcuts,
            R.string.network_media_servers, R.string.network_jcifs, R.string.network_cling,R.string.preferences
    };
    private static final String PREFERENCE_LAST_FRAGMENT = "preference_last_selected_fragment";
    private static final String PREFERENCE_LAST_PATH = "preference_last_selected_path";
    protected static final int ITEM_ID_BROWSER = 1;
    protected static final int ITEM_ID_VIDEO_FOLDER = 7;
    protected static final int ITEM_ID_OFFSET = 7;
    protected static final int ITEM_ID_SMB = 2;
    protected static final int ITEM_ID_UPNP = 3;
    protected static final int ITEM_ID_FTP = 4;
    protected static final int ITEM_ID_PROVIDER = 6;
    protected static final int ITEM_ID_NETWORK = 5;
    final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getData() != null) {
                    VideoInfoActivity.startInstance(getActivity(), null, result.getData().getData(), -1L);
                }
            });

    private int mLibrarySize;
    protected int mSelectedItemId;
    private int mSelectedItemTop;
    protected ArrayList<Object> mCategoryList;
    private CategoryAdapter mCategoryAdapter;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mHomeSourcesAdapter;
    private TextView mSourcesStatus;
    /** Tiles of the phone home grid (library, storage, one "Network" tile). */
    private final ArrayList<ItemData> mHomeTiles = new ArrayList<>();
    private final ArrayList<ItemData> mAvailableHomeTiles = new ArrayList<>();
    public static final String KEEP_HOME_SOURCES_VISIBLE = "classic_home_pin_sources";
    public static final String HOME_CARD_COLOR = "classic_home_source_card_color";
    public static final String HOME_SOURCES_BOTTOM = "classic_home_sources_bottom";
    public static final String HOME_COMBINED_GRID = "classic_home_combined_grid";
    public static final String HOME_POSTER_PAGE = "classic_home_poster_page";
    private static final String HOME_BACKGROUND_COLOR = "classic_home_background_color";   // same key as MainActivity's background setting
    private static final int DEFAULT_HOME_BACKGROUND = 0xFF0B1220;
    private static final String HIDDEN_HOME_ROWS = "classic_home_hidden_rows";
    private static final int[] HOME_ROW_IDS = {R.id.home_recent_row, R.id.home_added_row,
            R.id.home_movies_row, R.id.home_series_row};
    private static final int[] HOME_ROW_TITLES = {R.string.home_recent, R.string.home_added,
            R.string.home_movies, R.string.home_series};
    private static final String[] HOME_ROW_KEYS = {"played", "added", "movies", "shows"};
    public static final String HIDE_HOME_SOURCES_ROW = "classic_home_hide_sources_row";
    public static final String SHOW_ROW_ALL_VIDEOS = "classic_home_row_all_videos";
    public static final String SHOW_ROW_NETWORK = "classic_home_row_network";
    private static final String SHOWN_EXTRA_CARDS = "classic_home_extra_cards";
    private static final int LOADER_HOME_ALL = 9105;
    protected static final int ITEM_ID_NET_ENTRY = 104;
    private static final int ITEM_ID_HOME_DLNA_NOW = -205;
    public static final String HOME_BOTTOM_PILL = "classic_home_bottom_pill";
    private HomePosterAdapter mAllAdapter;
    private TextView mAllStatus;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mNetworkRowAdapter;
    private final ArrayList<ItemData> mNetworkRowItems = new ArrayList<>();
    /** Cards of the Home "Sources" row: DLNA Now, Movies, TV Shows, All videos, Recently played, Recently added, Network, then the storages and network sources. */
    private final ArrayList<ItemData> mHomeSourceCards = new ArrayList<>();
    private static final String HIDDEN_HOME_SOURCES = "classic_home_hidden_sources";
    protected static final int ITEM_ID_HOME_NETWORK = 100;
    // Files page: the "Network Sources" heading and the fixed network entries below it
    private static final int ITEM_ID_FILES_HEADER = -210;
    private static final int ITEM_ID_FILES_SMB = -211, ITEM_ID_FILES_UPNP = -212, ITEM_ID_FILES_SHORTCUT = -213,
            ITEM_ID_FILES_CLOUD = -214, ITEM_ID_FILES_DLNA_HOST = -215;
    private static final int ITEM_ID_HOME_SETTINGS = -201;
    private static final int ITEM_ID_HOME_PREFERENCES = -202;
    private static final int ITEM_ID_HOME_RESCAN = -203;
    private static final int LOADER_HOME_RECENT = 9101;
    private static final int LOADER_HOME_MOVIES = 9102;
    private static final int LOADER_HOME_SERIES = 9103;
    private static final int LOADER_HOME_ADDED = 9104;
    private static final int HOME_ROW_MAX = 40;
    private final android.util.LongSparseArray<HomePosterAdapter.Poster> mHomeShows = new android.util.LongSparseArray<>();
    private Cursor mHomeAddedCursor;
    private HomePosterAdapter mRecentAdapter, mAddedAdapter, mMoviesAdapter, mSeriesAdapter;
    /** The second ("cinema") phone home layout and the listener that switches between the two layouts. */
    private TextView mRecentStatus, mAddedStatus, mMoviesStatus, mSeriesStatus;
    private LayoutCallback mLayoutCallback;
    protected SharedPreferences mPreferences;
    protected int mOldSelectedItemId;

    private NetworkState networkState = null;
    private PropertyChangeListener propertyChangeListener = null;
    private boolean mNetworkStateListenerAdded = false;
    private boolean mEnableSponsor = false;
    private SharedPreferences.OnSharedPreferenceChangeListener mThemeChangeListener;

    /**
     * This object is used to store basic info for the category list item.
     */
    public static class ItemData {
        public int icon=-1;
        public int text;
        public String path;
        public int id;
        public String name;      // literal title (discovered network sources); null: use "text"
        public String details;   // literal details line; null: computed
    };

    public static class FragmentTitleStruc{
        public Fragment fragment;
        public int title;
    }

    private class LayoutCallback implements BrowserLayout.Callback {
        // The fragment to start when the callback is called.
        Fragment fragment;

        public void onLayoutChanged() {
            // Always update the category content when the application layout has changed
            // (i.e. after switching between the cover roll and the category content)
            if (fragment != null) {
                updateCategoryContent(fragment, fragment.getTag());
                fragment = null;
            }
        }
        public void onGoHome() {
            ((BrowserActivity) getActivity()).goHome();
        }
    }

    /**
     * Update (un)mount sdcard/usb host/samba/upnp.
     */
    private final BroadcastReceiver mExternalStorageReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DBG) Log.d(TAG, "mExternalStorageReceiver: intent " + intent);
            String action = intent.getAction();
            if (action.equals(ExtStorageReceiver.ACTION_MEDIA_MOUNTED)){
                String path = null;
                if(intent.getDataString().startsWith("file"))
                    path = intent.getDataString().substring("file".length());
                else if (intent.getDataString().startsWith(ExtStorageReceiver.ARCHOS_FILE_SCHEME))
                    path = intent.getDataString().substring(ExtStorageReceiver.ARCHOS_FILE_SCHEME.length());
                if (path == null || path.isEmpty())
                    return;
                mSelectedItemId = 0;
                updateExternalStorage();
            } else if (action.equals(ExtStorageReceiver.ACTION_MEDIA_CHANGED)){
                updateExternalStorage();
            } else if (action.equals(ExtStorageReceiver.ACTION_MEDIA_UNMOUNTED)){
                final String path = intent.getDataString();
                if (path == null || path.isEmpty())
                    return;
                mSelectedItemId = 0;
                updateExternalStorage();
            }
        }
    };

    public void onSaveInstanceState(Bundle outState) {
        if (mSelectedItemId != 0) {
            outState.putInt(SELECTED_ID, mSelectedItemId);
            outState.putInt(SELECTED_TOP, mSelectedItemTop);
        }
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        boolean phone = ThemeManager.getInstance(requireContext()).isPhoneMode(requireContext());
        View root = inflater.inflate(phone ? R.layout.browser_category_phone : R.layout.browser_category, container, false);
        ListView categoryView = phone ? root.findViewById(android.R.id.list) : (ListView) root;
        categoryView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        
        // Check if we're in phone mode with black theme for special selector
        ThemeManager themeManager = ThemeManager.getInstance(getContext());
        if (themeManager.isBlackTheme() && themeManager.isPhoneMode(getContext())) {
            // Phone + Black theme: Use rounded grey selector
            categoryView.setSelector(R.drawable.phone_category_selector);
        } else {
            // TV or Blue theme: Use standard selector
            android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
            int selectorColor = themeManager.getCategorySelectorColor();
            // Activated state (selected item)
            selector.addState(new int[]{android.R.attr.state_activated}, new android.graphics.drawable.ColorDrawable(selectorColor));
            // Focused state (keyboard navigation)
            selector.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.ColorDrawable(selectorColor));
            // Default state (transparent)
            selector.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            categoryView.setSelector(selector);
        }
        return root;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String lastPath = null;
        if (savedInstanceState != null) {
            mSelectedItemId = savedInstanceState.getInt(SELECTED_ID);
            mSelectedItemTop = savedInstanceState.getInt(SELECTED_TOP);
        }else{
            mSelectedItemId = mPreferences.getInt(PREFERENCE_LAST_FRAGMENT, getDefaultId());
            lastPath = PreferenceManager.getDefaultSharedPreferences(getContext()).getString(PREFERENCE_LAST_PATH, null);
            if(!checkAvailability(mSelectedItemId, lastPath))
                mSelectedItemId = getDefaultId();

        }
        mLayoutCallback = new LayoutCallback();

        updateLibrary();
        mCategoryAdapter = new CategoryAdapter(requireContext());
        setListAdapter(mCategoryAdapter);
        View homeScroll = view.findViewById(R.id.home_classic);
        if (homeScroll instanceof SmoothNestedScrollView && (com.archos.mediacenter.video.player.tvmenu.TVUtils.isTV(requireContext())
                || com.archos.environment.ArchosFeatures.isAndroidTV(requireContext()))) {
            ((SmoothNestedScrollView) homeScroll).setSmoothFocusScroll(true);   // remote: animated move to the focused row
        }
        View sourcesRow = view.findViewById(R.id.home_sources_row);
        if (sourcesRow != null) {
            ((TextView) sourcesRow.findViewById(R.id.home_row_title)).setText("");   // no "Select Source" label
            mSourcesStatus = sourcesRow.findViewById(R.id.home_row_status);
            mSourcesStatus.setText("");
            // Small spinner shown in front of the scan status text.
            ViewGroup statusParent = (ViewGroup) mSourcesStatus.getParent();
            mSourcesSpinner = new android.widget.ProgressBar(requireContext(), null, android.R.attr.progressBarStyleSmall);
            mSourcesSpinner.setIndeterminateTintList(ColorStateList.valueOf(0xFF9FB3D1));
            mSourcesSpinner.setVisibility(View.GONE);
            float spinnerDensity = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout.LayoutParams spinnerParams = new android.widget.LinearLayout.LayoutParams(
                    Math.round(16 * spinnerDensity), Math.round(16 * spinnerDensity));
            spinnerParams.setMarginStart(Math.round(12 * spinnerDensity));
            statusParent.addView(mSourcesSpinner, statusParent.indexOfChild(mSourcesStatus), spinnerParams);
            android.widget.LinearLayout.LayoutParams statusParams =
                    (android.widget.LinearLayout.LayoutParams) mSourcesStatus.getLayoutParams();
            statusParams.setMarginStart(Math.round(8 * spinnerDensity));
            mSourcesStatus.setLayoutParams(statusParams);
            // Keep the source count at the right edge of its section heading.
            TextView title = sourcesRow.findViewById(R.id.home_row_title);
            android.widget.LinearLayout.LayoutParams titleParams =
                    (android.widget.LinearLayout.LayoutParams) title.getLayoutParams();
            titleParams.width = 0;
            titleParams.weight = 1;
            title.setLayoutParams(titleParams);
            View sourceHeader = (View) title.getParent();
            sourceHeader.setOnClickListener(v -> showSourcePicker());
            sourceHeader.setFocusable(true);
            sourceHeader.setBackgroundResource(R.drawable.phone_header_focus); // visible focus for the remote
            RecyclerView sources = sourcesRow.findViewById(R.id.home_row_list);
            sources.setLayoutManager(new SensitiveLinearLayoutManager(requireContext()));
            sources.setNestedScrollingEnabled(false);
            mHomeSourcesAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override public int getItemCount() { return currentHomeTiles().size(); }
                @Override public int getItemViewType(int position) {
                    return currentHomeTiles().get(position).id == ITEM_ID_FILES_HEADER ? 1 : 0;
                }
                @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                    if (viewType == 1) return new RecyclerView.ViewHolder(createFilesHeader(parent.getContext())) {};
                    View tile = LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.browser_source_card_phone, parent, false);
                    return new RecyclerView.ViewHolder(tile) {};
                }
                @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                    View tile = holder.itemView;
                    ItemData item = currentHomeTiles().get(position);
                    if (item.id == ITEM_ID_FILES_HEADER) {
                        ((TextView) tile.findViewWithTag("files_header_text")).setText(item.text);
                        return;
                    }
                    tile.findViewById(R.id.tile_artwork).setBackgroundTintList(
                            ColorStateList.valueOf(sourceCardColor(item)));
                    ((TextView) tile.findViewById(R.id.tile_title)).setText(itemTitle(item));
                    TextView details = tile.findViewById(R.id.tile_subtitle);
                    CharSequence detailText = homeTileDetails(item);
                    details.setText(detailText);
                    details.setVisibility(detailText.length() > 0 ? View.VISIBLE : View.GONE);
                    tile.findViewById(R.id.tile_pin).setVisibility(isLastOpenedSource(item) ? View.VISIBLE : View.GONE);
                    ImageView icon = tile.findViewById(R.id.tile_icon);
                    icon.setVisibility(item.icon != -1 ? View.VISIBLE : View.GONE);
                    if (item.icon != -1) icon.setImageResource(item.icon);
                    tile.setOnClickListener(v -> onSourceCardClick(item, v));
                }
            };
            rebuildHomeTiles();
            sources.setAdapter(mHomeSourcesAdapter);
            // Remote (TV): start with the focus on the first source tile
            sources.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    View first = sources.getChildAt(0);
                    if (first == null) return;
                    sources.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (!sources.isInTouchMode() && sources.isShown() && getActivity() != null
                            && getActivity().getCurrentFocus() == null) {
                        first.requestFocus();
                    }
                }
            });
            setupHomeRows(view);
            applyHomeSourcePin(view);
            applyBottomPill();
        }

        // in some release mSelectedItemId and path can be in a weird state
        // meaning that mSelectedItemId is BrowserExt and path is null
        // avoiding this special case
        // I know this is ugly
        if(mSelectedItemId == ITEM_ID_BROWSER && lastPath == null)
            mSelectedItemId = BrowserCategoryVideo.ITEM_ID_RECENTLY_ADDED;

        final String initialPath = lastPath;
        view.post(() -> {
            if (!isAdded())
                return;
            if(savedInstanceState==null && !((MainActivity) requireActivity()).isPhoneHome()) // phone starts on its home page
                setFragment(initialPath);

            if(getActivity() instanceof BrowserActivity)
                ((MainActivity)getActivity()).updateHomeIcon(getParentFragmentManager().getBackStackEntryCount()>1);
        });

        // handles NetworkState changes
        networkState = NetworkState.instance(getContext());
        if (propertyChangeListener == null)
            propertyChangeListener = evt -> {
                if (evt.getOldValue() != evt.getNewValue()) {
                    if (DBG) Log.d(TAG, "onViewCreated: updateUI NetworkState for " + evt.getPropertyName() + " changed:" + evt.getOldValue() + " -> " + evt.getNewValue());
                    updateUI();
                }
            };
        updateUI(); // get initialization right

        // Register theme change listener to update category selector color
        mThemeChangeListener = (sharedPreferences, key) -> {
            if (ThemeManager.KEY_APP_THEME.equals(key)) {
                ListView listView = getListView();
                if (listView != null) {
                    android.graphics.drawable.StateListDrawable selector = new android.graphics.drawable.StateListDrawable();
                    int selectorColor = ThemeManager.getInstance(getContext()).getCategorySelectorColor();
                    // Activated state (selected item)
                    selector.addState(new int[]{android.R.attr.state_activated}, new android.graphics.drawable.ColorDrawable(selectorColor));
                    // Focused state (keyboard navigation)
                    selector.addState(new int[]{android.R.attr.state_focused}, new android.graphics.drawable.ColorDrawable(selectorColor));
                    // Default state (transparent)
                    selector.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    listView.setSelector(selector);
                }
                // Refresh adapter to update item backgrounds
                if (mCategoryAdapter != null) {
                    mCategoryAdapter.notifyDataSetChanged();
                }
            }
        };
        ThemeManager.getInstance(getContext()).registerThemeChangeListener(mThemeChangeListener);
    }

    /** Tile color per kind of source: storage blue, USB green, network purple, library slate. */
    private static int homeTileColor(ItemData item) {
        if (item.id == ITEM_ID_HOME_NETWORK) return 0xFF6A4C93;
        if (item.text == R.string.sd_card_storage || item.text == R.string.root_storage) return 0xFF2B5E8C;
        if (item.text == R.string.usb_host_storage) return 0xFF3C7A5B;
        return 0xFF34465F; // video library, other storage
    }

    // ---------------------------------------------------------------- phone home: tiles

    /** Shared folders, media servers, network shortcuts and cloud live behind the single "Network" tile. */
    private static boolean isNetworkEntry(ItemData d) {
        return d.text == R.string.network_shared_folders || d.text == R.string.network_media_servers
                || d.text == R.string.network_shortcuts || d.text == R.string.provider_folders;
    }

    /** Preferences, rescan, help and sponsor are in the action bar, not tiles. */
    private static boolean isActionBarEntry(ItemData d) {
        return d.text == R.string.preferences || d.text == R.string.rescan
                || d.text == R.string.help_faq || d.text == R.string.sponsor;
    }

    // ---- Network sources found on the network (SMB computers, DLNA/UPnP servers, our own DLNA host) ----
    protected static final int ITEM_ID_NET_SMB = 101;
    protected static final int ITEM_ID_NET_UPNP = 102;
    protected static final int ITEM_ID_NET_LOCAL_DLNA = 103;

    private com.archos.filecorelibrary.samba.SambaDiscovery mSambaDiscovery;
    private NetListeners mNetListeners;
    private final ArrayList<ItemData> mSmbSources = new ArrayList<>();
    private final ArrayList<ItemData> mUpnpSources = new ArrayList<>();
    private boolean mLocalDlnaShown;
    private String mLocalDlnaKey = "";

    private final class NetListeners implements com.archos.filecorelibrary.samba.SambaDiscovery.Listener,
            com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager.Listener {
        @Override public void onDiscoveryStart() { }
        @Override public void onDiscoveryEnd() { }
        @Override public void onDiscoveryFatalError() { }

        @Override
        public void onDiscoveryUpdate(List<com.archos.filecorelibrary.samba.Workgroup> workgroups) {
            final ArrayList<ItemData> found = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (com.archos.filecorelibrary.samba.Workgroup w : workgroups) {
                for (com.archos.filecorelibrary.samba.Share share : w.getShares()) {
                    if (!seen.add(share.toUri().toString().toLowerCase(Locale.ROOT))) continue;   // listed twice
                    ItemData d = new ItemData();
                    d.id = ITEM_ID_NET_SMB;
                    d.icon = R.drawable.category_common_network;
                    d.name = share.getDisplayName();
                    d.path = share.toUri().toString();
                    String host = Uri.parse(share.getAddress()).getHost();
                    d.details = getString(R.string.home_net_smb_details, host == null ? "" : host);
                    found.add(d);
                }
            }
            applyNetworkSources(mSmbSources, found);
        }

        @Override
        public void onDeviceListUpdate(List<org.jupnp.model.meta.Device> devices) {
            updateUpnpSources(devices);
        }
    }

    private void updateUpnpSources(List<org.jupnp.model.meta.Device> devices) {
        Context context = getContext();
        if (context == null) return;
        com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager manager =
                com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager.startServiceIfNeeded(context);
        String ownName = new com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs(context).getName();
        boolean ownRunning = com.archos.mediacenter.video.browser.dlna.DlnaServerService.isRunning();
        final ArrayList<ItemData> found = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (org.jupnp.model.meta.Device device : devices) {
            String name = manager.getDeviceFriendlyName(device);
            if (ownRunning && ownName.equals(name)) continue;      // our own server has its own card
            if (!seen.add(String.valueOf(manager.getDeviceUri(device)))) continue;
            ItemData d = new ItemData();
            d.id = ITEM_ID_NET_UPNP;
            d.icon = R.drawable.category_common_network;
            d.name = name;
            d.path = manager.getDeviceUri(device).toString();
            d.details = getString(R.string.home_sub_upnp);
            found.add(d);
        }
        applyNetworkSources(mUpnpSources, found);
    }

    /** Replaces a list of found sources and refreshes the grid; always on the main thread, one at a time. */
    private void applyNetworkSources(final ArrayList<ItemData> target, final ArrayList<ItemData> found) {
        mScanStatusHandler.post(() -> {
            target.clear();
            target.addAll(found);
            refreshHomeTilesNow();
        });
    }

    private Runnable mNetworkSourcesListener;

    /** The Network page listens here to follow the discovery while it is open. */
    public void setNetworkSourcesListener(Runnable listener) {
        mNetworkSourcesListener = listener;
    }

    /** SMB computers, media servers and our own DLNA host found on the network. */
    public ArrayList<ItemData> getDiscoveredNetworkSources() {
        ArrayList<ItemData> all = new ArrayList<>(mSmbSources);
        all.addAll(mUpnpSources);
        ItemData localDlna = localDlnaTile();
        if (localDlna != null) all.add(localDlna);
        return all;
    }

    public String getSourceTitle(ItemData item) { return itemTitle(item); }
    public CharSequence getSourceDetails(ItemData item) { return homeTileDetails(item); }
    public int getSourceColor(ItemData item) { return sourceCardColor(item); }

    /** Opens a discovered source from the Network page (Back returns to that page). */
    public void openDiscoveredSource(ItemData item) {
        if (item.id == ITEM_ID_NET_LOCAL_DLNA) {
            startContent(new com.archos.mediacenter.video.browser.dlna.DlnaServerFragment());
        } else if (item.path != null) {
            com.archos.mediacenter.video.browser.filebrowsing.network.NetworkHubFragment.openShare(this, Uri.parse(item.path), item.name, false);
        }
    }

    private void refreshHomeTilesNow() {
        if (mNetworkSourcesListener != null) mNetworkSourcesListener.run();
        rebuildHomeTiles();
        if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
    }

    /** Starts looking for SMB computers and DLNA servers (while the home page is visible). */
    private void startNetworkDiscovery() {
        Context context = getContext();
        if (context == null || getActivity() == null || !NetworkState.isNetworkConnected(context)) return;
        if (mNetListeners == null) mNetListeners = new NetListeners();
        try {
            if (mSambaDiscovery == null) {
                mSambaDiscovery = new com.archos.filecorelibrary.samba.SambaDiscovery(getActivity());
                mSambaDiscovery.setMinimumUpdatePeriodInMs(500);
                mSambaDiscovery.addListener(mNetListeners);
            }
            if (!mSambaDiscovery.isRunning()) mSambaDiscovery.start();
            com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager upnp =
                    com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager.startServiceIfNeeded(context);
            upnp.addListener(mNetListeners);
            upnp.start();
            if (upnp.getDevices() != null) updateUpnpSources(new ArrayList<>(upnp.getDevices()));
        } catch (RuntimeException e) {
            // discovery is a bonus: never break the home page because of it
        }
    }

    private void stopNetworkDiscovery() {
        try {
            if (mSambaDiscovery != null) mSambaDiscovery.abort();
            Context context = getContext();
            if (context != null && mNetListeners != null) {
                com.archos.mediacenter.filecoreextension.upnp2.UpnpServiceManager
                        .startServiceIfNeeded(context).removeListener(mNetListeners);
            }
        } catch (RuntimeException ignored) {
            // already stopped
        }
    }

    /** The card of our own DLNA host, shown while it runs. */
    private ItemData localDlnaTile() {
        Context context = getContext();
        if (context == null || !com.archos.mediacenter.video.browser.dlna.DlnaServerService.isRunning()) return null;
        com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs prefs = new com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs(context);
        ItemData d = new ItemData();
        d.id = ITEM_ID_NET_LOCAL_DLNA;
        d.icon = R.drawable.ic_dlna_server;
        d.name = prefs.getName();
        d.path = "dlna-host";
        d.details = getString(R.string.home_dlna_hosted, prefs.getFolders().size());
        return d;
    }

    /** Called every second: the DLNA host card follows the server being started or stopped. */
    private void refreshLocalDlnaTile() {
        ItemData tile = localDlnaTile();
        String key = tile == null ? "" : tile.name + "|" + tile.details;
        if (!key.equals(mLocalDlnaKey)) {
            mLocalDlnaKey = key;
            refreshHomeTilesNow();
        }
    }

    private void openNetworkSource(ItemData item) {
        if (!(getActivity() instanceof MainActivity)) return;
        if (item.id == ITEM_ID_NET_LOCAL_DLNA) {
            loadFragmentAfterStackReset(new com.archos.mediacenter.video.browser.dlna.DlnaServerFragment());
        } else {
            com.archos.mediacenter.video.browser.filebrowsing.network.NetworkHubFragment.openShare(this, Uri.parse(item.path), item.name, true);
        }
    }

    private String itemTitle(ItemData item) {
        return item.name != null ? item.name : getString(item.text);
    }

    // ---- Select Source cards: colour, details line and "last opened" bookmark ---------------------
    private static final int[] SOURCE_CARD_PALETTE = {
            0xFF2F7D6B, 0xFF8A4B5C, 0xFF4A6A2F, 0xFF4B5563, 0xFF85602A, 0xFF35618A};

    private int sourceCardColor(ItemData item) {
        // Every source has its own colour (the single "card colour" setting no longer applies here).
        if (item.id == ITEM_ID_NET_SMB) return 0xFF8A4B5C;
        if (item.id == ITEM_ID_NET_UPNP) return 0xFF2F7D6B;
        if (item.id == ITEM_ID_NET_LOCAL_DLNA) return 0xFF7A4FA0;
        if (item.text == R.string.dlna_now_title) return 0xFFB5451B;
        if (item.text == R.string.dlna_server_title) return 0xFF6B4C93;
        if (item.id == ITEM_ID_HOME_NETWORK) return 0xFF6B4C93;
        if (item.id == ITEM_ID_VIDEO_FOLDER) return 0xFF2B5F8F;
        if (item.id == ITEM_ID_BROWSER) return 0xFF7A5C2E;
        if (item.text == R.string.files_host_dlna) return 0xFF6B4C93;
        if (item.id <= ITEM_ID_FILES_SMB && item.id >= ITEM_ID_FILES_DLNA_HOST) {   // Files page network entries
            if (item.text == R.string.network_media_servers) return 0xFF2F7D6B;
            if (item.text == R.string.network_shared_folders) return 0xFF8A4B5C;
            if (item.text == R.string.network_shortcuts) return 0xFF4A6A2F;
            return 0xFF4B5563;
        }
        if (item.id < 0) return 0xFF3A4660;
        if (item.text == R.string.network_media_servers) return 0xFF2F7D6B;
        if (item.text == R.string.network_shared_folders) return 0xFF8A4B5C;
        if (item.text == R.string.network_shortcuts) return 0xFF4A6A2F;
        if (item.text == R.string.provider_folders) return 0xFF4B5563;
        return SOURCE_CARD_PALETTE[Math.abs(item.id) % SOURCE_CARD_PALETTE.length];
    }

    private String storageDetails(String path) {
        try {
            android.os.StatFs stat = new android.os.StatFs(path);
            double free = stat.getAvailableBytes() / 1e9;
            long total = Math.round(stat.getTotalBytes() / 1e9);
            return getString(R.string.home_storage_free_of_total,
                    String.format(Locale.getDefault(), "%.1fGB", free),
                    String.format(Locale.getDefault(), "%dGB", total));
        } catch (Exception e) {
            return path;
        }
    }

    /** The grey details line under the source name (empty: none). */
    private CharSequence homeTileDetails(ItemData item) {
        if (item.details != null) return item.details;
        if (item.id == ITEM_ID_VIDEO_FOLDER) {
            return storageDetails(android.os.Environment.getExternalStorageDirectory().getPath());
        }
        if (item.id == ITEM_ID_BROWSER && item.path != null) {
            java.io.File dir = new java.io.File(item.path);
            return dir.exists() ? storageDetails(item.path) : item.path;
        }
        if (item.id == ITEM_ID_HOME_NETWORK) return getString(R.string.home_network_summary);
        if (item.text == R.string.network_shared_folders) return getString(R.string.home_sub_smb);
        if (item.text == R.string.network_media_servers) return getString(R.string.home_sub_upnp);
        if (item.text == R.string.network_shortcuts) return getString(R.string.home_sub_shortcuts);
        if (item.text == R.string.provider_folders) return getString(R.string.home_sub_provider);
        if (item.path != null) return item.path;
        return "";
    }

    /** Bookmark on the source that was opened last. */
    private boolean isLastOpenedSource(ItemData item) {
        if (item.id < 0 || item.id == ITEM_ID_HOME_NETWORK || item.name != null) return false;
        if (mPreferences.getInt(PREFERENCE_LAST_FRAGMENT, Integer.MIN_VALUE) != item.id) return false;
        String lastPath = mPreferences.getString(PREFERENCE_LAST_PATH, null);
        return item.path == null || item.path.equals(lastPath);
    }

    // ---- Re-scan popup and scan status next to "Select Source" ------------------------------------
    public static final String PREF_FETCH_META_ON_STARTUP = "phone_fetch_meta_missing_on_startup";
    private static final long STATUS_FORCE_MS = 4000;

    private android.widget.ProgressBar mSourcesSpinner;
    private final android.os.Handler mScanStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long mStatusForcedUntil;
    private int mStatusForcedText;

    private final Runnable mScanStatusRunnable = new Runnable() {
        @Override public void run() {
            updateScanStatus();
            refreshLocalDlnaTile();
            refreshHomeRowsWhileScanning();
            mScanStatusHandler.postDelayed(this, 1000);
        }
    };

    private static final long HOME_ROWS_SCAN_REFRESH_MS = 3000;
    private long mLastHomeRowsRefresh;
    private boolean mWasScanningHome;

    /**
     * New files must show up in Recently added while a scan / metadata fetch is running, and once more when
     * it ends, without waiting for the database change notification (which can be missed or batched
     * during the startup import): re-query the home rows every few seconds while busy.
     */
    private void refreshHomeRowsWhileScanning() {
        boolean scanning = NetworkScannerReceiver.isScannerWorking()
                || ImportState.VIDEO.isInitialImport() || ImportState.VIDEO.isRegularImport()
                || LoaderUtils.getScrapeInProgress() || AutoScrapeService.getNumberOfFilesRemainingToProcess() > 0;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean finished = mWasScanningHome && !scanning;
        mWasScanningHome = scanning;
        if (!isAdded() || !(finished || (scanning && now - mLastHomeRowsRefresh >= HOME_ROWS_SCAN_REFRESH_MS))) return;
        mLastHomeRowsRefresh = now;
        LoaderManager lm = LoaderManager.getInstance(this);
        for (int id : new int[]{LOADER_HOME_RECENT, LOADER_HOME_ADDED, LOADER_HOME_MOVIES, LOADER_HOME_SERIES, LOADER_HOME_ALL}) {
            Loader<Cursor> loader = lm.getLoader(id);
            if (loader != null) loader.forceLoad();
        }
    }

    /** Shows the spinner and text right away (the real scanner/scraper state takes over after a few seconds). */
    private void forceScanStatus(int textRes) {
        mStatusForcedUntil = android.os.SystemClock.elapsedRealtime() + STATUS_FORCE_MS;
        mStatusForcedText = textRes;
        updateScanStatus();
    }

    private void updateScanStatus() {
        int textRes = 0;
        String count = "";
        if (NetworkScannerReceiver.isScannerWorking()) {
            textRes = R.string.home_status_scanning;
            int found = com.archos.mediaprovider.video.NetworkScannerServiceVideo.isDeleting()
                    ? com.archos.mediaprovider.video.NetworkScannerServiceVideo.getRemainingDeletesCount()
                    : com.archos.mediaprovider.video.NetworkScannerServiceVideo.getFilesFoundCount();
            if (found > 0) count = getString(com.archos.mediaprovider.video.NetworkScannerServiceVideo.isDeleting()
                    ? R.string.home_status_count_left : R.string.home_status_count_found, found);
        } else if (ImportState.VIDEO.isInitialImport() || ImportState.VIDEO.isRegularImport()) {
            textRes = R.string.home_status_scanning;
            int left = ImportState.VIDEO.isDeleting() ? ImportState.VIDEO.getNumberOfFilesRemainingToDelete()
                    : ImportState.VIDEO.getNumberOfFilesRemainingToImport();
            if (left > 0) count = getString(R.string.home_status_count_left, left);
        } else if (LoaderUtils.getScrapeInProgress() || AutoScrapeService.getNumberOfFilesRemainingToProcess() > 0) {
            textRes = R.string.home_status_fetching_meta;
            int left = AutoScrapeService.getNumberOfFilesRemainingToProcess();
            if (left > 0) count = getString(R.string.home_status_count_left, left);
        } else if (android.os.SystemClock.elapsedRealtime() < mStatusForcedUntil) {
            textRes = mStatusForcedText;
        }
        // the status now lives in a pill at the bottom centre of the home, not next to "Select Source"
        if (mSourcesStatus != null) mSourcesStatus.setText("");
        if (mSourcesSpinner != null) mSourcesSpinner.setVisibility(View.GONE);
        View root = getView();
        View pill = root == null ? null : root.findViewById(R.id.home_scan_pill);
        TextView pillText = root == null ? null : root.findViewById(R.id.home_scan_pill_text);
        if (pill == null || pillText == null) return;
        if (textRes != 0) {
            pillText.setText(count.isEmpty() ? getString(textRes) : getString(textRes) + " " + count);
            pill.setVisibility(View.VISIBLE);
        } else {
            pill.setVisibility(View.GONE);
        }
    }

    private void startMetaFetch(boolean onlyMissing) {
        Context context = getContext();
        if (context == null) return;
        if (!AutoScrapeService.isEnable(context)) {
            Toast.makeText(context, R.string.rescan_meta_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(AutoScrapeService.RESCAN_EVERYTHING, null, context, AutoScrapeService.class);
        intent.putExtra(AutoScrapeService.RESCAN_EVERYTHING, true);
        intent.putExtra(AutoScrapeService.RESCAN_ONLY_DESC_NOT_FOUND, onlyMissing);
        context.startService(intent);
        forceScanStatus(R.string.home_status_fetching_meta);
    }

    private void clearAllMeta() {
        final Context context = getContext() != null ? getContext().getApplicationContext() : null;
        if (context == null) return;
        new Thread(() -> {
            android.content.ContentValues values = new android.content.ContentValues(2);
            // Same reset the manual "reject info" action uses: also removes the scraper database data.
            values.put(VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_ID, String.valueOf(-1));
            values.put(VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE, String.valueOf(-1));
            context.getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, values, null, null);
            mScanStatusHandler.post(() -> Toast.makeText(context, R.string.rescan_clear_meta_done, Toast.LENGTH_SHORT).show());
        }).start();
    }

    /** The popup of the home "Rescan" button. */
    public void showRescanPopup() {
        final Context context = getContext();
        if (context == null || getActivity() == null) return;
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = Math.round(20 * density);

        final android.widget.LinearLayout content = new android.widget.LinearLayout(context);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(pad, Math.round(8 * density), pad, Math.round(8 * density));

        final android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        int[] actionTitles = {R.string.rescan_option_rescan, R.string.rescan_option_fetch_missing,
                R.string.rescan_option_fetch_all, R.string.rescan_option_clear_meta};
        for (final int title : actionTitles) {
            TextView row = new TextView(context);
            row.setText(title);
            row.setTextSize(16);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinHeight(Math.round(48 * density));
            row.setClickable(true);
            row.setFocusable(true);
            android.util.TypedValue background = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
            row.setBackgroundResource(background.resourceId);
            row.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                if (title == R.string.rescan_option_rescan) {
                    NetworkAutoRefresh.forceRescan(getActivity());
                    forceScanStatus(R.string.home_status_scanning);
                } else if (title == R.string.rescan_option_fetch_missing) {
                    startMetaFetch(true);
                } else if (title == R.string.rescan_option_fetch_all) {
                    startMetaFetch(false);
                } else {
                    new android.app.AlertDialog.Builder(getActivity())
                            .setMessage(R.string.rescan_clear_meta_confirm)
                            .setPositiveButton(android.R.string.ok, (d, w) -> clearAllMeta())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            });
            content.addView(row);
        }

        View divider = new View(context);
        divider.setBackgroundColor(0x33888888);
        content.addView(divider, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(1 * density)));

        final android.widget.CheckBox rescanOnStart = new android.widget.CheckBox(context);
        rescanOnStart.setText(R.string.rescan_option_on_startup_rescan);
        rescanOnStart.setChecked(NetworkAutoRefresh.autoRescanAtStart(context));
        rescanOnStart.setOnCheckedChangeListener((b, checked) -> NetworkAutoRefresh.setAutoRescanAtStart(context, checked));
        content.addView(rescanOnStart);

        final android.widget.CheckBox fetchOnStart = new android.widget.CheckBox(context);
        fetchOnStart.setText(R.string.rescan_option_on_startup_fetch);
        fetchOnStart.setChecked(mPreferences.getBoolean(PREF_FETCH_META_ON_STARTUP, false));
        fetchOnStart.setOnCheckedChangeListener((b, checked) ->
                mPreferences.edit().putBoolean(PREF_FETCH_META_ON_STARTUP, checked).apply());
        content.addView(fetchOnStart);

        dialog[0] = new android.app.AlertDialog.Builder(getActivity())
                .setTitle(R.string.rescan_popup_title)
                .setView(content)
                .create();
        dialog[0].show();
    }

    private void rebuildHomeTiles() {
        mHomeTiles.clear();
        if (mCategoryList == null) return;
        for (Object o : mCategoryList) {
            if (!(o instanceof ItemData)) continue;
            ItemData d = (ItemData) o;
            if (isNetworkEntry(d) || isActionBarEntry(d)) continue;   // network entries live on the Network page
            mHomeTiles.add(d);
        }
        // Sources found on the network, and our own DLNA host while it runs.
        mHomeTiles.addAll(mSmbSources);
        mHomeTiles.addAll(mUpnpSources);
        ItemData localDlna = localDlnaTile();
        if (localDlna != null) mHomeTiles.add(localDlna);
        ItemData network = new ItemData();
        network.icon = R.drawable.category_common_network;
        network.text = R.string.home_network;
        network.id = ITEM_ID_HOME_NETWORK;
        mHomeTiles.add(network);
        mAvailableHomeTiles.clear();
        mAvailableHomeTiles.addAll(mHomeTiles);
        // Discovered sources (SMB computers, media servers, our DLNA host) live on the Network page,
        // under "Available sources", not in this row.
        mHomeTiles.removeIf(item -> item.id == ITEM_ID_NET_SMB || item.id == ITEM_ID_NET_UPNP
                || item.id == ITEM_ID_NET_LOCAL_DLNA);
        java.util.Set<String> hidden = mPreferences.getStringSet(HIDDEN_HOME_SOURCES, java.util.Collections.emptySet());
        mHomeTiles.removeIf(item -> hidden.contains(sourceKey(item)));
        // Recently played / added, Movies and TV shows are home rows; Settings can also add them as cards here.
        java.util.Set<String> extras = mPreferences.getStringSet(SHOWN_EXTRA_CARDS, java.util.Collections.emptySet());
        mHomeTiles.removeIf(item -> isExtraCard(item) && !extras.contains(sourceKey(item)));
        // Permanent utility cards are added after source visibility filtering.
        int[] actionIds = {ITEM_ID_HOME_SETTINGS, ITEM_ID_HOME_PREFERENCES, ITEM_ID_HOME_RESCAN};
        int[] actionTitles = {R.string.phone_home_settings, R.string.preferences, R.string.rescan};
        int[] actionIcons = {R.drawable.ic_home_palette, R.drawable.android29_ic_settings, R.drawable.android29_ic_rescan};
        for (int i = 0; i < actionIds.length; i++) {
            ItemData action = new ItemData();
            action.id = actionIds[i];
            action.text = actionTitles[i];
            action.icon = actionIcons[i];
            if (action.id == ITEM_ID_HOME_SETTINGS || !hidden.contains(sourceKey(action))) mHomeTiles.add(action);
            if (action.id != ITEM_ID_HOME_SETTINGS) mAvailableHomeTiles.add(action); // Home settings stays: it is the way back here
        }
        if (com.archos.mediacenter.video.browser.dlna.DlnaCastSession.isActive()) {
            // "DLNA Now": first tile (left) of the Select Source row, opens the casting page
            ItemData now = new ItemData();
            now.id = ITEM_ID_HOME_DLNA_NOW;
            now.text = R.string.dlna_now_title;
            now.icon = R.drawable.ic_dlna_server;
            now.details = com.archos.mediacenter.video.browser.dlna.DlnaCastSession.getTitle();
            mHomeTiles.add(0, now);
        }
        rebuildNetworkRow();
        rebuildSectionTiles();
        applyBottomPill();
        updateScanStatus();   // no tile count: the area next to "Select Source" shows scan status only
    }

    private int dpi(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Bottom pill: Home, Library, Files, Network, Settings, Re-scan ----
    private static final int SECTION_HOME = 0, SECTION_LIBRARY = 1, SECTION_FILES = 2;
    private int mHomeSection = SECTION_HOME;
    private final ArrayList<ItemData> mSectionTiles = new ArrayList<>();

    private ArrayList<ItemData> currentHomeTiles() {
        return mHomeSection == SECTION_HOME ? mHomeTiles : mSectionTiles;
    }

    /** Library: Movies, TV shows, Recently added, Played, Playlists, Non scraped. Files: internal and other storages. */
    private void rebuildSectionTiles() {
        mSectionTiles.clear();
        if (mHomeSection == SECTION_LIBRARY) {
            int[] order = {1, 2, 3, 4, 5, 7, 6};   // ITEM_ID_OFFSET + n, see BrowserCategoryVideo
            for (int n : order) {
                for (ItemData t : mAvailableHomeTiles) {
                    if (t.id == ITEM_ID_OFFSET + n) { mSectionTiles.add(t); break; }
                }
            }
        } else if (mHomeSection == SECTION_FILES) {
            // every source available now, as in the Home "Sources" row ...
            mSectionTiles.addAll(mNetworkRowItems);
            // ... then a divider with "Network Sources" and the fixed network entries
            ItemData header = new ItemData();
            header.id = ITEM_ID_FILES_HEADER;
            header.text = R.string.files_network_sources;
            mSectionTiles.add(header);
            mSectionTiles.add(filesEntry(ITEM_ID_FILES_DLNA_HOST, R.string.files_host_dlna, R.drawable.ic_dlna_server, dlnaHostDetails()));
            mSectionTiles.add(filesEntry(ITEM_ID_FILES_SMB, R.string.network_shared_folders, R.drawable.category_common_network, null));
            mSectionTiles.add(filesEntry(ITEM_ID_FILES_UPNP, R.string.network_media_servers, R.drawable.category_common_network, null));
            mSectionTiles.add(filesEntry(ITEM_ID_FILES_SHORTCUT, R.string.network_shortcuts, R.drawable.category_network_shortcut, null));
            mSectionTiles.add(filesEntry(ITEM_ID_FILES_CLOUD, R.string.provider_folders, R.drawable.cloud_24px, null));
        }
    }

    /** Divider line with the "Network Sources" label. */
    private View createFilesHeader(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setPadding(0, Math.round(10 * d), Math.round(10 * d), 0);
        View line = new View(ctx);
        line.setBackgroundColor(0x33FFFFFF);
        box.addView(line, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d))));
        TextView label = new TextView(ctx);
        label.setTag("files_header_text");
        label.setTextColor(0xFF9FB3D1);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, Math.round(10 * d), 0, Math.round(4 * d));
        box.addView(label);
        return box;
    }

    private ItemData filesEntry(int id, int text, int icon, CharSequence details) {
        ItemData d = new ItemData();
        d.id = id;
        d.text = text;
        d.icon = icon;
        d.details = details == null ? null : details.toString();
        return d;
    }

    private CharSequence dlnaHostDetails() {
        Context c = getContext();
        if (c == null) return null;
        if (com.archos.mediacenter.video.browser.dlna.DlnaServerService.isRunning()) return getString(R.string.dlna_card_running);
        int folders = new com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs(c).getFolders().size();
        return folders == 0 ? getString(R.string.dlna_card_stopped)
                : getResources().getQuantityString(R.plurals.dlna_card_folders, folders, folders);
    }

    /** Opens one of the fixed entries of the Files page; false when the item is not one of them. */
    private boolean openFilesEntry(ItemData item) {
        int source;
        if (item.id == ITEM_ID_FILES_DLNA_HOST) source = 0;
        else if (item.id == ITEM_ID_FILES_SMB) source = R.string.network_shared_folders;
        else if (item.id == ITEM_ID_FILES_UPNP) source = R.string.network_media_servers;
        else if (item.id == ITEM_ID_FILES_SHORTCUT) source = R.string.network_shortcuts;
        else if (item.id == ITEM_ID_FILES_CLOUD) source = R.string.provider_folders;
        else return false;
        if (getActivity() != null) {
            int title = item.id == ITEM_ID_FILES_DLNA_HOST ? R.string.dlna_server_title : item.text;
            com.archos.mediacenter.video.browser.filebrowsing.network.NetworkHubFragment.openEntry(getActivity(), title, source);
        }
        return true;
    }

    // ---- Library page: tapping a library card shows all its videos in a row above the cards ----
    private int mLibraryRow = 0;        // loader id of the chosen library, 0 = none
    private int mLibraryRowView = 0;    // its row view
    private boolean mAllNonScraped = false;
    public static final String LIBRARY_LAST = "classic_home_library_last";
    private int mLibraryGrid = -1;          // -1 none, 0 movies grid, 1 TV shows grid (filters + grid above the cards)
    private View mLibraryGridHost = null;

    /** Movies / TV shows: filter chips and the poster grid, shown above the Library cards. */
    private void showLibraryGrid(boolean tv) {
        View root = getView();
        if (root == null) return;
        int want = tv ? 1 : 0;
        if (mLibraryGrid == want && mLibraryGridHost != null) return;
        resetLibraryRow(root);   // drops any other chosen library or grid
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.home_classic);
        if (scroll == null) return;
        ViewGroup box = (ViewGroup) scroll.getChildAt(0);
        android.widget.FrameLayout host = new android.widget.FrameLayout(requireContext());
        host.setId(View.generateViewId());
        View sources = root.findViewById(R.id.home_sources_row);
        int at = sources != null && sources.getParent() == box ? box.indexOfChild(sources) : 0;
        box.addView(host, at, new ViewGroup.LayoutParams(-1, -2));
        PosterGridFragment f = new PosterGridFragment();
        Bundle args = new Bundle(2);
        args.putBoolean(PosterGridFragment.ARG_TV, tv);
        args.putBoolean(PosterGridFragment.ARG_EMBED, true);
        f.setArguments(args);
        getChildFragmentManager().beginTransaction().replace(host.getId(), f, "library_grid").commitAllowingStateLoss();
        mLibraryGrid = want;
        mLibraryGridHost = host;
        applyHomeRowVisibility(root);
        scroll.smoothScrollTo(0, 0);
    }

    /** All videos / Recently added / Played: list (thumbnail, title, size - resolution - codec, page line) above the Library cards. */
    private void showLibraryList(int mode) {
        View root = getView();
        if (root == null) return;
        int want = 10 + mode;
        if (mLibraryGrid == want && mLibraryGridHost != null) return;
        resetLibraryRow(root);
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.home_classic);
        if (scroll == null) return;
        ViewGroup box = (ViewGroup) scroll.getChildAt(0);
        android.widget.FrameLayout host = new android.widget.FrameLayout(requireContext());
        host.setId(View.generateViewId());
        View sources = root.findViewById(R.id.home_sources_row);
        int at = sources != null && sources.getParent() == box ? box.indexOfChild(sources) : 0;
        box.addView(host, at, new ViewGroup.LayoutParams(-1, -2));
        VideoListFragment f = new VideoListFragment();
        Bundle args = new Bundle(1);
        args.putInt(VideoListFragment.ARG_MODE, mode);
        f.setArguments(args);
        getChildFragmentManager().beginTransaction().replace(host.getId(), f, "library_grid").commitAllowingStateLoss();
        mLibraryGrid = want;
        mLibraryGridHost = host;
        applyHomeRowVisibility(root);
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onDestroyView() {
        mLibraryGridHost = null;   // the view tree (and the child fragment) go away with it
        mHomeGridHost = null;
        mLibraryGrid = -1;
        super.onDestroyView();
    }

    private void removeLibraryGrid(View root) {
        if (mLibraryGridHost == null) { mLibraryGrid = -1; return; }
        Fragment f = getChildFragmentManager().findFragmentByTag("library_grid");
        if (f != null) getChildFragmentManager().beginTransaction().remove(f).commitAllowingStateLoss();
        ViewGroup parent = (ViewGroup) mLibraryGridHost.getParent();
        if (parent != null) parent.removeView(mLibraryGridHost);
        mLibraryGridHost = null;
        mLibraryGrid = -1;
    }

    /** Movies / TV shows poster grid page (3 columns, filters, search, no action bar). */
    private void openPosterGrid(boolean tv) {
        if (!(getActivity() instanceof MainActivity)) return;
        PosterGridFragment f = new PosterGridFragment();
        Bundle args = new Bundle(1);
        args.putBoolean(PosterGridFragment.ARG_TV, tv);
        f.setArguments(args);
        MainActivity activity = (MainActivity) getActivity();
        activity.hideSeachView();
        activity.setNavigationMode(androidx.appcompat.app.ActionBar.NAVIGATION_MODE_STANDARD);
        startContent(f);
    }

    void openPosterFromGrid(HomePosterAdapter.Poster p) {
        if (p.item instanceof Tvshow) openHomeShow(p);
        else onHomeArtworkTap(p);
    }

    boolean longPosterFromGrid(HomePosterAdapter.Poster p, View anchor) {
        return onHomePosterLongClick(p, anchor);
    }

    private void onSourceCardClick(ItemData item, View v) {
        if (mHomeSection != SECTION_LIBRARY) {
            onHomeTileClick(item, v);
            return;
        }
        int n = item.id - ITEM_ID_OFFSET;
        if (n >= 1 && n <= 6) mPreferences.edit().putInt(LIBRARY_LAST, n).apply();   // Library opens on this next time
        if ((n == 1 || n == 2) && mPreferences.getBoolean(HOME_POSTER_PAGE, true)) {
            showLibraryGrid(n == 2);   // Movies / TV shows: filters + poster grid above the cards
            return;
        }
        if (n == 3 || n == 4 || n == 5) {   // All videos / Recently added / Played
            showLibraryList(n == 3 ? VideoListFragment.MODE_ALL : n == 4 ? VideoListFragment.MODE_ADDED : VideoListFragment.MODE_PLAYED);
            return;
        }
        if (mLibraryGrid != -1 && getView() != null) removeLibraryGrid(getView());
        int loader, rowView;
        switch (n) {
            case 1: loader = LOADER_HOME_MOVIES; rowView = R.id.home_movies_row; break;
            case 2: loader = LOADER_HOME_SERIES; rowView = R.id.home_series_row; break;
            case 3: loader = LOADER_HOME_ALL; rowView = R.id.home_all_row; break;
            case 4: loader = LOADER_HOME_ADDED; rowView = R.id.home_added_row; break;
            case 5: loader = LOADER_HOME_RECENT; rowView = R.id.home_recent_row; break;
            case 6: loader = LOADER_HOME_ALL; rowView = R.id.home_all_row; break;
            default: onHomeTileClick(item, v); return;   // Playlists are lists, not videos: open their page
        }
        View root = getView();
        if (root == null) return;
        if (mLibraryRow == loader && !(loader == LOADER_HOME_ALL && mAllNonScraped != (n == 6))) return;
        int previous = mLibraryRow;
        mLibraryRow = loader;
        mLibraryRowView = rowView;
        LoaderManager lm = LoaderManager.getInstance(this);
        if (previous == LOADER_HOME_ALL || n == 3 || n == 6) {
            mAllNonScraped = n == 6;
            View all = root.findViewById(R.id.home_all_row);
            if (all != null) ((TextView) all.findViewById(R.id.home_row_title)).setText(mAllNonScraped ? R.string.non_scraped_videos : R.string.all_videos);
            lm.restartLoader(LOADER_HOME_ALL, null, mHomeRowCallbacks);
        }
        if (loader != LOADER_HOME_ALL) lm.restartLoader(loader, null, mHomeRowCallbacks);
        if (previous != 0 && previous != LOADER_HOME_ALL) lm.restartLoader(previous, null, mHomeRowCallbacks);   // back to the short home row
        View sources = root.findViewById(R.id.home_sources_row);
        View row = root.findViewById(rowView);
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.home_classic);
        if (row != null && scroll != null) {
            ViewGroup box = (ViewGroup) scroll.getChildAt(0);
            box.removeView(row);
            int at = sources != null && sources.getParent() == box ? box.indexOfChild(sources) : 0;   // above the Library cards
            box.addView(row, at);
        }
        applyHomeRowVisibility(root);
        if (scroll != null) scroll.smoothScrollTo(0, 0);
    }

    /** Leaves the chosen library: back to the short home rows and the original row order. */
    private void resetLibraryRow(View root) {
        removeLibraryGrid(root);
        int previous = mLibraryRow;
        boolean wasNonScraped = mAllNonScraped;
        mLibraryRow = 0;
        mLibraryRowView = 0;
        mAllNonScraped = false;
        if (previous != 0) {
            LoaderManager lm = LoaderManager.getInstance(this);
            if (wasNonScraped) {
                View all = root.findViewById(R.id.home_all_row);
                if (all != null) ((TextView) all.findViewById(R.id.home_row_title)).setText(R.string.all_videos);
                lm.restartLoader(LOADER_HOME_ALL, null, mHomeRowCallbacks);
            } else if (previous != LOADER_HOME_ALL) {
                lm.restartLoader(previous, null, mHomeRowCallbacks);
            }
        }
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.home_classic);
        if (scroll == null) return;
        applyHomeSourcePin(root);   // Select Source row back in place
        ViewGroup box = (ViewGroup) scroll.getChildAt(0);
        View listView = box.findViewById(android.R.id.list);   // hidden ListView required by ListFragment: the rows follow it
        int base = listView != null ? box.indexOfChild(listView) + 1 : 0;
        int[] order = {R.id.home_network_row, R.id.home_recent_row, R.id.home_added_row, R.id.home_movies_row, R.id.home_series_row, R.id.home_all_row};   // Sources first, right below the action bar
        for (int i = 0; i < order.length; i++) {
            View r = root.findViewById(order[i]);
            if (r == null) continue;
            box.removeView(r);
            box.addView(r, Math.min(base + i, box.getChildCount()));
        }
    }

    /** Shows the home page itself, or the same page with only the Library / Files cards (as a grid). */
    private void showHomeSection(int section) {
        mHomeSection = section;
        rebuildSectionTiles();
        View root = getView();
        if (root == null) return;
        resetLibraryRow(root);
        View sourcesRow = root.findViewById(R.id.home_sources_row);
        if (sourcesRow != null) {
            ((TextView) sourcesRow.findViewById(R.id.home_row_title)).setText("");   // no header above the sources
            RecyclerView list = sourcesRow.findViewById(R.id.home_row_list);
            if (section != SECTION_FILES) {   // Home / Library: horizontal scrolling row; Files keeps its wrapping grid
                list.setLayoutManager(new SensitiveLinearLayoutManager(requireContext()));
            } else {
                int cell = getResources().getDimensionPixelSize(R.dimen.phone_home_card_width) + dpi(10);
                int span = Math.max(2, (getResources().getDisplayMetrics().widthPixels - dpi(24)) / cell);
                androidx.recyclerview.widget.GridLayoutManager glm = new androidx.recyclerview.widget.GridLayoutManager(requireContext(), span);
                glm.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    @Override public int getSpanSize(int position) {   // the "Network Sources" divider takes the whole width
                        ArrayList<ItemData> tiles = currentHomeTiles();
                        return position < tiles.size() && tiles.get(position).id == ITEM_ID_FILES_HEADER ? span : 1;
                    }
                });
                list.setLayoutManager(glm);
            }
        }
        if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
        if (sourcesRow != null) applySourcesRowSpacing(sourcesRow, isSourcesAtBottom());
        applyHomeRowVisibility(root);
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.home_classic);
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        applyBottomPill();
        if (section == SECTION_LIBRARY) {
            // tapping Library opens the last selected source page (Movies the first time)
            int last = mPreferences.getInt(LIBRARY_LAST, 1);
            for (ItemData t : mSectionTiles) {
                if (t.id == ITEM_ID_OFFSET + last) { onSourceCardClick(t, null); break; }
            }
        }
    }

    /** The home bottom pill (Home, Library, Files, Settings) was removed. */
    void applyBottomPill() {
        // intentionally empty: kept so existing callers keep compiling
    }

    private static boolean isExtraCard(ItemData item) {
        return item.id == ITEM_ID_OFFSET + 1 || item.id == ITEM_ID_OFFSET + 2
                || item.id == ITEM_ID_OFFSET + 4 || item.id == ITEM_ID_OFFSET + 5;
    }

    private boolean isSourceShown(ItemData item) {
        String key = sourceKey(item);
        if (isExtraCard(item)) {
            return mPreferences.getStringSet(SHOWN_EXTRA_CARDS, java.util.Collections.emptySet()).contains(key);
        }
        return !mPreferences.getStringSet(HIDDEN_HOME_SOURCES, java.util.Collections.emptySet()).contains(key);
    }

    private void setSourceShown(ItemData item, boolean shown) {
        String key = sourceKey(item);
        boolean extra = isExtraCard(item);
        String pref = extra ? SHOWN_EXTRA_CARDS : HIDDEN_HOME_SOURCES;
        java.util.Set<String> set = new java.util.HashSet<>(mPreferences.getStringSet(pref, java.util.Collections.emptySet()));
        if (shown == extra) set.add(key); else set.remove(key);   // extras: listed when shown; others: listed when hidden
        mPreferences.edit().putStringSet(pref, set).apply();
    }

    /** Cards of the Home "Sources" row: DLNA Now while casting, then the sources available now, latest first: network sources, external storage, internal storage. */
    private void rebuildNetworkRow() {
        mNetworkRowItems.clear();
        if (com.archos.mediacenter.video.browser.dlna.DlnaCastSession.isActive()) {
            ItemData now = new ItemData();
            now.id = ITEM_ID_HOME_DLNA_NOW;
            now.text = R.string.dlna_now_title;
            now.icon = R.drawable.ic_dlna_server;
            now.details = com.archos.mediacenter.video.browser.dlna.DlnaCastSession.getTitle();
            mNetworkRowItems.add(now);
        }
        // internal and external storage, then the network sources, listed newest first
        ArrayList<ItemData> sources = new ArrayList<>();
        for (ItemData t : mAvailableHomeTiles) {
            if (t.id == ITEM_ID_VIDEO_FOLDER || t.id == ITEM_ID_BROWSER) sources.add(t);
        }
        sources.addAll(getDiscoveredNetworkSources());
        java.util.Collections.reverse(sources);
        mNetworkRowItems.addAll(sources);
        // Home "Sources" row: DLNA Now, then the library cards, the Network card, then the storages and network sources
        mHomeSourceCards.clear();
        ItemData dlnaNow = null;
        for (ItemData t : mNetworkRowItems) {
            if (t.id == ITEM_ID_HOME_DLNA_NOW) { dlnaNow = t; mHomeSourceCards.add(t); break; }
        }
        // the sources that are available come first: internal storage, external storage, DLNA, other network sources
        ArrayList<ItemData> internalStorage = new ArrayList<>();
        ArrayList<ItemData> externalStorage = new ArrayList<>();
        ArrayList<ItemData> dlnaSources = new ArrayList<>();
        ArrayList<ItemData> otherNetwork = new ArrayList<>();
        for (ItemData t : mAvailableHomeTiles) {
            if (t.id == ITEM_ID_VIDEO_FOLDER) internalStorage.add(t);
            else if (t.id == ITEM_ID_BROWSER) externalStorage.add(t);
        }
        for (ItemData t : getDiscoveredNetworkSources()) {
            if (t.id == ITEM_ID_NET_UPNP || t.id == ITEM_ID_NET_LOCAL_DLNA) dlnaSources.add(t);
            else otherNetwork.add(t);
        }
        mHomeSourceCards.addAll(internalStorage);
        mHomeSourceCards.addAll(externalStorage);
        mHomeSourceCards.addAll(dlnaSources);
        mHomeSourceCards.addAll(otherNetwork);
        // then the library cards and the Network card
        int[] libraryOrder = {1, 2, 3, 5, 4};   // Movies, TV shows, All videos, Recently played, Recently added (ITEM_ID_OFFSET + n)
        for (int n : libraryOrder) {
            for (ItemData t : mAvailableHomeTiles) {
                if (t.id == ITEM_ID_OFFSET + n) { mHomeSourceCards.add(t); break; }
            }
        }
        for (ItemData t : mAvailableHomeTiles) {
            if (t.id == ITEM_ID_HOME_NETWORK) { mHomeSourceCards.add(t); break; }
        }
        if (mNetworkRowAdapter != null) mNetworkRowAdapter.notifyDataSetChanged();
        if (mHomeSection == SECTION_FILES) {   // the Files page lists the same sources
            rebuildSectionTiles();
            if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
        }
        if (getView() != null) applyHomeRowVisibility(getView());   // the row appears when a network source is available
    }

    /** Horizontal row that scrolls (and flings) farther than the finger moves: a short swipe goes through several cards. */
    private static final class SensitiveLinearLayoutManager extends LinearLayoutManager {
        private static final float SWIPE_FACTOR = 1.9f;

        SensitiveLinearLayoutManager(Context context) {
            super(context, HORIZONTAL, false);
        }

        @Override
        public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
            int wanted = Math.round(dx * SWIPE_FACTOR);
            int scrolled = super.scrollHorizontallyBy(wanted, recycler, state);
            // report the finger's own distance so edge glow / nested scrolling still behave
            return Math.abs(scrolled) < Math.abs(wanted) ? Math.round(scrolled / SWIPE_FACTOR) : dx;
        }
    }

    private void bindNetworkRow(View row) {
        ((TextView) row.findViewById(R.id.home_row_title)).setText(R.string.home_sources_row_title);
        ((TextView) row.findViewById(R.id.home_row_status)).setText("");
        RecyclerView rv = row.findViewById(R.id.home_row_list);
        rv.setLayoutManager(new SensitiveLinearLayoutManager(requireContext()));
        rv.setNestedScrollingEnabled(false);
        mNetworkRowAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return mHomeSourceCards.size(); }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.browser_source_card_phone, parent, false)) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                View tile = holder.itemView;
                final ItemData item = mHomeSourceCards.get(position);
                tile.findViewById(R.id.tile_artwork).setBackgroundTintList(ColorStateList.valueOf(sourceCardColor(item)));
                ((TextView) tile.findViewById(R.id.tile_title)).setText(itemTitle(item));
                TextView details = tile.findViewById(R.id.tile_subtitle);
                CharSequence detailText = homeTileDetails(item);
                details.setText(detailText);
                details.setVisibility(detailText.length() > 0 ? View.VISIBLE : View.GONE);
                tile.findViewById(R.id.tile_pin).setVisibility(View.GONE);
                ImageView icon = tile.findViewById(R.id.tile_icon);
                icon.setVisibility(item.icon != -1 ? View.VISIBLE : View.GONE);
                if (item.icon != -1) icon.setImageResource(item.icon);
                tile.setOnClickListener(v -> {
                    if (item.id == ITEM_ID_NET_SMB || item.id == ITEM_ID_NET_UPNP || item.id == ITEM_ID_NET_LOCAL_DLNA) openNetworkSource(item);
                    else {
                        if (item.id == ITEM_ID_OFFSET + 1) {
                            // Movies always opens All Movies, not the last year/genre view.
                            mPreferences.edit().putInt(BrowserCategoryVideo.KEY_ACTIONBAR_NAVIGATION_POSITION,
                                    BrowserCategoryVideo.KEY_ACTIONBAR_NAVIGATION_POSITION_DEFAULT).apply();
                        }
                        onHomeTileClick(item, v);
                    }
                });
            }
        };
        rv.setAdapter(mNetworkRowAdapter);
        rebuildNetworkRow();
    }

    public void refreshSourceCardColors() {
        if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
        for (HomePosterAdapter adapter : new HomePosterAdapter[]{mRecentAdapter, mAddedAdapter, mMoviesAdapter, mSeriesAdapter, mAllAdapter}) {
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private String sourceKey(ItemData item) {
        if (item.name != null) return "net:" + (item.path == null ? "" : item.path);
        return getResources().getResourceEntryName(item.text) + ":" + (item.path == null ? "" : item.path);
    }

    boolean isSourcesAtBottom() {
        return mHomeSection == SECTION_LIBRARY;   // Library: one scrollable row of sources, fixed above the bottom pill
    }

    /**
     * Library: the row has no header, and the background above the tiles is as tall as the gap between two tiles
     * (the tile's own 5dp top margin + 5dp here = the 10dp between tiles).
     */
    private void applySourcesRowSpacing(View sources, boolean libraryStyle) {
        TextView title = sources.findViewById(R.id.home_row_title);
        View header = title == null ? null : (View) title.getParent();
        if (header != null) header.setVisibility(libraryStyle ? View.GONE : View.VISIBLE);
        sources.setPadding(0, libraryStyle ? dpi(5) : 0, 0, 0);
    }

    void applyHomeSourcePin(View home) {
        if (home == null) return;
        View sources = home.findViewById(R.id.home_sources_row);
        androidx.core.widget.NestedScrollView scroll = home.findViewById(R.id.home_classic);
        if (sources == null || scroll == null) return;

        boolean bottom = isSourcesAtBottom();
        boolean pinned = bottom || mPreferences.getBoolean(KEEP_HOME_SOURCES_VISIBLE, false);
        ViewGroup parent = (ViewGroup) sources.getParent();
        if (parent != null) parent.removeView(sources);
        if (pinned) {
            // top: first child of the home; bottom: fixed right above the bottom pill
            ViewGroup root = (ViewGroup) home;
            View scanPill = root.findViewById(R.id.home_scan_pill);
            int index = bottom && scanPill != null ? root.indexOfChild(scanPill) : 0;
            root.addView(sources, index);
        } else {
            ((ViewGroup) scroll.getChildAt(0)).addView(sources, 0);
        }
        applySourcesRowSpacing(sources, bottom);
        // the fixed bottom row uses the home background from Preferences, so the scrolling rows don't show through it
        if (bottom) {
            sources.setBackgroundColor(mPreferences.getInt(HOME_BACKGROUND_COLOR, DEFAULT_HOME_BACKGROUND));
        } else {
            sources.setBackground(null);
        }
    }

    private void applyHomeRowVisibility(View view) {
        if (view == null) return;
        if (mHomeSection != SECTION_HOME) {
            // Library / Files: the home page with only the source cards
            int[] others = {R.id.home_recent_row, R.id.home_added_row, R.id.home_movies_row, R.id.home_series_row,
                    R.id.home_all_row, R.id.home_network_row};
            for (int id : others) {
                View r = view.findViewById(id);
                if (r != null) r.setVisibility(View.GONE);
            }
            View sources = view.findViewById(R.id.home_sources_row);
            if (sources != null) sources.setVisibility(View.VISIBLE);
            if (mHomeSection == SECTION_LIBRARY && mLibraryRowView != 0) {
                View sel = view.findViewById(mLibraryRowView);
                if (sel != null) sel.setVisibility(View.VISIBLE);   // the chosen library, above the Library cards
            }
            updateHomeGrid(view);   // removes the Home grid outside Home
            return;
        }
        View sourcesRow = view.findViewById(R.id.home_sources_row);
        if (sourcesRow != null) sourcesRow.setVisibility(View.GONE);   // Home has poster rows only: sources live under Library and Files
        View allRow = view.findViewById(R.id.home_all_row);
        if (allRow != null) allRow.setVisibility(mPreferences.getBoolean(SHOW_ROW_ALL_VIDEOS, false) ? View.VISIBLE : View.GONE);
        View networkRow = view.findViewById(R.id.home_network_row);
        // the Network row (below Recently added) shows by itself as soon as a network source is available
        boolean networkFound = !mHomeSourceCards.isEmpty();   // library cards are always there: the Sources row shows unless hidden in Settings
        if (networkRow != null) networkRow.setVisibility(mPreferences.getBoolean(SHOW_ROW_NETWORK, true) && networkFound ? View.VISIBLE : View.GONE);
        java.util.Set<String> hidden = mPreferences.getStringSet(HIDDEN_HOME_ROWS, java.util.Collections.emptySet());
        for (int i = 0; i < HOME_ROW_IDS.length; i++) {
            View row = view.findViewById(HOME_ROW_IDS[i]);
            if (row != null) row.setVisibility(hidden.contains(HOME_ROW_KEYS[i]) ? View.GONE : View.VISIBLE);
        }
        updateHomeGrid(view);
    }

    // ---- Home: movies and TV shows together in one grid (Settings) ----
    private View mHomeGridHost = null;

    /** Settings switch: the Home grid on or off. */
    public void refreshHomeGrid() {
        applyHomeRowVisibility(getView());
    }

    private void updateHomeGrid(View root) {
        // Home always shows Movies and TV shows as two separate horizontal rows: the combined grid is no longer used here.
        boolean want = false;
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.home_classic);
        if (scroll == null) return;
        ViewGroup box = (ViewGroup) scroll.getChildAt(0);
        if (!want) {
            if (mHomeGridHost != null) {
                Fragment f = getChildFragmentManager().findFragmentByTag("home_grid");
                if (f != null) getChildFragmentManager().beginTransaction().remove(f).commitAllowingStateLoss();
                ViewGroup parent = (ViewGroup) mHomeGridHost.getParent();
                if (parent != null) parent.removeView(mHomeGridHost);
                mHomeGridHost = null;
            }
            return;
        }
        View moviesRow = root.findViewById(R.id.home_movies_row);
        View seriesRow = root.findViewById(R.id.home_series_row);
        if (moviesRow != null) moviesRow.setVisibility(View.GONE);   // replaced by the grid
        if (seriesRow != null) seriesRow.setVisibility(View.GONE);
        if (mHomeGridHost == null) {
            android.widget.FrameLayout host = new android.widget.FrameLayout(requireContext());
            host.setId(View.generateViewId());
            mHomeGridHost = host;
            int at = moviesRow != null && moviesRow.getParent() == box ? box.indexOfChild(moviesRow) : box.getChildCount();
            box.addView(host, at, new ViewGroup.LayoutParams(-1, -2));
            PosterGridFragment f = new PosterGridFragment();
            Bundle args = new Bundle(2);
            args.putBoolean(PosterGridFragment.ARG_EMBED, true);
            args.putBoolean(PosterGridFragment.ARG_BOTH, true);
            f.setArguments(args);
            getChildFragmentManager().beginTransaction().replace(host.getId(), f, "home_grid").commitAllowingStateLoss();
        } else if (moviesRow != null && moviesRow.getParent() == box) {
            int want2 = box.indexOfChild(moviesRow);   // keep it where the Movies row was, after the row reorder
            int have = box.indexOfChild(mHomeGridHost);
            if (have != want2 - 1 && have != want2) {
                box.removeView(mHomeGridHost);
                box.addView(mHomeGridHost, Math.min(box.indexOfChild(moviesRow), box.getChildCount()), new ViewGroup.LayoutParams(-1, -2));
            }
        }
        mHomeGridHost.setVisibility(View.VISIBLE);
    }

    /** Settings > Home layout: which cards the Select Source row holds (library, storage, network, YouTube, utility cards). */
    public void showHomeSourcesPicker() {
        ArrayList<ItemData> available = new ArrayList<>(mAvailableHomeTiles);
        java.util.Set<String> hidden = new java.util.HashSet<>(mPreferences.getStringSet(
                HIDDEN_HOME_SOURCES, java.util.Collections.emptySet()));
        String[] names = new String[available.size()];
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = itemTitle(available.get(i));
            checked[i] = isSourceShown(available.get(i));
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.phone_home_sources_row)
                .setMultiChoiceItems(names, checked, (dialog, which, selected) -> {
                    setSourceShown(available.get(which), selected);
                    rebuildHomeTiles();
                    if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Settings > Home layout: the Select Source row itself and the horizontal rows below it. */
    public void showHomeRowsPicker() {
        java.util.Set<String> hiddenRows = new java.util.HashSet<>(mPreferences.getStringSet(
                HIDDEN_HOME_ROWS, java.util.Collections.emptySet()));
        final int n = HOME_ROW_IDS.length;
        String[] names = new String[n + 2];
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < n; i++) {
            names[i] = getString(HOME_ROW_TITLES[i]);
            checked[i] = !hiddenRows.contains(HOME_ROW_KEYS[i]);
        }
        names[n] = getString(R.string.all_videos);
        checked[n] = mPreferences.getBoolean(SHOW_ROW_ALL_VIDEOS, false);
        names[n + 1] = getString(R.string.home_sources_row_title);
        checked[n + 1] = mPreferences.getBoolean(SHOW_ROW_NETWORK, true);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.phone_home_rows)
                .setMultiChoiceItems(names, checked, (dialog, which, selected) -> {
                    if (which == n) {
                        mPreferences.edit().putBoolean(SHOW_ROW_ALL_VIDEOS, selected).apply();
                    } else if (which == n + 1) {
                        mPreferences.edit().putBoolean(SHOW_ROW_NETWORK, selected).apply();
                    } else {
                        String key = HOME_ROW_KEYS[which];
                        if (selected) hiddenRows.remove(key); else hiddenRows.add(key);
                        mPreferences.edit().putStringSet(HIDDEN_HOME_ROWS, new java.util.HashSet<>(hiddenRows)).apply();
                    }
                    applyHomeRowVisibility(getView());
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showSourcePicker() {
        ArrayList<ItemData> available = new ArrayList<>(mAvailableHomeTiles);
        java.util.Set<String> hidden = new java.util.HashSet<>(mPreferences.getStringSet(
                HIDDEN_HOME_SOURCES, java.util.Collections.emptySet()));
        java.util.Set<String> hiddenRows = new java.util.HashSet<>(mPreferences.getStringSet(
                HIDDEN_HOME_ROWS, java.util.Collections.emptySet()));
        String[] names = new String[available.size() + HOME_ROW_IDS.length];
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < available.size(); i++) {
            names[i] = itemTitle(available.get(i));
            checked[i] = isSourceShown(available.get(i));
        }
        for (int i = 0; i < HOME_ROW_IDS.length; i++) {
            names[available.size() + i] = getString(HOME_ROW_TITLES[i]);
            checked[available.size() + i] = !hiddenRows.contains(HOME_ROW_KEYS[i]);
        }
        androidx.appcompat.app.AlertDialog picker = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.home_select_source)
                .setMultiChoiceItems(names, checked, (dialog, which, selected) -> {
                    if (which < available.size()) {
                        setSourceShown(available.get(which), selected);
                        rebuildHomeTiles();
                        if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
                    } else {
                        String key = HOME_ROW_KEYS[which - available.size()];
                        if (selected) hiddenRows.remove(key); else hiddenRows.add(key);
                        mPreferences.edit().putStringSet(HIDDEN_HOME_ROWS, new java.util.HashSet<>(hiddenRows)).apply();
                        applyHomeRowVisibility(getView());
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        picker.show();
    }

    private void onHomeTileClick(ItemData item, View tile) {
        if (openFilesEntry(item)) return;
        if (item.id == ITEM_ID_HOME_DLNA_NOW) {
            com.archos.mediacenter.video.browser.dlna.DlnaCastActivity.open(requireActivity());
            return;
        }
        if (item.id == ITEM_ID_HOME_SETTINGS || item.id == ITEM_ID_HOME_PREFERENCES
                || item.id == ITEM_ID_HOME_RESCAN) {
            if (!(getActivity() instanceof MainActivity)) return;
            MainActivity host = (MainActivity) getActivity();
            if (item.id == ITEM_ID_HOME_SETTINGS) host.showHomeBackgroundPicker();   // Preferences is inside Settings
            else if (item.id == ITEM_ID_HOME_PREFERENCES) host.startPreference();
            else showRescanPopup();
            return;
        }
        if (item.id == ITEM_ID_NET_SMB || item.id == ITEM_ID_NET_UPNP || item.id == ITEM_ID_NET_LOCAL_DLNA) {
            openNetworkSource(item);
            return;
        }
        if (item.id == ITEM_ID_HOME_NETWORK) {
            // The Network card opens its own page: shared folders, media servers, network, cloud, DLNA host.
            loadFragmentAfterStackReset(new com.archos.mediacenter.video.browser.filebrowsing.network.NetworkHubFragment());
        } else {
            handleItemClick(item, tile);
        }
    }

    /** Opens one of the network entries (shared folders, media servers, shortcuts, cloud) by its title. */
    public void openSourceByTitle(int textRes) {
        if (mCategoryList != null) {
            for (Object o : mCategoryList) {
                if (o instanceof ItemData && ((ItemData) o).text == textRes) {
                    handleItemClick(o, null);
                    return;
                }
            }
        }
        if (getContext() != null) {
            android.widget.Toast.makeText(getContext(), R.string.network_not_available, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showNetworkPopup() {
        final ArrayList<ItemData> targets = new ArrayList<>();
        ArrayList<CharSequence> names = new ArrayList<>();
        for (Object o : mCategoryList) {
            if (o instanceof ItemData && isNetworkEntry((ItemData) o)) {
                targets.add((ItemData) o);
                names.add(getText(((ItemData) o).text));
            }
        }
        if (targets.isEmpty() || getActivity() == null) return;
        new android.app.AlertDialog.Builder(
                new android.view.ContextThemeWrapper(getActivity(), android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle(R.string.home_network)
                .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> handleItemClick(targets.get(which), null))
                .show();
    }

    // ---------------------------------------------------------------- phone home: poster rows

    private void setupHomeRows(View view) {
        View recent = view.findViewById(R.id.home_recent_row);
        View added = view.findViewById(R.id.home_added_row);
        View movies = view.findViewById(R.id.home_movies_row);
        View series = view.findViewById(R.id.home_series_row);
        if (recent == null || added == null || movies == null || series == null) return;
        applyHomeRowVisibility(view);
        mRecentAdapter = new HomePosterAdapter(this::openHomeVideo, this::onHomeArtworkTap);
        mAddedAdapter = new HomePosterAdapter(p -> {
            if (p.item instanceof Tvshow) openHomeShow(p);
            else openHomeVideo(p);
        }, p -> {
            if (p.item instanceof Tvshow) openHomeShow(p);
            else onHomeArtworkTap(p);
        });
        mMoviesAdapter = new HomePosterAdapter(this::openHomeVideo, this::onHomeArtworkTap);
        mSeriesAdapter = new HomePosterAdapter(this::openHomeShow);
        mRecentStatus = bindHomeRow(recent, R.string.home_recent, mRecentAdapter);
        mAddedStatus = bindHomeRow(added, R.string.home_added, mAddedAdapter);
        mMoviesStatus = bindHomeRow(movies, R.string.home_movies, mMoviesAdapter);
        mSeriesStatus = bindHomeRow(series, R.string.home_series, mSeriesAdapter);
        LoaderManager lm = LoaderManager.getInstance(this);
        lm.initLoader(LOADER_HOME_RECENT, null, mHomeRowCallbacks);
        lm.initLoader(LOADER_HOME_ADDED, null, mHomeRowCallbacks);
        lm.initLoader(LOADER_HOME_MOVIES, null, mHomeRowCallbacks);
        lm.initLoader(LOADER_HOME_SERIES, null, mHomeRowCallbacks);
        View all = view.findViewById(R.id.home_all_row);
        if (all != null) {
            mAllAdapter = new HomePosterAdapter(p -> {
                if (p.item instanceof Tvshow) openHomeShow(p);
                else openHomeVideo(p);
            }, this::onHomeArtworkTap);
            mAllStatus = bindHomeRow(all, R.string.all_videos, mAllAdapter);
            lm.initLoader(LOADER_HOME_ALL, null, mHomeRowCallbacks);
        }
        for (HomePosterAdapter adapter : new HomePosterAdapter[]{mRecentAdapter, mAddedAdapter, mMoviesAdapter, mSeriesAdapter, mAllAdapter}) {
            if (adapter != null) adapter.setLongClickListener(this::onHomePosterLongClick);
        }
        View networkRow = view.findViewById(R.id.home_network_row);
        if (networkRow != null) bindNetworkRow(networkRow);
    }

    private TextView bindHomeRow(View row, int titleRes, HomePosterAdapter adapter) {
        ((TextView) row.findViewById(R.id.home_row_title)).setText(titleRes);
        RecyclerView rv = row.findViewById(R.id.home_row_list);
        rv.setLayoutManager(new SensitiveLinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        return row.findViewById(R.id.home_row_status);
    }

    private final LoaderManager.LoaderCallbacks<Cursor> mHomeRowCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            Context c = requireContext();
            if (id == LOADER_HOME_ALL)
                return mAllNonScraped
                        ? new com.archos.mediacenter.video.browser.loader.NonScrapedVideosLoader(c).getV4CursorLoader(false, false)
                        : new com.archos.mediacenter.video.browser.loader.AllVideosLoader(c).getV4CursorLoader(false, false);
            if (id == LOADER_HOME_ADDED)
                return new LastAddedLoader(c, true).getV4CursorLoader(false, false);
            if (id == LOADER_HOME_RECENT)
                return new LastPlayedLoader(c).getV4CursorLoader(false, false);
            if (id == LOADER_HOME_MOVIES)
                return new MoviesLoader(c, MoviesLoader.DEFAULT_SORT, true, true, false, 0).getV4CursorLoader(true, false);
            return new AllTvshowsLoader(c).getV4CursorLoader(false, false);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            onHomeRowLoaded(loader.getId(), cursor);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            onHomeRowLoaded(loader.getId(), null);
        }
    };

    private void onHomeRowLoaded(int id, Cursor c) {
        if (id == LOADER_HOME_ADDED) mHomeAddedCursor = c;
        if (id == LOADER_HOME_SERIES) mHomeShows.clear();
        final HomePosterAdapter adapter = id == LOADER_HOME_ALL ? mAllAdapter : id == LOADER_HOME_RECENT ? mRecentAdapter
                : id == LOADER_HOME_ADDED ? mAddedAdapter : id == LOADER_HOME_MOVIES ? mMoviesAdapter : mSeriesAdapter;
        final TextView status = id == LOADER_HOME_ALL ? mAllStatus : id == LOADER_HOME_RECENT ? mRecentStatus
                : id == LOADER_HOME_ADDED ? mAddedStatus : id == LOADER_HOME_MOVIES ? mMoviesStatus : mSeriesStatus;
        if (adapter == null || status == null || !isAdded()) return;
        final int total = c == null || c.isClosed() ? 0 : c.getCount();
        final ArrayList<HomePosterAdapter.Poster> list = new ArrayList<>();
        final int cap = id == mLibraryRow ? 500 : HOME_ROW_MAX;   // a library chosen on the Library page shows all its videos
        if (total > 0 && c.moveToFirst()) {
            try {
                CompatibleCursorMapper mapper = id == LOADER_HOME_SERIES ? new TvshowCursorMapper() : new VideoCursorMapper();
                mapper.bindColumns(c);
                int seasonColumn = c.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_E_SEASON);
                int showColumn = c.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID);
                do {
                    HomePosterAdapter.Poster p = id == LOADER_HOME_ADDED && showColumn >= 0
                            ? mHomeShows.get(c.getLong(showColumn)) : null;
                    if (p == null) {
                        p = toHomePoster(id, mapper.bind(c), seasonColumn >= 0 ? c.getInt(seasonColumn) : 1);
                    }
                    if (p == null) continue;
                    if (id == LOADER_HOME_SERIES && p.item instanceof Tvshow) {
                        mHomeShows.put(((Tvshow) p.item).getTvshowId(), p);
                    }
                    if (list.size() < cap) list.add(p);
                } while ((id == LOADER_HOME_SERIES || list.size() < cap) && c.moveToNext());
            } catch (RuntimeException e) {
                Log.w(TAG, "onHomeRowLoaded: could not read row " + id, e);
            }
        }
        adapter.setItems(list);
        if (id == LOADER_HOME_SERIES && mHomeAddedCursor != null) {
            onHomeRowLoaded(LOADER_HOME_ADDED, mHomeAddedCursor);
        }
        if (total == 0) {
            status.setText(id == LOADER_HOME_ALL ? R.string.home_none_all : id == LOADER_HOME_RECENT ? R.string.home_none_recent
                    : id == LOADER_HOME_ADDED ? R.string.home_none_added
                    : id == LOADER_HOME_MOVIES ? R.string.home_none_movies : R.string.home_none_series);
        } else {
            status.setText(getString((id == LOADER_HOME_RECENT || id == LOADER_HOME_ADDED || id == LOADER_HOME_ALL) ? R.string.home_count_videos
                    : id == LOADER_HOME_MOVIES ? R.string.home_count_movies : R.string.home_count_series, total));
        }
    }

    private HomePosterAdapter.Poster toHomePoster(int id, Object o, int season) {
        if (o instanceof Tvshow) {
            Tvshow t = (Tvshow) o;
            String meta = t.getSeasonCount() > 1
                    ? getString(R.string.home_seasons_episodes, t.getSeasonCount(), t.getEpisodeCount())
                    : getString(R.string.home_season_episodes, t.getEpisodeCount());
            return new HomePosterAdapter.Poster(t.getName(), meta, t.getPosterUri(), t, season);
        }
        if (o instanceof Video) {
            Video v = (Video) o;
            String meta;
            if (id == LOADER_HOME_RECENT) {
                int resume = v.getResumeMs();
                meta = resume > 0
                        ? getString(R.string.home_resume_at, resume / 60000, (resume / 1000) % 60)
                        : getString(R.string.home_play_again);
            } else if (v instanceof Movie) {
                Movie m = (Movie) v;
                meta = m.getYear() > 0 ? String.valueOf(m.getYear()) : "";
                if (m.getRating() > 0)
                    meta += (meta.isEmpty() ? "" : "  ") + String.format(Locale.US, "\u2605 %.1f", m.getRating());
            } else {
                meta = "";
            }
            return new HomePosterAdapter.Poster(v.getName(), meta, v.getPosterUri(), v, season);
        }
        return null;
    }

    /** Same screen a tap on that video opens anywhere else in the app (details, with play / resume). */
    private void onHomeArtworkTap(HomePosterAdapter.Poster poster) {
        if (!(poster.item instanceof Video) || !(getActivity() instanceof MainActivity)) return;
        if (!mPreferences.getBoolean(HomePosterAdapter.POSTER_RESUME, false)) {
            openHomeVideo(poster);
            return;
        }
        Video video = (Video) poster.item;
        int local = video.getResumeMs();
        int remote = video.getRemoteResumeMs();
        int resume = local > 0 && local >= remote
                ? com.archos.mediacenter.video.player.PlayerService.RESUME_FROM_LOCAL_POS
                : remote > 0 ? com.archos.mediacenter.video.player.PlayerService.RESUME_FROM_REMOTE_POS
                : com.archos.mediacenter.video.player.PlayerService.RESUME_NO;
        com.archos.mediacenter.video.utils.PlayUtils.startVideo(requireActivity(), video, resume, false,
                Math.max(0, Math.max(local, remote)), (MainActivity) requireActivity(), -1L);
    }

    /** Long press on a poster: small popup with Fetch meta / Delete Video. */
    private boolean onHomePosterLongClick(HomePosterAdapter.Poster poster, View anchor) {
        if (!(poster.item instanceof Video) || getActivity() == null) return false;
        final Video video = (Video) poster.item;
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(requireActivity(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.home_poster_fetch_meta);
        menu.getMenu().add(0, 2, 1, R.string.home_poster_delete_video);
        menu.getMenu().add(0, 3, 2, R.string.cast_on_tv);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) fetchHomeMeta(video, anchor);
            else if (item.getItemId() == 3)
                com.archos.mediacenter.video.browser.dlna.DlnaCastActivity.start(requireActivity(), video);
            else confirmDeleteHomeVideo(video);
            return true;
        });
        menu.show();
        return true;
    }

    private void fetchHomeMeta(Video video, View anchor) {
        if (getActivity() == null) return;
        if (video instanceof com.archos.mediacenter.video.browser.adapters.object.Movie
                || video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode) {
            com.archos.mediacenter.video.utils.PhoneMetadataRefresh.start(requireActivity(), video, anchor,
                    this::reloadHomeRows);
        } else {      // not identified yet: the manual search screen
            Intent intent = new Intent(getActivity(), com.archos.mediacenter.video.info.VideoInfoScraperActivity.class);
            intent.putExtra(com.archos.mediacenter.video.info.VideoInfoScraperActivity.EXTRA_VIDEO, video);
            startActivity(intent);
        }
    }

    private void confirmDeleteHomeVideo(final Video video) {
        if (getActivity() == null) return;
        final android.content.Context app = requireContext().getApplicationContext();
        new androidx.appcompat.app.AlertDialog.Builder(requireActivity())
                .setMessage(R.string.confirm_delete)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes, (d, w) -> new Delete(new Delete.DeleteListener() {
                    @Override public void onVideoFileRemoved(Uri videoFile, boolean askForFolderRemoval, Uri folderToDelete) { }
                    @Override public void onFolderRemoved(Uri folder) { }
                    @Override public void onDeleteVideoFailed(Uri videoFile) {
                        Toast.makeText(app, R.string.delete_error, Toast.LENGTH_LONG).show();
                    }
                    @Override public void onDeleteSuccess() {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> reloadHomeRows());
                    }
                }, app).startDeleteProcess(video.getFileUri()))
                .show();
    }

    private void reloadHomeRows() {
        if (!isAdded()) return;
        LoaderManager lm = LoaderManager.getInstance(this);
        for (int id : new int[]{LOADER_HOME_RECENT, LOADER_HOME_ADDED, LOADER_HOME_MOVIES, LOADER_HOME_SERIES, LOADER_HOME_ALL}) {
            Loader<Cursor> loader = lm.getLoader(id);
            if (loader != null) loader.forceLoad();
        }
    }
    private void openHomeVideo(HomePosterAdapter.Poster p) {
        if (!(p.item instanceof Video) || getActivity() == null) return;
        Video v = (Video) p.item;
        VideoInfoActivity.startInstance(getActivity(), v, Uri.parse(v.getFilePath()), -1L);
    }

    /** Same screens as a tap on a show in the TV shows list: seasons, or the episodes if there is only one season. */
    private void openHomeShow(HomePosterAdapter.Poster p) {
        if (!(p.item instanceof Tvshow) || !(getActivity() instanceof MainActivity)) return;
        Tvshow tvshow = (Tvshow) p.item;
        Bundle args = new Bundle(4);
        args.putInt(VideoStore.Video.VideoColumns.SCRAPER_E_SEASON, p.season);
        args.putString(CursorBrowserByVideo.SUBCATEGORY_NAME, tvshow.getName());
        args.putSerializable(BrowserListOfSeasons.EXTRA_SHOW_ITEM, tvshow);
        args.putLong(VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID, tvshow.getTvshowId());
        Fragment f = tvshow.getSeasonCount() > 1 ? new BrowserListOfSeasons() : new BrowserListOfEpisodes();
        f.setArguments(args);
        MainActivity activity = (MainActivity) getActivity();
        activity.hideSeachView();
        activity.setNavigationMode(androidx.appcompat.app.ActionBar.NAVIGATION_MODE_STANDARD);
        startContent(f);
    }

    private void addNetworkListener() {
        if (networkState == null) networkState = NetworkState.instance(getContext());
        if (!mNetworkStateListenerAdded && propertyChangeListener != null) {
            if (DBG_LISTENER) Log.d(TAG, "addNetworkListener: networkState.addPropertyChangeListener");
            networkState.addPropertyChangeListener(propertyChangeListener);
            mNetworkStateListenerAdded = true;
        }
    }

    private void removeNetworkListener() {
        if (networkState == null) networkState = NetworkState.instance(getContext());
        if (mNetworkStateListenerAdded && propertyChangeListener != null) {
            if (DBG_LISTENER) Log.d(TAG, "removeListener: networkState.removePropertyChangeListener");
            networkState.removePropertyChangeListener(propertyChangeListener);
            mNetworkStateListenerAdded = false;
        }
    }

    protected abstract int getDefaultId();

    protected boolean checkAvailability(int selectedItemId, String lastPath){
        if(lastPath!=null&&(selectedItemId == ITEM_ID_BROWSER)){
            return new File(lastPath).exists();
        }
        return true;
    }

    private void updateUI() {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                if (DBG) Log.d(TAG, "updateUI");
                // run this on UI thread
                updateExternalStorage();
            });
    }

    public void onResume() {
        if (DBG) Log.d(TAG, "onResume");
        super.onResume();
        // Listen (un)mount sdcard/usb host/samba/upnp events.
        IntentFilter intentFilter = new IntentFilter(ExtStorageReceiver.ACTION_MEDIA_MOUNTED);
        intentFilter.addAction(ExtStorageReceiver.ACTION_MEDIA_UNMOUNTED);
        intentFilter.addAction(ExtStorageReceiver.ACTION_MEDIA_CHANGED);
        intentFilter.addDataScheme(ExtStorageReceiver.ARCHOS_FILE_SCHEME);//new android nougat send UriExposureException when scheme = file
        intentFilter.addDataScheme("file");
        ContextCompat.registerReceiver(getActivity(), mExternalStorageReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        addNetworkListener();
        com.archos.mediacenter.video.browser.dlna.DlnaCastSession.setListener(
                () -> mScanStatusHandler.post(() -> {
                    rebuildHomeTiles();
                    if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
                }));
        // Remove non constant category items.
        for (int index = mCategoryList.size() - 1; index >= mLibrarySize; index--)
            mCategoryList.remove(index);
        updateExternalStorage();
        mScanStatusHandler.post(mScanStatusRunnable);
        startNetworkDiscovery();
    }

    public void onPause() {
        if (DBG) Log.d(TAG, "onPause");
        mScanStatusHandler.removeCallbacks(mScanStatusRunnable);
        stopNetworkDiscovery();
        com.archos.mediacenter.video.browser.dlna.DlnaCastSession.setListener(null);
        getActivity().unregisterReceiver(mExternalStorageReceiver);
        removeNetworkListener();
        // Unregister theme change listener
        if (mThemeChangeListener != null) {
            ThemeManager.getInstance(getContext()).unregisterThemeChangeListener(mThemeChangeListener);
        }
        super.onPause();
    }

    public void setFragment(String path){
        FragmentTitleStruc struc = getContentFragmentAndTitle(mSelectedItemId);
        Fragment f = struc.fragment;
        if (path != null && !path.isEmpty()) {
            Bundle b = new Bundle();
            b.putString(BrowserCategory.MOUNT_POINT, path);
            f.setArguments(b);
        }
        loadFragmentAfterStackReset(f);
        if (getActivity() != null && ((AppCompatActivity)getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(struc.title);
        }
    }

    /**
     * Called when the user has clicked on a category item
     */
    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        handleItemClick(mCategoryList.get(position), v);
    }

    protected void handleItemClick(Object object, View v) {
        if (object instanceof ItemData) {
            ItemData item = (ItemData) object;
            if(item.text == R.string.preferences){
                if(getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).startPreference();
                setSelection(mSelectedItemId); //restore selection
            } else if (item.text == R.string.rescan){
                showRescanPopup();
                setSelection(mSelectedItemId); //restore selection
            } else if (item.text == R.string.help_faq){
                WebUtils.openWebLink(getActivity(),getString(R.string.faq_url));
            } else if (item.text == R.string.sponsor){
                WebUtils.openWebLink(getActivity(),getString(R.string.sponsor_url));
            } else if(item.text  == R.string.activate_private_mode || item.text  == R.string.deactivate_private_mode){
                if (!PrivateMode.isActive() && PrivateMode.canShowDialog(getActivity())) {
                    PrivateMode.showDialog(getActivity());
                }
                PrivateMode.toggle(getActivity());
                setSelection(mSelectedItemId); //restore selection
                ((MainActivity) getActivity()).setBackground();
                updateExternalStorage();
            } else {

                updateListSelection(v, item);
                setFragment(item.path);
                if(item.id!=ITEM_ID_PROVIDER) { //don't save when provider to avoid restarting with android browser view
                    PreferenceManager.getDefaultSharedPreferences(getContext()).edit().putInt(PREFERENCE_LAST_FRAGMENT, mSelectedItemId).apply();
                    PreferenceManager.getDefaultSharedPreferences(getContext()).edit().putString(PREFERENCE_LAST_PATH, item.path).apply();
                }
                if (getActivity() instanceof MainActivity && !((MainActivity) getActivity()).isPhoneHome())
                    ((MainActivity) getActivity()).closeDrawer();
            }

        }
    }

    protected void updateListSelection(View v, ItemData item) {
        mOldSelectedItemId = mSelectedItemId;
        mSelectedItemId = item.id;
        if(v!=null)
            mSelectedItemTop = v.getTop();
    }

    public void loadFragmentAfterStackReset(Fragment f) {
        FragmentManager fm = getParentFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            // Clear the back stack as a new category is started.
            fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }

        // Update the content of the fragment corresponding to the selected category
        // and make it visible if needed
        startContent(f);
    }

    /**
     * Returns the fragment corresponding to the category name
     */
    abstract public FragmentTitleStruc getContentFragmentAndTitle(int id);

    public void startContent(Fragment fragment) {
        startContent(fragment, null);
    }

    public void startContent(Fragment fragment, String tag) {
         updateCategoryContent(fragment, tag);
    }

    private void showCategoryContent(Fragment fragment) {
        // Make the fragment corresponding to the current category visible
        mLayoutCallback.fragment = fragment;
    }

    /** True while the swipe animation moves the page itself (the fragment enter animation would double it). */
    private boolean mSuppressContentAnim;

    private void updateCategoryContent(Fragment fragment, String tag) {
        // Update the content of the fragment corresponding to the current category
        FragmentManager fm = getParentFragmentManager();
        if (fm != null) {
            FragmentTransaction ft = getParentFragmentManager().beginTransaction();
            if (!mSuppressContentAnim) {
                ft.setCustomAnimations(R.anim.browser_content_enter,
                    R.anim.browser_content_exit, R.anim.browser_content_pop_enter,
                    R.anim.browser_content_pop_exit);
            }
            ft.replace(R.id.content, fragment, tag);
            ft.addToBackStack(null);
            ft.commitAllowingStateLoss();
            if (getActivity() instanceof MainActivity && ((MainActivity) getActivity()).isPhoneHome())
                ((MainActivity) getActivity()).closeDrawer();
            if(getActivity() instanceof BrowserActivity)
                ((MainActivity)getActivity()).updateHomeIcon(getParentFragmentManager().getBackStackEntryCount()>0);
        }
    }

    /**
     * Update the library category's items
     */
    private void updateLibrary() {
        if (mCategoryList == null)
            mCategoryList = new ArrayList<Object>();
        else
            mCategoryList.clear();

        mCategoryList.add(getText(R.string.goto_start));
        setLibraryList(mCategoryList);
        mLibrarySize = mCategoryList.size();
    }

    /**
     * Add the library category items.
     */
    abstract public void setLibraryList(ArrayList<Object> categoryList);


    /**
     * Update the view for network, sd card and usb host.
     */
    private void updateExternalStorage() {
        if (DBG) Log.d(TAG, "updateExternalStorage");
        // First, remove old external items.
       boolean oneAgain = true;
        // Remove non constant category items.
        for (int index = mCategoryList.size() - 1; index >= mLibrarySize; index--) {
            mCategoryList.remove(index);
        }
       /*  while (oneAgain) {
            int lastIndex = mCategoryList.size() - 1;
            Object item = mCategoryList.get(lastIndex);
            if (item instanceof CharSequence) {
                mCategoryList.remove(lastIndex);
                oneAgain = false;
            } else {
                boolean contains = false;
                int size = mExternalIDs.length;
                int id = ((ItemData) item).text;
                for (int i = 0; i < size && !contains; i++) {
                    if (id == mExternalIDs[i])
                        contains = true;
                }
                if (contains)
                    mCategoryList.remove(lastIndex);
                else
                    oneAgain = false;
            }
        }
        */
        ExtStorageManager storageManager = ExtStorageManager.getExtStorageManager();
        final boolean hasExternal = storageManager.hasExtStorage();
        final boolean isConnected = isConnected();
        // internal storage browsing has no dependency on external storage or network
        // availability: always show it, regardless of connectivity state (it previously
        // vanished together with the network-only entries when offline with no removable
        // media attached, e.g. airplane mode)
        mCategoryList.add(getText(R.string.leanback_browsing));

        {
            ItemData itemData2 = new ItemData();
            itemData2.icon = R.drawable.category_common_folder;
            itemData2.text = R.string.root_storage;
            itemData2.id = ITEM_ID_VIDEO_FOLDER;
            mCategoryList.add(itemData2);
        }

        if (hasExternal) {
            for(String s : storageManager.getExtSdcards()) {
                ItemData itemData = new ItemData();
                itemData.icon = R.drawable.category_common_sdcard;
                itemData.text = R.string.sd_card_storage;
                itemData.path = s;
                itemData.id = ITEM_ID_BROWSER;
                mCategoryList.add(itemData);
            }
            for(String s : storageManager.getExtUsbStorages()) {
                ItemData itemData = new ItemData();
                itemData.icon = R.drawable.category_common_usb;
                itemData.text = R.string.usb_host_storage;
                itemData.path = s;
                itemData.id = ITEM_ID_BROWSER;
                mCategoryList.add(itemData);
            }
            for(String s : storageManager.getExtOtherStorages()) {
                ItemData itemData = new ItemData();
                itemData.icon = R.drawable.category_common_folder;
                itemData.text = R.string.other_storage;
                itemData.path = s;
                itemData.id = ITEM_ID_BROWSER;
                mCategoryList.add(itemData);
            }
        }

        if (isConnected){
            ItemData itemData = new ItemData();
            itemData.icon = R.drawable.category_common_network;
            itemData.text = R.string.network_shared_folders;
            itemData.id = ITEM_ID_SMB;
            mCategoryList.add(itemData);
        }

        if (isConnected){
            ItemData itemData = new ItemData();
            itemData.icon = R.drawable.category_common_network;
            itemData.text = R.string.network_media_servers;
            itemData.id = ITEM_ID_UPNP;
            mCategoryList.add(itemData);
        }
        if ( NetworkState.isNetworkConnected(getActivity())){
            ItemData itemData = new ItemData();
            itemData.icon = R.drawable.category_network_shortcut;
            itemData.text = R.string.network_shortcuts;
            itemData.id = ITEM_ID_NETWORK;
            mCategoryList.add(itemData);
        }
        // one could argue that "cloud" should be made available only if connected
        // but offline capability is present in drive and provider is more generic
        // than cloud in reality. Perhaps think of better name
        ItemData itemData = new ItemData();
        itemData.icon = R.drawable.cloud_24px;
        itemData.text = R.string.provider_folders;
        itemData.id = ITEM_ID_PROVIDER;
        mCategoryList.add(itemData);
        addLastItems();
        mCategoryAdapter.notifyDataSetChanged();
        // Set the selection when rotating.
        if (mSelectedItemId != 0) {
            int index = 0;
            oneAgain = true;
            while (index < mCategoryList.size() && oneAgain) {
                Object o = mCategoryList.get(index);
                if (o instanceof ItemData && ((ItemData) o).id == mSelectedItemId) {
                    oneAgain = false;
                    ListView lv = getListView();
                    lv.setItemChecked(index, true);
                    lv.setSelectionFromTop(index, mSelectedItemTop);
                }
                index++;
            }
        }
    }

    private void addLastItems() {
        mCategoryList.add("Nova" + " v" + getText(R.string.VERSION_NAME));
        ItemData itemData = new ItemData();
        itemData.icon = R.drawable.android29_ic_settings;
        itemData.text = R.string.preferences;
        mCategoryList.add(itemData);
        itemData = new ItemData();
        itemData.icon = R.drawable.android29_ic_rescan;
        itemData.text = R.string.rescan;
        mCategoryList.add(itemData);
        itemData = new ItemData();
        itemData.icon = R.drawable.android29_ic_menu_help;
        itemData.text = R.string.help_faq;
        mCategoryList.add(itemData);
        // Google Play is allergic to piggies... no donation button
        if (BuildConfig.ENABLE_SPONSOR) mEnableSponsor = mPreferences.getBoolean(VideoPreferencesCommon.KEY_ENABLE_SPONSOR, VideoPreferencesCommon.ENABLE_SPONSOR_DEFAULT);
        if (((! ArchosUtils.isInstalledfromPlayStore(getActivity().getApplicationContext())) || mEnableSponsor) && BuildConfig.ENABLE_SPONSOR) {
            itemData = new ItemData();
            itemData.icon = R.drawable.piggy_bank;
            itemData.text = R.string.sponsor;
            mCategoryList.add(itemData);
        }
    }

    public void clearCheckedItem() {
        ListView lv = getListView();
        int checkPosition = lv.getCheckedItemPosition();
        if (checkPosition != ListView.INVALID_POSITION) {
            lv.setItemChecked(checkPosition,false);
        }
        mSelectedItemId = 0;
    }

    private boolean isConnected(){
        if (mPreferences.getBoolean(getString(R.string.preferences_network_mobile_vpn_key), false))
            return NetworkState.isNetworkConnected(getActivity());
        else
            return NetworkState.isLocalNetworkConnected(getActivity());
    }

    /**
     * Adapter class for displaying category items.
     */
    protected class CategoryAdapter extends BaseAdapter {
        static final private int ITEM_VIEW_TYPE_CATEGORY = 0;
        static final private int ITEM_VIEW_TYPE_SEPARATOR = 1;
        static final private int ITEM_VIEW_TYPE_COUNT = 2;
        private final LayoutInflater inflater;

        @Override public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            rebuildHomeTiles();
            if (mHomeSourcesAdapter != null) mHomeSourcesAdapter.notifyDataSetChanged();
        for (HomePosterAdapter adapter : new HomePosterAdapter[]{mRecentAdapter, mAddedAdapter, mMoviesAdapter, mSeriesAdapter, mAllAdapter}) {
            if (adapter != null) adapter.notifyDataSetChanged();
        }
        }

        public CategoryAdapter(Context context) {
            super();
            inflater = LayoutInflater.from(context);
        }

        public boolean areAllItemsEnabled() {
            return false;
        }
        public int getCount() {
            return mCategoryList.size();
        }

        public Object getItem(int position) {
            return mCategoryList.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public int getViewTypeCount() {
            return ITEM_VIEW_TYPE_COUNT;
        }

        public int getItemViewType(int position) {
            int type;
            Object v = mCategoryList.get(position);
            if (v instanceof CharSequence){
                type = ITEM_VIEW_TYPE_SEPARATOR;
            }
            else {
                type = ITEM_VIEW_TYPE_CATEGORY;
            }
            return type;
        }

        public boolean isEnabled(int position) {
            // A separator cannot be clicked !
            return getItemViewType(position) == ITEM_VIEW_TYPE_CATEGORY;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            final int type = getItemViewType(position);
            if (convertView == null) {
                if (type == ITEM_VIEW_TYPE_CATEGORY) {
                    convertView = inflater.inflate(R.layout.browser_category_item_shortcut, parent, false);
                } else {
                    convertView = inflater.inflate(R.layout.browser_category_item_separator, parent, false);
                }
            }

            final TextView tv = (TextView) convertView;
            if (type == ITEM_VIEW_TYPE_CATEGORY) {
                // Create theme-aware background selector
                android.graphics.drawable.StateListDrawable bgSelector = new android.graphics.drawable.StateListDrawable();
                int selectorColor = ThemeManager.getInstance(getContext()).getCategorySelectorColor();
                // Pressed state
                bgSelector.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(selectorColor));
                // Activated/selected state
                bgSelector.addState(new int[]{android.R.attr.state_activated}, new android.graphics.drawable.ColorDrawable(selectorColor));
                // Default state (transparent)
                bgSelector.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                convertView.setBackground(bgSelector);
                // Set the category name
                ItemData item = (ItemData) mCategoryList.get(position);
                tv.setText(item.text);
                if(item.icon!=-1)
                    tv.setCompoundDrawablesWithIntrinsicBounds(item.icon, 0 , 0 , 0);
                else tv.setCompoundDrawablesWithIntrinsicBounds(0, 0 , 0 , 0);
            }
            else {
                tv.setText((CharSequence) mCategoryList.get(position));
            }

            return convertView;
        }

        /*private int getSelectedItemIndex() {
            // Look for the index of the item whose id is equal to
            // mSelectedItemId 
            int size = mCategoryList.size();
            int index = 0;
            while (index < size) {
                Object o = mCategoryList.get(index);
                if (o instanceof ItemData && ((ItemData) o).text == mSelectedItemId) {
                    return index;
                }
                index++;
            }
            return -1;
        }*/
    }
}
