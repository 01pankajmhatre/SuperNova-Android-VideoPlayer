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

import static com.archos.filecorelibrary.FileUtils.hasManageExternalStoragePermission;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.preference.PreferenceManager;

import com.archos.filecorelibrary.FileUtils;
import com.archos.mediaprovider.video.LoaderUtils;
import com.archos.mediacenter.video.CustomApplication;
import com.archos.mediacenter.video.DensityTweak;
import com.archos.mediacenter.video.EntryActivity;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.UiChoiceDialog;
import com.archos.mediacenter.video.autoscraper.AutoScraperActivity;
import com.archos.mediacenter.video.browser.BrowserByIndexedVideos.BrowserListOfSeasons;
import com.archos.mediacenter.video.browser.BrowserByIndexedVideos.CursorBrowserByVideo;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.dialogs.Paste;
import com.archos.mediacenter.video.browser.filebrowsing.BrowserByVideoFolder;
import com.archos.mediacenter.video.info.SingleVideoLoader;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.player.PrivateMode;
import com.archos.mediacenter.video.utils.ActiveOperationMonitor;
import com.archos.mediacenter.video.utils.ExternalPlayerResultListener;
import com.archos.mediacenter.video.utils.ExternalPlayerWithResultStarter;
import com.archos.mediacenter.video.utils.MiscUtils;
import com.archos.mediacenter.video.utils.PlayUtils;
import com.archos.mediacenter.video.utils.ThemeManager;
import com.archos.mediacenter.video.utils.VideoPreferencesActivity;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;
import com.archos.mediaprovider.video.LoaderUtils;
import com.archos.mediaprovider.video.ScraperStore;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediaprovider.video.VideoStore.Video.VideoColumns;
import com.archos.mediascraper.AutoScrapeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This is the launch class for the video browser.
 */
public class MainActivity extends BrowserActivity implements ExternalPlayerWithResultStarter {

    private static final Logger log = LoggerFactory.getLogger(MainActivity.class);

    public final static int DIALOG_DELETE = 1;
    public final static int DIALOG_DELETING = 2;
    public final static int MENU_SCRAPER_GROUP = 1;

    private final static int MENU_START_AUTO_SCRAPER_ACTIVITY = 0;
    private final static int MENU_SCRAPER_SETTINGS = 1;

    private final static int MENU_PREFERENCES_GROUP = 5;
    private final static int MENU_PREFERENCES_ITEM = 31;

    private static final int MENU_SEARCH_GROUP = 6;
    private static final int MENU_SEARCH_ITEM = 32;

    private static final int MENU_PRIVATE_MODE_GROUP = 7;
    private static final int MENU_PRIVATE_MODE_ITEM = 33;

    // phone home page action bar items (VideoPlayer-style home: Preferences, Rescan, Help in the action bar)
    private static final int MENU_RESCAN_ITEM = 34;
    private static final int MENU_HELP_ITEM = 35;
    private static final int MENU_SPONSOR_ITEM = 36;
    private static final int MENU_HOME_THEME_ITEM = 38;
    private static final String HOME_BACKGROUND_COLOR = "classic_home_background_color";
    private static final int DEFAULT_HOME_BACKGROUND = 0xFF0B1220;
    private static final int PERMISSION_REQUEST = 1;
    private static final int PLAY_ACTIVITY_REQUEST_CODE = 900;
    public static String LAUNCH_DIALOG = "LAUNCH_DIALOG";
    private PermissionChecker mPermissionChecker;

    private SearchView mSearchView;
    public static final int MENU_CHANGE_FOLDER = 6;

    public static final int ACTIVITY_REQUEST_CODE_PREFERENCES = 101;

    private String mCurrentUiModeLeanback = null;



    private final ActivityResultLauncher<Intent> playLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> ExternalPlayerResultListener.getInstance().onActivityResult(
                    PLAY_ACTIVITY_REQUEST_CODE, result.getResultCode(), result.getData()));

    private final ActivityResultLauncher<Intent> preferencesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == VideoPreferencesCommon.ACTIVITY_RESULT_UI_MODE_CHANGED) {
                    String newUiModeLeanback = PreferenceManager.getDefaultSharedPreferences(this)
                            .getString(UiChoiceDialog.UI_CHOICE_LEANBACK_KEY, "-");
                    if (!newUiModeLeanback.equals(mCurrentUiModeLeanback)) {
                        finish();
                        startActivity(new Intent(this, EntryActivity.class));
                    }
                    mCurrentUiModeLeanback = null;
                } else if (result.getResultCode() == VideoPreferencesCommon.ACTIVITY_RESULT_UI_ZOOM_CHANGED) {
                    new DensityTweak(this).forceDensityDialogAtNextStart();
                    finish();
                    startActivity(new Intent(this, EntryActivity.class));
                }
            });




    private SearchManager mSearchManager;

    private final static String SCRAPER_SELECTION = ScraperStore.AllVideos.SCRAPER_TYPE + "=? AND "
            + ScraperStore.AllVideos.SCRAPER_ID + "=?";
    private final static String TITLE_FORMAT = "%s  S%dE%d <i> %s </i>";

    private final static String[] CURSORS = {
            VideoStore.Video.VideoColumns._ID, VideoStore.Video.VideoColumns.TITLE,
            VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_ID,
            VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE
    };

    private final static String[] SCRAPER_PROJECTION = {
            ScraperStore.AllVideos.MOVIE_OR_SHOW_NAME, ScraperStore.AllVideos.MOVIE_OR_SHOW_COVER,
            ScraperStore.AllVideos.EPISODE_NUMBER, ScraperStore.AllVideos.EPISODE_SEASON_NUMBER,
            ScraperStore.AllVideos.EPISODE_NAME
    };

    private final static String StereoActivity = "com.archos.mediacenter.video.browser.MainActivityStereo";

    private NewVideosActionProvider mNewVideosActionProvider = null;

    protected SharedPreferences mPreferences;

    private View mGlobalBackdrop;

    public static Boolean mStereoForced = false;


    private SharedPreferences.OnSharedPreferenceChangeListener mThemeChangeListener;
    private DrawerLayout mDrawerLayout;
    private ActionBarDrawerToggle mDrawerToggle;


    private MenuItem mSearchItem;
    private int mNavigationMode;
    private void updateStereoMode(Intent intent) {
        mStereoForced = false;
        try {
            if (intent.getComponent().getClassName().equals(StereoActivity)) {
                ActivityInfo ai = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_ACTIVITIES|PackageManager.GET_META_DATA);
                Bundle bundle = ai.metaData;
                mStereoForced = bundle.getBoolean("stereo_mode");
            }
        } catch (NameNotFoundException e) {
            log.error("Failed to load meta-data, NameNotFound: {}", e.getMessage());
        } catch (NullPointerException e) {
            log.error("Failed to load meta-data, NullPointer: {}", e.getMessage());
        }        
    }

    public void setBackground() {
        // Update private mode indicator visibility
        View privateModeIndicator = findViewById(R.id.private_mode_indicator);
        if (privateModeIndicator != null) {
            privateModeIndicator.setVisibility(PrivateMode.isActive() ? View.VISIBLE : View.GONE);
        }
        
        if (PrivateMode.isActive()) {
            // Private mode: use theme-appropriate color for both phone and TV
            int backgroundColor = ThemeManager.getInstance(this).getPrivateModeColor();
            getWindow().getDecorView().setBackgroundColor(backgroundColor);
            if(mDrawerLayout != null)
                mDrawerLayout.findViewById(R.id.category_container).setBackgroundColor(backgroundColor);
        } else if (ThemeManager.getInstance(this).isBlackTheme() && 
                   ThemeManager.getInstance(this).isPhoneMode(this)) {
            // Phone + Black theme: Apply gradient to window AND category container
            ThemeManager.getInstance(this).applyPhoneWindowGradient(this);
            if(mDrawerLayout != null)
                mDrawerLayout.findViewById(R.id.category_container).setBackground(
                    ThemeManager.getInstance(this).getPhoneCategoryGradient());
        } else {
            // TV or Blue theme: Keep existing behavior
            ThemeManager.getInstance(this).applyWindowGradient(this);
            if(mDrawerLayout != null)
                mDrawerLayout.findViewById(R.id.category_container).setBackgroundColor(
                    ThemeManager.getInstance(this).getLeanbackBackgroundColor());
        }
        // Theme gradients are applied after the initial home setup as well.
        // Restore the selected phone-home background and system-bar surface last.
        if (isPhoneHome() && !PrivateMode.isActive()) updatePhoneHomeActionBar();
    }

    private void setHomeButton() {
        int iconResId = PrivateMode.isActive() ? R.mipmap.nova_private : R.mipmap.nova;
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(false);

    }

    private static boolean sDlnaStartupChecked;

    /** "Start the server when the app opens" of the DLNA host. Once per app start. */
    private void startDlnaServerOnStartup(Bundle savedInstanceState) {
        if (sDlnaStartupChecked || savedInstanceState != null) return;
        sDlnaStartupChecked = true;
        com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs dlna = new com.archos.mediacenter.video.browser.dlna.DlnaServerPrefs(this);
        if (dlna.isAutoStart() && !dlna.getFolders().isEmpty()) {
            com.archos.mediacenter.video.browser.dlna.DlnaServerService.start(this);
        }
    }

    private static boolean sStartupMetaChecked;

    /** "Fetch meta for missing files on startup" (checkbox of the re-scan popup). Once per app start. */
    private void fetchMissingMetaOnStartup(Bundle savedInstanceState) {
        if (sStartupMetaChecked || savedInstanceState != null) return;
        sStartupMetaChecked = true;
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(BrowserCategory.PREF_FETCH_META_ON_STARTUP, false)
                && AutoScrapeService.isEnable(this)) {
            Intent intent = new Intent(AutoScrapeService.RESCAN_EVERYTHING, null, this, AutoScrapeService.class);
            intent.putExtra(AutoScrapeService.RESCAN_EVERYTHING, true);
            intent.putExtra(AutoScrapeService.RESCAN_ONLY_DESC_NOT_FOUND, true);
            startService(intent);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Apply correct theme before super.onCreate() to avoid visual flash
        ThemeManager themeManager = ThemeManager.getInstance(this);
        if (themeManager.isBlackTheme()) {
            setTheme(R.style.ArchosThemeBlackNoActionBar);
        } else {
            setTheme(R.style.ArchosThemeBlueNoActionBar);
        }
        // requestWindowFeature() must be called before applyWindowTheme() since
        // setBackgroundDrawable() can install the decor view on older API levels
        requestWindowFeature(Window.FEATURE_OPTIONS_PANEL);
        themeManager.applyWindowTheme(this);
        ((CustomApplication) getApplication()).loadLocale();
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        super.onCreate(savedInstanceState);
        fetchMissingMetaOnStartup(savedInstanceState);
        startDlnaServerOnStartup(savedInstanceState);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isPhoneHome() && safeIsDrawerOpen()) {
                    supportFinishAfterTransition();
                    return;
                }
                int backStackCount = getSupportFragmentManager().getBackStackEntryCount();
                if (isPhoneHome()) {
                    if (backStackCount <= 1) {
                        // back to the home page: drop the page that was open, so the next page does not stack on top of it
                        if (backStackCount > 0) {
                            getSupportFragmentManager().popBackStackImmediate(null,
                                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
                        }
                        safeOpenDrawer();
                    } else getSupportFragmentManager().popBackStackImmediate();
                    updateHomeIcon(true);
                    return;
                }
                if (backStackCount <= 1) {
                    if (mDrawerLayout == null || safeIsDrawerOpen())
                        supportFinishAfterTransition();
                    else {
                        safeOpenDrawer();
                    }
                } else {
                    // Get the fragment that is currently at the top of the back stack
                    String fragmentTag = getSupportFragmentManager().getBackStackEntryAt(backStackCount - 1).getName();
                    Fragment currentFragment = getSupportFragmentManager().findFragmentByTag(fragmentTag);
                    // Check if the fragment is added before trying to remove it
                    if (currentFragment != null && currentFragment.isAdded()) {
                        getSupportFragmentManager().beginTransaction().remove(currentFragment).commit();
                    }
                    getSupportFragmentManager().popBackStackImmediate();
                }
                updateHomeIcon(getSupportFragmentManager().getBackStackEntryCount() > 1);
            }
        });

        mThemeChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (VideoPreferencesCommon.KEY_APP_THEME.equals(key)) {
                    recreate();
                }
            }
        };
        ThemeManager.getInstance(this).registerThemeChangeListener(mThemeChangeListener);

        // Update uimode/uimode_leanback to reflect we're in phone/tablet mode
        com.archos.mediacenter.video.UiChoiceDialog.updateUiModePreferences(this, false);

        //Setup an preferences before we start activites.
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        
        //Set the Hide watched videos on Startup.
        LoaderUtils.mMustHideWatchedVideo = mPreferences.getBoolean("hide_watched", false);
        LoaderUtils.mSmartRecentlyRows = mPreferences.getBoolean("smart_recently_rows", false);

        //If we are starting the Browser again, we aren't unpausing a Video
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putBoolean("user_paused_video", false);

        //Reset Video brightness to System on Startup.
        if (!mPreferences.getBoolean("reset_brightness_on_start", false))
            editor.putInt("brightness_saved",-1);

        //Apply all the changes at once!
        editor.apply();

        try {
            mSearchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
            if (mSearchManager == null) {
                log.error("onCreate: searchManager is null");
            } else {
                mSearchView = new SearchView(this);
                mSearchView.setSearchableInfo(mSearchManager.getSearchableInfo(getComponentName()));
            }
        } catch (IllegalStateException e) {
            log.error("onCreate: searchManager is null");
        }

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);
        mDrawerLayout = (DrawerLayout)findViewById(R.id.drawer_layout);
        if (isPhoneHome()) {
            disableDrawer();
            setPhoneHomeVisible(savedInstanceState == null
                    || savedInstanceState.getBoolean("phone_home_visible", true));
        } else if (mDrawerLayout != null){
            try {
                mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                        R.string.drawer_open, R.string.drawer_close);
                mDrawerToggle.setDrawerIndicatorEnabled(true);
                mDrawerToggle.syncState();

                if (savedInstanceState == null && !isShortcutIntent())
                    safeOpenDrawer();
            } catch (Throwable t) {
                log.error("onCreate: caught exception setting up drawer, locking drawer closed", t);
                disableDrawer();
            }
        }

        getSupportFragmentManager().addOnBackStackChangedListener(this::updateSourceActionBar);
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(androidx.fragment.app.FragmentManager fm,
                            androidx.fragment.app.Fragment f, View v, Bundle savedInstanceState) {
                        if (f.getId() == R.id.content && isHomeStyleFragment(f))
                            v.setBackgroundColor(com.archos.mediacenter.video.browser.presenter.PhoneCardStyle.pageColor(MainActivity.this));
                    }

                    @Override
                    public void onFragmentResumed(androidx.fragment.app.FragmentManager fm, androidx.fragment.app.Fragment f) {
                        if (f.getId() == R.id.content) postHomeBarUpdate();
                    }

                    @Override
                    public void onFragmentViewDestroyed(androidx.fragment.app.FragmentManager fm, androidx.fragment.app.Fragment f) {
                        if (f.getId() == R.id.content) postHomeBarUpdate();
                    }
                }, false);
        // pages opened and closed with the back stack: re-evaluate the action bar style once the change is done
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            View bar = findViewById(R.id.main_toolbar);
            if (bar != null) bar.post(this::updatePhoneHomeActionBar);
        });
        updateSourceActionBar();

        // determine if display has cutouts
        getWindow().getDecorView().setOnApplyWindowInsetsListener( new View.OnApplyWindowInsetsListener() {
            @SuppressLint("NewApi")
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = getWindow().getDecorView().getRootWindowInsets().getDisplayCutout();
                    if (cutout != null) {
                        if (log.isDebugEnabled()) log.debug("device with cutout");
                        MiscUtils.hasCutout = true;
                    } else
                        if (log.isDebugEnabled()) log.debug("device without cutout");
                }
                getWindow().getDecorView().setOnApplyWindowInsetsListener(null);
                return view.onApplyWindowInsets(insets);
            }
        });

        AutoScrapeService.registerObserver(this);
        mPermissionChecker = new PermissionChecker(hasManageExternalStoragePermission(getApplicationContext()));
        setBackground();

        mNewVideosActionProvider = new NewVideosActionProvider(this);
        LoaderManager.getInstance(this).initLoader(0, null, mNewVideosActionProvider);

        // Register a content observer which will be used to update the global
        // resume view
        if (savedInstanceState==null) {
            handleIntent(getIntent());
        }

        ViewGroup globalLayout = (ViewGroup) getWindow().getDecorView();
        mGlobalBackdrop = getLayoutInflater().inflate(R.layout.browser_main_video_backdrop, globalLayout, false);
        globalLayout.addView(mGlobalBackdrop, 0);
        

        //in case we need to re-log in trakt
        CustomApplication.showChangelogDialog(CustomApplication.getChangelog(this.getApplicationContext()), this);
    }

    private boolean isShortcutIntent() {
        return getIntent()!=null && getIntent().getAction() != null
                && (getString(R.string.action_resume).equals(getIntent().getAction())
                || getString(R.string.action_recently_added).equals(getIntent().getAction())
                || getString(R.string.action_recently_played).equals(getIntent().getAction()));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

    }



    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Extra set by the player's More popup: open the app UI settings (home settings) page. */
    public static final String EXTRA_OPEN_UI_SETTINGS = "org.novastudio.player.OPEN_UI_SETTINGS";

    private void handleIntent(Intent intent) {
        updateStereoMode(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_UI_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_UI_SETTINGS);
            getWindow().getDecorView().post(this::showHomeBackgroundPicker);
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            try {
                // Open a show
                if (data.getScheme().equals("show")) {
                    int showId = Integer.parseInt(FileUtils.getName(data));
                    Bundle args = new Bundle(2);
                    args.putLong(VideoColumns.SCRAPER_SHOW_ID, showId);
                    args.putString(CursorBrowserByVideo.SUBCATEGORY_NAME, ""); // should better have the show title, but...
                    Fragment f = new BrowserListOfSeasons();
                    f.setArguments(args);
                    BrowserCategory category = (BrowserCategory) getSupportFragmentManager().findFragmentById(R.id.category);
                    category.clearCheckedItem(); // a category may have be selected previously
                    category.startContent(f);
                }
                // Open a file
                else {
                    int videoId = Integer.parseInt(FileUtils.getName(data));
                    Uri uri = ContentUris.withAppendedId(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, videoId);
                    Intent intent2 = new Intent(Intent.ACTION_VIEW, uri);
                    if (!mPreferences.getBoolean(VideoPreferencesActivity.ALLOW_3RD_PARTY_PLAYER, VideoPreferencesActivity.ALLOW_3RD_PARTY_PLAYER_DEFAULT)) {
                        intent2.setClass(this, PlayerActivity.class);
                        intent2.putExtra(PlayerActivity.EXTRA_INTERNAL_LAUNCH, true);
                    }
                    try {
                        startActivity(intent2);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.cannot_open_video, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (NullPointerException npe) {}
        }
        else if(LAUNCH_DIALOG.equals(intent.getAction())){
            if(FileManagerService.fileManagerService!=null&&FileManagerService.fileManagerService.isPastingInProgress()&&(mPasteDialog==null||!mPasteDialog.isShowing())) {
                mPasteDialog = new Paste(this);
                mPasteDialog.show();
            }
        }
        else if(getString(R.string.action_resume).equals(intent.getAction())){
            ContentResolver contentResolver = getContentResolver();
            try (Cursor c = contentResolver.query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, CURSORS,
                    VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + "!=0" + (LoaderUtils.mustHideUserHiddenObjects() ? " AND " + LoaderUtils.HIDE_USER_HIDDEN_FILTER : ""), null,
                    VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + " DESC LIMIT 1")) {

                if (c != null && c.moveToFirst()) {
                    int index_id = c.getColumnIndex(VideoStore.Video.VideoColumns._ID);
                    long resumeId = c.getLong(index_id);
                    Video video = getVideoFromId(resumeId);
                    PlayUtils.startVideo(this, video, PlayerActivity.RESUME_FROM_LAST_POS, true, -1, this, -1);
                } else {
                    Toast.makeText(this, R.string.no_resume_available, Toast.LENGTH_LONG).show();
                }
            }
        }
        else if(getString(R.string.action_recently_added).equals(intent.getAction())){
            final BrowserCategoryVideo category = (BrowserCategoryVideo) getSupportFragmentManager().findFragmentById(R.id.category);
            category.getView().post(new Runnable() {
                @Override
                public void run() {
                    closeDrawer();
                    category.goToRecentlyAdded();
                }
            });
        }
        else if(getString(R.string.action_recently_added).equals(intent.getAction())){
            final BrowserCategoryVideo category = (BrowserCategoryVideo) getSupportFragmentManager().findFragmentById(R.id.category);
            category.getView().post(new Runnable() {
                @Override
                public void run() {
                    closeDrawer();
                    category.goToNonScraped();
                }
            });
        }
        else if(getString(R.string.action_recently_played).equals(intent.getAction())){
            final BrowserCategoryVideo category = (BrowserCategoryVideo) getSupportFragmentManager().findFragmentById(R.id.category);
            category.getView().post(new Runnable() {
                @Override
                public void run() {
                    closeDrawer();
                    category.goToRecentlyPlayed();
                }
            });
        }
    }

    private Video getVideoFromId(long resumeId) {
        SingleVideoLoader singleVideoLoader = new SingleVideoLoader(this, resumeId);
        Cursor c = singleVideoLoader.loadInBackground();
        if(c.getCount()>0){
            VideoCursorMapper cursorMapper = new VideoCursorMapper();
            cursorMapper.publicBindColumns(c);
            c.moveToFirst();
            Video video = (Video) cursorMapper.publicBind(c);
            if (c != null) c.close();
            return video;
        }
        if (c != null) c.close();
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mPermissionChecker.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    private final Handler mKeepScreenOnHandler = new Handler(Looper.getMainLooper());
    private final Runnable mKeepScreenOnRunnable = new Runnable() {
        @Override
        public void run() {
            ActiveOperationMonitor.updateKeepScreenOn(MainActivity.this);
            mKeepScreenOnHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        ((CustomApplication) getApplication()).loadLocale();

        mPermissionChecker.checkAndRequestPermission(this);

        // Reload preferences that may have changed
        LoaderUtils.mMustHideWatchedVideo = mPreferences.getBoolean("hide_watched", false);
        LoaderUtils.mSmartRecentlyRows = mPreferences.getBoolean("smart_recently_rows", false);

        LoaderManager.getInstance(this).restartLoader(0, null, mNewVideosActionProvider);
        mKeepScreenOnHandler.post(mKeepScreenOnRunnable);
    }


    @Override
    public void onPause() {
        mKeepScreenOnHandler.removeCallbacks(mKeepScreenOnRunnable);
        ActiveOperationMonitor.clearKeepScreenOn(this);
        

        // Make sure to cancel any request to display the help overlay for the "new videos" action
        // so that it won't be displayed later on top of the new active screen
        if (mNewVideosActionProvider != null) {
            mNewVideosActionProvider.cancelHelpOverlayRequest();
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mThemeChangeListener != null) {
            ThemeManager.getInstance(this).unregisterThemeChangeListener(mThemeChangeListener);
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                launchGlobalResume();
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public int getLayoutID() {
        /*if (Build.DEVICE.equals("shieldtablet") || Build.DEVICE.equals("flounder"))
            return R.layout.browser_main_video_no_coverroll;
        else*/
            return ThemeManager.getInstance(this).isPhoneMode(this)
                    ? R.layout.browser_main_phone : R.layout.browser_main_video;
    }

    @Override
    public int getTitleID() {
        return R.string.nova;
    }

    public View getGlobalBackdropView() {
        return mGlobalBackdrop;
    }

    protected void launchGlobalResume() {
        // Hardware/launcher resume remains available without a home-screen panel.
        handleIntent(new Intent(getString(R.string.action_resume)));
    }

    @Override
    public void startActivityWithResultListener(Intent intent) {
        playLauncher.launch(intent);
    }



    public SearchView getSearchView() { //useful for sftp activity filter
        return mSearchView;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean ret = super.onCreateOptionsMenu(menu);
        /// /setHomeButtonsetHomeButton();

        if (mSearchManager == null) {
            log.error("onCreateOptionsMenu: searchManager is null");
        } else {
            MenuItem item = menu.add(MENU_SEARCH_GROUP, MENU_SEARCH_ITEM, Menu.NONE, R.string.search_title);
            item.setIcon(R.drawable.android29_ic_menu_search_mtrl_alpha);
            item.setShowAsAction((isPhoneHome() ? MenuItem.SHOW_AS_ACTION_ALWAYS
                    : MenuItem.SHOW_AS_ACTION_IF_ROOM) | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            item.setActionView(mSearchView);
            mSearchItem = item;
        }
        MenuItem menuItem = menu.add(MENU_SCRAPER_GROUP, MENU_START_AUTO_SCRAPER_ACTIVITY, Menu.NONE,
                R.string.start_auto_scraper_activity);
        menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // below line removed to avoid warning W/ActionProvider(support): setVisibilityListener: Setting a new ActionProvider.VisibilityListener when one is already set. Are you reusing this NewVideosActionProvider instance while it is still in use somewhere else?
        //MenuItemCompat.setActionProvider(menuItem, mNewVideosActionProvider);
        mNewVideosActionProvider.manageVisibility(menuItem);

        if (!isPhoneHome()) {
            menuItem = menu.add(MENU_PRIVATE_MODE_GROUP, MENU_PRIVATE_MODE_ITEM, Menu.CATEGORY_SECONDARY, R.string.activate_private_mode);
            menuItem.setIcon(R.drawable.ic_menu_private_mode);
            menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }

        if (isPhoneHome()) {
            // these used to be tiles of the home page: they are now action bar items (shown on the home page only)
            final android.content.res.ColorStateList white = android.content.res.ColorStateList.valueOf(0xFFFFFFFF);
            MenuItem prefs = menu.add(MENU_PREFERENCES_GROUP, MENU_PREFERENCES_ITEM, Menu.NONE, R.string.preferences);
            prefs.setIcon(R.drawable.android29_ic_settings);
            androidx.core.view.MenuItemCompat.setIconTintList(prefs, white);
            prefs.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

            // Home action bar: app label, then Re-scan, then Settings
            MenuItem rescan = menu.add(MENU_PREFERENCES_GROUP, MENU_RESCAN_ITEM, Menu.NONE, R.string.rescan);
            rescan.setIcon(R.drawable.android29_ic_rescan);
            androidx.core.view.MenuItemCompat.setIconTintList(rescan, white);
            rescan.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            MenuItem theme = menu.add(MENU_PREFERENCES_GROUP, MENU_HOME_THEME_ITEM, Menu.NONE, R.string.home_background_theme);
            theme.setTitle(R.string.phone_home_settings);
            theme.setIcon(R.drawable.android29_ic_settings);
            androidx.core.view.MenuItemCompat.setIconTintList(theme, white);
            theme.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            // same condition the sponsor tile used to have
            boolean sponsorPref = com.archos.mediacenter.video.BuildConfig.ENABLE_SPONSOR
                    && PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                            com.archos.mediacenter.video.utils.VideoPreferencesCommon.KEY_ENABLE_SPONSOR,
                            com.archos.mediacenter.video.utils.VideoPreferencesCommon.ENABLE_SPONSOR_DEFAULT);
            if (com.archos.mediacenter.video.BuildConfig.ENABLE_SPONSOR
                    && (!com.archos.environment.ArchosUtils.isInstalledfromPlayStore(getApplicationContext()) || sponsorPref)) {
                MenuItem sponsor = menu.add(MENU_PREFERENCES_GROUP, MENU_SPONSOR_ITEM, Menu.CATEGORY_SECONDARY, R.string.sponsor);
                sponsor.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            }
        }

        return true;
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        boolean prepared = super.onPreparePanel(featureId, view, menu);
        // Hide fragment sort/view actions only on the phone home page.
        View home = findViewById(R.id.category_container);
        if (isPhoneHome() && home != null && home.getVisibility() == View.VISIBLE) {
            MenuItem sort = menu.findItem(Browser.MENU_VIEW_MODE);
            if (sort != null) sort.setVisible(false);
        }
        return prepared;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean scraperVisible = true;
        // Allow to launch the auto scraper activity only if it is not currently running
        if (((CustomApplication) getApplication()).isAutoScraperActive()) {
            scraperVisible = false;
        }
        mNewVideosActionProvider.setEnabled(scraperVisible);

        MenuItem item = menu.findItem(MENU_PRIVATE_MODE_ITEM);
        if (item != null) {
            item.setTitle(PrivateMode.isActive() ? R.string.deactivate_private_mode : R.string.activate_private_mode);
        }
        // Rescan / Settings only make sense on the phone home page, where the action bar is just
        // the app label + those two buttons: search and the overflow items (Preferences, Help, Sponsor) stay out of it
        final boolean homeShowing = isPhoneHome() && findViewById(R.id.category_container).getVisibility() == View.VISIBLE;
        for (int id : new int[]{MENU_RESCAN_ITEM}) {
            MenuItem phoneItem = menu.findItem(id);
            if (phoneItem != null) phoneItem.setVisible(homeShowing);
        }
        for (int id : new int[]{MENU_PREFERENCES_ITEM, MENU_HELP_ITEM, MENU_SPONSOR_ITEM}) {
            MenuItem phoneItem = menu.findItem(id);
            if (phoneItem != null) phoneItem.setVisible(false);
        }
        MenuItem searchMenuItem = menu.findItem(MENU_SEARCH_ITEM);
        if (searchMenuItem != null) searchMenuItem.setVisible(!homeShowing);

        MenuItem themeItem = menu.findItem(MENU_HOME_THEME_ITEM);
        if (themeItem != null) themeItem.setVisible(homeShowing);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean ret = super.onOptionsItemSelected(item);

        switch (item.getItemId()) {
            case MENU_START_AUTO_SCRAPER_ACTIVITY:
                // Search all the videos in the database
                Intent as = new Intent(Intent.ACTION_MAIN);
                as.setComponent(new ComponentName(this, AutoScraperActivity.class));
                startActivity(as);
                break;

            case MENU_PREFERENCES_ITEM:
                startPreference();
                break;

            case MENU_HOME_THEME_ITEM:
                showHomeBackgroundPicker();
                return true;

            case MENU_RESCAN_ITEM: {
                Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
                if (category instanceof BrowserCategory) ((BrowserCategory) category).showRescanPopup();
                else com.archos.mediaprovider.video.NetworkAutoRefresh.forceRescan(this);
                break;
            }

            case MENU_HELP_ITEM:
                com.archos.mediacenter.video.utils.WebUtils.openWebLink(this, getString(R.string.faq_url));
                break;

            case MENU_SPONSOR_ITEM:
                com.archos.mediacenter.video.utils.WebUtils.openWebLink(this, getString(R.string.sponsor_url));
                break;

            case MENU_PRIVATE_MODE_ITEM:
                if (!PrivateMode.isActive() && PrivateMode.canShowDialog(this)) {
                    PrivateMode.showDialog(this);
                }
                PrivateMode.toggle(this);
                setBackground();
                //setHomeButton();
                break;
            case android.R.id.home:
                boolean drawerHandled = false;
                if (mDrawerLayout != null && mDrawerToggle != null) {
                    try {
                        drawerHandled = mDrawerToggle.onOptionsItemSelected(item);
                    } catch (Throwable t) {
                        log.error("onOptionsItemSelected: caught exception in drawer toggle", t);
                    }
                }
                if (!drawerHandled)
                    getOnBackPressedDispatcher().onBackPressed();
                break;

            }

        return ret;
    }

    public void startPreference(){
        Intent p = new Intent(Intent.ACTION_MAIN);
        p.setComponent(new ComponentName(this, VideoPreferencesActivity.class));
        // Save the uimode_leanback to check if it changed when back from preferences
        mCurrentUiModeLeanback = PreferenceManager.getDefaultSharedPreferences(this).getString(UiChoiceDialog.UI_CHOICE_LEANBACK_KEY, "-");
        preferencesLauncher.launch(p);
    }

    /**
     * For DEMO purpose only: Reset scraper info for all movies
     */
    private void resetScraperInfo() {
        ContentValues cv = new ContentValues(2);
        cv.put(VideoStore.Video.Media.ARCHOS_MEDIA_SCRAPER_ID, 0);
        cv.put(VideoStore.Video.Media.ARCHOS_MEDIA_SCRAPER_TYPE, 0);
        getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, cv, null, null);
        Toast.makeText(this, "Movie info reset done", Toast.LENGTH_SHORT).show();
    }

    public void updateHomeIcon(boolean show) {
        if (isPhoneHome()) {
            show = !safeIsDrawerOpen();
        }
        if (mDrawerLayout != null && mDrawerToggle != null) {
            try {
                mDrawerToggle.setDrawerIndicatorEnabled(!show);
                return;
            } catch (Throwable t) {
                log.error("updateHomeIcon: caught exception", t);
                disableDrawer();
            }
        }
        getSupportActionBar().setDisplayHomeAsUpEnabled(show);
        getSupportActionBar().setHomeButtonEnabled(show);
    }

    public void closeDrawer() {
        safeCloseDrawer();
    }

    private void disableDrawer() {
        if (mDrawerLayout != null) {
            try {
                mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            } catch (Throwable ignored) {}
        }
        mDrawerToggle = null;
    }

    private void safeOpenDrawer() {
        if (isPhoneHome()) {
            setPhoneHomeVisible(true);
            return;
        }
        if (mDrawerLayout != null) {
            try {
                mDrawerLayout.openDrawer(GravityCompat.START);
            } catch (Throwable t) {
                log.error("safeOpenDrawer: caught exception opening drawer", t);
                disableDrawer();
            }
        }
    }

    private void safeCloseDrawer() {
        if (isPhoneHome()) {
            setPhoneHomeVisible(false);
            return;
        }
        if (mDrawerLayout != null) {
            try {
                mDrawerLayout.closeDrawer(GravityCompat.START);
            } catch (Throwable t) {
                log.error("safeCloseDrawer: caught exception closing drawer", t);
                disableDrawer();
            }
        }
    }

    private boolean safeIsDrawerOpen() {
        if (isPhoneHome())
            return findViewById(R.id.category_container).getVisibility() == View.VISIBLE;
        if (mDrawerLayout != null) {
            try {
                return mDrawerLayout.isDrawerOpen(GravityCompat.START);
            } catch (Throwable t) {
                log.error("safeIsDrawerOpen: caught exception checking drawer state", t);
                disableDrawer();
            }
        }
        return false;
    }

    public boolean isPhoneHome() {
        View home = findViewById(R.id.category_container);
        return home != null && "phone_home".equals(home.getTag());
    }

    /** Phone: go back to the home page (used by the cinema layout's bottom source row). */
    public void showPhoneHome() {
        if (isPhoneHome()) setPhoneHomeVisible(true);
    }

    private void setPhoneHomeVisible(boolean visible) {
        findViewById(R.id.category_container).setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.content).setVisibility(visible ? View.GONE : View.VISIBLE);
        Fragment content = getSupportFragmentManager().findFragmentById(R.id.content);
        if (content != null) content.setMenuVisibility(!visible);
        invalidateOptionsMenu();
        if (visible) {
            setNavigationMode(androidx.appcompat.app.ActionBar.NAVIGATION_MODE_STANDARD);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            getSupportActionBar().setTitle(R.string.nova);   // Home action bar: app label "SuperNova"
            hideSeachView();
        }
        updateHomeIcon(!visible);
        updatePhoneHomeActionBar();
    }

    public void showHomeBackgroundPicker() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) {
            ((BrowserCategory) category).startContent(new PhoneHomeSettingsFragment());
        }
    }

    void refreshHomeMetadata() {
        if (!AutoScrapeService.isEnable(this)) return;
        Intent refresh = new Intent(AutoScrapeService.RESCAN_EVERYTHING, null, this, AutoScrapeService.class);
        refresh.putExtra(AutoScrapeService.RESCAN_EVERYTHING, true);
        refresh.putExtra(AutoScrapeService.RESCAN_ONLY_DESC_NOT_FOUND, false);
        startService(refresh);
        Toast.makeText(this, R.string.rescrap_in_progress, Toast.LENGTH_SHORT).show();
    }

    /** Settings > Home layout: cards in the Select Source row. */
    void showHomeSourcesPicker() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) ((BrowserCategory) category).showHomeSourcesPicker();
    }

    /** Settings > Re-scan. */
    void showHomeRescan() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) ((BrowserCategory) category).showRescanPopup();
    }

    /** Settings: bottom pill switched on or off. */
    void updateHomeBottomPill() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) ((BrowserCategory) category).applyBottomPill();
    }

    /** Settings > Home layout: Select Source row and the rows below it. */
    void showHomeRowsPicker() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) ((BrowserCategory) category).showHomeRowsPicker();
    }

    /** Settings: the Home movies and TV shows grid switched on or off. */
    void updateHomeGrid() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) ((BrowserCategory) category).refreshHomeGrid();
    }

    void updateHomeSourcePin() {
        Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
        if (category instanceof BrowserCategory) {
            ((BrowserCategory) category).applyHomeSourcePin(category.getView());
        }
    }

    /** Settings > Movie details page: the standard phone page, or the real TV details page (TV layout). */
    void showDetailsStylePicker() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean tvLayout = com.archos.mediacenter.video.info.VideoInfoActivity.isTvLayoutEnabled(this);
        final boolean page3 = com.archos.mediacenter.video.info.VideoInfoActivity.isPage3Enabled(this);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.phone_details_style)
                .setSingleChoiceItems(new CharSequence[]{getString(R.string.phone_details_style_standard),
                        getString(R.string.phone_details_style_tvlayout),
                        getString(R.string.phone_details_style_page3)},
                        tvLayout ? 1 : page3 ? 2 : 0,
                        (dialog, which) -> {
                            preferences.edit()
                                    .putBoolean(com.archos.mediacenter.video.info.VideoInfoActivity.PREF_TV_LAYOUT_DETAILS, which == 1)
                                    .putBoolean(com.archos.mediacenter.video.info.VideoInfoActivity.PREF_PHONE_DETAILS_PAGE3, which == 2)
                                    .apply();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void showPosterTapPicker() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.phone_poster_tap)
                .setSingleChoiceItems(new CharSequence[]{getString(R.string.phone_poster_details),
                        getString(R.string.phone_poster_resume)},
                        preferences.getBoolean(HomePosterAdapter.POSTER_RESUME, false) ? 1 : 0,
                        (dialog, which) -> {
                            preferences.edit().putBoolean(HomePosterAdapter.POSTER_RESUME, which == 1).apply();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void showHomeColorPicker(boolean cards) {
        final int[] colors = {cards ? 0xFF1F2B44 : DEFAULT_HOME_BACKGROUND, 0xFF000000, 0xFF202124,
                0xFF102A43, 0xFF12332E, 0xFF2D1B3D, 0xFF3B2028, 0xFF33271D};
        final String[] names = getResources().getStringArray(R.array.home_background_names);
        String key = cards ? BrowserCategory.HOME_CARD_COLOR : HOME_BACKGROUND_COLOR;
        int current = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt(key, colors[0]);
        int selected = 0;
        for (int i = 0; i < colors.length; i++) if (colors[i] == current) selected = i;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(cards ? R.string.home_card_color : R.string.home_background_theme)
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putInt(key, colors[which]).apply();
                    Fragment category = getSupportFragmentManager().findFragmentById(R.id.category);
                    if (cards && category instanceof BrowserCategory) ((BrowserCategory) category).refreshSourceCardColors();
                    if (!cards && category instanceof BrowserCategory) ((BrowserCategory) category).applyHomeSourcePin(category.getView());   // bottom row follows the background
                    updatePhoneHomeActionBar();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateSourceActionBar() {
        var actionBar = getSupportActionBar();
        View toolbar = findViewById(R.id.main_toolbar);
        if (actionBar == null || toolbar == null) return;

        // The action bar is shown on every page, Home included (app label + Re-scan + Settings).
        actionBar.show();
        toolbar.setVisibility(View.VISIBLE);
    }

    /** Movies / TV shows grid pages ask for a refresh of the action bar; the style itself follows the page shown in the content area. */
    public void setHomeStyleToolbar(boolean on, Object owner) {
        updatePhoneHomeActionBar();
    }

    /** Pages that use the Home background and action bar: Movies, TV shows, All videos, Recently added, Played, storages, network. */
    private static boolean isHomeStyleFragment(androidx.fragment.app.Fragment f) {
        if (f instanceof com.archos.mediacenter.video.browser.BrowserByIndexedVideos.CursorBrowserByVideo
                && ((com.archos.mediacenter.video.browser.BrowserByIndexedVideos.CursorBrowserByVideo) f).isHomeStylePage())
            return true;
        return f instanceof com.archos.mediacenter.video.browser.BrowserByIndexedVideos.BrowserAllVideos
                || f instanceof com.archos.mediacenter.video.browser.BrowserByIndexedVideos.BrowserLastAdded
                || f instanceof com.archos.mediacenter.video.browser.BrowserByIndexedVideos.BrowserLastPlayed
                || f instanceof com.archos.mediacenter.video.browser.filebrowsing.BrowserByFolder      // internal / external storage, USB, network folders
                || f instanceof com.archos.mediacenter.video.browser.filebrowsing.network.NewRootFragment
                || f instanceof com.archos.mediacenter.video.browser.filebrowsing.network.NetworkHubFragment;
    }

    /**
     * Fragments call this on resume instead of painting the theme colour on the action bar themselves:
     * on the phone the activity chooses between the Home colour and the theme colour.
     */
    public static void applyThemeToolbarColor(android.app.Activity activity) {
        if (!(activity instanceof androidx.appcompat.app.AppCompatActivity)) return;
        final androidx.appcompat.app.AppCompatActivity compat = (androidx.appcompat.app.AppCompatActivity) activity;
        if (compat.getSupportActionBar() == null) return;
        if (compat instanceof MainActivity && ((MainActivity) compat).isPhoneHome()) {
            ((MainActivity) compat).updatePhoneHomeActionBar();
            return;
        }
        compat.getSupportActionBar().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(ThemeManager.getInstance(compat).getToolbarBackgroundColor()));
    }

    private void postHomeBarUpdate() {
        View bar = findViewById(R.id.main_toolbar);
        if (bar != null) bar.post(this::updatePhoneHomeActionBar);
    }

    /** True while one of those pages is the one shown in the content area. */
    private boolean isHomeStyleContentShown() {
        androidx.fragment.app.Fragment content = getSupportFragmentManager().findFragmentById(R.id.content);
        return content != null && content.isAdded() && !content.isHidden() && isHomeStyleFragment(content);
    }

    /** Keep the Classic Home background and toolbar consistent. */
    public void updatePhoneHomeActionBar() {
        if (!isPhoneHome() || getSupportActionBar() == null) return;
        View home = findViewById(R.id.category_container);
        View toolbar = findViewById(R.id.main_toolbar);
        boolean homeVisible = home.getVisibility() == View.VISIBLE;
        if (homeVisible) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
            getSupportActionBar().setTitle(R.string.nova);   // Home action bar: app label "SuperNova"
        }
        int homeColor = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt(HOME_BACKGROUND_COLOR, DEFAULT_HOME_BACKGROUND);
        home.setBackgroundColor(homeColor);
        int pageColor = homeColor;
        if (Build.VERSION.SDK_INT < 35) getWindow().setStatusBarColor(pageColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getWindow().setStatusBarContrastEnforced(false);
        getWindow().getDecorView().setBackgroundColor(pageColor);
        // DrawerLayout paints its own status-bar inset over the window background.
        // This is the visible surface with edge-to-edge on Android 15 and later.
        if (mDrawerLayout != null) mDrawerLayout.setStatusBarBackgroundColor(pageColor);
        MiscUtils.applyStatusBarIconContrast(new androidx.core.view.WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView()), pageColor);
        final boolean homeStyle = homeVisible || isHomeStyleContentShown();
        int color = homeStyle ? homeColor : ThemeManager.getInstance(this).getToolbarBackgroundColor();
        getSupportActionBar().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color));
        toolbar.setBackgroundColor(color);
        float elevation = homeStyle ? 0f : getResources().getDimension(R.dimen.toolbar_default_elevation);
        getSupportActionBar().setElevation(elevation);
        androidx.core.view.ViewCompat.setElevation(toolbar, elevation);
        updateSourceActionBar();
        android.widget.RelativeLayout.LayoutParams toolbarParams =
                (android.widget.RelativeLayout.LayoutParams) toolbar.getLayoutParams();
        toolbarParams.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        toolbarParams.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
        toolbarParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        toolbar.setLayoutParams(toolbarParams);
        if (home.getLayoutParams() instanceof android.widget.RelativeLayout.LayoutParams) {
            android.widget.RelativeLayout.LayoutParams params =
                    (android.widget.RelativeLayout.LayoutParams) home.getLayoutParams();
            params.removeRule(android.widget.RelativeLayout.BELOW);
            params.removeRule(android.widget.RelativeLayout.ABOVE);
            params.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            params.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            // Home and the other pages both sit below the action bar
            params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
            params.addRule(android.widget.RelativeLayout.BELOW, R.id.main_toolbar);
            home.setLayoutParams(params);
        }
        toolbar.bringToFront();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (isPhoneHome()) state.putBoolean("phone_home_visible", safeIsDrawerOpen());
        super.onSaveInstanceState(state);
    }

    @Override
    public void goHome() {
        super.goHome();
        if (isPhoneHome()) setPhoneHomeVisible(true);
    }

    public void hideSeachView() {
        if(mSearchItem!=null)
            mSearchItem.collapseActionView();
    }

    //delegating to activity because getNavigationMode on support action bar doesn't work anymore
    @SuppressWarnings("deprecation") // ActionBar navigation mode
    public void setNavigationMode(int navigationMode) {
        getSupportActionBar().setNavigationMode(navigationMode);
        mNavigationMode = navigationMode;
    }

    public int getNavigationMode(){
        return mNavigationMode;
    }







    public void reloadBrowserByVideoFolder() {
        BrowserCategory category = (BrowserCategory) getSupportFragmentManager().findFragmentById(R.id.category);
        Fragment f = new BrowserByVideoFolder();
        category.loadFragmentAfterStackReset(f);
    }

    // ====================== UiChoiceDialog ====================

    // Only used to skip the UiChoiceDialog check for a few hardware keys; it does not consume
    // or otherwise intercept the BACK event (always delegates to super), so there is no
    // OnBackPressedCallback/predictive-back equivalent to migrate to here.
    @SuppressLint("GestureBackNavigation")
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        boolean ignore = false;

        // Special case: BACK button on touchscreen generate a keyboard event... Let's ignore it...
        if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            ignore = true;
        }

        // Special case: Vol- and Vol+ buttons on phone generate a keyboard event... Let's ignore it...
        if (ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            ignore = true;
        }

        // Special case: many phones also have an hardware MENU key...
        if (ev.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            ignore = true;
        }

        if (!ignore) {
            checkUiChoice(ev);
        }
        return super.dispatchKeyEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        checkUiChoice(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        checkUiChoice(ev);
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        checkUiChoice(ev);
        return super.dispatchTrackballEvent(ev);
    }

    static boolean sUiChoiceCheckDone = false;

    /**
     * Check if the input event make us think that user is on TV.
     * If it is the case and if the Ui mode is not setup, then we propose to try the TV UI.
     * @param event
     */
    private void checkUiChoice(InputEvent event) {

        // Make sure we go through this method only once
        if (sUiChoiceCheckDone) {
            return;
        }
        sUiChoiceCheckDone = true;

        boolean probablyTv = false;

        switch (event.getSource()) {
            // All these case mean the user is probably on TV
            case InputDevice.SOURCE_KEYBOARD:
            case InputDevice.SOURCE_TOUCHPAD:
            case InputDevice.SOURCE_DPAD:
            case InputDevice.SOURCE_GAMEPAD:
            case InputDevice.SOURCE_JOYSTICK:
            case InputDevice.SOURCE_HDMI:
                if (log.isDebugEnabled()) log.debug("event source = {} -> probably TV", event.getSource());
                probablyTv = true;
                break;
            case InputDevice.SOURCE_STYLUS:
            case InputDevice.SOURCE_TOUCHSCREEN:
            case InputDevice.SOURCE_TRACKBALL:
            case InputDevice.SOURCE_MOUSE:
            default:
                if (log.isDebugEnabled()) log.debug("event source = {} -> probably not TV", event.getSource());
                probablyTv = false;
                break;
        }

        if (!probablyTv) {
            return;
        }

        final String uiMode = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(UiChoiceDialog.UI_CHOICE_LEANBACK_KEY, "unset");

        // If the choice has not been done yet, ask user
        if (uiMode.equals("unset") &&
             !getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) { // no UI choice to do on actual AndroidTV devices
            new UiChoiceDialog().show(getSupportFragmentManager(), "UiChoiceDialog");
        }
    }
}
