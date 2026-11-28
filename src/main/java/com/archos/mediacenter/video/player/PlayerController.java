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

package com.archos.mediacenter.video.player;

import static androidx.core.content.ContextCompat.getDrawable;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.preference.PreferenceManager;
import androidx.appcompat.app.ActionBar;

import com.archos.mediacenter.video.leanback.overlay.Clock;

import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.archos.mediacenter.utils.RepeatingImageButton;
import com.archos.mediacenter.utils.MediaUtils;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.player.tvmenu.TVCardDialog;
import com.archos.mediacenter.video.player.tvmenu.TVCardView;
import com.archos.mediacenter.video.player.tvmenu.TVMenuAdapter;
import com.archos.mediacenter.video.player.tvmenu.TVUtils;
import com.archos.mediacenter.video.utils.MiscUtils;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;

import com.archos.environment.ArchosFeatures;
import static com.archos.environment.ArchosFeatures.isChromeOS;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A view containing controls for a MediaPlayer. Typically contains the
 * buttons like "Play/Pause", "Rewind", "Fast Forward" and a progress
 * slider. It takes care of synchronizing the controls with the state
 * of the MediaPlayer.
 * <p>
 * The way to use this class is to instantiate it programatically.
 * The PlayerController will create a default set of controls
 * and put them in a window floating above your application. Specifically,
 * the controls will float above the view specified with setAnchorView().
 * The window will disappear if left idle for three seconds and reappear
 * when the user touches the anchor view.
 * <p>
 * Functions like show() and hide() have no effect when PlayerController
 * is created in an xml layout.
 *
 * PlayerController will hide and
 * show the buttons according to these rules:
 * </ul>
 * <li> The "previous" and "next" buttons are hidden until setPrevNextListeners()
 *   has been called
 * <li> The "previous" and "next" buttons are visible but disabled if
 *   setPrevNextListeners() was called with null listeners
 * <li> The "rewind" and "fastforward" buttons are shown unless requested
 *   otherwise by using the PlayerController(Context, boolean) constructor
 *   with the boolean set to false
 * </ul>
 */

public class PlayerController implements View.OnTouchListener, OnGenericMotionListener, GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener
{
    private static final Logger log = LoggerFactory.getLogger(PlayerController.class);
    private static final boolean DBG_ALWAYS_SHOW = false;

    private static final int MSG_FADE_OUT = 1;
    private static final int MSG_SHOW_PROGRESS = 2;
    private static final int MSG_SEEK = 3;
    private static final int MSG_SEEK_RESUME = 4;
    private static final int MSG_SWITCH_VIDEO_FORMAT = 5;
    private static final int MSG_HIDE_SYSTEM_BAR = 6;
    private static final int MSG_OVERLAY_FADE_OUT = 7;
    private static final int MSG_SWITCH_FULLSCREEN_WITH_CUTOUT = 8;

    // introducing states to inform listeners/player that it is in some specific state
    public static final int STATE_NORMAL = 0;
    public static final int STATE_SEEK = 1;
    public static final int STATE_OTHER = 42;

    public static final int SEEK_LONG_INIT_DELAY = 200;
    public static final int SEEK_LONG_DELAY = 50;
    private static final int SEEK_ACCEL_MSECS[]  = {5000, 15000, 30000, 60000};
    private static final int SEEK_SHORT_MSEC     = SEEK_ACCEL_MSECS[0];
    private static final int SEEK_ACCEL_PERMIL[] = {10, 20, 40, 80};
    private static final int SEEK_SHORT_PERMIL   = SEEK_ACCEL_PERMIL[0];
    private static final int SEEK_ACCEL_STEPS[]  = {0, 3000, 6000, 9000};
    
    private static final int SEEK_RESUME_DELAY = 80;
    public static final int SEEK_PROGRESS_THRESHOLD = 4;
    public static final int SEEK_PROGRESS_TIME_THRESHOLD = 10; //ms

    protected static final int SHOW_TIMEOUT = 3000;
    /** Compact UI: the control area goes away after 5 s without activity (only while the video is playing). */
    private static final int MINI_SHOW_TIMEOUT = 5001;
    protected static final float SIDE_SIZE_RATIO = (float) 0.25;

    protected static final int  FLAG_SIDE_CONTROL_BAR = 0x01;
    protected static final int  FLAG_SIDE_SYSTEM_BAR = 0x02;
    protected static final int  FLAG_SIDE_ACTION_BAR = 0x04;
    protected static final int  FLAG_SIDE_VOLUME_BAR = 0x08;
    protected static final int  FLAG_SIDE_UNLOCK_INSTRUCTIONS = 0X10;
    protected static final int FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS = FLAG_SIDE_CONTROL_BAR|FLAG_SIDE_SYSTEM_BAR|FLAG_SIDE_ACTION_BAR|FLAG_SIDE_VOLUME_BAR;
    private  static final int  FLAG_SIDE_ALL = FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS | FLAG_SIDE_UNLOCK_INSTRUCTIONS;
    private static final String PLAYER_HELP_OVERLAY_KEY = "play_help_overlay";

    private static final int STATUS_BAR_DISABLE_NOTIFICATION_ICONS = 0x00020000;
    private static final int STATUS_BAR_DISABLE_NOTIFICATION_ALERTS = 0x00040000;
    private static final int STATUS_BAR_DISABLE_NOTIFICATION_TICKER = 0x00080000;

    final private Context       mContext;
    private IPlayerControl      mPlayer;
    private Window              mWindow;
    private int                 mLayoutWidth, mLayoutHeight;
    public int floatingPlayerSize;

    private int                 mActionBarHeight = 0;
    private int                 mSystemBarHeight = 0;
    private SurfaceController   mSurfaceController;
    private Settings            mSettings;
    private View                mRootView, mControllerView, mControllerViewLeft,mControllerViewRight;
    private ViewGroup           mPlayerView;
    private ActionBar           mActionBar;

    private TextView            mVideoTitle;

    private View                mControlBar;
    private View                mControlBar2;
    private ImageButton         mPauseButton;
    private ImageButton         mPauseButton2;
    private ImageButton         mBackwardButton;
    private ImageButton         mForwardButton;
    private ImageButton         mFormatButton;
    private ImageButton         mFullscreenWithCutoutButton;
    //private ImageButton         mBackwardButton2;
    //private ImageButton         mForwardButton2;
    //private ImageButton         mFormatButton2;
    private TextView            mOsdLeftTextView;
    private TextView            mOsdRightTextView;
    private ImageView           mCentralPlayIcon;
    private float               scrollGestureVertical = 0f;
    private float               scrollGestureHorizontal = 0f;
    private final float         BORDER_WIDTH = MiscUtils.dp2Px(24);
    private final float         SCROLL_THRESHOLD = MiscUtils.dp2Px(16);
    private final float         SCROLL_ANGLE = 30;
    private SeekBar             mProgress;
    private SeekBar             mProgress2;
    private TextView            mEndTime, mCurrentTime;
    private TextView            mEndTime2, mCurrentTime2;
    private View                mSeekState;
    private View                mSeekState2;
    private TextView            mClock;
    private SimpleDateFormat    mDateFormat;
    private boolean				splitView;

    private View                mVolumeBar;
    private View                mVolumeBar2;
    private SeekBar             mVolumeLevel;
    private RepeatingImageButton mVolumeUpButton;
    private RepeatingImageButton mVolumeDownButton;
    private SeekBar             mVolumeLevel2;
    private RepeatingImageButton mVolumeUpButton2;
    private RepeatingImageButton mVolumeDownButton2;

    private StringBuilder       mFormatBuilder;
    private Formatter           mFormatter;
    private boolean             mEnabled;
    private boolean             mDragging;
    private boolean             mSeekComplete;
    private int                 mSeekKeyDirection;
    private int                 mBarXYIconResource = R.drawable.video_format_arrow_horizontal;

    private static boolean      mControlBarShowing, mSystemBarShowing, mSystemBarGone, mActionBarShowing, mVolumeBarShowing, mNavigationBarShowing, mIsNavBarOnBottom, mIsGestureAreaShowing;
    private static int          mGestureAreaHeight, mControlBarHeight;
    // Static handle to the live control bar view, so SubtitleManager can resolve its parent
    // for getControlBarClearanceAbove(). Cleared in detachWindow() to avoid leaking the view.
    @SuppressLint("StaticFieldLeak")
    private static View          sControlBarView;
    // Tallest height control_bar reached since it was last shown. The bar's height jitters while
    // visible (seek spinner toggling, end-time text with/without the clock glyph), and subtitles
    // must not follow that jitter, so clearance is computed from this sticky value instead.
    // Reset when the bar hides and on detach.
    private static int           sControlBarMaxHeight = 0;

    private boolean             mVolumeBarEnabled = false;

    private boolean             mSeekWasPlaying;
    private boolean mShortSeek;
    private boolean mSeekKeptPlaying;
    /** A newer short-seek target arrived while a seek was in flight; send it the moment the decoder is free. */
    private boolean mPendingSeek;
    private boolean             mIsStopped = true;
    private boolean             mJoystickSeekingActive = false;
    private int                 mJoystickZone = MediaUtils.JOYSTICK_ZONE_CENTER;
    private int                 mSeekAccelStepCount = SEEK_ACCEL_STEPS.length;

    private AudioManager        mAudioManager;
    private Toast               mToast = null;
    private int                 mNextSeek;
    private int                 mSeekDir;
    private int                 mLongSeekTime;
    private int                 mLastRelativePosition;
    private int                 mLastSeek;
    private long                mLastProgressTime;
    private int                 mLastProgress;
    private Rect                mLastCrop = new Rect();
    private int                 mSystemUiVisibility;
    private WindowInsetsControllerCompat mInsetsController;
    private int 				UIMode;
    private int 				testSwitchView=0;

    private TVMenuAdapter mTVMenuAdapter;
    private View				mTVMenuView;
    private View                mTVMenuView2;
    private boolean				isTVMenuDisplayed;
    private View                mTVMenuContainer;
    private View                mTVMenuContainer2;
    private boolean             manualVisibilityChange;
    private FrameLayout         playerControllersContainer;
    private TVCardDialog tvCardDialog = null;

    private boolean             isTVMode = false;
    private long                mLastTouchEventTime = -1;
    private View mPlayPauseTouchZone;
    private boolean mPlayPauseOnTouchActivated = false;
    private static boolean mFullScreenWithCutout = true;

    private GestureDetector gestureDetector;
    private final GestureDetector mStandardTapDetector;
    private float currentBrightness;
    public static int pauseTimeout = 0;

    public interface Settings {
        void switchSubtitleTrack();
        void switchAudioTrack();
        void setSubtitleDelay(int delay);
    }

    public interface OnControlBarVisibilityListener {
        void onControlBarVisibilityChanged(boolean visible);
    }

    private OnControlBarVisibilityListener mOnControlBarVisibilityListener;

    public void setOnControlBarVisibilityListener(OnControlBarVisibilityListener listener) {
        mOnControlBarVisibilityListener = listener;
    }

    @SuppressLint("InflateParams") // mVideoTitle is detached view for ActionBar setCustomView
    @SuppressWarnings("deprecation") // setStatusBarColor: pre-API 35 fallback
    public PlayerController(Context context, Window window, ViewGroup playerView, SurfaceController surfaceController, Settings settings, ActionBar actionBar) {
        mContext = context;
        mSurfaceController = surfaceController;
        splitView = false;
        mSettings = settings;

        gestureDetector = new GestureDetector(mContext, this);
        mStandardTapDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!canHandleStandardTap()) return false;
                toggleMediaControlsVisiblity(FLAG_SIDE_CONTROL_BAR);
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP && canHandleStandardTap()) {
                    handleStandardDoubleTap(event);
                }
                return true;
            }
        });
        UIMode	= VideoEffect.getDefaultMode();
        mWindow = window;

        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        mPlayerView = playerView;
        mActionBar = actionBar;

        mVideoTitle = (TextView) inflater.inflate(R.layout.video_title, null);
        // Default to GONE, enable it if you want it
        mVideoTitle.setVisibility(View.GONE);
        if (mActionBar != null) {
            mActionBar.setDisplayShowCustomEnabled(true);
            mActionBar.setCustomView(mVideoTitle);
        }
        manualVisibilityChange=false;

        mSystemUiVisibility = 0;
        WindowCompat.setDecorFitsSystemWindows(mWindow, false);
        // setStatusBarColor is deprecated and ignored on Android 15+ (edge-to-edge enforced,
        // bar is already transparent there)
        if (Build.VERSION.SDK_INT < 35) {
            mWindow.setStatusBarColor(Color.TRANSPARENT); // FLAG_TRANSLUCENT_STATUS replacement: keep status bar transparent when visible
        }
        mInsetsController = WindowCompat.getInsetsController(mWindow, mWindow.getDecorView());
        mInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        // Do not hide bars here — old code only set LAYOUT_* flags + IMMERSIVE behavior, not FULLSCREEN|HIDE_NAVIGATION.
        // Bars are hidden later via showSystemBar(false) / MSG_HIDE_SYSTEM_BAR.
        manualVisibilityChange=true;

        if (mActionBar != null) {
            /* Hack:
             * dispatch touch events to the VolumeBar that is under the ActionBar
             */
            setRecursiveOnTouchListener(new OnTouchListener() {
            
                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // TODO Auto-generated method stub
                    if (mVolumeBar!= null && event.getX() <= mVolumeBar.getWidth()) {
                        return mVolumeBar.dispatchTouchEvent(event);
                    }
                    return false;
                }
            }, MiscUtils.getActionBarView(mWindow));
            mActionBar.getCustomView().setOnTouchListener(new View.OnTouchListener() {

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (mVolumeBar!= null && event.getX() <= mVolumeBar.getWidth()) {
                        return mVolumeBar.dispatchTouchEvent(event);
                    }
                    return false;
                }
            });
        }
    }

    private boolean isInfoActivityDisplayed() {
        if(mContext instanceof PlayerActivity)
            return ((PlayerActivity)mContext).isInfoActivityDisplayed();
        return false;
    }

    public void reset() {
        clearPhoneGesture();
        mHandler.removeCallbacks(hideMobileVolumeValue);
        hideMobileVolumeValue.run();
        mDragging = false;
        mSeekWasPlaying = false;
        mShortSeek = false;
        mSeekKeptPlaying = false;
        mIsStopped = false;
        mSeekComplete = true;
        mLastRelativePosition = mNextSeek = mLastSeek = -1;
        mLastCrop.bottom = mLastCrop.left = mLastCrop.right = mLastCrop.top = -1;
        mActionBarShowing = mControlBarShowing = mVolumeBarShowing = mSystemBarGone = false;
        mSystemBarShowing = true;
        mSeekKeyDirection = 0;
        mLastProgressTime = mLastProgress = 0;
        if (mControlBar != null)
            mControlBar.setVisibility(View.INVISIBLE);
        if (mVolumeBar != null) {
            mVolumeBar.setVisibility(View.GONE);
        }
        if (mControlBar2 != null)
            mControlBar2.setVisibility(View.INVISIBLE);
        updateStandardTopTitle(mControllerViewLeft, false);
        updateStandardTopTitle(mControllerViewRight, false);
        if (mVolumeBar2 != null) {
            mVolumeBar2.setVisibility(View.GONE);
        }
        if (mCentralPlayIcon != null) {
            mCentralPlayIcon.setVisibility(View.GONE);
        }
        if (mPlayPauseTouchZone != null) {
            mPlayPauseTouchZone.setVisibility(View.INVISIBLE);
        }
        if (mClock != null) {
            mClock.setText(getDateFormat().format(new Date()));
        }
    }

    public void addToMenuContainer(View v){
        View container1 = mControllerViewLeft.findViewById(R.id.tv_menu_container);
        if(container1!=null && container1 instanceof FrameLayout){
            ((FrameLayout)container1).addView(v);
        }
        TVCardDialog dialog = null;
        View cardView = v.findViewById(R.id.card_view);
        if (cardView instanceof TVCardDialog) {
            dialog = (TVCardDialog) cardView;
            // Keep the primary dialog on every TV layout. Previously this was assigned only
            // when a split-screen slave controller existed, leaving normal Android TV playback
            // unable to dismiss the overlay through the predictive-back callback.
            tvCardDialog = dialog;
        }
        if(mControllerViewRight!=null){
            View container2 = mControllerViewRight.findViewById(R.id.tv_menu_container);
            if(container2!=null && container2 instanceof FrameLayout){
                //then we have to inflate a slave view
                if(dialog != null){
                    ((FrameLayout)container2).addView(dialog.createSlaveView());
                }
            }
        }
    }

    private void initMenuAdapter(View v){
        mTVMenuView = v.findViewById(R.id.my_recycler_view);
        if(mTVMenuView!=null){
            mTVMenuAdapter = new TVMenuAdapter((FrameLayout)mTVMenuView,mContext, mWindow );
            mTVMenuAdapter.setOnFocusOutListener(new TVCardView.onFocusOutListener() {
                @Override
                public boolean onFocusOut(int keyCode) {
                    // TODO Auto-generated method stub
                    if(keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode==KeyEvent.KEYCODE_DPAD_DOWN){
                        //showTVMenu(false);
                        return true;
                    }

                    return false;
                }
            });
           
        }
        showTVMenu(false);
    }

    /*
     * 
     * Init controller initialize what is in player_controller_inside.xml. Main view means that this 
     * part can have focus (for TV). Any secondary view will just reproduce the same visual behavior 
     * for split view, like side by side
     * 
     */

    private int getControllerLayout() {
        return TVUtils.isTV(mContext)
                ? R.layout.player_controller_inside : R.layout.player_controller_mobile;
    }

    /** Legacy on/off switch of the compact UI; only read as a fallback when {@link #PREF_PHONE_PLAYER_STYLE} was never set. */
    public static final String PREF_COMPACT_PHONE_PLAYER = "phone_compact_playback_ui";
    /** Phone playback UI: {@link #PHONE_STYLE_MINI} (Compact), the only phone playback UI. */
    public static final String PREF_PHONE_PLAYER_STYLE = "phone_player_style";
    public static final String PHONE_STYLE_COMPACT = "compact";
    /** The "Compact" setting: title / time line, timeline, one button row: list + more | playback buttons | subtitles, audio, video. */
    public static final String PHONE_STYLE_MINI = "mini";

    public static final String PREF_STANDARD_TAP_ZONES = "phone_standard_tap_zones";
    public static final String PREF_STANDARD_TITLE_POSITION = "phone_standard_title_position";
    public static final String PREF_STANDARD_TITLE_OUTLINE = "phone_standard_title_outline";
    public static final String PREF_STANDARD_TITLE_SHADOW = "phone_standard_title_shadow";
    public static final String PREF_STANDARD_TITLE_SIZE = "phone_standard_title_size";
    public static final String PREF_STANDARD_TITLE_STATUS = "phone_standard_title_status";
    public static final String PREF_STANDARD_TITLE_BACKGROUND = "phone_standard_title_background";
    public static final String STANDARD_TITLE_ABOVE_SEEKBAR = "above_seekbar";
    public static final String STANDARD_TITLE_BUTTON_ROW = "button_row";
    public static final String STANDARD_TITLE_TOP = "top";
    public static final String STANDARD_TITLE_BELOW = "below_buttons";
    public static final int MIN_STANDARD_TITLE_SIZE = 12;
    public static final int MAX_STANDARD_TITLE_SIZE = 28;
    public static final int DEFAULT_STANDARD_TITLE_SIZE = 18;

    /** "Seek bar color" setting (the key keeps its old name so saved colours still apply). */
    public static final String PREF_YTUI_SEEK_COLOR = "phone_ytui_seek_color";
    /** Hide the buttons except the playback buttons; the buttons pill (swipe up / D-pad UP) has the rest. */
    public static final String PREF_HIDE_EXTRA_BUTTONS = "phone_hide_extra_buttons";
    /** Compact UI: show the video title in a bar at the top of the screen instead of above the seek bar. */
    public static final String PREF_MINI_TITLE_ON_TOP = "phone_mini_title_on_top";
    /** Compact UI: the buttons pill (swipe up / D-pad UP). On by default. */
    public static final String PREF_BUTTON_PILL = "phone_button_pill";
    /** Compact UI: subtitles, audio, video, more and list buttons sit at the right of the title row. */
    public static final String PREF_TITLE_ROW_BUTTONS = "phone_title_row_buttons";
    /** Compact UI, title on top and no buttons in the title row: centre the title. */
    public static final String PREF_MINI_TITLE_CENTER = "phone_mini_title_center";
    /** Compact UI, no buttons in the title row: hide the title row; the title shows in the info pill when a video starts or resumes. */
    public static final String PREF_HIDE_TITLE_ROW = "phone_hide_title_row";
    private static final String DEFAULT_MINI_SEEK_COLOR = "#2B2BE0";   // the compact UI's original blue

    public static String getPhonePlayerStyle(android.content.SharedPreferences prefs) {
        String style = prefs.getString(PREF_PHONE_PLAYER_STYLE, null);
        // The standard style was removed: anything else (including the old standard value) is the Compact UI.
        return PHONE_STYLE_MINI;
    }

    /** True once the Compact ("mini") control bar has replaced the standard one in this controller. */
    private boolean mMiniUi;

    /** Global gesture switches (all phone UI styles), both on by default. */
    public static final String PREF_GESTURE_SWIPE_SEEK = "phone_gesture_swipe_seek";

    private boolean swipeSeekEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_GESTURE_SWIPE_SEEK, true);
    }

    private boolean tapPlayPauseEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(PREF_STANDARD_TAP_ZONES, true);
    }
    private boolean mPlainButtonRow;

    public static final String PREF_COMPACT_SHOW_SKIP = "phone_compact_show_skip";
    public static final String PREF_COMPACT_SKIP_BACKWARD = "phone_compact_skip_backward";
    public static final String PREF_COMPACT_SKIP_FORWARD = "phone_compact_skip_forward";
    public static final String DEFAULT_COMPACT_SKIP_SECONDS = "15";

    private boolean mPhoneRemainingTime;
    private boolean mCompactShowTotalTime;
    private boolean mPhoneCutoutOriginal;
    private int mFormatBeforeCutoutOriginal;

    private void updateMobilePlaybackInfo(View view, int position, int duration, CharSequence time) {
        if (view == null) return;
        if (view.findViewById(R.id.mobile_title_row) == null) return;

        TextView current = view.findViewById(R.id.time_current);
        current.setText(time);
        boolean compact = view.findViewById(R.id.mobile_compact_transport) != null;
        if (duration > 0) {
            boolean showRemaining = compact ? !mCompactShowTotalTime : mPhoneRemainingTime;
            String end = showRemaining ? "-" + stringForTime(Math.max(0, duration - position))
                    : stringForTime(duration);
            TextView total = compact ? null : view.findViewById(R.id.time);
            if (total != null) {
                // Standard phone UI: elapsed on the left of the bar, total/remaining on the right.
                total.setText(end);
            } else {
                current.setText(mContext.getString(R.string.mobile_player_times, time, end));
            }
        }
        if (compact) updateCompactVideoDetails(view);
        TimeLabelSeekBar seekBar = view.findViewById(R.id.seek_progress);
        // the compact bar has no floating time above the playhead
        seekBar.setTimeText(compact ? "" : time);   // mini + standard: floating time above the playhead
    }

    private void updateCompactVideoDetails(View view) {
        TextView details = view.findViewById(R.id.mobile_video_technical_info);
        Player player = Player.sPlayer;
        if (player == null) return;
        var metadata = player.getVideoMetadata();
        var track = metadata == null ? null : metadata.getVideoTrack();
        java.util.ArrayList<String> fields = new java.util.ArrayList<>(3);
        int width = player.getVideoWidth();
        int height = player.getVideoHeight();
        if (width > 0 && height > 0) fields.add(width + "×" + height);
        if (track != null) {
            if (track.format != null && !track.format.isEmpty()) fields.add(track.format);
            if (track.bitRate > 0) fields.add(mContext.getString(R.string.phone_audio_bitrate, track.bitRate));
        }
        details.setText(android.text.TextUtils.join(" · ", fields));
    }

    private void configureCompactPhoneControls(View view) {
        if (!PHONE_STYLE_COMPACT.equals(getPhonePlayerStyle(PreferenceManager.getDefaultSharedPreferences(mContext)))
                || view.findViewById(R.id.mobile_video_title) == null) return;

        RelativeLayout root = (RelativeLayout) view;
        View standard = root.findViewById(R.id.control_bar);
        View compact = LayoutInflater.from(mContext).inflate(R.layout.player_controls_mobile_compact, root, false);
        int index = root.indexOfChild(standard);
        root.removeView(standard);
        root.addView(compact, index);

        View transport = LayoutInflater.from(mContext).inflate(R.layout.mobile_compact_transport, root, false);
        root.addView(transport);
        bindCompactSkipLabel(transport, R.id.backward, R.id.mobile_skip_backward_seconds, -1);
        bindCompactSkipLabel(transport, R.id.forward, R.id.mobile_skip_forward_seconds, 1);

        compact.findViewById(R.id.time_current).setOnClickListener(button -> {
            mCompactShowTotalTime = !mCompactShowTotalTime;
            setProgress();
            sendFadeOut(SHOW_TIMEOUT);
        });
    }

    /**
     * Compact phone controls: swaps the standard control bar for player_controls_mobile_mini. The bottom
     * row holds everything: list and more on the left, the playback buttons in the middle and subtitles,
     * audio and video on the right. There is no Controls button and no second button row any more.
     */
    private void configureMiniPhoneControls(View view) {
        if (isTVMode || TVUtils.isTV(mContext)
                || !PHONE_STYLE_MINI.equals(getPhonePlayerStyle(PreferenceManager.getDefaultSharedPreferences(mContext)))
                || !(view instanceof RelativeLayout)
                || view.findViewById(R.id.mobile_video_title) == null) return;
        RelativeLayout root = (RelativeLayout) view;
        View standard = root.findViewById(R.id.control_bar);
        if (standard == null) return;
        View mini = LayoutInflater.from(mContext).inflate(R.layout.player_controls_mobile_mini, root, false);
        int index = root.indexOfChild(standard);
        root.removeView(standard);
        root.addView(mini, index);
        mMiniUi = true;
        mMiniPillEnabled = PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_BUTTON_PILL, true);
        applyMiniSeekColor(mini);
        moveMiniButtonsToTitleRow(mini);
        moveMiniTitleToTop(root, mini);
        hideMiniTitleRow(root);
    }

    /** "Hide title row" only applies while no buttons sit in the title row. */
    private boolean hideTitleRowActive() {
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean buttonsInTitle = !hideExtraButtonsActive() && prefs.getBoolean(PREF_TITLE_ROW_BUTTONS, false);
        return mMiniUi && !buttonsInTitle && prefs.getBoolean(PREF_HIDE_TITLE_ROW, false);
    }

    /** Collapses the title row to zero height so later setVisibility() calls cannot bring it back. */
    private void hideMiniTitleRow(RelativeLayout root) {
        if (!hideTitleRowActive()) return;
        View row = root.findViewById(R.id.mobile_title_row);
        if (row == null) return;
        row.setVisibility(View.GONE);
        row.setMinimumHeight(0);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            if (lp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
            row.setLayoutParams(lp);
        }
    }

    /** Hidden title row: show the video title in the top info pill (called on video start and on resume). */
    public void showTitleInfoPill() {
        if (!hideTitleRowActive() || mVideoTitle == null) return;
        final CharSequence title = mVideoTitle.getText();
        if (title == null || title.length() == 0) return;
        mHandler.post(() -> {
            if (mWindow == null || !hideTitleRowActive()) return;
            showPhonePill(title.toString(), R.drawable.mobile_ic_play);
            mHandler.postDelayed(mHidePhonePill, 3000);
        });
    }

    /** Compact UI: the buttons pill is switched on in the settings (default). */
    private boolean mMiniPillEnabled = true;

    /** "Hide buttons except playback buttons" only applies while the buttons pill is on: the pill has the rest. */
    private boolean hideExtraButtonsActive() {
        return mMiniPillEnabled
                && PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_HIDE_EXTRA_BUTTONS, false);
    }

    /** "Buttons in title row" setting: list, more, subtitles, audio and video go to the right of the title, centred vertically. */
    private void moveMiniButtonsToTitleRow(View mini) {
        if (hideExtraButtonsActive()
                || !PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_TITLE_ROW_BUTTONS, false)) return;
        ViewGroup container = mini.findViewById(R.id.mobile_title_buttons);
        if (container == null) return;
        for (int id : new int[]{R.id.mobile_strip, R.id.mobile_more, R.id.mobile_subtitles, R.id.mobile_audio,
                R.id.mobile_video_fit}) {
            View button = mini.findViewById(id);
            if (button == null || !(button.getParent() instanceof ViewGroup)) continue;
            ((ViewGroup) button.getParent()).removeView(button);
            container.addView(button);
        }
        container.setVisibility(View.VISIBLE);
        for (int id : new int[]{R.id.mobile_left_buttons, R.id.mobile_right_buttons}) {
            View group = mini.findViewById(id);
            if (group != null) group.setVisibility(View.GONE);
        }
    }

    /**
     * "Title on top" setting: moves the title row out of the bottom bar to the top edge of the screen. It is a
     * direct child of the controller, so showControlBar() shows / hides it together with the control bar.
     */
    private void moveMiniTitleToTop(RelativeLayout root, View mini) {
        if (!PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_MINI_TITLE_ON_TOP, false)) return;
        View row = mini.findViewById(R.id.mobile_title_row);
        if (row == null || !(row.getParent() instanceof ViewGroup)) return;
        ((ViewGroup) row.getParent()).removeView(row);
        row.setBackgroundResource(R.drawable.mobile_player_gradient_top);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        View titleButtons = mini.findViewById(R.id.mobile_title_buttons);
        boolean buttonsInTitle = titleButtons != null && titleButtons.getVisibility() == View.VISIBLE;
        TextView title = row.findViewById(R.id.mobile_video_title);
        if (title != null && !buttonsInTitle
                && PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_MINI_TITLE_CENTER, false)) {
            title.setGravity(Gravity.CENTER);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setPaddingRelative(0, 0, 0, 0);
        }
        root.setClipToPadding(false);   // the gradient is drawn behind the status bar / cutout area
        root.addView(row, params);
        row.setVisibility(View.GONE);
        applyMiniTopTitleInsets(row, 0, 0);
    }

    /** Top title: touches the top edge; the text keeps the same distance from it as the button bar has from the bottom. */
    private void applyMiniTopTitleInsets(View row, int left, int right) {
        int top = row.getParent() instanceof View ? ((View) row.getParent()).getPaddingTop() : 0;
        int spacing = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_padding_bottom)
                + mContext.getResources().getDimensionPixelSize(R.dimen.mobile_button_bar_margin_bottom);
        int side = swipeDp(16) + mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_title_track_margin);
        row.setPaddingRelative(left + side, top + spacing, right + side, swipeDp(24));
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            if (margins.topMargin != -top) {
                margins.topMargin = -top;   // start at the very top, above the controller's safe-area padding
                row.setLayoutParams(margins);
            }
        }
    }

    public boolean isMiniUi() {
        return mMiniUi;
    }

    /** Compact UI: the "Seek bar color" setting colours the progress and the thumb. */
    private void applyMiniSeekColor(View view) {
        SeekBar seek = view.findViewById(R.id.seek_progress);
        if (seek == null) return;
        int color;
        try {
            color = Color.parseColor(PreferenceManager.getDefaultSharedPreferences(mContext)
                    .getString(PREF_YTUI_SEEK_COLOR, DEFAULT_MINI_SEEK_COLOR));
        } catch (IllegalArgumentException e) {
            color = Color.parseColor(DEFAULT_MINI_SEEK_COLOR);
        }
        android.graphics.drawable.LayerDrawable progress = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                        ytuiBar(0x88FFFFFF),
                        new android.graphics.drawable.ClipDrawable(ytuiBar(color), android.view.Gravity.START,
                                android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            progress.setLayerHeight(i, swipeDp(3));
            progress.setLayerGravity(i, android.view.Gravity.CENTER_VERTICAL);
        }
        seek.setProgressDrawable(progress);
        // Keep the layout's thumb (transparent unless focused): TimeLabelSeekBar only draws the floating
        // play/pause icon + time when it has a thumb to anchor to, so it must not be removed.
        seek.setThumbOffset(0);
    }

    /** Compact UI: keeps the control area (seek bar included) on screen without a timeout while a popup is open. */
    public void showWhilePopupOpen() {
        hideWhileVideoPopupOpen();   // any button popup hides the buttons pill and the compact control bar
    }

    /** A button popup is gone: bring back the buttons pill when the popup was opened from it, else the control bar. */
    public void showAfterPopup(boolean openedFromPill) {
        if (openedFromPill && mMiniUi && mSwipePill != null && swipePillEnabled()) {
            if (!mSwipePillShowing) showSwipePill();
        } else {
            show();
        }
    }

    /** Compact UI: while the video popup (fit / zoom / aspect ratio / decoder) is open, no control is shown. */
    public void hideWhileVideoPopupOpen() {
        if (mControlBarShowing) mKeepPlainRowOnShow = isPlainRowOpen();   // not again while a popup hands over to another
        if (mSwipePillShowing) dismissSwipePillImmediately();
        cancelFadeOut();
        hide();
    }

    private android.graphics.drawable.GradientDrawable ytuiShape(boolean oval, int color) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(oval ? android.graphics.drawable.GradientDrawable.OVAL
                : android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setColor(color);
        if (!oval) shape.setCornerRadius(swipeDp(30));      // clamped to a full pill
        return shape;
    }

    private android.graphics.drawable.GradientDrawable ytuiDot(int sizeDp, int color) {
        android.graphics.drawable.GradientDrawable dot = ytuiShape(true, color);
        dot.setSize(swipeDp(sizeDp), swipeDp(sizeDp));
        return dot;
    }

    private android.graphics.drawable.GradientDrawable ytuiBar(int color) {
        android.graphics.drawable.GradientDrawable bar = ytuiShape(false, color);
        bar.setCornerRadius(swipeDp(2));
        return bar;
    }

    // ---- Night mode: dims the white of the player interface -------------------------------------
    public static final String PREF_NIGHT_MODE = "phone_night_mode_ui";
    private static final float NIGHT_MODE_BRIGHTNESS = 0.45f;

    public boolean isNightModeUi() {
        return isNightMode();
    }

    public void toggleNightModeUi() {
        boolean enabled = !isNightMode();
        PreferenceManager.getDefaultSharedPreferences(mContext).edit().putBoolean(PREF_NIGHT_MODE, enabled).apply();
        applyNightModeEverywhere();
    }

    /** Title marquee: stays still, then starts rolling 2 seconds after it is (re)set. */
    private void armTitleMarquee(final TextView title) {
        if (title == null) return;
        title.setSelected(false);
        title.postDelayed(() -> title.setSelected(true), 2000);
    }

    private boolean isNightMode() {
        return PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean(PREF_NIGHT_MODE, false);
    }

    /** Night mode for a popup / dialog of the player: dims the content view when night mode is on. */
    public static void applyNightModeTo(Context context, View root) {
        if (root == null) return;
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_NIGHT_MODE, false)) {
            android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix(new float[]{
                    NIGHT_MODE_BRIGHTNESS, 0, 0, 0, 0,
                    0, NIGHT_MODE_BRIGHTNESS, 0, 0, 0,
                    0, 0, NIGHT_MODE_BRIGHTNESS, 0, 0,
                    0, 0, 0, 1, 0});
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
            root.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        } else {
            root.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    /** Dims every colour of the controller (white icons, text, seek bar, pill) when night mode is on. */
    private void applyNightMode(View root) {
        if (root == null) return;
        if (isNightMode()) {
            android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix(new float[]{
                    NIGHT_MODE_BRIGHTNESS, 0, 0, 0, 0,
                    0, NIGHT_MODE_BRIGHTNESS, 0, 0, 0,
                    0, 0, NIGHT_MODE_BRIGHTNESS, 0, 0,
                    0, 0, 0, 1, 0});
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
            root.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        } else {
            root.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private void applyNightModeEverywhere() {
        applyNightMode(mControllerViewLeft);
        applyNightMode(mControllerViewRight);
        applyNightMode(mPhoneGesturePill);          // the top info pill is dimmed too
    }

    /**
     * Night mode is a setting, not a state of the views: put it back on the controller and the top info pill
     * (coming back from another app, a rebuilt controller). The layers are made again so that the dimming
     * is really drawn.
     */
    public void refreshNightMode() {
        for (View v : new View[]{mControllerViewLeft, mControllerViewRight, mPhoneGesturePill}) {
            if (v != null) v.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        applyNightModeEverywhere();
    }

    /** Wires the night mode button of a pill; {@code root} is the controller view that contains it. */
    private void bindNightModeButton(final View root, View pill) {
        ImageButton found = pill.findViewById(R.id.mobile_night_mode);
        if (found == null) found = root.findViewById(R.id.mobile_night_mode);   // moved out of the pill
        final ImageButton button = found;
        if (button == null) return;
        button.setImageResource(isNightMode() ? R.drawable.ic_night_mode_on : R.drawable.ic_night_mode_off);
        button.setOnClickListener(b -> {
            boolean enabled = !isNightMode();
            PreferenceManager.getDefaultSharedPreferences(mContext).edit().putBoolean(PREF_NIGHT_MODE, enabled).apply();
            button.setImageResource(enabled ? R.drawable.ic_night_mode_on : R.drawable.ic_night_mode_off);
            applyNightMode(root);
            applyNightModeEverywhere();
            sendFadeOut(SHOW_TIMEOUT);
        });
        applyNightMode(root);
    }

    /** Compact UI: wires the buttons that now sit directly in the playback row (no pill any more). */
    private void bindMiniButtons(View view) {
        mSurfaceController.enableCompactVideoFit();
        View.OnClickListener optionsListener = button -> {
            if (!mIsStopped && mContext instanceof PlayerActivity) {
                ((PlayerActivity) mContext).showMobilePlaybackOptions(button);
            }
        };
        for (int id : new int[]{R.id.mobile_subtitles, R.id.mobile_audio, R.id.mobile_video_fit, R.id.mobile_more}) {
            View button = view.findViewById(id);
            if (button != null) button.setOnClickListener(optionsListener);
        }
        // swipe up / D-pad UP still opens the overlay pill (night mode, audio channels, ...)
        setupTvSwipePill(view);
        setupMiniFocusOrder(view);
    }

    private void bindPhoneControlsPill(View view) {
        if (mMiniUi && view.findViewById(R.id.mobile_strip) != null) {
            bindMiniButtons(view);
            return;
        }
        View pill = view.findViewById(R.id.mobile_compact_pill);
        if (pill == null) return;

        mSurfaceController.enableCompactVideoFit();
        pill.findViewById(R.id.mobile_video_fit).setOnClickListener(button -> {
            if (!mIsStopped && mContext instanceof PlayerActivity) {
                ((PlayerActivity) mContext).showMobilePlaybackOptions(button);
            }
        });
        bindPillStripButton(pill);

        pill.findViewById(R.id.mobile_controls_toggle).setOnClickListener(
                button -> setCompactPillExpanded(view, true));
        pill.findViewById(R.id.mobile_controls_close).setOnClickListener(
                button -> setCompactPillExpanded(view, false));
        if ("plain_row".equals(pill.getTag())) {
            pill.findViewById(R.id.mobile_controls_toggle).setOnClickListener(button -> togglePlainControls(view));
        }
        bindNightModeButton(view, pill);
        if (mMiniUi) {
            // compact UI: keep the Controls button in the bar and add the standard buttons pill as an overlay
            setupTvSwipePill(view);
            setupMiniFocusOrder(view);
        } else {
            setupSwipePill(view, pill);
        }
    }

    /** Compact UI + remote: seek bar -> DOWN -> playback buttons, playback buttons -> UP -> seek bar. */
    private void setupMiniFocusOrder(View view) {
        if (hideExtraButtonsActive()) {
            // only backward, play / pause and forward stay in the button row: everything else is hidden
            for (int id : new int[]{R.id.mobile_left_buttons, R.id.mobile_right_buttons, R.id.mobile_controls_toggle,
                    R.id.mobile_strip, R.id.mobile_more, R.id.mobile_subtitles, R.id.mobile_audio,
                    R.id.mobile_video_fit}) {
                View extra = view.findViewById(id);
                if (extra != null) extra.setVisibility(View.GONE);
            }
        }
        View strip = view.findViewById(R.id.mobile_strip);
        if (strip != null && !hideExtraButtonsActive()) {
            strip.setOnClickListener(button -> {
                if (mContext instanceof PlayerActivity) ((PlayerActivity) mContext).showMobileVideoStrip();
            });
        }
        View seek = view.findViewById(R.id.seek_progress);
        View pause = view.findViewById(R.id.pause);
        if (seek == null || pause == null) return;
        seek.setFocusable(true);
        seek.setNextFocusDownId(R.id.pause);
        for (int id : new int[]{R.id.mobile_strip, R.id.mobile_more, R.id.backward, R.id.pause, R.id.forward,
                R.id.mobile_subtitles, R.id.mobile_audio, R.id.mobile_video_fit}) {
            View button = view.findViewById(id);
            if (button != null) button.setNextFocusUpId(R.id.seek_progress);
        }
        if (hideExtraButtonsActive()) {
            // only backward / play / forward are left: the cursor stays on them (nothing beyond forward or backward,
            // DOWN is the list strip key, see handlePhoneRemoteKey)
            View backward = view.findViewById(R.id.backward);
            View forward = view.findViewById(R.id.forward);
            if (backward != null) backward.setNextFocusLeftId(R.id.backward);
            if (forward != null) forward.setNextFocusRightId(R.id.forward);
            for (int id : new int[]{R.id.backward, R.id.pause, R.id.forward}) {
                View button = view.findViewById(id);
                if (button != null) button.setNextFocusDownId(id);
            }
        }
    }

    /**
     * Plain button row: the "Controls" button hides the current bottom buttons and shows subtitle, audio,
     * video and more, centred in the same row; the Controls button (still visible) brings everything back.
     */
    private void togglePlainControls(View view) {
        View barView = view.findViewById(R.id.button_bar);
        View toggle = view.findViewById(R.id.mobile_controls_toggle);
        View pill = view.findViewById(R.id.mobile_compact_pill);
        if (!(barView instanceof RelativeLayout) || toggle == null || !(toggle.getParent() instanceof ViewGroup)) return;
        RelativeLayout bar = (RelativeLayout) barView;
        View panel = bar.findViewWithTag("plain_panel");
        boolean expand = panel == null || panel.getVisibility() != View.VISIBLE;
        if (expand) {
            if (panel == null) {
                panel = buildPlainControlsPanel();
                RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.addRule(RelativeLayout.CENTER_IN_PARENT);
                bar.addView(panel, lp);
            }
            java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child == panel) continue;
                if (child == pill) {
                    ViewGroup row = (ViewGroup) toggle.getParent();
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View button = row.getChildAt(j);
                        if (button != toggle && button.getVisibility() == View.VISIBLE) {
                            button.setVisibility(View.INVISIBLE);
                            hidden.add(button);
                        }
                    }
                } else if (child.getVisibility() == View.VISIBLE) {
                    child.setVisibility(View.INVISIBLE);
                    hidden.add(child);
                }
            }
            panel.setTag(R.id.mobile_controls_toggle, hidden);
            panel.setVisibility(View.VISIBLE);
            setMiniToggleBack(toggle, true);
        } else {
            Object hidden = panel.getTag(R.id.mobile_controls_toggle);
            if (hidden instanceof java.util.ArrayList) {
                for (Object o : (java.util.ArrayList<?>) hidden) ((View) o).setVisibility(View.VISIBLE);
            }
            panel.setVisibility(View.GONE);
            setMiniToggleBack(toggle, false);
        }
        sendFadeOut(SHOW_TIMEOUT);
    }

    private boolean mKeepPlainRowOnShow;

    /**
     * Compact UI: hides the controls for a popup / dialog opened from a button of the Controls button row.
     * When the controls come back they show that row again instead of the playback row.
     */
    public void hideKeepingButtonsRow() {
        mKeepPlainRowOnShow = isPlainRowOpen();
        hide();
    }

    private boolean isPlainRowOpen() {
        if (!mMiniUi || mControllerViewLeft == null) return false;
        View bar = mControllerViewLeft.findViewById(R.id.button_bar);
        View panel = bar == null ? null : bar.findViewWithTag("plain_panel");
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    /** Compact UI: closes the Controls button row (if open) so the playback button row is shown again. */
    private void collapsePlainControls() {
        if (!mMiniUi || mControllerViewLeft == null) return;
        View bar = mControllerViewLeft.findViewById(R.id.button_bar);
        if (!(bar instanceof RelativeLayout)) return;
        View panel = bar.findViewWithTag("plain_panel");
        if (panel == null || panel.getVisibility() != View.VISIBLE) return;
        Object hidden = panel.getTag(R.id.mobile_controls_toggle);
        if (hidden instanceof java.util.ArrayList) {
            for (Object o : (java.util.ArrayList<?>) hidden) ((View) o).setVisibility(View.VISIBLE);
        }
        panel.setVisibility(View.GONE);
        setMiniToggleBack(mControllerViewLeft.findViewById(R.id.mobile_controls_toggle), false);
    }

    /** Compact UI: while the row with all the buttons is open the Controls button is a Back button. */
    private void setMiniToggleBack(View toggle, boolean back) {
        if (!mMiniUi || !(toggle instanceof android.widget.ImageView)) return;
        ((android.widget.ImageView) toggle).setImageResource(back ? R.drawable.mobile_ic_back : R.drawable.mobile_ic_controls);
        toggle.setContentDescription(mContext.getString(back ? R.string.mobile_player_back : R.string.phone_controls_toggle));
    }

    private View buildPlainControlsPanel() {
        android.widget.LinearLayout panel = new android.widget.LinearLayout(mContext);
        panel.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER);
        panel.setTag("plain_panel");
        int size = Math.round(44 * mContext.getResources().getDisplayMetrics().density);
        int[][] defs = {
                {R.id.mobile_subtitles, R.drawable.mobile_ic_subtitle, R.string.menu_subtitles},
                {R.id.mobile_audio, R.drawable.mobile_ic_audio, R.string.menu_audio},
                {R.id.mobile_video_fit, R.drawable.ic_display_settings_24, R.string.phone_video_menu},
                {R.id.mobile_more, R.drawable.mobile_player_more, R.string.mobile_player_more}};
        for (int[] def : defs) {
            ImageButton button = new ImageButton(mContext, null, 0, R.style.MobilePlayerButton);
            button.setId(def[0]);
            button.setImageResource(def[1]);
            button.setContentDescription(mContext.getString(def[2]));
            button.setOnClickListener(b -> {
                if (!mIsStopped && mContext instanceof PlayerActivity) {
                    ((PlayerActivity) mContext).showMobilePlaybackOptions(b);
                }
            });
            panel.addView(button, new android.widget.LinearLayout.LayoutParams(size, size));
        }
        return panel;
    }

    private int compactSkipSeconds(int direction) {
        String key = direction < 0 ? PREF_COMPACT_SKIP_BACKWARD : PREF_COMPACT_SKIP_FORWARD;
        String value = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(key, DEFAULT_COMPACT_SKIP_SECONDS);
        try {
            return Math.max(1, Math.min(60, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return Integer.parseInt(DEFAULT_COMPACT_SKIP_SECONDS);
        }
    }

    private void bindCompactSkipLabel(View transport, int buttonId, int labelId, int direction) {
        int seconds = compactSkipSeconds(direction);
        TextView label = transport.findViewById(labelId);
        label.setText(String.valueOf(seconds));
        View button = transport.findViewById(buttonId);
        boolean showSkip = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getBoolean(PREF_COMPACT_SHOW_SKIP, true);
        ((View) button.getParent()).setVisibility(showSkip ? View.VISIBLE : View.GONE);
        button.setContentDescription(mContext.getString(
                direction < 0 ? R.string.phone_skip_back_seconds : R.string.phone_skip_forward_seconds, seconds));
    }

    private void setCompactPillExpanded(View controller, boolean expanded) {
        ViewGroup row = controller.findViewById(R.id.button_bar);
        View pill = controller.findViewById(R.id.mobile_compact_pill);
        View toggle = controller.findViewById(R.id.mobile_controls_toggle);
        View actions = controller.findViewById(R.id.mobile_compact_actions);
        animateCompactPill(row, pill);
        toggle.setVisibility(expanded ? View.GONE : View.VISIBLE);
        actions.setVisibility(expanded ? View.VISIBLE : View.GONE);
        applyPhoneTrackPopupVisibility();
        if (expanded) cancelFadeOut();
        else sendFadeOut(SHOW_TIMEOUT);
    }
    private void animateCompactPill(ViewGroup row, View pill) {
        android.transition.ChangeBounds bounds = new android.transition.ChangeBounds();
        bounds.addTarget(pill);
        bounds.setDuration(200);
        android.transition.TransitionManager.beginDelayedTransition(row, bounds);
    }

    // ---------------------------------------------------------------------------------------
    // "Swipe up for buttons": the controls pill leaves the control bar and, on an upward swipe
    // in the control area, grows out of a circle at the play button position.
    // ---------------------------------------------------------------------------------------
    private android.widget.FrameLayout mSwipePillOverlay;
    private View mSwipePill;
    private boolean mSwipePillShowing;
    private boolean mSwipePillClosing;
    private boolean mSwipePillFromControls;   // opened while the control area was visible
    private android.animation.ValueAnimator mSwipePillAnimator;
    private boolean mSwipeTracking, mSwipeConsuming, mSwipeSendingCancel;
    private boolean mSwipeOutsidePending, mSwipeTouchOnPill;
    private float mSwipeDownX, mSwipeDownY;
    private final Runnable mAutoDismissSwipePill = () -> dismissSwipePill(true);

    private boolean swipePillEnabled() {
        if (mMiniUi) return mMiniPillEnabled;   // compact UI: "Buttons pill" setting (swipe up / D-pad UP)
        return false;
    }

    private int swipeDp(float value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }

    /** Moves the controls pill out of the control bar into its own overlay (hidden until swiped up). */
    private void setupSwipePill(View root, View pill) {
        if (!(root instanceof RelativeLayout) || mSwipePill == pill || !swipePillEnabled()) return;
        ViewGroup parent = (ViewGroup) pill.getParent();
        if (parent != null) parent.removeView(pill);

        android.widget.FrameLayout overlay = new android.widget.FrameLayout(mContext);
        overlay.setVisibility(View.GONE);
        ((RelativeLayout) root).addView(overlay, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int height = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_buttons_height);
        overlay.addView(pill, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height, android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));

        pill.findViewById(R.id.mobile_controls_toggle).setVisibility(View.GONE);
        pill.findViewById(R.id.mobile_compact_actions).setVisibility(View.GONE);
        pill.findViewById(R.id.mobile_controls_close).setOnClickListener(button -> dismissSwipePill(true));
        mSwipePillOverlay = overlay;
        mSwipePill = pill;
        PlayerPopup.sController = new java.lang.ref.WeakReference<>(this);
    }

    /** Top edge of the buttons pill on screen (where it opens, shifted down from the seek bar line), or -1 without pill. */
    int buttonsPillTopOnScreen() {
        if (mSwipePill == null) return -1;
        int height = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_buttons_height);
        return swipeSeekCenterY() - height / 2 + swipeDp(16);
    }

    private void showSwipePill() {
        if (mSwipePill == null || mSwipePillShowing) return;
        View actions = mSwipePill.findViewById(R.id.mobile_compact_actions);
        ViewGroup.LayoutParams raw = mSwipePill.getLayoutParams();
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) raw;
        final int height = lp.height;

        // Center of the seek bar's line, relative to the overlay (works while the control bar is hidden too).
        int[] overlayLocation = new int[2];
        mSwipePillOverlay.getLocationOnScreen(overlayLocation);
        // shifted a little down from the seek bar line
        lp.topMargin = Math.max(0, swipeSeekCenterY() - overlayLocation[1] - height / 2 + swipeDp(16));

        // Full width of the pill with its buttons.
        actions.setVisibility(View.VISIBLE);
        // Measure the row of buttons itself (not the scroll view around it) so that every button fits.
        ViewGroup buttonRow = (ViewGroup) ((ViewGroup) actions).getChildAt(0);
        buttonRow.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        final int rowWidth = buttonRow.getMeasuredWidth();
        final int fullWidth = Math.max(height, rowWidth
                + mSwipePill.getPaddingLeft() + mSwipePill.getPaddingRight());
        // Give the buttons their full width up front, centered in the pill. While the pill is still
        // narrow (circle -> pill animation) the outer edges are simply clipped, so no button can be
        // squeezed or pushed out of view, and the buttons never need to scroll.
        actions.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                rowWidth, height, android.view.Gravity.CENTER));

        mSwipePillFromControls = mControlBarShowing;   // remember if the controls were open
        mSwipePillShowing = true;
        mSwipePillClosing = false;
        cancelFadeOut();
        hide(FLAG_SIDE_CONTROL_BAR);                       // control area disappears

        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        mSwipePill.setLayoutParams(lp);
        actions.setAlpha(1f);
        mSwipePill.setAlpha(0f);
        mSwipePillOverlay.setVisibility(View.VISIBLE);
        if (isTVMode || TVUtils.isTV(mContext)) mSwipePill.post(this::focusFirstSwipePillButton);
        animateSwipePill(0f, 1f, 220, height, fullWidth, () -> {
            // Fully open: let the pill size itself to all of its buttons.
            android.widget.FrameLayout.LayoutParams open = (android.widget.FrameLayout.LayoutParams) mSwipePill.getLayoutParams();
            open.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            mSwipePill.setLayoutParams(open);
            actions.scrollTo(0, 0);
            mHandler.removeCallbacks(mAutoDismissSwipePill);
            mHandler.postDelayed(mAutoDismissSwipePill, 6000);
        });
    }

    /** Fades and scales the whole pill (all buttons visible) in or out. */
    private void animateSwipePill(float from, float to, int duration, final int circle, final int full, final Runnable end) {
        if (mSwipePillAnimator != null) mSwipePillAnimator.cancel();
        final android.view.animation.DecelerateInterpolator decel = new android.view.animation.DecelerateInterpolator(1.6f);
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        animator.addUpdateListener(a -> {
            float f = decel.getInterpolation((float) a.getAnimatedValue());
            mSwipePill.setAlpha(f);
            mSwipePill.setScaleX(0.85f + 0.15f * f);
            mSwipePill.setScaleY(0.85f + 0.15f * f);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && end != null) end.run();
            }
        });
        mSwipePillAnimator = animator;
        animator.start();
    }

    /** Fades the pill out, then brings the controls back (if requested). */
    private void dismissSwipePill(boolean restoreControls) {
        if (!mSwipePillShowing || mSwipePillClosing || mSwipePill == null) return;
        mSwipePillClosing = true;
        mHandler.removeCallbacks(mAutoDismissSwipePill);
        final int height = mSwipePill.getLayoutParams().height;
        final int full = Math.max(height, mSwipePill.getWidth());
        animateSwipePill(1f, 0f, 180, height, full, () -> {
            finishSwipePillDismiss();
            // Only bring the controls back if the pill was opened from them.
            if (restoreControls && mSwipePillFromControls) show();
        });
    }

    private void finishSwipePillDismiss() {
        if (mSwipePill == null) return;
        mSwipePill.findViewById(R.id.mobile_compact_actions).setVisibility(View.GONE);
        mSwipePillOverlay.setVisibility(View.GONE);
        mSwipePillShowing = false;
        mSwipePillClosing = false;
        mSwipePill.findViewById(R.id.mobile_controls_toggle).setVisibility(View.GONE);
        View root = mSwipePillOverlay.getParent() instanceof View ? (View) mSwipePillOverlay.getParent() : null;
        if (root != null) {
            for (int id : new int[]{R.id.mobile_transport, R.id.mobile_compact_transport}) {
                View transport = root.findViewById(id);
                if (transport != null && id == R.id.mobile_transport) transport.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Closes the swipe-up pill (without bringing the controls back), e.g. when a subtitle panel opens. */
    public void hideSwipePillForPanel() {
        if (mSwipePillShowing) dismissSwipePill(false);
    }

    private void dismissSwipePillImmediately() {
        if (mSwipePillAnimator != null) mSwipePillAnimator.cancel();
        mHandler.removeCallbacks(mAutoDismissSwipePill);
        finishSwipePillDismiss();
    }

    private boolean mSwipeBottomZone;      // current touch started in the bottom swipe area
    private int mSwipeSeekCenterYCache = -1;

    /** Screen Y of the seek bar's line; remembered while the control bar is visible. */
    private int swipeSeekCenterY() {
        View seek = mProgress;
        if (seek != null && seek.isShown() && seek.getHeight() > 0) {
            int[] location = new int[2];
            seek.getLocationOnScreen(location);
            int top = location[1] + seek.getPaddingTop();
            int bottom = location[1] + seek.getHeight() - seek.getPaddingBottom();
            mSwipeSeekCenterYCache = (top + bottom) / 2;
        }
        if (mSwipeSeekCenterYCache >= 0) return mSwipeSeekCenterYCache;
        View root = mControllerViewLeft != null ? mControllerViewLeft : mWindow.getDecorView();
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        return origin[1] + root.getHeight() - swipeDp(70);
    }

    /** True if the point is in the bottom strip, from the screen bottom up to the seek bar. */
    private boolean inSwipeBottomZone(MotionEvent event) {
        if (mControllerViewLeft == null || mControllerViewLeft.getHeight() == 0) return false;
        int[] origin = new int[2];
        mControllerViewLeft.getLocationOnScreen(origin);
        float bottom = origin[1] + mControllerViewLeft.getHeight();
        float top = swipeSeekCenterY() - swipeDp(22);          // a little above the seek line
        return event.getRawY() >= top && event.getRawY() <= bottom;
    }

    /** Returns true when the event was consumed by the swipe-up-for-buttons logic. */
    private boolean handleSwipePillTouch(MotionEvent event) {
        if (mSwipeSendingCancel || mSwipePill == null || !swipePillEnabled()) return false;
        int action = event.getActionMasked();

        if (mSwipePillShowing) {
            if (action == MotionEvent.ACTION_DOWN) {
                mSwipeTouchOnPill = phoneHit(mSwipePill, event);
                mSwipeOutsidePending = !mSwipeTouchOnPill;
                mSwipeDownX = event.getRawX();
                mSwipeDownY = event.getRawY();
                mHandler.removeCallbacks(mAutoDismissSwipePill);
            }
            if (mSwipeTouchOnPill) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    // Touching the pill or its buttons never closes it; just restart the idle timer.
                    mHandler.removeCallbacks(mAutoDismissSwipePill);
                    if (mSwipePillShowing && !mSwipePillClosing) mHandler.postDelayed(mAutoDismissSwipePill, 6000);
                }
                return false;                                  // let the pill buttons handle it
            }
            float dy = event.getRawY() - mSwipeDownY;
            if (action == MotionEvent.ACTION_MOVE && mSwipeOutsidePending && dy > swipeDp(40)) {
                mSwipeOutsidePending = false;
                dismissSwipePill(true);                        // swipe down closes the pill
            } else if (action == MotionEvent.ACTION_UP) {
                float moved = Math.max(Math.abs(event.getRawX() - mSwipeDownX), Math.abs(dy));
                if (mSwipeOutsidePending && moved < swipeDp(12)) dismissSwipePill(true); // tap outside
                else if (mSwipePillShowing && !mSwipePillClosing) {
                    mHandler.removeCallbacks(mAutoDismissSwipePill);
                    mHandler.postDelayed(mAutoDismissSwipePill, 6000);
                }
                mSwipeOutsidePending = false;
            }
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            mSwipeConsuming = false;
            boolean ready = !mIsStopped && !mIsLocked && !mDragging
                    && Player.sPlayer != null && Player.sPlayer.isInPlaybackState();
            // Controls visible: swipe anywhere on them. Controls hidden: swipe from the bottom strip.
            mSwipeBottomZone = ready && !mControlBarShowing && inSwipeBottomZone(event);
            mSwipeTracking = ready && ((mControlBarShowing && hitsPhoneControls(event)) || mSwipeBottomZone);
            if (mControlBarShowing) swipeSeekCenterY();
            mSwipeDownX = event.getRawX();
            mSwipeDownY = event.getRawY();
            return false;
        }
        if (mSwipeConsuming) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mSwipeConsuming = false;
            return true;
        }
        if (mSwipeTracking && action == MotionEvent.ACTION_MOVE && !mDragging) {
            float up = mSwipeDownY - event.getRawY();
            float side = Math.abs(event.getRawX() - mSwipeDownX);
            if (up > swipeDp(32) && up > 1.5f * side) {
                mSwipeTracking = false;
                mSwipeConsuming = true;
                // Tell the touched control (button, seek bar...) that its touch is over.
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                mSwipeSendingCancel = true;
                try {
                    mWindow.getDecorView().dispatchTouchEvent(cancel);
                } finally {
                    mSwipeSendingCancel = false;
                    cancel.recycle();
                }
                showSwipePill();
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mSwipeTracking = false;
        return false;
    }

    /** TV: adds the same buttons pill to the TV controller; it is opened with D-pad UP. */
    private void setupTvSwipePill(View root) {
        if (mSwipePill != null || !(root instanceof RelativeLayout) || !swipePillEnabled()) return;
        View pill = LayoutInflater.from(mContext).inflate(R.layout.mobile_playback_controls_pill,
                (ViewGroup) root, false);
        View.OnClickListener optionsListener = button -> {
            if (mContext instanceof PlayerActivity) {
                ((PlayerActivity) mContext).showMobilePlaybackOptions(button);
            }
        };
        pill.findViewById(R.id.mobile_video_fit).setOnClickListener(optionsListener);
        pill.findViewById(R.id.mobile_audio_channels).setOnClickListener(optionsListener);
        pill.findViewById(R.id.mobile_audio).setOnClickListener(optionsListener);
        pill.findViewById(R.id.mobile_subtitles).setOnClickListener(optionsListener);
        pill.findViewById(R.id.mobile_more).setOnClickListener(optionsListener);
        bindPillStripButton(pill);
        bindNightModeButton(root, pill);
        setupSwipePill(root, pill);
    }

    /** The list strip button of the buttons pill: episodes / movies strip. */
    private void bindPillStripButton(View pill) {
        View strip = pill.findViewById(R.id.mobile_pill_strip);
        if (strip == null) return;
        strip.setOnClickListener(button -> {
            if (!mIsStopped && mContext instanceof PlayerActivity) ((PlayerActivity) mContext).showMobileVideoStrip();
        });
    }

    /** Keys while the pill is open: D-pad moves between its buttons (handled by focus), DOWN/BACK close it. */
    private boolean handleSwipePillKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        mHandler.removeCallbacks(mAutoDismissSwipePill);
        if (!mSwipePillClosing) mHandler.postDelayed(mAutoDismissSwipePill, 8000);
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) dismissSwipePill(true);
        }
        return true;      // focus movement and clicks were already handled by the buttons themselves
    }

    private void focusFirstSwipePillButton() {
        if (mSwipePill == null) return;
        ViewGroup row = (ViewGroup) ((ViewGroup) mSwipePill.findViewById(R.id.mobile_compact_actions)).getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getId() != R.id.mobile_controls_close) {
                child.setFocusable(true);
                child.requestFocus();
                return;
            }
        }
    }

    private View mTopTitleRow;
    private TopTitleBackground mTopTitleBackground;

    private static final class TopTitleBackground extends LayerDrawable {
        TopTitleBackground(Drawable gradient, int sideMargin) {
            super(new Drawable[]{gradient});
            setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
            setLayerInset(0, sideMargin, 0, sideMargin, 0);
        }

        void setHeight(int height) {
            if (getLayerHeight(0) == height) return;
            setLayerHeight(0, height);
            onBoundsChange(getBounds());
            invalidateSelf();
        }
    }

    private void configureStandardTitle(View controller) {
        TextView title = controller.findViewById(R.id.mobile_video_title);
        if (!(title instanceof StandardPlaybackTitleView standardTitle)) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        int size = prefs.getInt(PREF_STANDARD_TITLE_SIZE, DEFAULT_STANDARD_TITLE_SIZE);
        boolean outline = prefs.getBoolean(PREF_STANDARD_TITLE_OUTLINE, false);
        boolean shadow = prefs.getBoolean(PREF_STANDARD_TITLE_SHADOW, false);
        standardTitle.setAppearance(Math.max(MIN_STANDARD_TITLE_SIZE, Math.min(MAX_STANDARD_TITLE_SIZE, size)),
                outline, shadow);
        TimeLabelSeekBar seek = controller.findViewById(R.id.seek_progress);
        seek.setPlaybackShadow(shadow);
        StandardPlaybackTitleView.applyPlaybackShadow(controller.findViewById(R.id.time_current), shadow);
        StandardPlaybackTitleView.applyPlaybackShadow(controller.findViewById(R.id.time), shadow);

        boolean showStatus = prefs.getBoolean(PREF_STANDARD_TITLE_STATUS, false);
        boolean background = prefs.getBoolean(PREF_STANDARD_TITLE_BACKGROUND, false);
        String position = prefs.getString(PREF_STANDARD_TITLE_POSITION, STANDARD_TITLE_ABOVE_SEEKBAR);
        View row = controller.findViewById(R.id.mobile_title_row);
        StandardPlaybackStatusView status = controller.findViewById(R.id.mobile_title_status);
        if (background && !STANDARD_TITLE_TOP.equals(position)) {
            row.setBackgroundResource(R.drawable.mobile_title_row_gradient);
        }
        if (showStatus) {
            status.setAppearance(outline, shadow);
            status.setVisibility(View.VISIBLE);

            View buttonArea = controller.findViewById(R.id.mobile_buttons_area);
            ViewGroup.MarginLayoutParams areaParams = (ViewGroup.MarginLayoutParams) buttonArea.getLayoutParams();
            areaParams.setMarginStart(swipeDp(12));
            areaParams.setMarginEnd(swipeDp(12));
            buttonArea.setLayoutParams(areaParams);

            if (!swipePillEnabled()) {
                View transport = controller.findViewById(R.id.mobile_transport);
                View pill = controller.findViewById(R.id.mobile_compact_pill);
                RelativeLayout.LayoutParams pillParams = (RelativeLayout.LayoutParams) pill.getLayoutParams();
                pillParams.setMarginEnd(0);
                pill.setLayoutParams(pillParams);
                RelativeLayout.LayoutParams transportParams = (RelativeLayout.LayoutParams) transport.getLayoutParams();
                transportParams.removeRule(RelativeLayout.CENTER_IN_PARENT);
                transportParams.addRule(RelativeLayout.ALIGN_PARENT_END);
                int buttonSize = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_buttons_height);
                transportParams.setMarginEnd(buttonSize + pill.getPaddingStart() + pill.getPaddingEnd());
                transport.setLayoutParams(transportParams);
            }
        }
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (STANDARD_TITLE_BUTTON_ROW.equals(position)) {
            RelativeLayout buttons = controller.findViewById(R.id.button_bar);
            params.addRule(RelativeLayout.ALIGN_PARENT_START);
            params.addRule(RelativeLayout.START_OF, R.id.mobile_transport);
            var metrics = standardTitle.getPaint().getFontMetricsInt();
            int titleHeight = Math.max(row.getMinimumHeight(), metrics.bottom - metrics.top);
            if (showStatus) {
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                params.addRule(RelativeLayout.ABOVE, R.id.mobile_title_status);
                RelativeLayout.LayoutParams statusParams = (RelativeLayout.LayoutParams) status.getLayoutParams();
                statusParams.removeRule(RelativeLayout.CENTER_VERTICAL);
                statusParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                status.setLayoutParams(statusParams);
                status.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                titleHeight += status.getMeasuredHeight();
            } else {
                params.addRule(RelativeLayout.CENTER_VERTICAL);
            }
            ((ViewGroup) row.getParent()).removeView(row);
            buttons.addView(row, 0, params);
            ViewGroup.LayoutParams buttonParams = buttons.getLayoutParams();
            buttonParams.height = Math.max(buttonParams.height, titleHeight);
            buttons.setLayoutParams(buttonParams);
        } else if (STANDARD_TITLE_BELOW.equals(position)) {
            // file name under the seek bar and the play / forward / backward buttons
            View bar = controller.findViewById(R.id.control_bar);
            if (bar instanceof android.widget.LinearLayout) {
                standardTitle.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                standardTitle.setGravity(Gravity.CENTER);
                standardTitle.setPaddingRelative(0, 0, 0, 0);
                ((ViewGroup) row.getParent()).removeView(row);
                ((android.widget.LinearLayout) bar).addView(row, new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        } else if (STANDARD_TITLE_TOP.equals(position)) {
            standardTitle.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            standardTitle.setGravity(Gravity.CENTER);
            standardTitle.setPaddingRelative(0, 0, 0, 0);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            int margin = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_title_track_margin);
            int top = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_top_title_margin);
            int padding = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_top_title_padding);
            params.setMargins(margin, top, margin, 0);
            row.setPadding(padding, padding, padding, padding);
            ((ViewGroup) row.getParent()).removeView(row);
            ((ViewGroup) controller).addView(row, params);
            row.setVisibility(View.GONE);
            if (background && controller == mControllerViewLeft) {
                mTopTitleRow = row;
                mTopTitleBackground = new TopTitleBackground(
                        getDrawable(mContext, R.drawable.mobile_title_row_gradient), margin);
                row.addOnLayoutChangeListener((view, left, y, right, bottom, oldLeft, oldY, oldRight, oldBottom) ->
                        updateTopTitleBackground());
            }
        }
    }

    private void initControllerView(View v, boolean isMainView) {
        configureCompactPhoneControls(v);
        configureMiniPhoneControls(v);
        configureStandardTitle(v);
        mPlainButtonRow = mMiniUi;
        bindPhoneControlsPill(v);
        if (isMainView && (isTVMode || TVUtils.isTV(mContext))) setupTvSwipePill(v);
        // next TV menu

        View mControlBar = v.findViewById(R.id.control_bar);

        if(mControlBar!=null &&isMainView){
            mControlBar.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    // TODO Auto-generated method stub
                }
            });
        }
        ImageButton mPauseButton = (ImageButton) v.findViewById(R.id.pause);
        if (mPauseButton != null) {

            mPauseButton.setOnClickListener(mPauseListener);
        }

        View playPauseTouchZone = v.findViewById(R.id.play_touch_zone);
        if(playPauseTouchZone != null &&mPlayPauseOnTouchActivated)
            if (isTVMode || TVUtils.isTV(mContext)) {
                playPauseTouchZone.setOnClickListener(mPauseListener);
            } else {
                playPauseTouchZone.setOnClickListener(null);
                playPauseTouchZone.setVisibility(View.GONE);
            }

        // Find central play icon
        ImageView centralPlayIcon = (ImageView) v.findViewById(R.id.central_play_icon);
        if (log.isDebugEnabled()) log.debug("initControllerView: looking for central_play_icon in v, found = {}", (centralPlayIcon != null));

        ImageButton mForwardButton = (ImageButton) v.findViewById(R.id.forward);
        if (mForwardButton != null) {
            mForwardButton.setOnClickListener(mForwardListener);
            mForwardButton.setOnLongClickListener(mForwardLongListener);
        }


        ImageButton mBackwardButton = (ImageButton) v.findViewById(R.id.backward);
        if (mBackwardButton != null) {
            mBackwardButton.setOnClickListener(mBackwardListener);
            mBackwardButton.setOnLongClickListener(mBackwardLongListener);
        }


        ImageButton mFormatButton = (ImageButton) v.findViewById(R.id.format);
        if (mFormatButton != null) {
            mFormatButton.setOnClickListener(mFormatListener);
        }

        ImageButton fullscreenWithCutoutButton = (ImageButton) v.findViewById(R.id.fullscreen_cutouts);
        if (fullscreenWithCutoutButton != null) {
            //If we have a Cutout, show the Fullscreen with Cutouts Button
            if (hasCutout()) {
                setFullscreenWithCutoutButtonIcon(mSurfaceController.mFullScreenWithCutout);
                fullscreenWithCutoutButton.setVisibility((isTVMode || TVUtils.isTV(mContext)) ? View.GONE : View.VISIBLE);
                fullscreenWithCutoutButton.setOnClickListener(fullscreenWithCutoutListener);
            } else {
                fullscreenWithCutoutButton.setVisibility(View.GONE);
                fullscreenWithCutoutListener = null;
                fullscreenWithCutoutButton = null;
            }
        }

        SeekBar mProgress = (SeekBar) v.findViewById(R.id.seek_progress);
        if (mProgress != null) {
            mProgress.setOnSeekBarChangeListener(mProgressListener);
            
            mProgress.setMax(1000);
        }

        TextView bottomTitle = v.findViewById(R.id.mobile_video_title);
        if (bottomTitle != null) {
            bottomTitle.setText(mVideoTitle.getText());
            if (v.findViewById(R.id.mobile_compact_transport) == null) {
                TextView timeDisplay = v.findViewById(R.id.time_current);
                View.OnClickListener toggleRemaining = button -> {
                    mPhoneRemainingTime = !mPhoneRemainingTime;
                    setProgress();
                    sendFadeOut(SHOW_TIMEOUT);
                };
                timeDisplay.setOnClickListener(toggleRemaining);
                View totalDisplay = v.findViewById(R.id.time);
                if (totalDisplay != null) totalDisplay.setOnClickListener(toggleRemaining);
            }
            armTitleMarquee(bottomTitle);
        }
        if (v.findViewById(R.id.mobile_title_row) != null) {
            View.OnClickListener optionsListener = button -> {
                if (mContext instanceof PlayerActivity) {
                    show(FLAG_SIDE_CONTROL_BAR, 0);
                    ((PlayerActivity) mContext).showMobilePlaybackOptions(button);
                }
            };
            // the compact UI has no audio channels button in its bar (it lives in the swipe-up pill)
            for (int id : new int[]{R.id.mobile_audio_channels, R.id.mobile_audio, R.id.mobile_subtitles,
                    R.id.mobile_more}) {
                View optionsButton = v.findViewById(id);
                if (optionsButton != null) optionsButton.setOnClickListener(optionsListener);
            }



        }

        View volumeBar = v.findViewById(R.id.volume_bar);

        if(isChromeOS(mContext)) {
            if (volumeBar != null) volumeBar.setVisibility(View.GONE);
            mControlBar.setPadding(0,0,0,0);
            volumeBar = null;
        }

        final View finalVolumeBar = volumeBar;
        if (finalVolumeBar != null) {
            if(isMainView){
                //mVolumeBar.setFocusable(true);
                finalVolumeBar.setOnFocusChangeListener(new OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        // The focus display is handled by mPlayerController
                        int resId = hasFocus ? R.drawable.volume_bar_background_focused : R.drawable.volume_bar_background;
                        finalVolumeBar.setBackgroundResource(resId);
                        if(mVolumeBar2!=null)
                            mVolumeBar2.setBackgroundResource(resId);

                    }
                });


            }
            mVolumeBarEnabled = true;
            SeekBar mVolumeLevel = (SeekBar) v.findViewById(R.id.volume_level);
            if (mVolumeLevel != null) {
                mVolumeLevel.setMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                mVolumeLevel.setOnSeekBarChangeListener(mVolumeLevelListener);

            }
            RepeatingImageButton mVolumeUpButton = (RepeatingImageButton) v.findViewById(R.id.volume_up);
            mVolumeUpButton.setOnClickListener(mVolumeUpListener);
            mVolumeUpButton.setRepeatListener(mVolumeUpRepeatListener, 100);
            RepeatingImageButton mVolumeDownButton = (RepeatingImageButton) v.findViewById(R.id.volume_down);
            mVolumeDownButton.setOnClickListener(mVolumeDownListener);
            mVolumeDownButton.setRepeatListener(mVolumeDownRepeatListener, 100);

            if(isMainView){

                this.mVolumeUpButton=mVolumeUpButton;
                this.mVolumeDownButton=mVolumeDownButton;
                this.mVolumeLevel = mVolumeLevel;
            }
            else{
                this.mVolumeUpButton2=mVolumeUpButton;
                this.mVolumeDownButton2=mVolumeDownButton;
                this.mVolumeLevel2 = mVolumeLevel;
            }


        } else {
            mVolumeBarEnabled = false;
        }
        View unlockInstructions = v.findViewById(R.id.unlock_instructions);
        View lock = null;

        if (isMainView) {
            this.mUnlockInstructions = unlockInstructions;
            this.mVolumeBar = volumeBar;
            this.mPlayPauseTouchZone = playPauseTouchZone;
            this.mCentralPlayIcon = centralPlayIcon;
            this.mControlBar=mControlBar;
            sControlBarView = mControlBar;
            if (mControlBar != null) {
                // control_bar is GONE until first shown, so its geometry is stale when
                // OnControlBarVisibilityListener fires on the first show. Re-notify once it
                // has really been laid out so subtitles clear the whole bar.
                // Deliberately NOT on every size change: the bar's height jitters during
                // playback and notifying each time makes the subtitles bounce. Notify only when
                //  - the bar reaches a new maximum height (first layout, or it genuinely grew), or
                //  - it was merely translated (same height, e.g. nav bar hid/showed).
                mControlBar.addOnLayoutChangeListener((bar, l, t, r, b, ol, ot, or_, ob) -> {
                    int h = b - t;
                    int oh = ob - ot;
                    boolean grew = h > sControlBarMaxHeight;
                    if (grew) sControlBarMaxHeight = h;
                    boolean translated = h > 0 && h == oh && t != ot;
                    if (grew || translated) {
                        // View.layout() sets isLaidOut() only AFTER it has called the layout-change
                        // listeners, so notifying here would make getControlBarClearanceAbove()
                        // see an un-laid-out bar and return 0. Defer until layout has completed.
                        bar.post(() -> {
                            if (bar == sControlBarView && mOnControlBarVisibilityListener != null)
                                mOnControlBarVisibilityListener.onControlBarVisibilityChanged(mControlBarShowing);
                        });
                    }
                });
            }
            this.mPauseButton=mPauseButton;
            this.mFormatButton=mFormatButton ;
            this.mFullscreenWithCutoutButton = fullscreenWithCutoutButton;
            setFullscreenWithCutoutButtonIcon(mSurfaceController.mFullScreenWithCutout);
            this.mBackwardButton=mBackwardButton ;
            this.mForwardButton=mForwardButton ;
            this.mProgress=mProgress ;
            mEndTime = (TextView) v.findViewById(R.id.time);
            mSeekState = v.findViewById(R.id.seek_state);
            mCurrentTime = (TextView) v.findViewById(R.id.time_current);
            // The clock is only for actual leanback devices
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK) || isChromeOS(mContext)) {
                if (log.isDebugEnabled()) log.debug("initControllerView: FEATURE_LEANBACK");
                mClock = (TextView) v.findViewById(R.id.clock);
                if(mClock!=null) {
                    // in the player we change the typeface and add shadow to improve visibility over the video plane
                    mClock.setShadowLayer(2, 0, 0, Color.BLACK);
                    mClock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    mClock.setTextColor(Color.WHITE);
                    if (DateFormat.is24HourFormat(mContext)) {
                        mDateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
                    } else {
                        mDateFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
                    }
                    updateClock();
                }
            } else if (log.isDebugEnabled()) log.debug("initControllerView: no FEATURE_LEANBACK");
        }
        else{
            this.mUnlockInstructions2 = unlockInstructions;
            this.mVolumeBar2 = mVolumeBar;
            this.mControlBar2=mControlBar;
            this.mPauseButton2=mPauseButton;
            //this.mFormatButton2=mFormatButton;
            //this.mBackwardButton2=mBackwardButton;
            this.mProgress2=mProgress;
            //this.mForwardButton2=mForwardButton ;
            mCurrentTime2 = (TextView) v.findViewById(R.id.time_current);
            mEndTime2 = (TextView) v.findViewById(R.id.time);
            mSeekState2 = v.findViewById(R.id.seek_state);
        }

        updateVolumeBar();
        mFormatBuilder = new StringBuilder();
        mFormatter = new Formatter(mFormatBuilder, Locale.getDefault());

        if (mActionBar != null) mActionBar.hide();

        if(mIsLocked)
            lock();
    }

    public void setSizes(int displayWidth, int displayHeight, int layoutWidth, int layoutHeight) {
        // to debug nvplog | grep Player | grep -i -E 'size|height|width'
        // on phones layoutWidth is smaller than MATCH_PARENT by navigationBarHeight thus cannot use ViewGroup.LayoutParams.MATCH_PARENT=-1
        mLayoutWidth = layoutWidth;
        mLayoutHeight = layoutHeight;
        mSystemBarHeight = displayHeight - mLayoutHeight;
        if (log.isDebugEnabled()) log.debug("CONFIG setSizes layout: {}x{} / display: {}x{}, systemBarHeight: {}", mLayoutWidth, mLayoutHeight, displayWidth, displayHeight, mSystemBarHeight);
        if (mControllerView != null) {
            if (log.isDebugEnabled()) log.debug("CONFIG setSizes, mControllerView != null, recreate whole layout");
            // size changed and maybe orientation too, recreate the whole layout
            detachWindow();
            attachWindow();
        } else {
            if (log.isDebugEnabled()) log.debug("CONFIG setSizes, mControllerView == null, doing nothing");
        }
    }



    // setOnSystemUiVisibilityChangeListener is the only reliable way to track transient bar visibility;
    // no WindowInsetsControllerCompat equivalent exists for this use case.
    @SuppressLint("InflateParams") // Controller views are detached before being added with custom RelativeLayout.LayoutParams to mPlayerView
    @SuppressWarnings("deprecation")
    private void attachWindow() {
        SharedPreferences mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        if (mPreferences != null) mFullScreenWithCutout = mPreferences.getBoolean("enable_cutout_mode_short_edges", true);

        if (log.isDebugEnabled()) log.debug("CONFIG attachWindow getStatusBarHeight={}, getNavigationBarHeight={}, getActionBarHeight={}, getGestureAreaHeight={}, isGestureAreaDisplayed={}, ",
                MiscUtils.getStatusBarHeight(mContext), MiscUtils.getNavigationBarHeight(mContext), MiscUtils.getActionBarHeight(mContext),
                MiscUtils.getGestureAreaHeight(mContext), MiscUtils.isGestureAreaDisplayed(mContext));

        if (mControllerView != null) return;
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        int layoutID=R.layout.player_controller;
        mControllerView = inflater.inflate(layoutID, null);
        if (mControllerView == null) return;
        mControllerView.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        mControllerView.setOnTouchListener(this);
        mControllerView.setOnGenericMotionListener(this);
        // twice for sidebyside and topbottom view
        mControllerViewLeft= inflater.inflate(getControllerLayout(), null);
        if (mControllerViewLeft != null) {
            mControllerViewLeft.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            mOsdLeftTextView = mControllerViewLeft.findViewById(R.id.osd_left);
            mOsdRightTextView = mControllerViewLeft.findViewById(R.id.osd_right);
            mControllerViewLeft.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    // TODO Auto-generated method stub
                    return PlayerController.this.onKey(keyCode, event);
                }
            });
        }

        initControllerView(mControllerViewLeft, true);
        if (mControllerViewLeft != null) {
            mControllerViewLeft.setOnKeyListener(new View.OnKeyListener() {

                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    // TODO Auto-generated method stub
                    return PlayerController.this.onKey(keyCode, event);
                }
            });
        }


        if (mControllerView == null) return;

        playerControllersContainer = (FrameLayout)mControllerView.findViewById(R.id.playerControllersContainer);
        if (playerControllersContainer != null && mControllerViewLeft != null)
            playerControllersContainer.addView(mControllerViewLeft);

        if (log.isDebugEnabled()) log.debug("CONFIG attachWindow: layout WxH {}x{}", mLayoutWidth, mLayoutHeight);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(mLayoutWidth, mLayoutHeight);
        mPlayerView.addView(mControllerView, params);
        if (log.isDebugEnabled()) log.debug("CONFIG attachWindow, adjustControllerViewForInsets();");

        if (mControllerView != null) {
            mRootView = mControllerView.getRootView();
            // note OnApplyWindowInsetsListener does not update when navigation bar fades away, OnGlobalLayoutListener or addOnPreDrawListener are constantly triggering -> only setOnSystemUiVisibilityChangeListener works
            // however setOnSystemUiVisibilityChangeListener is unreliable on Android 6.0 thus use addOnLayoutChangeListener
            // in reality we need to do combination of setOnApplyWindowInsetsListener to get insets but not updated when UI mode changes and thus combine with setOnSystemUiVisibilityChangeListener

            // insets observer is needed for rotation
            mControllerView.setOnApplyWindowInsetsListener((v, insets) -> {
                if (log.isDebugEnabled()) log.debug("attachWindow, onApplyWindowInsetsListener");
                if (!adjustPhoneControllerInsets(WindowInsetsCompat.toWindowInsetsCompat(insets, v)))
                    adjustView();
                return insets;
            });

            // ui visibility listener is needed for UI mode changes
            // No WindowInsetsControllerCompat equivalent for transient bar visibility tracking;
            // setOnSystemUiVisibilityChangeListener remains the only reliable option here.
            //noinspection deprecation
            mRootView.setOnSystemUiVisibilityChangeListener(visibility -> {
                //noinspection deprecation
                mNavigationBarShowing = (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;
                //noinspection deprecation
                mSystemBarShowing = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
                // SYSTEM_UI_FLAG_LOW_PROFILE is no longer set by WindowInsetsControllerCompat;
                // mActionBarShowing is tracked directly by showActionBar(), so skip update here.
                mIsNavBarOnBottom = MiscUtils.isNavigationBarOnBottom(mRootView, mContext);
                mIsGestureAreaShowing = MiscUtils.isGestureAreaDisplayed(mContext);
                mGestureAreaHeight = MiscUtils.getGestureAreaHeight(mContext);
                // assume that controlBar (seek bar + controls) is visible if mNavigationBarShowing || mIsGestureAreaShowing
                mControlBarHeight = (mNavigationBarShowing || mIsGestureAreaShowing ? mControlBar.getHeight() : 0);
                if (log.isDebugEnabled()) log.debug("attachWindow, setOnSystemUiVisibilityChangeListener: mNavigationBarShowing={}, mSystemBarShowing={}, mActionBarShowing={}, mControlBarShowing={}, mIsNavBarOnBottom={}, mIsGestureAreaShowing={}",
                        mNavigationBarShowing, mSystemBarShowing, mActionBarShowing, mControlBarShowing, mIsNavBarOnBottom, mIsGestureAreaShowing);
                adjustView();
            });

            ViewCompat.requestApplyInsets(mControllerView);

        }
        if (log.isDebugEnabled()) log.debug("CONFIG attachWindow, mPlayerView.addView");

        initMenuAdapter(mControllerViewLeft);
        switchMode(TVUtils.isTV(mContext));
        setUIMode(UIMode);

        //help overlay
        if(mContext instanceof PlayerActivity){
            if(((PlayerActivity)mContext).isPluggedOnTv()){
                mPreferences =  mContext.getSharedPreferences(MediaUtils.SHARED_PREFERENCES_NAME, mContext.MODE_PRIVATE);
                if(!mPreferences.getBoolean(PLAYER_HELP_OVERLAY_KEY, false)){
                    showHelpOverlay(mControllerViewLeft);
                    if(mControllerViewRight!=null && UIMode!=VideoEffect.NORMAL_2D_MODE){
                        showHelpOverlay(mControllerViewRight);
                    }
                    Editor ed = mPreferences.edit();
                    ed.putBoolean(PLAYER_HELP_OVERLAY_KEY, true);
                    ed.apply();
                    sendOverlayFadeOut(6000);
                }
            }
        }
        showControlBar(mControlBarShowing);
    }

    private void adjustView() {
        if (mControllerView != null && adjustPhoneControllerInsets(
                ViewCompat.getRootWindowInsets(mControllerView))) return;
        if (mControlBar != null) {
            mControlBarHeight = (mNavigationBarShowing || mIsGestureAreaShowing ? mControlBar.getHeight() : 0);
        }
        MiscUtils.adjustViewLayoutForInsets(mContext, mRootView, mControllerView,"mControllerView",
                mNavigationBarShowing, mSystemBarShowing, mActionBarShowing, mControlBarShowing, mIsNavBarOnBottom, mIsGestureAreaShowing,
                0, 0,
                true, true, true, true,
                true, true, true, true, false, false);
    }

    /** Bottom-anchored phone controls, spanning the cutout area in landscape. */
    private boolean adjustPhoneControllerInsets(WindowInsetsCompat insets) {
        if (isTVMode || TVUtils.isTV(mContext)) return false;
        if (insets == null || mControllerView == null) return true;
        androidx.core.graphics.Insets cutout = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.displayCutout());
        androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        boolean landscape = mContext.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // In landscape the seek bar and buttons use the notification/cutout strip too.
        // Only visible navigation bars reserve space; hidden gesture bars do not lift the controls.
        int left = landscape ? bars.left : Math.max(cutout.left, bars.left);
        int top = Math.max(cutout.top, bars.top);
        int right = landscape ? bars.right : Math.max(cutout.right, bars.right);
        int bottom = landscape ? bars.bottom : Math.max(cutout.bottom, bars.bottom);
        ViewGroup.LayoutParams params = mControllerView.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) return true;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
        if (margins.leftMargin != 0 || margins.topMargin != 0
                || margins.rightMargin != 0 || margins.bottomMargin != 0
                || margins.width != ViewGroup.LayoutParams.MATCH_PARENT
                || margins.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            // Keep the overlay/gradient edge-to-edge; only its contents avoid unsafe areas.
            margins.setMargins(0, 0, 0, 0);
            margins.width = ViewGroup.LayoutParams.MATCH_PARENT;
            margins.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mControllerView.setLayoutParams(margins);
        }
        if (mControllerView.getPaddingTop() != top
                || mControllerView.getPaddingLeft() != 0
                || mControllerView.getPaddingRight() != 0
                || mControllerView.getPaddingBottom() != 0) {
            mControllerView.setPadding(0, top, 0, 0);
            updateTopTitleBackground();
        }
        if (mMiniUi) {
            View topTitle = mControllerView.findViewById(R.id.mobile_title_row);
            if (topTitle != null && topTitle.getParent() == mControllerView) applyMiniTopTitleInsets(topTitle, left, right);
        }
        if (mControlBar != null) {
            int paddingTop = mContext.getResources().getDimensionPixelSize(R.dimen.mobile_controls_padding_top);
            int bottomPaddingRes = isCompactPhoneControls()
                    ? R.dimen.mobile_compact_controls_padding_bottom
                    : R.dimen.mobile_controls_padding_bottom;
            int paddingBottom = mContext.getResources().getDimensionPixelSize(bottomPaddingRes) + bottom;
            // compact UI: extra room at the left and right of the whole control area
            final int sidePadding = mMiniUi ? swipeDp(16) : 0;
            final int paddingLeft = left + sidePadding;
            final int paddingRight = right + sidePadding;
            if (mControlBar.getPaddingLeft() != paddingLeft || mControlBar.getPaddingRight() != paddingRight
                    || mControlBar.getPaddingTop() != paddingTop || mControlBar.getPaddingBottom() != paddingBottom) {
                mControlBar.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
            }
        }
        return true;
    }

    public static int getControlBarCurrentHeight() {
        return mControlBarHeight;
    }

    /**
     * Pixels the on-screen control bar reaches above the bottom edge of the given reference
     * view, or 0 if the bar is not currently shown (or not laid out yet).
     *
     * Unlike getControlBarCurrentHeight() (0 unless the nav bar / gesture area happens to be
     * showing, so unreliable on TV) this measures control_bar's real on-screen top edge, so it
     * is correct for every layout variant. The bar's height is NOT a fixed dimen:
     *   - land / sw500dp-notouch / sw600dp: a single row, sized by player2014_bar_width.
     *   - default (portrait phone): a vertical LinearLayout (wrap_content) holding the seek
     *     bar + time row AND the button row, well over 100dp tall, and never using
     *     player2014_bar_width at all.
     * control_bar's top is the top of everything in it (seek bar, times, buttons), so the
     * distance from there to the reference view's bottom is the clearance text subtitles need.
     *
     * The bar is GONE until first shown, so its position is only valid after its first layout.
     * This returns 0 until then; initControllerView() registers a layout listener that
     * re-notifies OnControlBarVisibilityListener once the bar has actually been laid out, so
     * SubtitleManager recomputes with fresh geometry.
     *
     * Tracks only the main pane's bar (sControlBarView is set from the isMainView branch of
     * initControllerView()). splitView/mControlBar2 is private to PlayerController, so there
     * is no second SubtitleManager/SubtitleEngine instance that could ask for clearance
     * against the secondary pane's bar. If a future change wires subtitles into split-view,
     * this will need its own reference per pane.
     */
    public static int getControlBarClearanceAbove(View reference) {
        boolean seekOnly = sSeekOnlyView != null && sSeekOnlyView.isShown();
        View bar = seekOnly ? sSeekOnlyView : sControlBarView;
        if (reference == null || bar == null || (!seekOnly && !mControlBarShowing)
                || !bar.isShown() || !bar.isLaidOut() || !reference.isLaidOut()) return 0;
        int[] barLoc = new int[2];
        int[] refLoc = new int[2];
        bar.getLocationOnScreen(barLoc);
        reference.getLocationOnScreen(refLoc);
        if (seekOnly) return Math.max(0, refLoc[1] + reference.getHeight() - barLoc[1]);
        // The bar is bottom-anchored, so derive its top from its bottom and the sticky maximum
        // height. This ignores transient height jitter, which would otherwise move the subtitles.
        int barBottom = barLoc[1] + sControlBarView.getHeight();
        int barTop = barBottom - Math.max(sControlBarView.getHeight(), sControlBarMaxHeight);
        int refBottom = refLoc[1] + reference.getHeight();
        return Math.max(refBottom - barTop, 0);
    }

    public static boolean isControlBarShowing() {
        return mControlBarShowing;
    }

    public static boolean isActionBarShowing() {
        return mActionBarShowing;
    }

    private void showHelpOverlay(View controllerView) {
        View overlay = controllerView.findViewById(R.id.help_overlay);
        if(overlay!=null){
            overlay.setAlpha(0);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1);
            ImageView icon = (ImageView)overlay.findViewById(R.id.up_arrow_image);
            if (icon.getBackground() instanceof AnimationDrawable) {
                ((AnimationDrawable)icon.getBackground()).start();
            }
        }
    }

    private void detachWindow() {
        clearPhoneGesture();
        mSeekOnlySession = false;
        clearSeekOnly();
        if (mControllerView == null)
            return;
        if (log.isDebugEnabled()) log.debug("detachWindow");
        mHandler.removeCallbacks(hideMobileVolumeValue);
        mPlayerView.removeView(mControllerView);
        mControllerView = null;
        mControllerViewLeft=null;
        mControllerViewRight=null;
        mTopTitleRow = null;
        mTopTitleBackground = null;
        sControlBarView = null;
        sControlBarMaxHeight = 0;
    }

    public void setMediaPlayer(IPlayerControl player) {
        reset();

        mPlayer = player;
        updatePausePlay();
    }

    /**
     * Disable pause or seek buttons if the stream cannot be paused or seeked.
     * This requires the control interface to be a MediaControlExt
     */
    private void updatePauseButton() {
        if (isSeekOnlyShowing()) return;
        if (mSeekState == null || mPauseButton == null)
            return;
        if (Player.sPlayer.isBusy()) {
            mSeekState.setVisibility(View.VISIBLE);
        } else {
            mSeekState.setVisibility(View.GONE);
        }
        if (mPauseButton != null) {
            mPauseButton.setEnabled(mEnabled && Player.sPlayer.canPause() && !Player.sPlayer.isBusy());
            if(mPauseButton2!=null)
                mPauseButton2.setEnabled(mEnabled && Player.sPlayer.canPause() && !Player.sPlayer.isBusy());
        }
        if (mFormatButton != null) {
            mFormatButton.setEnabled(mEnabled);
        }
    }

    private void updateButtons() {
        if (mIsStopped)
            return;
        updatePauseButton();

        if (mBackwardButton != null) {
            mBackwardButton.setEnabled(mEnabled && Player.sPlayer.canSeekBackward());
        }
        if (mForwardButton != null) {
            mForwardButton.setEnabled(mEnabled && Player.sPlayer.canSeekForward());
        }
        if (mProgress != null) {
            mProgress.setEnabled(mEnabled && (Player.sPlayer.canSeekForward() || Player.sPlayer.canSeekBackward()));
        }
    }

    public boolean isShowing() {
        return mControlBarShowing || mActionBarShowing || mVolumeBarShowing;
    }

    private void setVisibility(final View view, boolean show, boolean doAnim) {
        if (mIsStopped) return;
        if (show) view.setVisibility(View.VISIBLE);
        else view.setVisibility(View.GONE);
    }

    private void showActionBar(boolean show) {
        if (mActionBar == null) return;
        if (!isTVMode && !TVUtils.isTV(mContext)) show = false;
        if (isTVMode || TVUtils.isTV(mContext)) {
            if (!mControlBarShowing || Player.sPlayer == null) show = false;
        }
        if (mActionBarShowing != show || mActionBar.isShowing() != show) {
            if (show) mActionBar.show();
            else mActionBar.hide();
        }
        mActionBarShowing = show;
    }
    public View getVolumeBar(){
        return mVolumeBar;
    }
    public View getControlBar(){
        return mControlBar;
    }
    protected void showSystemBar(boolean show) {
        // Showing playback controls should not bring the phone status bar over the video.
        if (!isTVMode && !TVUtils.isTV(mContext)) {
            show = false;
            mInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            mNavigationBarShowing = false;
        }
        if (log.isDebugEnabled()) log.debug("showSystemBar {}", show);
        if (show) {
            mInsetsController.show(WindowInsetsCompat.Type.systemBars());
            mNavigationBarShowing = true;
        } else {
            mInsetsController.hide(WindowInsetsCompat.Type.statusBars());
        }
        manualVisibilityChange=true;
        mSystemBarGone = false;
        if (!show)
            mHandler.sendEmptyMessageDelayed(MSG_HIDE_SYSTEM_BAR, 1000);
        mSystemBarShowing = show;
    }

    private void showControlBar(boolean show) {
        if (isSeekOnlyShowing() && !show) return;
        if (log.isDebugEnabled()) log.debug("showControlBar {}", show);
        if (show && !isTVMode && !TVUtils.isTV(mContext)) showSystemBar(false);
        if (show && !mControlBarShowing) {
            applyNightModeEverywhere();             // night mode stays on when the controls come back
            // compact UI: reopen on the playback row, except when coming back from the video popup that
            // was opened from the Controls button row
            if (mKeepPlainRowOnShow) mKeepPlainRowOnShow = false;
            else collapsePlainControls();
        }
        if (mControlBar != null && (mControlBarShowing != show
                || mControlBar.getVisibility() != (show ? View.VISIBLE : View.GONE))) {
            if (log.isDebugEnabled()) log.debug("showControlBar {}", String.valueOf(show));
            mControlBarShowing = show;
            if (!show) sControlBarMaxHeight = 0;
            adjustView();
            setVisibility(mControlBar, show, true);
            boolean titleVisible = show && !mPhoneTrackPopupOpen && !isCompactPillExpanded();
            updateStandardTopTitle(mControllerViewLeft, titleVisible);
            updateStandardTopTitle(mControllerViewRight, titleVisible && splitView);
            if(mPlayPauseTouchZone!=null){
                setVisibility(mPlayPauseTouchZone, show, false);
            }
            if (mControlBar2 != null && splitView ) {
                setVisibility(mControlBar2, show, true);
            }

            if (mClock!=null) {
                setVisibility(mClock, show && !splitView, true);
            }

            if (show) {
                setProgress();

                updateButtons();
                updatePausePlay();
                updateFormat();
            }

            if (mOnControlBarVisibilityListener != null) {
                mOnControlBarVisibilityListener.onControlBarVisibilityChanged(show);
            }
        }
    }

    private void showVolumeBar(boolean show) {
        if (!TVUtils.isTV(mContext)) return;
        if (log.isDebugEnabled()) log.debug("showVolumeBar {}", show);
        if (mVolumeBarEnabled && mVolumeBarShowing != show) {
            if (log.isDebugEnabled()) log.debug("showVolumeBar, volume2");
            setVisibility(mVolumeBar, show, true);
            if(mVolumeBar2!=null&&splitView){
                setVisibility(mVolumeBar2, show, true);
                if (log.isDebugEnabled()) log.debug("showVolumeBar, showing volume bar2");
            }
            mVolumeBarShowing = show;
        }
    }

    /**
     * Show the controller on screen. It will go away
     * automatically after 3 seconds of inactivity.
     */
    public void show() {
        show(FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS, SHOW_TIMEOUT);
    }

    private boolean mPhoneTrackPopupOpen;
    private boolean isCompactPhoneControls() {
        return mControllerViewLeft != null
                && mControllerViewLeft.findViewById(R.id.mobile_compact_transport) != null;
    }

    public boolean isPhoneTrackPopupOpen() {
        return mPhoneTrackPopupOpen;
    }

    public boolean isSwipePillShowing() {
        return mSwipePillShowing;
    }

    /**
     * Remote: after a popup opened from a button closes, the cursor goes back to that button, in the buttons
     * pill when the popup was opened from there, otherwise in the control area.
     */
    public void focusOptionsButton(int buttonId, boolean fromPill) {
        if (buttonId == 0) return;
        if (fromPill && mSwipePill != null) {
            if (!mSwipePillShowing) showSwipePill();
            View button = mSwipePill.findViewById(buttonId);
            if (button != null) {
                button.setFocusable(true);
                button.requestFocus();
            }
            return;
        }
        if (mControllerViewLeft == null) return;
        if (!mControlBarShowing) show();
        View button = findShownById(mControllerViewLeft, buttonId);
        if (button != null) {
            button.setFocusable(true);
            button.requestFocus();
        }
    }

    private static View findShownById(View root, int id) {
        if (root.getId() == id && root.isShown()) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findShownById(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void setPhoneTrackPopupOpen(boolean open) {
        mPhoneTrackPopupOpen = open;
        if (mOnControlBarVisibilityListener != null && mMiniUi) mOnControlBarVisibilityListener.onControlBarVisibilityChanged(mControlBarShowing);
        // Compact UI: the control area stays on screen while a popup is open, so it must not time out
        if (mMiniUi && open) mHandler.removeMessages(MSG_FADE_OUT);
        applyPhoneTrackPopupVisibility();
    }

    private void updateStandardTopTitle(View controller, boolean visible) {
        if (controller == null) return;
        View title = controller.findViewById(R.id.mobile_title_row);
        if (title != null && title.getParent() == controller) {
            title.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (title == mTopTitleRow) updateTopTitleBackground();
        }
    }

    private void updateTopTitleBackground() {
        if (mControllerView == null || mTopTitleBackground == null) return;
        if (mTopTitleRow.getVisibility() != View.VISIBLE || mTopTitleRow.getHeight() == 0) {
            if (mControllerView.getBackground() == mTopTitleBackground) mControllerView.setBackground(null);
            return;
        }

        // Draw behind the controller's safe-area padding while keeping the title in its usual place.
        mTopTitleBackground.setHeight(mControllerView.getPaddingTop() + mTopTitleRow.getBottom());
        if (mControllerView.getBackground() != mTopTitleBackground) {
            mControllerView.setBackground(mTopTitleBackground);
        }
    }

    private void applyPhoneTrackPopupVisibility() {
        if (isSeekOnlyShowing()) return;
        if (isTVMode || TVUtils.isTV(mContext)) return;
        for (View controller : new View[]{mControllerViewLeft, mControllerViewRight}) {
            if (controller == null) continue;
            // Compact UI: a popup never hides the title, seek bar or buttons, and closing it must not bring back
            // the playback row while the row with all the buttons is open
            if (mMiniUi) continue;
            View title = controller.findViewById(R.id.mobile_title_row);
            View seek = controller.findViewById(R.id.seek_progress);
            int visibility = mPhoneTrackPopupOpen || isCompactPillExpanded() ? View.INVISIBLE : View.VISIBLE;
            if (title != null) {
                if (title.getParent() == controller) {
                    updateStandardTopTitle(controller, mControlBarShowing && visibility == View.VISIBLE);
                } else {
                    title.setVisibility(visibility);
                }
            }
            View status = controller.findViewById(R.id.mobile_title_status);
            if (status != null && status.getVisibility() != View.GONE) {
                status.setVisibility(visibility);
            }
            if (seek != null) seek.setVisibility(visibility);
            if (controller.findViewById(R.id.mobile_controls_toggle) != null) {
                View transport = controller.findViewById(R.id.mobile_compact_transport);
                if (transport != null) {
                    transport.setVisibility(mControlBarShowing && visibility == View.VISIBLE && !mIsLocked
                            ? View.VISIBLE : View.GONE);
                }
                View standardTransport = controller.findViewById(R.id.mobile_transport);
                if (standardTransport != null) standardTransport.setVisibility(visibility);
                if (!mControlBarShowing && !mSwipePillShowing && !mPlainButtonRow) {
                    controller.findViewById(R.id.mobile_compact_actions).setVisibility(View.GONE);
                    controller.findViewById(R.id.mobile_controls_toggle).setVisibility(View.VISIBLE);
                }
                controller.findViewById(R.id.time_current).setVisibility(visibility);
            }
            if (mSwipePillShowing) {
                for (int id : new int[]{R.id.mobile_transport, R.id.mobile_compact_transport}) {
                    View transport = controller.findViewById(id);
                    if (transport != null) transport.setVisibility(View.INVISIBLE);
                }
                View toggle = controller.findViewById(R.id.mobile_controls_toggle);
                if (toggle != null) toggle.setVisibility(View.GONE);
            }
        }
        if (mSwipePillShowing && mCentralPlayIcon != null) mCentralPlayIcon.setVisibility(View.GONE);
        if (mPhoneTrackPopupOpen) {
            showActionBar(false);
            if (mClock != null) mClock.setVisibility(View.GONE);
            mHidePhonePill.run();
        }
    }

    private void setOSDVisibility(boolean visible, int flags) {
        if (log.isDebugEnabled()) log.debug("setOSDVisibility, visiblity {}", flags);
        if ((flags & FLAG_SIDE_CONTROL_BAR) != 0) {
            showControlBar(visible);
            // On phone we don't know how to display control bar without volume bar nicely, so force volume bar
            
        }
        if ((flags & FLAG_SIDE_SYSTEM_BAR) != 0) {
            showSystemBar(visible);
        }
        if ((flags & FLAG_SIDE_ACTION_BAR) != 0) {
            showActionBar(visible);
        }
        if ((flags & FLAG_SIDE_VOLUME_BAR) != 0) {
            if (log.isDebugEnabled()) log.debug("setOSDVisibility, volume");
            showVolumeBar(visible);
        }
        if((flags & FLAG_SIDE_UNLOCK_INSTRUCTIONS)!=0){
            showUnlockInstructions(visible);
        }
        applyPhoneTrackPopupVisibility();
        if (!visible && mCentralPlayIcon != null) mCentralPlayIcon.setVisibility(View.GONE);
    }

    /**
     * Show the controller on screen. It will go away
     * automatically after 'timeout' milliseconds of inactivity.
     * @param timeout The timeout in milliseconds. Use 0 to show
     * the controller until hide() is called.
     */
    private void show(int flags, int timeout) {
        if (log.isDebugEnabled()) log.debug("show({}, {})", flags, timeout);
        if (mIsStopped)
            return;
        if (mMiniUi && timeout == SHOW_TIMEOUT) timeout = MINI_SHOW_TIMEOUT;
        if ((flags & FLAG_SIDE_CONTROL_BAR) != 0) clearSeekOnly();
        // While the swipe-up pill is open the control area stays hidden (the pill itself brings it back).
        if (mSwipePillShowing) flags &= ~FLAG_SIDE_CONTROL_BAR;

        // cause the progress bar to be updated even if mShowing
        // was already true.  This happens, for example, if we're
        // paused with the progress bar showing the user hits play.
        mHandler.removeMessages(MSG_SHOW_PROGRESS);
        mHandler.sendEmptyMessage(MSG_SHOW_PROGRESS);
        mHandler.removeMessages(MSG_FADE_OUT);

        if (mHandler.hasMessages(MSG_HIDE_SYSTEM_BAR)) {
            mHandler.removeMessages(MSG_HIDE_SYSTEM_BAR);
            flags |= FLAG_SIDE_SYSTEM_BAR;
        }
        setOSDVisibility(true, flags);

        // isPlaying() is still false right after start() (resume / end of a seek): also count "not paused"
        if ((timeout == 5000) || (timeout != 0 && (Player.sPlayer.isPlaying() || !Player.sPlayer.isPauseRequested()))) {
            sendFadeOut(timeout);
        }
    }

    private boolean isCompactPillExpanded() {
        if (mPlainButtonRow || !mControlBarShowing || mControllerViewLeft == null) return false;
        View actions = mControllerViewLeft.findViewById(R.id.mobile_compact_actions);
        return actions != null && actions.getVisibility() == View.VISIBLE;
    }

    private void sendFadeOut(int timeout) {
        if (mMiniUi && timeout == SHOW_TIMEOUT) timeout = MINI_SHOW_TIMEOUT;
        mHandler.removeMessages(MSG_FADE_OUT);
        if (mMiniUi && mPhoneTrackPopupOpen) return;   // popup open: keep the control area
        if (!isCompactPillExpanded()) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FADE_OUT), timeout);
        }
    }
    private void sendOverlayFadeOut(int timeout) {
        mHandler.removeMessages(MSG_FADE_OUT);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_OVERLAY_FADE_OUT), timeout);
    }
    private void cancelFadeOut() {
        mHandler.removeMessages(MSG_FADE_OUT);
    }

    public void hide() {
        hide(FLAG_SIDE_ALL);
    }

    /**
     * Remove the controller from the screen.
     */
    public void hide(int flags) {
        if (log.isDebugEnabled()) log.debug("hide({})", flags);
        if (mIsStopped || DBG_ALWAYS_SHOW)
            return;

        setOSDVisibility(false, flags);

        if (!isShowing()) {
            mHandler.removeMessages(MSG_FADE_OUT);
        }
    }

    public void updateBookmarkToast(int position) {
        updateToast(mContext.getResources().getText(R.string.player_bookmark_set) + " " +
                stringForTime(position));
    }

    public void updateToast(int resId) {
        if (mToast == null) {
            mToast = Toast.makeText(mContext, resId, Toast.LENGTH_SHORT);
        } else {
            mToast.cancel();
            mToast = Toast.makeText(mContext, resId, Toast.LENGTH_SHORT);
        }
        mToast.show();

    }

    public void updateToast(CharSequence text) {
        if (Player.sPlayer.isInPlaybackState()) {
            if (mToast == null) {
                mToast = Toast.makeText(mContext, text, Toast.LENGTH_SHORT);
            } else {
                mToast.cancel();
                mToast = Toast.makeText(mContext, text, Toast.LENGTH_SHORT);
            }
            mToast.show();
        }
    }

    public void cancelToast() {
        if (mToast != null) {
            if (log.isDebugEnabled()) log.debug("cancelToast: canceling toast");
            mToast.cancel();
        }
    }

    private final int getSeekAccelStep(int time) {
        int i;

        for (i = SEEK_ACCEL_STEPS.length - 1; i >= 0; i-- ) {
            if( time >= SEEK_ACCEL_STEPS[i] )
                break;
        }
        return i;
    }

    public boolean isSeekPressed() {
        return mSeekKeyDirection != 0 || mJoystickSeekingActive ||
                (mForwardButton != null && mForwardButton.isPressed()) ||
                (mBackwardButton != null && mBackwardButton.isPressed());
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            int pos;
            switch (msg.what) {
                case MSG_FADE_OUT:
                    if (log.isDebugEnabled()) log.debug("Handle: MSG_FADE_OUT");
                    if (!isCompactPillExpanded()) hide();
                    break;
                case MSG_SHOW_PROGRESS:
                    if (log.isDebugEnabled()) log.debug("Handle: MSG_SHOW_PROGRESS");
                    pos = setProgress();
                    if (!mDragging && mControlBarShowing && Player.sPlayer.isPlaying()) {
                        msg = obtainMessage(MSG_SHOW_PROGRESS);
                        sendMessageDelayed(msg, 1000 - (pos % 1000));
                    }
                    break;
                case MSG_SEEK:
                    if (log.isDebugEnabled()) log.debug("Handle: MSG_SEEK");
                    if (mNextSeek >= 0) {
                        boolean stop = false;

                        int step;
                        if (mJoystickSeekingActive) {
                            // Seeking with a joystick => set either a slow or a fast seeking speed depending on
                            // how much the joystick is moved. Make slow speed match  the first acceleration step
                            // and fast speed match the last acceleration step.
                            step = (mJoystickZone == MediaUtils.JOYSTICK_ZONE_FAR_LEFT || mJoystickZone == MediaUtils.JOYSTICK_ZONE_FAR_RIGHT) ? mSeekAccelStepCount - 1 : 0;
                        } else {
                            // Seeking with keys => make seeking accelerate while keeping the key pressed a long time
                            step = getSeekAccelStep(mLongSeekTime);
                        }

                        if (mLastRelativePosition == -1) {
                            do {
                                mNextSeek += SEEK_ACCEL_MSECS[step] * mSeekDir;
                            }
                            while ((mSeekDir > 0) ? (mNextSeek <= Player.sPlayer.getCurrentPosition()) : (mNextSeek >= Player.sPlayer.getCurrentPosition()));

                            if (mNextSeek < 0) {
                                /* beginning of the video, don't seek anymore */
                                mNextSeek = 0;
                                stop = true;
                            } else if (mNextSeek >= Player.sPlayer.getDuration() - 2000) {
                                /* end of the video, don't seek anymore */
                                mNextSeek = Player.sPlayer.getDuration() - 2000;
                                stop = true;
                            }
                        } else {
                            do {
                                mNextSeek += SEEK_ACCEL_PERMIL[step] * mSeekDir;
                            }
                            while ((mSeekDir > 0) ? (mNextSeek <= Player.sPlayer.getRelativePosition()) : (mNextSeek >= Player.sPlayer.getRelativePosition()));

                            if (mNextSeek < 0) {
                                /* beginning of the video, don't seek anymore */
                                mNextSeek = 0;
                                stop = true;
                            } else if (mNextSeek >= 1000) {
                                /* end of the video, don't seek anymore */
                                mNextSeek = 980;
                                stop = true;
                            }
                        }
                        if (mSeekComplete && isSeekPressed()) {
                            mSeekComplete = false;
                            if (log.isDebugEnabled()) log.debug("current pos is {} seek to {}", Player.sPlayer.getCurrentPosition(), mNextSeek);
                            Player.sPlayer.seekTo((int) mNextSeek);
                            updatePauseButton();
                        }
                        mLongSeekTime += SEEK_LONG_DELAY;
                        if (stop) {
                            mNextSeek = -1;
                        }
                    }
                    if (isSeekPressed()) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SEEK),
                                SEEK_LONG_DELAY);
                    } else {
                        mDragging = false;
                        if (mNextSeek > 0 && mLongSeekTime > 2 * SEEK_LONG_DELAY) {
                            mSeekComplete = false;
                            if (log.isDebugEnabled()) log.debug("current pos is {} seek to {}", Player.sPlayer.getCurrentPosition(), mNextSeek);
                            Player.sPlayer.seekTo((int) mNextSeek);
                            updatePauseButton();
                        }
                        if (mSeekComplete && !mDragging) {
                            onSeekAndDraggingComplete();
                        }

                    }
                    setProgress();
                    break;
                case MSG_SEEK_RESUME:
                    if (mSeekWasPlaying) {
                        if (log.isDebugEnabled()) log.debug("Handle: MSG_SEEK_RESUME");
                        Player.sPlayer.start(PlayerController.STATE_SEEK);
                        mSeekWasPlaying = false;
                    }
                    updatePausePlay();
                    updateButtons();
                    if (mSeekOnlySession) {
                        mSeekOnlySession = false;
                        scheduleSeekOnlyHide();
                    } else {
                        show(FLAG_SIDE_CONTROL_BAR, SHOW_TIMEOUT);
                    }
                    break;
                case MSG_SWITCH_FULLSCREEN_WITH_CUTOUT:
                    if (mPhoneCutoutOriginal) {
                        // Leave Original and resume the previously selected display format.
                        mPhoneCutoutOriginal = false;
                        mSurfaceController.mFullScreenWithCutout = true;
                        mSurfaceController.mCutBothSidesX = true;
                        mSurfaceController.setVideoFormat(mFormatBeforeCutoutOriginal);
                        updateFormat();
                    } else if (!mSurfaceController.mFullScreenWithCutout && mSurfaceController.mCutBothSidesX) {
                        if (!isTVMode && !TVUtils.isTV(mContext)) {
                            mFormatBeforeCutoutOriginal = mSurfaceController.getCurrentVideoFormat();
                            mPhoneCutoutOriginal = true;
                            mSurfaceController.mFullScreenWithCutout = false;
                            mSurfaceController.mCutBothSidesX = false;
                            mSurfaceController.setVideoFormat(SurfaceController.VideoFormat.ORIGINAL);
                            updateFormat();
                        } else {
                            mSurfaceController.mFullScreenWithCutout = true;
                            mSurfaceController.mCutBothSidesX = true;
                        }
                    } else if (mSurfaceController.mFullScreenWithCutout && mSurfaceController.mCutBothSidesX) {
                        // Go to Fullscreen with Cutouts off 
                        mSurfaceController.mFullScreenWithCutout = false;
                        mSurfaceController.mCutBothSidesX = false;
                    } else if (!mSurfaceController.mFullScreenWithCutout && !mSurfaceController.mCutBothSidesX) {
                        // Go to Fullscreen with Cutouts off (euqal size)
                        mSurfaceController.mFullScreenWithCutout = false;
                        mSurfaceController.mCutBothSidesX = true;
                     } else {
                        // Invalid combo fallback → reset to normal
                        mSurfaceController.mFullScreenWithCutout = false;
                        mSurfaceController.mCutBothSidesX = false;
                    }

                    setFullscreenWithCutoutButtonIcon(mSurfaceController.mFullScreenWithCutout);
                    mSurfaceController.updateSurface();
                    if (!isTVMode && !TVUtils.isTV(mContext)) {
                        int label = mPhoneCutoutOriginal ? R.string.phone_cutout_original
                                : mSurfaceController.mFullScreenWithCutout ? R.string.phone_cutout_fullscreen
                                : mSurfaceController.mCutBothSidesX ? R.string.phone_cutout_symmetric : R.string.phone_cutout_avoided;
                        showPhonePill(mContext.getString(label), R.drawable.video_fullscreen_cutout_on);
                        mHandler.postDelayed(mHidePhonePill, 1500);
                    }
                    break;
                case MSG_SWITCH_VIDEO_FORMAT:
                    if (log.isDebugEnabled()) log.debug("Handle: MSG_SWITCH_VIDEO_FORMAT");
                    mPhoneCutoutOriginal = false;
                    mSurfaceController.switchVideoFormat();
                    rememberShowVideoFormat();
                    updateFormat();
                    if (!isTVMode && !TVUtils.isTV(mContext)) {
                        int label;
                        switch (mSurfaceController.getCurrentVideoFormat()) {
                            case SurfaceController.VideoFormat.ORIGINAL: label = R.string.format_original; break;
                            case SurfaceController.VideoFormat.STRETCH_XY: label = R.string.format_stretched; break;
                            case SurfaceController.VideoFormat.FULL_SCREEN: label = R.string.format_fullscreen; break;
                            case SurfaceController.VideoFormat.FORCE43: label = R.string.format_43; break;
                            case SurfaceController.VideoFormat.FORCE169: label = R.string.format_169; break;
                            case SurfaceController.VideoFormat.FORCE185: label = R.string.format_185; break;
                            case SurfaceController.VideoFormat.FORCE239: label = R.string.format_239; break;
                            default: label = R.string.format_optimized; break;
                        }
                        showPhonePill(mContext.getString(R.string.phone_format_info, mContext.getString(label)), R.drawable.video_format_0);
                        mHandler.postDelayed(mHidePhonePill, 1500);
                    }
                    break;
                case MSG_HIDE_SYSTEM_BAR:
                    if (log.isDebugEnabled()) log.debug("Handle: MSG_HIDE_SYSTEM_BAR");
                    mInsetsController.hide(WindowInsetsCompat.Type.navigationBars());
                    mNavigationBarShowing = false;
                    manualVisibilityChange=true;
                    break;
                case MSG_OVERLAY_FADE_OUT:
                    if (log.isDebugEnabled()) log.debug("Handle: MSG_OVERLAY_FADE_OUT");
                    final View overlay1 = mControllerViewLeft.findViewById(R.id.help_overlay);
                    if(overlay1!=null){
                        overlay1.animate().alpha(0).setListener(new AnimatorListener() {
                            public void onAnimationStart(Animator animation) {}
                            public void onAnimationRepeat(Animator animation) {}
                            public void onAnimationEnd(Animator animation) {
                                if(overlay1!=null){
                                    overlay1.setVisibility(View.GONE);
                                }
                            }
                            public void onAnimationCancel(Animator animation) {}
                        });
                        if (log.isDebugEnabled()) log.debug("hidding 1");
                    }
                    if(mControllerViewRight!=null){
                        final View overlay2 = mControllerViewRight.findViewById(R.id.help_overlay);
                        if(overlay2!=null){
                            overlay2.animate().alpha(0).setListener(new AnimatorListener() {
                                public void onAnimationStart(Animator animation) {}
                                public void onAnimationRepeat(Animator animation) {}
                                public void onAnimationEnd(Animator animation) {
                                    if(overlay2!=null){
                                        overlay2.setVisibility(View.GONE);
                                    }
                                }
                                public void onAnimationCancel(Animator animation) {}
                            });
                            if (log.isDebugEnabled()) log.debug("hidding 2");
                        }
                    }
            }
        }

    };

    private String stringForTime(int timeMs) {
        int totalSeconds = timeMs / 1000;

        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours   = totalSeconds / 3600;

        mFormatBuilder.setLength(0);
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    private int setProgress() {
        if (mIsStopped) {
            return 0;
        }
        int position = mNextSeek == -1 ? Player.sPlayer.getCurrentPosition() : mNextSeek;
        if (isSeekOnlyShowing() && mPhoneTouch && mPhoneAxis == 1) position = mPhoneSeekPosition;
        if (position < 0) {
            position = 0;
        }
        int duration = Player.sPlayer.getDuration();
        if (log.isDebugEnabled()) log.debug("setProgress player position/duration={}/{}", position, duration);
        CharSequence endText = "";
        CharSequence currentText = "";

        if (mProgress != null) {
            if (duration > 0) {
                // use long to avoid overflow
                long pos = 1000L * position / duration;
                mProgress.setProgress((int) pos);
                if(mProgress2!=null)
                    mProgress2.setProgress((int) pos);
                currentText = stringForTime(position);
                if (log.isDebugEnabled()) log.debug("setProgress player currentText={}", currentText);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
                boolean makeTimeNegative = prefs.getBoolean(VideoPreferencesCommon.KEY_MAKE_TIME_NEGATIVE, VideoPreferencesCommon.MAKE_TIME_NEGATIVE_DEFAULT);

                int remaining = duration - position > 0 ? duration - position : 0;
                String remainingText = (!makeTimeNegative ? "" : "-") + stringForTime(remaining);

                boolean isLeanback = mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
                if (!isLeanback && duration > 0 && remaining > 0 && Player.sPlayer != null && (Player.sPlayer.isPlaying() || Player.sPlayer.isPaused())) {
                    long now = System.currentTimeMillis();
                    float speed = PlayerService.sPlayerService != null ? PlayerService.sPlayerService.getAudioSpeed() : 1.0f;
                    if (speed <= 0f) speed = 1.0f;
                    long remainingMs = (long) (remaining / speed);
                    String endClockText = getDateFormat().format(new Date(now + remainingMs));
                    endText = remainingText + " → 🕖 " + endClockText;
                } else {
                    endText = remainingText;
                }
            } else {
                if (mDragging || !mSeekComplete) {
                    if (mLastRelativePosition != -1 && position <= 1000) {
                        mProgress.setProgress(position);
                        if(mProgress2!=null)
                            mProgress2.setProgress(position);
                        currentText = String.valueOf(position / 10) + "%";
                    } else {
                        currentText = stringForTime(position);
                    }
                } else {
                    int relativePosition = Player.sPlayer.getRelativePosition();
                    if (relativePosition != mLastRelativePosition && relativePosition >= 0) {
                        mLastRelativePosition = relativePosition;
                        mProgress.setProgress(mLastRelativePosition);
                        if(mProgress2!=null)
                            mProgress2.setProgress(mLastRelativePosition);
                    }
                    currentText = stringForTime(position);
                }
            }
            if (!Player.sPlayer.isLocalVideo()) {
                int bufferPosition = Player.sPlayer.getBufferPosition();
                if (bufferPosition >= 0) {
                    mProgress.setSecondaryProgress(bufferPosition);
                    if(mProgress2!=null)
                        mProgress2.setSecondaryProgress(bufferPosition);
                }
            }
        }

        if (mEndTime != null) {
            mEndTime.setText(endText);
            if(mEndTime2!=null)
                mEndTime2.setText(endText);
        }
        if (mCurrentTime != null) {
            mCurrentTime.setText(currentText);
            if(mCurrentTime2!=null)
                mCurrentTime2.setText(currentText);
        }
        updateMobilePlaybackInfo(mControllerViewLeft, position, duration, currentText);
        updateMobilePlaybackInfo(mControllerViewRight, position, duration, currentText);

        updateClock();

        return position;
    }

    // Phone uses the outline icons of the new phone controls; TV keeps its own selectors untouched.
    private int pauseIconRes() {
        return TVUtils.isTV(mContext) ? R.drawable.video_pause_selector : R.drawable.mobile_ic_pause;
    }

    private int playIconRes() {
        return TVUtils.isTV(mContext) ? R.drawable.video_play_selector : R.drawable.mobile_ic_play;
    }

    private void updatePausePlay() {
        if (!isTVMode && !TVUtils.isTV(mContext)) {
            if (mCentralPlayIcon != null) mCentralPlayIcon.setVisibility(View.GONE);
            if (mPlayPauseTouchZone != null) mPlayPauseTouchZone.setVisibility(View.GONE);
        }
        boolean labelPaused = !(mSeekWasPlaying || Player.sPlayer.isPlaying());
        if (mProgress instanceof TimeLabelSeekBar) ((TimeLabelSeekBar) mProgress).setPaused(labelPaused);
        if (mProgress2 instanceof TimeLabelSeekBar) ((TimeLabelSeekBar) mProgress2).setPaused(labelPaused);
        if (mPauseButton == null)
            return;

        if (mSeekWasPlaying || Player.sPlayer.isPlaying()) {
            if (log.isDebugEnabled()) log.debug("updatePausePlay: video is playing, hiding central play icon");
            mPauseButton.setImageResource(pauseIconRes());
            if(mPauseButton2!=null)
                mPauseButton2.setImageResource(pauseIconRes());
            // Hide central play icon when playing
            if (mCentralPlayIcon != null) {
                mCentralPlayIcon.setVisibility(View.GONE);
                if (mPlayPauseTouchZone != null)
                    mPlayPauseTouchZone.setVisibility(View.INVISIBLE);
            }
        } else {
            if (log.isDebugEnabled()) log.debug("updatePausePlay: video is paused, showing central play icon (locked={})", mIsLocked);
            mPauseButton.setImageResource(playIconRes());
            if(mPauseButton2!=null)
                mPauseButton2.setImageResource(playIconRes());
            // Show central play icon when paused, but only if not locked
            if (mCentralPlayIcon != null && !isSeekOnlyShowing() && !mIsLocked && !isTVMode && TVUtils.isTV(mContext)) {
                if (log.isDebugEnabled()) log.debug("updatePausePlay: making play touch zone and central icon visible");
                mPlayPauseTouchZone.setVisibility(View.VISIBLE);
                mCentralPlayIcon.setVisibility(View.VISIBLE);
            } else {
                if (log.isDebugEnabled()) log.debug("updatePausePlay: central play icon is null or device is locked");
            }
        }
    }

    private void updateFormat() {
        if (mFormatButton == null)
            return;
        
        mBarXYIconResource = mSurfaceController.willStretchY ? R.drawable.video_format_arrow_vertical : R.drawable.video_format_arrow_horizontal;
        switch (mSurfaceController.getCurrentVideoFormat()) {
            case SurfaceController.VideoFormat.ORIGINAL:
                mFormatButton.setImageResource(R.drawable.video_format_0);
                break;
            case SurfaceController.VideoFormat.STRETCH_XY:
                mFormatButton.setImageResource(mBarXYIconResource);
                break;
            case SurfaceController.VideoFormat.FULL_SCREEN:
                mFormatButton.setImageResource(R.drawable.video_format_arrow_xy);
                break;
            case SurfaceController.VideoFormat.FORCE43:
                mFormatButton.setImageResource(R.drawable.video_format_43);
                break;
            case SurfaceController.VideoFormat.FORCE169:
                mFormatButton.setImageResource(R.drawable.video_format_169);
                break;
            case SurfaceController.VideoFormat.FORCE185:
                mFormatButton.setImageResource(R.drawable.video_format_185);
                break;
            case SurfaceController.VideoFormat.FORCE239:
                mFormatButton.setImageResource(R.drawable.video_format_239);
                break;
            case SurfaceController.VideoFormat.AUTO:
                mFormatButton.setImageResource(R.drawable.video_format_a);
                break;
        }
    }

    public void setStretchXYIcon() {
        mBarXYIconResource = mSurfaceController.willStretchY ? R.drawable.video_format_arrow_vertical : R.drawable.video_format_arrow_horizontal;
        if (mFormatButton != null && mSurfaceController.getCurrentVideoFormat() == SurfaceController.VideoFormat.STRETCH_XY)
            mFormatButton.setImageResource(mBarXYIconResource);
    }

    private void doPauseResume(boolean showControlsOnResume) {
        if (mIsStopped)
            return;
        if (log.isDebugEnabled()) log.debug("doPauseResume: {} - {}", Player.sPlayer.isPlaying(), mSeekWasPlaying);
        if (mNextSeek != -1 && !mSeekKeptPlaying) {
            mSeekWasPlaying = !mSeekWasPlaying;
        } else {
            boolean playing = mSeekKeptPlaying ? !Player.sPlayer.isPauseRequested() : Player.sPlayer.isPlaying();
            if (playing) {
                show(FLAG_SIDE_CONTROL_BAR, pauseTimeout);
                Player.sPlayer.pause(PlayerController.STATE_NORMAL);
            } else {
                Player.sPlayer.start(PlayerController.STATE_NORMAL);
                if (showControlsOnResume) show(FLAG_SIDE_CONTROL_BAR, SHOW_TIMEOUT);
            }
           // setVisibility(mAdView, !Player.sPlayer.isPlaying(), true);
        }
        updatePausePlay();
    }

    public void setEnabled(boolean enabled) {
        if (mControllerView == null)
            return;
        mEnabled = enabled;
        updateButtons();
    }

    public void setVideoTitleEnabled(boolean enable) {
        if (mVideoTitle != null) {
            if (enable) {
                mVideoTitle.setVisibility(View.VISIBLE);

            } else {
                mVideoTitle.setVisibility(View.GONE);
            }
        }
    }

    public void setVideoTitle(String title) {
        if (mVideoTitle != null && title != null && !title.isEmpty()) {
            mVideoTitle.setText(title);
            if (mControllerViewLeft != null) {
                TextView bottomTitle = mControllerViewLeft.findViewById(R.id.mobile_video_title);
                if (bottomTitle != null) { bottomTitle.setText(title); armTitleMarquee(bottomTitle); }
            }
            if (mControllerViewRight != null) {
                TextView bottomTitle = mControllerViewRight.findViewById(R.id.mobile_video_title);
                if (bottomTitle != null) { bottomTitle.setText(title); armTitleMarquee(bottomTitle); }
            }
        }
    }

    public void start() {
        if (log.isDebugEnabled()) log.debug("start");
        attachWindow();
        setEnabled(true);
        if (DBG_ALWAYS_SHOW)
            show(FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS, 0);
    }

    public void stop() {
        if (log.isDebugEnabled()) log.debug("stop");

        if (mIsStopped)
            return;
        mIsStopped = true;

        mHandler.removeCallbacksAndMessages(null);
        if (hideOsdHandler != null) hideOsdHandler.removeCallbacksAndMessages(hideOsdRunnable);

        mPlayer = null;
        cancelToast();
        detachWindow();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (log.isDebugEnabled()) log.debug("onWindowFocusChanged: {}", hasFocus);
        if (!mIsStopped) {
            /* volume can be changed by an other application: update it */
            if (hasFocus) {
                if (!isTVMode && !TVUtils.isTV(mContext)) {
                    showSystemBar(false);
                    if (mControllerView != null) ViewCompat.requestApplyInsets(mControllerView);
                }
                updateVolumeBar();
                if (mActionBarShowing) show(FLAG_SIDE_ACTION_BAR, SHOW_TIMEOUT);
            } else {
                if (mSeekOnlySession && mSeekKeyDirection != 0) {
                    mSeekKeyDirection = 0;
                    scheduleSeekOnlyHide();
                }
                /* notification panel is showing, don't hide the status bar */
                if (mActionBarShowing) show(FLAG_SIDE_ACTION_BAR, 0);
            }
        }
    }

    /** TV show episode: remember the video format for the other episodes of the show. */
    private void rememberShowVideoFormat() {
        if (PlayerService.sPlayerService != null && mSurfaceController != null) {
            PlayerService.sPlayerService.rememberShowVideoFormat(mSurfaceController.getCurrentVideoFormat());
        }
    }

    private void onSeekAndDraggingComplete() {
        if (log.isDebugEnabled()) log.debug("onSeekAndDraggingComplete: {}", mSeekWasPlaying);
        if (mIsStopped)
            return;
        mNextSeek = -1;
        mPendingSeek = false;
        boolean keptPlaying = mSeekKeptPlaying;
        int resumeDelay = mShortSeek ? 0 : SEEK_RESUME_DELAY;
        mShortSeek = false;
        mSeekKeptPlaying = false;

        if (mSeekWasPlaying) {
            mHandler.removeMessages(MSG_SEEK_RESUME);
            mHandler.sendEmptyMessageDelayed(MSG_SEEK_RESUME, resumeDelay);
        } else {
            updatePausePlay();
            updateButtons();
            if (keptPlaying) setProgress();
            if (mSeekOnlySession) {
                mSeekOnlySession = false;
                scheduleSeekOnlyHide();
            } else if (keptPlaying && mControlBarShowing && Player.sPlayer.isPlaying()) {
                show(FLAG_SIDE_CONTROL_BAR, SHOW_TIMEOUT);
            }
        }
    }

    public void onAllSeekComplete() {
        if (log.isDebugEnabled()) log.debug("onAllSeekComplete");
        if (mIsStopped)
            return;


        mSeekComplete = true;
        if (mPendingSeek) {
            mPendingSeek = false;
            if (!mDragging && mNextSeek >= 0 && Player.sPlayer != null) {
                // Button pressed again during the previous seek: jump straight to the latest target.
                mSeekComplete = false;
                Player.sPlayer.seekTo((int) mNextSeek);
                return;
            }
        }
        if (!mDragging) {
            onSeekAndDraggingComplete();
        }
    }

    public void onSeekComplete() {
        if (log.isDebugEnabled()) log.debug("onSeekComplete");
        setProgress();
    }

    public void resumePosition(int position, boolean playOnResume) {
        mShortSeek = false;
        mSeekKeptPlaying = false;
        if (log.isDebugEnabled()) log.debug("resumePositionresumePosition: {} pos: {}", playOnResume, position);
        if (position > 0) {
            mSeekWasPlaying = playOnResume;
            if (Player.sPlayer.getCurrentPosition() > 0) {
                /*
                 * case where mPlayer started at a time, no need to re-seek
                 */
                mHandler.removeMessages(MSG_SEEK_RESUME);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_SEEK_RESUME));
            } else {
                mSeekComplete = false;
                Player.sPlayer.seekTo(position);
                show(FLAG_SIDE_CONTROL_BAR, 0);
            }
        } else {
            if (playOnResume) {
                Player.sPlayer.start(PlayerController.STATE_SEEK);
                updatePausePlay();
                hide();
            } else {
                show(FLAG_SIDE_CONTROL_BAR, 0);
                updatePausePlay();
            }
        }
    }

    private void setNextSeekPos(int way) {
        if (log.isDebugEnabled()) log.debug("setNextSeekPos {}", way);
        mSeekDir = way;
        if (isCompactPhoneControls() && mLastRelativePosition == -1) {
            int position = mNextSeek >= 0 ? mNextSeek : Player.sPlayer.getCurrentPosition();
            int duration = Player.sPlayer.getDuration();
            int target = position + way * compactSkipSeconds(way) * 1000;
            mNextSeek = Math.max(0, duration > 0 ? Math.min(duration, target) : target);
            return;
        }
        if (mLastRelativePosition == -1) {
            if (mNextSeek == -1) {
                mNextSeek = Player.sPlayer.getCurrentPosition();
            }
            do {
                mNextSeek += way * SEEK_SHORT_MSEC;
            } while ((mSeekDir>0)?(mNextSeek <= Player.sPlayer.getCurrentPosition()):(mNextSeek >= Player.sPlayer.getCurrentPosition()));

            } else {
            if (mNextSeek == -1) {
                mNextSeek = mLastRelativePosition = Player.sPlayer.getRelativePosition();
            }
            do {
                mNextSeek += way * SEEK_SHORT_PERMIL;
            } while ((mSeekDir>0)?(mNextSeek <= Player.sPlayer.getRelativePosition()):(mNextSeek >= Player.sPlayer.getRelativePosition()));

        }
    }

    private void onSeek(int way, boolean longPress) {
        onSeek(way, longPress, false);
    }

    private void onSeek(int way, boolean longPress, boolean seekOnly) {
        if (!longPress && !seekOnly) {
            clearSeekOnly();
            mSeekOnlySession = false;
        }
        Player player = Player.sPlayer;
        int pillStartPosition = player.getCurrentPosition();
        if (log.isDebugEnabled()) log.debug("onSeek {}", way);
        cancelFadeOut();
        mHandler.removeMessages(MSG_SHOW_PROGRESS);
        mHandler.removeMessages(MSG_SEEK_RESUME);
        mHandler.removeMessages(MSG_SEEK);

        if (mSeekComplete) {
            mShortSeek = !longPress && !mDragging;
            // A single local jump can let the decoder render the new frame without a transport pause.
            mSeekKeptPlaying = mShortSeek && !mSeekWasPlaying && player.isLocalVideo()
                    && player.getDuration() > 0 && player.isPlaying();
        } else if (longPress) {
            mShortSeek = false;
            if (mSeekKeptPlaying) {
                mSeekKeptPlaying = false;
                mDragging = true;
            }
        }

        if (!mSeekWasPlaying && !mSeekKeptPlaying && player.isPlaying()) {
            mSeekWasPlaying = true;
            player.pause(PlayerController.STATE_SEEK);
        }

        if (mSeekComplete) {
            mLongSeekTime = 0;
            mDragging = longPress;
            mSeekComplete = false;
            mPendingSeek = false;
            setNextSeekPos(way);
            player.seekTo(mNextSeek);
        } else if (!longPress && !mDragging && mNextSeek >= 0) {
            // Previous seek still in flight: retarget immediately (UI updates now, decoder follows
            // as soon as it is free) instead of ignoring the press.
            setNextSeekPos(way);
            int duration = player.getDuration();
            if (mLastRelativePosition == -1) {
                if (mNextSeek < 0) mNextSeek = 0;
                if (duration > 0 && mNextSeek > duration) mNextSeek = duration;
            }
            mPendingSeek = true;
        }
        updatePauseButton();
        if (longPress) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SEEK), SEEK_LONG_INIT_DELAY);
        }
        setProgress();
        if ((!mSeekOnlySession || mMiniUi) && !isTVMode && !TVUtils.isTV(mContext) && mNextSeek >= 0) {
            showPhonePill(mContext.getString(R.string.phone_gesture_seek,
                    Math.abs(mNextSeek - pillStartPosition) / 1000, phoneSeekTime(mNextSeek)),
                    way > 0 ? R.drawable.mobile_ic_forward : R.drawable.mobile_ic_rewind);
            mHandler.postDelayed(mHidePhonePill, 1200);
        }
    }

    // There are two scenarios that can trigger the seekbar listener to trigger:
    //
    // The first is the user using the touchpad to adjust the posititon of the
    // seekbar's thumb. In this case onStartTrackingTouch is called followed by
    // a number of onProgressChanged notifications, concluded by onStopTrackingTouch.
    // We're setting the field "mDragging" to true for the duration of the dragging
    // session to avoid jumps in the position in case of ongoing playback.
    //
    // The second scenario involves the user operating the scroll ball, in this
    // case there WON'T BE onStartTrackingTouch/onStopTrackingTouch notifications,
    // we will simply apply the updated position without suspending regular updates.
    private SeekBar.OnSeekBarChangeListener mProgressListener = new SeekBar.OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
            if (log.isDebugEnabled()) log.debug("onStartTrackingTouch");
            if (mIsStopped)
                return;
            mSeekOnlySession = false;
            mShortSeek = false;
            mSeekKeptPlaying = false;
            show(FLAG_SIDE_CONTROL_BAR, 0);
            mHandler.removeMessages(MSG_SHOW_PROGRESS);
            mHandler.removeMessages(MSG_SEEK_RESUME);

            mDragging = true;
            mSeekComplete = false;

            // By removing these pending progress messages we make sure
            // that a) we won't update the progress while the user adjusts
            // the seekbar and b) once the user is done dragging the thumb
            // we will post one of these messages to the queue again and
            // this ensures that there will be exactly one message queued up.
            if (!mSeekWasPlaying) {
                if (Player.sPlayer.isPlaying()) {
                    mSeekWasPlaying = true;
                    Player.sPlayer.pause(PlayerController.STATE_SEEK);
                }
            }
        }

        public void onProgressChanged(SeekBar bar, int progress, boolean fromuser) {
            if (!fromuser || mIsStopped) {
                // We're not interested in programmatically generated changes to
                // the progress bar's position.
                return;
            }

            if (mProgress != bar) mProgress.setProgress(progress);
            if (mProgress2 != null && mProgress2 != bar) mProgress2.setProgress(progress);

            long duration = Player.sPlayer.getDuration();
            long newposition;
            CharSequence currentText;
            CharSequence endText;

            if (duration > 0) {
                newposition = (duration * progress) / 1000L;
                currentText = stringForTime( (int) newposition);
                endText = stringForTime( (int) (duration-newposition));
            } else {
                newposition = progress;
                mLastRelativePosition = Player.sPlayer.getRelativePosition();
                if (newposition >= 0) {
                    currentText = String.valueOf(newposition / 10) + "%";
                } else {
                    currentText = stringForTime( (int) newposition);
                }
                endText = "";
            }


            long currentProgressTime = System.currentTimeMillis();

            // don't try to seek too much
            if (Player.sPlayer.isLocalVideo() &&
                    (currentProgressTime - mLastProgressTime > SEEK_PROGRESS_TIME_THRESHOLD) &&
                    (Math.abs(progress -  mLastProgress) > SEEK_PROGRESS_THRESHOLD)) {
                mSeekComplete = false;
                Player.sPlayer.seekTo((int) newposition);
                updatePauseButton();
                mLastSeek = (int) newposition;
                mLastProgressTime = currentProgressTime;
                mLastProgress = progress;
            }
            mNextSeek = (int) newposition;

            if (mEndTime != null) {
                mEndTime.setText(endText);
            }
            if (mCurrentTime != null) {
                mCurrentTime.setText(currentText);
            }
            updateMobilePlaybackInfo(mControllerViewLeft, (int) newposition, (int) duration, currentText);
            updateMobilePlaybackInfo(mControllerViewRight, (int) newposition, (int) duration, currentText);
        }

        public void onStopTrackingTouch(SeekBar bar) {
            if (mIsStopped)
                return;
            mDragging = false;

            if (mNextSeek != -1 && mNextSeek != mLastSeek) {
                Player.sPlayer.seekTo(mNextSeek);
                updatePauseButton();
            }
            mLastSeek = -1;

            if (mSeekComplete && !mDragging) {
                onSeekAndDraggingComplete();
            }
        }
    };

    private View.OnClickListener mPauseListener = new View.OnClickListener() {
        public void onClick(View v) {
            boolean compact = isCompactPhoneControls();
            doPauseResume(!compact);
            if (!isTVMode && !TVUtils.isTV(mContext) && Player.sPlayer != null) {
                boolean playing = Player.sPlayer.isPlaying();
                showPhonePill(mContext.getString(playing ? R.string.phone_pill_playing : R.string.phone_pill_paused),
                        playing ? R.drawable.mobile_ic_play : R.drawable.mobile_ic_pause);
                mHandler.postDelayed(mHidePhonePill, 1200);
            }
        }
    };

    private View.OnClickListener mBackwardListener = new View.OnClickListener() {
        public void onClick(View v) {
            onSeek(-1, false);
        }
    };

    private View.OnLongClickListener mBackwardLongListener = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            if (!mIsStopped) {
                onSeek(-1, true);
                return true;
            } else {
                return false;
            }
        }
    };

    private View.OnClickListener mForwardListener = new View.OnClickListener() {
        public void onClick(View v) {
            onSeek(1, false);
        }
    };

    private View.OnLongClickListener mForwardLongListener = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            if (!mIsStopped) {
                onSeek(1, true);
                return true;
            } else {
                return false;
            }
        }
    };

    private View.OnClickListener mFormatListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (!mIsStopped) {
                sendFadeOut(SHOW_TIMEOUT);
                /* video format change can take long time: exec asynchronously */
                mHandler.removeMessages(MSG_SWITCH_VIDEO_FORMAT);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_SWITCH_VIDEO_FORMAT));
            }
        }
    };

    private View.OnClickListener fullscreenWithCutoutListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (!mIsStopped) {
                sendFadeOut(SHOW_TIMEOUT);
                /* video format change can take long time: exec asynchronously */
                mHandler.removeMessages(MSG_SWITCH_FULLSCREEN_WITH_CUTOUT);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_SWITCH_FULLSCREEN_WITH_CUTOUT));
            }
        }
    };

    private void setMusicVolume(int index) {
        //nvidia shield on android 7 has only one global volume that merged
        // mute and DND state (the latter needs a specific permission)
        try {
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);

        /*
         * Since android jb 4.3, the safe volume warning dialog is displayed only with FLAG_SHOW_UI flag.
         * We don't want to always show the default ui volume, so show it only when volume is not set.
         */
            int newIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (index != newIndex)
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI);

            setVolumeBarMuteUI(index == 0);
        } catch (SecurityException ignored) {}
    }

    private int getMobileVolumeStep() {
        try {
            String value = PreferenceManager.getDefaultSharedPreferences(mContext)
                    .getString("mobile_player_volume_step", "5");
            int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return Math.max(1, Math.min(maxVolume, Integer.parseInt(value)));
        } catch (NumberFormatException | ClassCastException ignored) {
            return 5;
        }
    }

    private void changeVolumeBy(int amount) {
        if (mIsStopped) return;
        boolean mobile = !TVUtils.isTV(mContext);
        int step = mobile ? getMobileVolumeStep() : 1;
        int current = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maximum = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        setMusicVolume(Math.max(0, Math.min(maximum, current + amount * step)));
        updateVolumeBar();
        if (mobile) showPhoneVolumePill();
        else show(FLAG_SIDE_VOLUME_BAR, Player.sPlayer.isPlaying() ? SHOW_TIMEOUT : 0);
    }

    private void setMobileVolumeValueVisible(View controller, boolean visible) {
        if (controller == null) return;
        TextView value = controller.findViewById(R.id.mobile_volume_value);
        if (value == null) return;
        if (visible) {
            value.setText(String.valueOf(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)));
        }
        value.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private final Runnable hideMobileVolumeValue = () -> {
        setMobileVolumeValueVisible(mControllerViewLeft, false);
        setMobileVolumeValueVisible(mControllerViewRight, false);
    };

    private void showMobileVolumeValue() {
        if (mIsStopped) return;
        setMobileVolumeValueVisible(mControllerViewLeft, true);
        setMobileVolumeValueVisible(mControllerViewRight, true);
        mHandler.removeCallbacks(hideMobileVolumeValue);
        mHandler.postDelayed(hideMobileVolumeValue, 1500);
    }

    private void updateVolumeBar() {
        if (mVolumeLevel != null && mAudioManager != null) {
            int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            mVolumeLevel.setProgress(volume);
            if(mVolumeLevel2!=null)
                mVolumeLevel2.setProgress(volume);
            setVolumeBarMuteUI(volume == 0);
        }
    }

    private SeekBar.OnSeekBarChangeListener mVolumeLevelListener = new SeekBar.OnSeekBarChangeListener() {
        public void onProgressChanged(SeekBar bar, int progress, boolean fromuser) {
            if( !fromuser || mAudioManager == null)
                return;

            setMusicVolume(progress);
            //updating secondary volume view

            updateVolumeBar();
        }

        public void onStartTrackingTouch(SeekBar seekBar) {
            show(FLAG_SIDE_VOLUME_BAR, 0);
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
            show(FLAG_SIDE_VOLUME_BAR, SHOW_TIMEOUT);
        }
    };

    private View.OnClickListener mVolumeUpListener = new View.OnClickListener() {
        public void onClick(View v) {
            changeVolumeBy(1);
        }
    };

    private View.OnClickListener mVolumeDownListener = new View.OnClickListener() {
        public void onClick(View v) {
            changeVolumeBy(-1);
        }
    };

    private RepeatingImageButton.RepeatListener mVolumeUpRepeatListener =
            new RepeatingImageButton.RepeatListener() {
        public void onRepeat(View v, long howlong, int repcnt) {
            changeVolumeBy(1);
        }
    };

    private RepeatingImageButton.RepeatListener mVolumeDownRepeatListener =
            new RepeatingImageButton.RepeatListener() {
        public void onRepeat(View v, long howlong, int repcnt) {
            changeVolumeBy(-1);
        }
    };


    private void toggleMediaControlsVisiblity(int flags) {
        if (flags == FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS && isShowing()) {
            hide(FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS);
        } else if (((flags & FLAG_SIDE_CONTROL_BAR) != 0 && !mControlBarShowing) ||
                ((flags & FLAG_SIDE_SYSTEM_BAR) != 0 && !mSystemBarShowing) ||
                ((flags & FLAG_SIDE_ACTION_BAR) != 0 && !mActionBarShowing) ||
                ((flags & FLAG_SIDE_VOLUME_BAR) != 0 && !mVolumeBarShowing)) {
            show(flags, SHOW_TIMEOUT);
        } else {
            hide(FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS);
        }
    }
    
    public void showControlBar() {
        show(FLAG_SIDE_CONTROL_BAR|FLAG_SIDE_ACTION_BAR|FLAG_SIDE_SYSTEM_BAR, SHOW_TIMEOUT);
    }
    private static final int SEEK_ONLY_LINGER_MS = 1200;
    private static View sSeekOnlyView;
    private final java.util.ArrayList<View> mSeekOnlyHiddenViews = new java.util.ArrayList<>();
    private boolean mSeekOnlyShowing;
    private boolean mSeekOnlyReservesVolumeSpace;
    private int mSeekOnlyRestoreFlags;
    private boolean mSeekOnlyRestoreFocus;
    private boolean mSeekOnlySession;

    private final Runnable mFinishSeekOnly = () -> {
        int restoreFlags = mSeekOnlyRestoreFlags;
        boolean restoreFocus = mSeekOnlyRestoreFocus;
        clearSeekOnly();
        if (restoreFlags != 0 && !isTVMenuDisplayed && Player.sPlayer != null) {
            show(restoreFlags, SHOW_TIMEOUT);
            if (restoreFocus) focusSeekBar();
        }
    };

    private boolean isSeekOnlyShowing() {
        return mSeekOnlyShowing;
    }

    private void showSeekOnly() {
        if (mIsStopped || mControlBar == null || mProgress == null) return;
        mHandler.removeCallbacks(mFinishSeekOnly);
        if (isSeekOnlyShowing()) return;

        mSeekOnlyRestoreFlags = (mControlBarShowing ? FLAG_SIDE_CONTROL_BAR : 0)
                | (mActionBarShowing ? FLAG_SIDE_ACTION_BAR : 0)
                | (mSystemBarShowing ? FLAG_SIDE_SYSTEM_BAR : 0)
                | (mVolumeBarShowing ? FLAG_SIDE_VOLUME_BAR : 0);
        mSeekOnlyRestoreFocus = isTimelineFocused();
        mSeekOnlyReservesVolumeSpace = mVolumeBar != null && mVolumeBar.getVisibility() == View.VISIBLE;
        hide(FLAG_SIDE_ALL & ~FLAG_SIDE_SYSTEM_BAR);
        mHidePhonePill.run();
        if (mSeekOnlyReservesVolumeSpace) mVolumeBar.setVisibility(View.INVISIBLE);

        // Leave each row's measured space in place so the timeline never moves.
        mSeekOnlyShowing = true;
        keepOnlyTimeline(mControlBar);
        mControlBar.setVisibility(View.VISIBLE);
        if (splitView && mControlBar2 != null) {
            keepOnlyTimeline(mControlBar2);
            mControlBar2.setVisibility(View.VISIBLE);
        }
        sSeekOnlyView = (View) mProgress.getParent();
        mControlBar.post(() -> {
            if (isSeekOnlyShowing()) notifySeekOnlyVisibility(true);
        });
    }

    private void keepOnlyTimeline(View view) {
        int id = view.getId();
        if (id == R.id.seek_progress || id == R.id.time_current || id == R.id.time) return;
        if (view instanceof ViewGroup group && (group.findViewById(R.id.seek_progress) != null
                || group.findViewById(R.id.time_current) != null || group.findViewById(R.id.time) != null)) {
            for (int i = 0; i < group.getChildCount(); i++) keepOnlyTimeline(group.getChildAt(i));
        } else if (view.getVisibility() == View.VISIBLE) {
            view.setVisibility(View.INVISIBLE);
            mSeekOnlyHiddenViews.add(view);
        }
    }

    private void scheduleSeekOnlyHide() {
        mHandler.removeCallbacks(mFinishSeekOnly);
        if (isSeekOnlyShowing()) mHandler.postDelayed(mFinishSeekOnly, SEEK_ONLY_LINGER_MS);
    }

    private void clearSeekOnly() {
        mHandler.removeCallbacks(mFinishSeekOnly);
        mSeekOnlyRestoreFlags = 0;
        mSeekOnlyRestoreFocus = false;
        if (!isSeekOnlyShowing()) return;
        mSeekOnlyShowing = false;
        sSeekOnlyView = null;
        if (mControlBar != null) mControlBar.setVisibility(View.GONE);
        if (mControlBar2 != null) mControlBar2.setVisibility(View.GONE);
        if (mSeekOnlyReservesVolumeSpace && mVolumeBar != null) mVolumeBar.setVisibility(View.GONE);
        mSeekOnlyReservesVolumeSpace = false;
        for (View view : mSeekOnlyHiddenViews) view.setVisibility(View.VISIBLE);
        mSeekOnlyHiddenViews.clear();
        if (!mIsStopped && Player.sPlayer != null) updatePauseButton();
        notifySeekOnlyVisibility(false);
    }

    private void notifySeekOnlyVisibility(boolean visible) {
        if (mOnControlBarVisibilityListener != null) {
            mOnControlBarVisibilityListener.onControlBarVisibilityChanged(visible || mControlBarShowing);
        }
    }

    private static final int PHONE_PREVIEW_SEEK_INTERVAL_MS = 100;
    private static final int PHONE_PREVIEW_SEEK_MIN_DELTA_MS = 120;
    // Swipe-seek sensitivity, in milliseconds of video per screen width swiped.
    // Slow swipes give fine control (full width = ~18 s), fast flicks travel far (full width = ~8 min).
    private static final float PHONE_SWIPE_SLOW_GAIN = 0.15f;
    private static final float PHONE_SWIPE_FAST_GAIN = 4f;
    private static final float PHONE_SWIPE_BASE_MS_PER_WIDTH = 120000f;
    private static final float PHONE_SWIPE_SLOW_SPEED = 0.15f;  // screen widths per second
    private static final float PHONE_SWIPE_FAST_SPEED = 2f;     // screen widths per second
    private float mPhoneSeekOffset, mPhoneLastX, mPhoneSwipeSpeed;
    private long mPhoneLastMoveTime;
    private Player mPhonePreviewSource;
    private long mPhonePreviewTime;
    private int mPhonePreviewPosition = -1;

    private boolean mPhoneTouch, mPhoneCancelled;
    private boolean mDetectStandardDoubleTap;
    /** The touch started in the top 20% of the screen: no brightness / volume swipe from there. */
    private boolean mPhoneTopZone;
    private int mPhoneAxis, mPhoneStartPosition, mPhoneSeekPosition, mPhoneStartVolume, mPhoneStartBrightness;
    private float mPhoneDownX, mPhoneDownY;
    private TextView mPhoneGesturePill;
    private final Runnable mHidePhonePill = () -> {
        if (mPhoneGesturePill != null) mPhoneGesturePill.setVisibility(View.GONE);
    };

    private boolean phoneHit(View view, MotionEvent event) {
        if (view == null || !view.isShown()) return false;
        android.graphics.Rect bounds = new android.graphics.Rect();
        return view.getGlobalVisibleRect(bounds) && bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    private void clearPhoneGesture() {
        cancelStandardTapDetection();
        mPhoneTouch = false;
        endPhonePreview();
        mPhonePreviewPosition = -1;
        mHandler.removeCallbacks(mHidePhonePill);
        if (mPhoneGesturePill != null) {
            ViewGroup parent = (ViewGroup) mPhoneGesturePill.getParent();
            if (parent != null) parent.removeView(mPhoneGesturePill);
            mPhoneGesturePill = null;
        }
    }

    private void showPhoneVolumePill() {
        int max = Math.max(1, mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int value = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        showPhonePill(mContext.getString(R.string.phone_gesture_volume, Math.round(value * 100f / max)),
                value == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        mHandler.postDelayed(mHidePhonePill, 1200);
    }

    void showEngineFallbackNotice(int message) {
        showPhonePill(mContext.getString(message), R.drawable.ic_display_settings_24);
        mHandler.postDelayed(mHidePhonePill, 3500);
    }

    private void showPhonePill(String text, int iconRes) {
        if (mWindow == null) return;
        FrameLayout decor = (FrameLayout) mWindow.getDecorView();
        float density = mContext.getResources().getDisplayMetrics().density;
        if (mPhoneGesturePill == null) {
            mPhoneGesturePill = new TextView(mContext);
            mPhoneGesturePill.setTextSize(13);
            mPhoneGesturePill.setTextColor(0xFFFFFFFF);
            mPhoneGesturePill.setPadding((int)(16*density), (int)(9*density), (int)(16*density), (int)(9*density));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(0xD9000000);
            background.setCornerRadius(30*density);
            mPhoneGesturePill.setBackground(background);
            mPhoneGesturePill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            decor.addView(mPhoneGesturePill, new FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL));
        }
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
        androidx.core.graphics.Insets safe = insets == null ? androidx.core.graphics.Insets.NONE
                : insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPhoneGesturePill.getLayoutParams();
        params.leftMargin = Math.max(safe.left, safe.right) + (int)(16*density);
        params.rightMargin = params.leftMargin;
        params.topMargin = safe.top + (int)(32*density);
        mPhoneGesturePill.setLayoutParams(params);
        android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(mContext, iconRes);
        if (icon != null) {
            icon = icon.mutate();
            androidx.core.graphics.drawable.DrawableCompat.setTint(icon, 0xFFFFFFFF);
            int size = (int)(20*density);
            icon.setBounds(0, 0, size, size);
        }
        mPhoneGesturePill.setCompoundDrawables(icon, null, null, null);
        mPhoneGesturePill.setCompoundDrawablePadding((int)(8*density));
        mPhoneGesturePill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mPhoneGesturePill.setText(text);
        applyNightMode(mPhoneGesturePill);
        mPhoneGesturePill.setVisibility(View.VISIBLE);
        mPhoneGesturePill.bringToFront();
        mHandler.removeCallbacks(mHidePhonePill);
    }

    /** Compact UI: the selected audio / subtitle stream in the top info pill. */
    void showTrackPill(boolean audio, CharSequence name) {
        if (!mMiniUi || name == null) return;
        showPhonePill(mContext.getString(audio ? R.string.player_audio_track_toast : R.string.player_subtitle_track_toast)
                + " " + name, audio ? R.drawable.mobile_ic_audio : R.drawable.mobile_ic_subtitle);
        mHandler.postDelayed(mHidePhonePill, 2500);
    }

    private String phoneSeekTime(int millis) {
        int seconds = Math.max(0, millis / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static final float PHONE_SIDE_GESTURE_WIDTH = 0.40f;

    private float phoneTouchXFraction(float rawX) {
        View decor = mWindow.getDecorView();
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
        return (rawX - origin[0]) / Math.max(1, decor.getWidth());
    }

    private boolean isAboveCompactControls(float rawY) {
        if (mControlBar == null || mControllerViewLeft == null) return false;
        View root = mControllerViewLeft;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        float y = rawY - origin[1];
        int controlsHeight = mControlBar.getMeasuredHeight();
        if (controlsHeight == 0 && root.getWidth() > 0) {
            mControlBar.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            controlsHeight = mControlBar.getMeasuredHeight();
        }
        return y >= root.getHeight() * 0.30f && y < root.getHeight() - controlsHeight;
    }

    private boolean standardTapZonesEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String style = getPhonePlayerStyle(prefs);
        return PHONE_STYLE_MINI.equals(style)
                && prefs.getBoolean(PREF_STANDARD_TAP_ZONES, true);
    }

    private boolean canHandleStandardTap() {
        Player player = Player.sPlayer;
        return standardTapZonesEnabled() && !mIsStopped && !mIsLocked && !mPhoneTrackPopupOpen
                && !mSwipePillShowing && !isCompactPillExpanded() && !isTVMenuDisplayed
                && !isInfoActivityDisplayed() && mWindow != null && mWindow.getDecorView().hasWindowFocus()
                && player != null && player.isInPlaybackState();
    }

    private void cancelStandardTapDetection() {
        if (!mDetectStandardDoubleTap) return;
        mDetectStandardDoubleTap = false;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        mStandardTapDetector.onTouchEvent(cancel);
        cancel.recycle();
    }

    private void handleStandardDoubleTap(MotionEvent event) {
        Player player = Player.sPlayer;
        if (player == null || player.isBusy()
                || event.getEventTime() - event.getDownTime() >= android.view.ViewConfiguration.getLongPressTimeout()) return;

        float x = phoneTouchXFraction(event.getRawX());
        if (x < 1f / 3f) {
            if (player.canSeekBackward()) seekWithDoubleTap(-1);
        } else if (x >= 2f / 3f) {
            if (player.canSeekForward()) seekWithDoubleTap(1);
        } else if (player.canPause() && tapPlayPauseEnabled()) {
            mPauseListener.onClick(mPauseButton);
        }
    }

    private void seekWithDoubleTap(int direction) {
        // No seek bar: onSeek shows the info pill (seconds jumped and new position) for a moment.
        onSeek(direction, false, false);
    }

    private void handlePhoneTap(MotionEvent event) {
        if (isCompactPillExpanded()) {
            if (!phoneHit(mControllerViewLeft.findViewById(R.id.mobile_compact_pill), event)) {
                setCompactPillExpanded(mControllerViewLeft, false);
            }
            return;
        }

        if (isCompactPhoneControls()) {
            float x = phoneTouchXFraction(event.getRawX());
            if (x >= PHONE_SIDE_GESTURE_WIDTH && x <= 1f - PHONE_SIDE_GESTURE_WIDTH
                    && isAboveCompactControls(event.getRawY())) {
                if (tapPlayPauseEnabled() && !mIsStopped && Player.sPlayer != null && Player.sPlayer.isInPlaybackState()
                        && !Player.sPlayer.isBusy() && Player.sPlayer.canPause()) {
                    if (!isCompactPillExpanded()) hide(FLAG_SIDE_CONTROL_BAR);
                    mPauseListener.onClick(mPauseButton);
                }
                return;
            }
        }
        if (!isCompactPillExpanded()) toggleMediaControlsVisiblity(FLAG_SIDE_CONTROL_BAR);
    }

    private boolean hitsPhoneControls(MotionEvent event) {
        if (isSeekOnlyShowing()) return phoneHit(mProgress, event) || phoneHit(mProgress2, event);
        if (isCompactPillExpanded()) {
            return phoneHit(mControllerViewLeft.findViewById(R.id.mobile_compact_pill), event);
        }
        return phoneHit(mControlBar, event) || phoneHit(mControlBar2, event)
                || (isCompactPhoneControls() && phoneHit(
                        mControllerViewLeft.findViewById(R.id.mobile_compact_transport), event));
    }

    private void previewPhoneSeek(long eventTime, int duration) {
        Player player = Player.sPlayer;
        if (player == null || !player.isLocalVideo() || !mSeekComplete || mDragging || isSeekPressed()
                || mPhoneSeekPosition >= (player.isPlaying() ? duration - 1500 : duration)) return;
        if (mPhonePreviewPosition >= 0 && (eventTime - mPhonePreviewTime < PHONE_PREVIEW_SEEK_INTERVAL_MS
                || Math.abs(mPhoneSeekPosition - mPhonePreviewPosition) < PHONE_PREVIEW_SEEK_MIN_DELTA_MS)) return;

        if (player.seekToPreviewFrame(mPhoneSeekPosition)) {
            mPhonePreviewSource = player;
            mPhonePreviewPosition = mPhoneSeekPosition;
            mPhonePreviewTime = eventTime;
        }
    }

    private void endPhonePreview() {
        if (mPhonePreviewSource == null) return;
        mPhonePreviewSource.endSeekPreview();
        mPhonePreviewSource = null;
    }

    private void finishPhoneSeek(boolean commit) {
        endPhonePreview();
        Player player = Player.sPlayer;
        if (player == null || mIsStopped || !player.isInPlaybackState()) return;
        int target = commit ? mPhoneSeekPosition : mPhoneStartPosition;
        if (target < 0 || (target == mPhoneStartPosition && mPhonePreviewPosition < 0)) return;
        player.seekTo(target);
    }

    private boolean handlePhoneGesture(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mPhoneTouch = false;
            if (mIsStopped || mIsLocked || isTVMenuDisplayed || isInfoActivityDisplayed()
                    || mWindow == null || !mWindow.getDecorView().hasWindowFocus()
                    || Player.sPlayer == null || !Player.sPlayer.isInPlaybackState()
                    || hitsPhoneControls(event)
                    || (mActionBarShowing && event.getY() < mActionBarHeight)) {
                cancelStandardTapDetection();
                return false;
            }
            boolean detectDoubleTap = canHandleStandardTap();
            if (!detectDoubleTap) cancelStandardTapDetection();
            mDetectStandardDoubleTap = detectDoubleTap;
            if (mDetectStandardDoubleTap) mStandardTapDetector.onTouchEvent(event);
            mPhoneTouch = true;
            mPhoneCancelled = false;
            mPhoneTopZone = event.getRawY() < 0.2f * Math.max(1, mWindow.getDecorView().getHeight());
            mPhoneAxis = 0;
            mPhoneDownX = event.getRawX();
            mPhoneDownY = event.getRawY();
            endPhonePreview();
            mPhoneStartPosition = mPhoneSeekPosition = Player.sPlayer.getCurrentPosition();
            mPhoneSeekOffset = 0;
            mPhoneSwipeSpeed = 0;
            mPhonePreviewPosition = -1;
            mPhoneStartVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            mPhoneStartBrightness = PlayerBrightnessManager.getLinearBrightness(mWindow);
            return true;
        }
        if (!mPhoneTouch) return false;
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_CANCEL
                || Player.sPlayer == null || mIsStopped || !mWindow.getDecorView().hasWindowFocus()) mPhoneCancelled = true;
        float dx = event.getRawX() - mPhoneDownX;
        float dy = mPhoneDownY - event.getRawY();
        float width = Math.max(1, mWindow.getDecorView().getWidth());
        float height = Math.max(1, mWindow.getDecorView().getHeight());
        if (!mPhoneCancelled && action == MotionEvent.ACTION_MOVE) {
            int slop = android.view.ViewConfiguration.get(mContext).getScaledTouchSlop();
            if (mPhoneAxis == 0 && Math.max(Math.abs(dx), Math.abs(dy)) > slop) {
                mPhoneAxis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                if (mPhoneAxis == 1) {
                    // Speed-based seeking is measured from here, so the video does not jump at the start.
                    mPhoneLastX = event.getRawX();
                    mPhoneLastMoveTime = event.getEventTime();
                    mPhoneSeekOffset = 0;
                    mPhoneSwipeSpeed = 0;
                }
                cancelStandardTapDetection();
                if (mPhoneAxis == 2 && !mSwipeBottomZone && !mPhoneTopZone) {
                    // Start the vertical gesture from the value that is current right now, and
                    // measure the swipe from this point so the value does not jump at the start.
                    mPhoneDownY = event.getRawY();
                    dy = 0;
                    mPhoneStartVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    mPhoneStartBrightness = PlayerBrightnessManager.beginBrightnessGesture(mWindow);
                }
            }
            if (mPhoneAxis == 1 && swipeSeekEnabled()) {
                int duration = Player.sPlayer.getDuration();
                if (duration > 0 && (dx >= 0 ? Player.sPlayer.canSeekForward() : Player.sPlayer.canSeekBackward())) {
                    // Seek distance depends on swipe speed: slow = fine, fast = large jumps.
                    float step = event.getRawX() - mPhoneLastX;
                    long dt = event.getEventTime() - mPhoneLastMoveTime;
                    mPhoneLastX = event.getRawX();
                    mPhoneLastMoveTime = event.getEventTime();
                    if (dt > 0) {
                        float instant = Math.abs(step) / width / (dt / 1000f);
                        mPhoneSwipeSpeed = mPhoneSwipeSpeed * 0.6f + instant * 0.4f;
                    }
                    float t = Math.max(0f, Math.min(1f, (mPhoneSwipeSpeed - PHONE_SWIPE_SLOW_SPEED)
                            / (PHONE_SWIPE_FAST_SPEED - PHONE_SWIPE_SLOW_SPEED)));
                    float gain = (float) (PHONE_SWIPE_SLOW_GAIN
                            * Math.pow(PHONE_SWIPE_FAST_GAIN / PHONE_SWIPE_SLOW_GAIN, t));
                    mPhoneSeekOffset += step / width * PHONE_SWIPE_BASE_MS_PER_WIDTH * gain;
                    // Keep the offset inside the video so reversing direction responds immediately at the ends.
                    mPhoneSeekOffset = Math.max(-mPhoneStartPosition,
                            Math.min(duration - mPhoneStartPosition, mPhoneSeekOffset));
                    mPhoneSeekPosition = (int)Math.max(0L, Math.min((long)duration,
                            mPhoneStartPosition + Math.round(mPhoneSeekOffset)));
                    showSeekOnly();
                    if (mPhoneSeekPosition != mPhoneStartPosition || mPhonePreviewPosition >= 0) {
                        previewPhoneSeek(event.getEventTime(), duration);
                    }
                    if (isSeekOnlyShowing()) setProgress();
                    if (mMiniUi) {
                        int delta = mPhoneSeekPosition - mPhoneStartPosition;
                        showPhonePill(mContext.getString(R.string.phone_gesture_seek,
                                Math.abs(delta) / 1000, phoneSeekTime(mPhoneSeekPosition)),
                                delta >= 0 ? R.drawable.mobile_ic_forward : R.drawable.mobile_ic_rewind);
                        mHandler.postDelayed(mHidePhonePill, 1200);
                    }
                }
            } else if (mPhoneAxis == 2 && !mSwipeBottomZone && !mPhoneTopZone) {
                float x = phoneTouchXFraction(mPhoneDownX);
                if (x < PHONE_SIDE_GESTURE_WIDTH) {
                    int level = Math.max(0, Math.min(30, mPhoneStartBrightness + Math.round(dy / height * 30)));
                    int current = PlayerBrightnessManager.getLinearBrightness(mWindow);
                    PlayerBrightnessManager.setLinearBrightness(level, level >= current, mWindow);
                    showPhonePill(mContext.getString(R.string.phone_gesture_brightness,
                            Math.round(PlayerBrightnessManager.getLinearBrightness(mWindow) * 100f / 30)), R.drawable.ic_brightness);
                } else if (x > 1f - PHONE_SIDE_GESTURE_WIDTH) {
                    int max = Math.max(1, mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    int steps = Math.round(dy / height * max / getMobileVolumeStep());
                    setMusicVolume(Math.max(0, Math.min(max, mPhoneStartVolume + steps * getMobileVolumeStep())));
                    updateVolumeBar();
                    showPhonePill(mContext.getString(R.string.phone_gesture_volume,
                            Math.round(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)), R.drawable.ic_volume);
                }
            }
        }
        if (mPhoneCancelled) cancelStandardTapDetection();
        if (mDetectStandardDoubleTap) mStandardTapDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mPhoneTouch = false;
            boolean commit = !mPhoneCancelled && action == MotionEvent.ACTION_UP;
            if (mPhoneAxis == 1) {
                finishPhoneSeek(commit);
            } else if (commit && mPhoneAxis == 0 && !mDetectStandardDoubleTap) {
                handlePhoneTap(event);
            }
            if (mPhoneAxis == 1 && isSeekOnlyShowing()) {
                if (mPhoneCancelled) mFinishSeekOnly.run();
                else scheduleSeekOnlyHide();
            }
            mHandler.postDelayed(mHidePhonePill, 1200);
        }
        return true;
    }

    //this will be sent by activity
    public boolean onTouch(MotionEvent event){
        if (event == null) return false;
        if (isTVMenuDisplayed) mLastTouchEventTime = event.getEventTime();
        if (!isTVMode && !TVUtils.isTV(mContext)) {
            if (handleSwipePillTouch(event)) return true;
            // Touches on the open pill go straight to its buttons, never to the player gestures/taps.
            if (mSwipePillShowing && mSwipeTouchOnPill) return false;
            return handlePhoneGesture(event);
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouch(View v, MotionEvent event) {
        if (event == null) return false;
        return gestureDetector.onTouchEvent(event);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent event) {
        if (event == null) return false;
        if (log.isDebugEnabled()) log.debug("onSingleTapConfirmed");
        if (isTVMenuDisplayed) mLastTouchEventTime = event.getEventTime();
        if(mControllerViewLeft!=null){
            View overlay = mControllerViewLeft.findViewById(R.id.help_overlay);
            if(event.getAction()==KeyEvent.ACTION_DOWN&&overlay!=null&&overlay.getVisibility()==View.VISIBLE){
                sendOverlayFadeOut(0); 
                return true;
            }
        }
        
        if (mControllerView == null) {
            return false;
        }
        if(isTVMenuDisplayed){
            showTVMenu(false);
            return false;
        }

        if(mIsLocked){
            showUnlockInstructions(true);
            return true;
        }

        if (mActionBarShowing && event.getY() < mActionBarHeight) {
            mWindow.superDispatchTouchEvent(event);
            return true;
        }

        if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY)!=0) {
            if (log.isDebugEnabled()) log.debug("onSingleTapConfirmed: BUTTON_SECONDARY");
            return false;
        }

        if (!isTVMode && !TVUtils.isTV(mContext)) {
            handlePhoneTap(event);
            return true;
        }

        // Check if touch is in central screen zone (middle third both horizontally and vertically)
        if (isTouchInCentralZone(event)) {
            if (log.isDebugEnabled()) log.debug("onSingleTapConfirmed: central zone touch");
            handleCentralZoneTouch();
        } else {
            // Original behavior for touches outside central zone
            int flags = FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS;

            if (!mVolumeBarEnabled) {
                flags &= ~FLAG_SIDE_VOLUME_BAR;
            }
            if (mIsStopped || !Player.sPlayer.isInPlaybackState()) {
                flags &= ~(FLAG_SIDE_CONTROL_BAR);
            }

            toggleMediaControlsVisiblity(flags);
        }
        
        switchMode(false);
        return true;
    }

    private boolean isTouchInCentralZone(MotionEvent event) {
        if (mControllerView == null) return false;
        
        int viewWidth = mControllerView.getWidth();
        int viewHeight = mControllerView.getHeight();

        // Define central zone as middle third horizontally and Vertically.
        float centralZoneLeft = viewWidth / 3.0f;
        float centralZoneRight = viewWidth * 2.0f / 3.0f;
        float centralZoneTop = viewHeight / 4.0f;
        float centralZoneBottom = viewHeight * 3.0f / 4.0f;
        
        float touchX = event.getX();
        float touchY = event.getY();
        
        return (touchX >= centralZoneLeft && touchX <= centralZoneRight) && (touchY >= centralZoneTop && touchY <= centralZoneBottom) ;
    }

    private void handleCentralZoneTouch() {
        if (mIsStopped || !Player.sPlayer.isInPlaybackState()) {
            // If not in playback state, just toggle OSD visibility
            int flags = FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS;
            if (!mVolumeBarEnabled) {
                flags &= ~FLAG_SIDE_VOLUME_BAR;
            }
            toggleMediaControlsVisiblity(flags);
            return;
        }

        // Central zone behavior: always toggle play/pause, show OSD when pausing, hide when resuming
        if (Player.sPlayer.isPlaying()) {
            //Update the controls visibility 1st, so the Fadeout takes effect.
            int flags = FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS;
            if (!mVolumeBarEnabled) {
                flags &= ~FLAG_SIDE_VOLUME_BAR;
            }
            show(flags, pauseTimeout);

            // If playing, pause and show controls (keep them visible)
            Player.sPlayer.pause(PlayerController.STATE_NORMAL);
            updatePausePlay();
        } else {
            // If paused, resume and hide controls
            Player.sPlayer.start(PlayerController.STATE_NORMAL);
            updatePausePlay();
            hide();
        }
    }

    @Override
    public boolean onDown(MotionEvent e) {
        scrollGestureVertical = 0f;
        scrollGestureHorizontal = 0f;
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {}

    @Override
    public boolean onSingleTapUp(MotionEvent e) { return false; }

    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!isTVMode && !TVUtils.isTV(mContext)) return true;
        if (e1 == null || e2 == null) return false;

        if(mIsLocked){
            showUnlockInstructions(true);
            return true;
        }

        float deltaY = e2.getY() - e1.getY();

        DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;

        if (scrollGestureHorizontal == 0 || scrollGestureVertical == 0) {
            scrollGestureHorizontal = 0.0001f;
            scrollGestureVertical = 0.0001f;
            return false;
        }

        // Exclude border
        if (e1.getY() < BORDER_WIDTH || e1.getX() < BORDER_WIDTH ||
                e1.getY() > screenHeight - BORDER_WIDTH || e1.getX() > screenWidth - BORDER_WIDTH)
            return false;

        scrollGestureHorizontal += distanceX;
        scrollGestureVertical += distanceY;
        float halfWidth = screenWidth / 2f;

        //Check the angle of the scroll, and make sure its in range.
        float angleRadians = (float) Math.atan2(distanceY, distanceX);
        float angleDegrees = (float) Math.toDegrees(angleRadians);
        if (angleDegrees < 0)
            angleDegrees += 360;

        // Define upward/downward angle ranges
        boolean isVerticalScroll = (angleDegrees >= 90-SCROLL_ANGLE && angleDegrees <= 90+SCROLL_ANGLE) || (angleDegrees >= 270-SCROLL_ANGLE && angleDegrees <= 270+SCROLL_ANGLE);
        //boolean isVerticalScroll = (Math.abs(distanceY) > Math.abs(distanceX));

        if (Math.abs(scrollGestureVertical) > SCROLL_THRESHOLD && isVerticalScroll) {
            if (e1.getX() < halfWidth) { // left screen part
                if (log.isDebugEnabled()) log.debug("onScroll: left screen part, direction={}", (scrollGestureVertical > 0 ? "up" : "down"));
                scrollIncrementalBrightnessUpdate(scrollGestureVertical > 0);
            } else { // right screen part
                if (log.isDebugEnabled()) log.debug("onScroll: left screen part, direction={}", (scrollGestureVertical > 0 ? "up" : "down"));
                scrollIncrementalVolumeUpdate(scrollGestureVertical > 0);
            }
            scrollGestureVertical = 0.0001f;
        }

        return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {}

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) { return false; }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        if (!isTVMode && !TVUtils.isTV(mContext)) return true;

        if(mIsLocked){
            showUnlockInstructions(true);
            return true;
        }

        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (log.isDebugEnabled()) log.debug("onDoubleTapEvent");
            float x = e.getX();
            float y = e.getY();
            if (mControllerView == null) return false;
            float viewWidth = (float) mControllerView.getWidth();
            float viewHeight = (float) mControllerView.getHeight();
            
            // Determine if we're in top 75% (seek) or bottom 25% (audio speed) zone
            boolean isTopZone = y < (viewHeight * 0.75f);
            
            if (x < viewWidth / 3) { // left region
                if (isTopZone) {
                    // Top 75% left: fast rewind
                    if (Player.sPlayer.canSeekBackward() && mSeekKeyDirection != -1) {
                        if (mOsdLeftTextView != null) {
                            mOsdLeftTextView.setText("");
                            Drawable osdIcon = getDrawable(mContext, R.drawable.media_fast_rewind);
                            mOsdLeftTextView.setCompoundDrawablesWithIntrinsicBounds(osdIcon, null, null, null);
                            mOsdLeftTextView.setVisibility(View.VISIBLE);
                            hideOsdHandler.removeCallbacks(hideOsdRunnable);
                            hideOsdHandler.postDelayed(hideOsdRunnable, 300);
                        }
                        Player.sPlayer.seekTo(Player.sPlayer.getCurrentPosition() - 10000);
                    }
                } else {
                    // Bottom 25% left: decrease audio speed
                    if (VideoPreferencesCommon.isAudioSpeedEnabled(mContext)) {
                        PlayerService.sPlayerService.decrementAudioSpeed();
                        showAudioSpeedOSD(PlayerService.sPlayerService.getAudioSpeed());
                    }
                }
            } else if (x > viewWidth / 1.5) {  // right region
                if (isTopZone) {
                    // Top 75% right: fast forward
                    if (Player.sPlayer.canSeekBackward() && mSeekKeyDirection != 1) {
                        if (mOsdRightTextView != null) {
                            mOsdRightTextView.setText("");
                            Drawable osdIcon = getDrawable(mContext, R.drawable.media_fast_forward);
                            mOsdRightTextView.setCompoundDrawablesWithIntrinsicBounds(osdIcon, null, null, null);
                            mOsdRightTextView.setVisibility(View.VISIBLE);
                            hideOsdHandler.removeCallbacks(hideOsdRunnable);
                            hideOsdHandler.postDelayed(hideOsdRunnable, 300);
                        }
                        Player.sPlayer.seekTo(Player.sPlayer.getCurrentPosition() + 10000);
                    }
                } else {
                    // Bottom 25% right: increase audio speed
                    if (VideoPreferencesCommon.isAudioSpeedEnabled(mContext)) {
                        PlayerService.sPlayerService.incrementAudioSpeed();
                        showAudioSpeedOSD(PlayerService.sPlayerService.getAudioSpeed());
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean onGenericMotion(View v, MotionEvent event) {
        if(!isTVMenuDisplayed){
            if (log.isDebugEnabled()) log.debug("onGenericMotion : event={}", event);
            if (event.getActionButton() == MotionEvent.BUTTON_PRIMARY)
                return false;
            int action = event.getAction();

            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_EXIT) {
                // Ignore events sent by the remote control when it is in pointer mode
                return false;
            }

            show(FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS, 3000);

            return true;
        }
        return false;
    }

    private Handler hideOsdHandler = new Handler(Looper.getMainLooper());
    private Runnable hideOsdRunnable = new Runnable() {
        @Override
        public void run() {
            if (log.isDebugEnabled()) log.debug("hideOsdRunnable");
            // Hide both the fast forward and fast backward icons
            if (mOsdLeftTextView != null) mOsdLeftTextView.setVisibility(View.INVISIBLE);
            if (mOsdRightTextView != null) mOsdRightTextView.setVisibility(View.INVISIBLE);
        }
    };

    private void scrollIncrementalVolumeUpdate(boolean increase) {
        if (!TVUtils.isTV(mContext)) {
            int current = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maximum = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int amount = increase ? 1 : -1;
            setMusicVolume(Math.max(0, Math.min(maximum, current + amount)));
            updateVolumeBar();
            return;
        }
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int newVolume;
        Drawable volumeIcon;
        if (currentVolume == 0) volumeIcon = getDrawable(mContext, R.drawable.ic_volume_off);
        else volumeIcon = getDrawable(mContext, R.drawable.ic_volume);
        if (mOsdLeftTextView != null) {
            mOsdLeftTextView.setCompoundDrawablesWithIntrinsicBounds(volumeIcon, null, null, null);
            mOsdLeftTextView.setText(String.valueOf(currentVolume));
            mOsdLeftTextView.setVisibility(View.VISIBLE);
        }
        if (increase) newVolume = currentVolume + 1;
        else newVolume = currentVolume - 1;
        newVolume = Math.max(0, Math.min(newVolume, maxVolume)); // Constrain the value between 0 and maxVolume
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
        if (mOsdLeftTextView != null) mOsdLeftTextView.setText(String.valueOf(newVolume));
        if (log.isDebugEnabled()) log.debug("scrollIncrementalVolumeUpdate: increase={}, currentVolume={}, maxVolume={}, newVolume={}", increase, currentVolume, maxVolume, newVolume);
        if (newVolume == 0) volumeIcon = getDrawable(mContext, R.drawable.ic_volume_off);
        else volumeIcon = getDrawable(mContext, R.drawable.ic_volume);
        if (mOsdLeftTextView != null) mOsdLeftTextView.setCompoundDrawablesWithIntrinsicBounds(volumeIcon, null, null, null);
        hideOsdHandler.removeCallbacks(hideOsdRunnable);
        hideOsdHandler.postDelayed(hideOsdRunnable, 300);
        updateVolumeBar();
    }

    private void scrollIncrementalBrightnessUpdate(boolean increase) {
        float currentBrightness = PlayerBrightnessManager.getBrightness(mWindow);
        int currentIntBrightness= PlayerBrightnessManager.getLinearBrightness(mWindow);
        int newIntBrightness;
        Drawable brightnessIcon = getDrawable(mContext, R.drawable.ic_brightness);
        if (mOsdRightTextView != null) {
            mOsdRightTextView.setCompoundDrawablesWithIntrinsicBounds(brightnessIcon, null, null, null);
            mOsdRightTextView.setText(String.valueOf(currentIntBrightness));
            mOsdRightTextView.setVisibility(View.VISIBLE);
        }
        if (increase) newIntBrightness = currentIntBrightness + 1;
        else newIntBrightness = currentIntBrightness - 1;
        newIntBrightness = Math.max(0, Math.min(newIntBrightness, 30)); // Constrain the brightness between 0 and maxBrightness
        PlayerBrightnessManager.setLinearBrightness(newIntBrightness, increase, mWindow);
        if (mOsdRightTextView != null) mOsdRightTextView.setCompoundDrawablesWithIntrinsicBounds(brightnessIcon, null, null, null);
        if (log.isDebugEnabled()) log.debug("scrollIncrementalBrightnessUpdate: increase={}, currentBrightness={}, maxBrightness={}, newBrightness={}", increase, currentBrightness, 30, newIntBrightness);
        hideOsdHandler.removeCallbacks(hideOsdRunnable);
        hideOsdHandler.postDelayed(hideOsdRunnable, 300);
    }

    public void showAudioSpeedOSD(float audioSpeed) {
        String speedText = String.format(Locale.getDefault(), "%.2fx", audioSpeed);
        if (mOsdRightTextView != null) {
            mOsdRightTextView.setText(speedText);
            mOsdRightTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            mOsdRightTextView.setVisibility(View.VISIBLE);
            hideOsdHandler.removeCallbacks(hideOsdRunnable);
            hideOsdHandler.postDelayed(hideOsdRunnable, 300);
        }
        if (log.isDebugEnabled()) log.debug("showAudioSpeedOSD: audioSpeed={}", audioSpeed);
    }

    public boolean hasFocus() {
        // TODO Auto-generated method stub
        return (mControlBar != null && mControlBar.isFocused())
                || (mVolumeBar != null && mVolumeBar.isFocused());
    }

    boolean handlePillHorizontalKey(KeyEvent event) {
        if (mPlainButtonRow && !mSwipePillShowing) return false;     // plain focus navigation
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (mPhoneTrackPopupOpen || isTVMenuDisplayed) return false;

        View pill = mSwipePillShowing ? mSwipePill
                : isCompactPillExpanded() ? mControllerViewLeft.findViewById(R.id.mobile_compact_pill) : null;
        if (pill == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && !mSwipePillClosing) {
            if (mSwipePillShowing) {
                mHandler.removeCallbacks(mAutoDismissSwipePill);
                mHandler.postDelayed(mAutoDismissSwipePill, 8000);
            }
            ViewGroup actions = pill.findViewById(R.id.mobile_compact_actions);
            ViewGroup row = (ViewGroup) actions.getChildAt(0);
            int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            int focused = row.indexOfChild(row.getFocusedChild());
            int next = focused >= 0 ? focused + step : step > 0 ? 0 : row.getChildCount() - 1;
            for (; next >= 0 && next < row.getChildCount(); next += step) {
                View button = row.getChildAt(next);
                if (button.isShown() && button.isEnabled() && button.requestFocus()) break;
            }
        }
        return true;
    }

    /**
     * Compact UI + remote (D-pad):
     * - controls hidden: OK pauses / resumes, DOWN opens the controls on the seek bar, UP opens the buttons pill
     * - seek bar focused: LEFT / RIGHT seek, OK pauses / resumes, DOWN goes to the playback buttons, UP opens the pill
     * - playback buttons focused: LEFT / RIGHT move, OK clicks, UP goes back to the seek bar
     * - buttons pill open: LEFT / RIGHT move, OK clicks, DOWN / BACK close it
     * Returns true when the key was consumed.
     */
    boolean handleMiniRemoteKey(KeyEvent event) {
        if (!mMiniUi || mControllerViewLeft == null || mIsStopped || mIsLocked || isTVMenuDisplayed
                || mPhoneTrackPopupOpen || Player.sPlayer == null) return false;
        final int key = event.getKeyCode();
        final boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean first = isDown && event.getRepeatCount() == 0;
        final boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        final boolean vertical = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;

        if (mSwipePillShowing) {
            if (!ok && !vertical && key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_ESCAPE
                    && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            mHandler.removeCallbacks(mAutoDismissSwipePill);
            if (!mSwipePillClosing) mHandler.postDelayed(mAutoDismissSwipePill, 8000);
            if (ok || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;                 // nothing above the pill
            if (first) dismissSwipePill(true);                                // DOWN / BACK close it
            return true;
        }

        if (!ok && !vertical) return false;
        if (!isPlaybackReadyForRemote()) return false;

        final boolean timeline = isTimelineFocused();
        final View focus = mControllerViewLeft.findFocus();
        final boolean onButtons = mControlBarShowing && focus != null && !timeline;

        if (ok) {
            if (onButtons) return false;                                      // OK clicks the focused button
            if (first) mPauseListener.onClick(mPauseButtonOrNull());
            return true;                                                      // key up is swallowed too
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (first) {
                if (onButtons) {
                    show(FLAG_SIDE_CONTROL_BAR | FLAG_SIDE_ACTION_BAR | FLAG_SIDE_SYSTEM_BAR, SHOW_TIMEOUT);
                    focusSeekBar();                                           // buttons -> seek bar
                } else if (mSwipePill != null) {
                    showSwipePill();                                          // hidden / seek bar -> buttons pill
                    focusMiniPillFirstButton();
                }
            }
            return true;
        }
        // DOWN
        if (first) {
            if (!mControlBarShowing || focus == null) {
                showControlBar();                                             // hidden -> controls, cursor on the button bar
                mHandler.post(this::focusButtonBar);
            } else {
                show(FLAG_SIDE_CONTROL_BAR | FLAG_SIDE_ACTION_BAR | FLAG_SIDE_SYSTEM_BAR, SHOW_TIMEOUT);
                if (timeline) focusMiniPlaybackRow();                         // seek bar -> playback buttons
            }
        }
        return true;
    }

    private boolean isPlaybackReadyForRemote() {
        return Player.sPlayer != null && Player.sPlayer.isInPlaybackState();
    }

    private View mPauseButtonOrNull() {
        return mControllerViewLeft == null ? null : mControllerViewLeft.findViewById(R.id.pause);
    }

    private void focusMiniPlaybackRow() {
        View play = mPauseButtonOrNull();
        if (play != null && play.isShown()) {
            play.setFocusable(true);
            play.requestFocus();
        }
    }

    private void focusMiniPillFirstButton() {
        if (mSwipePill == null) return;
        View first = mSwipePill.findViewById(R.id.mobile_more);
        if (first != null) {
            first.setFocusable(true);
            first.requestFocus();
        }
    }

    boolean handlePhonePillRemoteKey(KeyEvent event) {
        if (mPlainButtonRow) return false;     // the pill is always open: plain focus navigation
        if (mControllerViewLeft == null || mIsStopped || mIsLocked || isTVMenuDisplayed
                || mPhoneTrackPopupOpen || TVUtils.isTV(mContext)) return false;
        View pill = mControllerViewLeft.findViewById(R.id.mobile_compact_pill);
        if (pill == null) return false;
        int key = event.getKeyCode();
        boolean open = mSwipePillShowing || isCompactPillExpanded();
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !open) {
                if (mSwipePill != null && swipePillEnabled()) {
                    showSwipePill();
                } else {
                    show(FLAG_SIDE_CONTROL_BAR, 0);
                    setCompactPillExpanded(mControllerViewLeft, true);
                }
                View first = pill.findViewById(R.id.mobile_more);
                first.setFocusable(true);
                first.requestFocus();
            }
            return true;
        }
        if (open && (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_BACK
                || key == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mSwipePillShowing) {
                    dismissSwipePill(true);
                } else {
                    setCompactPillExpanded(mControllerViewLeft, false);
                    pill.findViewById(R.id.mobile_controls_toggle).requestFocus();
                }
            }
            return true;
        }
        return false;
    }

    private boolean isTimelineFocused() {
        return (mProgress != null && mProgress.isFocused()) || (mProgress2 != null && mProgress2.isFocused());
    }

    boolean handleFocusedSeekBarKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (mIsStopped || mIsLocked || isTVMenuDisplayed || mPhoneTrackPopupOpen || Player.sPlayer == null
                || (!isTimelineFocused() && !isSeekOnlyShowing() && !mSeekOnlySession)) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        seekWithKey(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, true, action == KeyEvent.ACTION_DOWN);
        return true;
    }

    private void seekWithKey(int direction, boolean dpad, boolean down) {
        if (!down) {
            if (mSeekKeyDirection == direction) {
                mSeekKeyDirection = 0;
                if (mSeekOnlySession) scheduleSeekOnlyHide();
            }
            return;
        }
        Player player = Player.sPlayer;
        if (player == null || !player.isInPlaybackState() || mSeekKeyDirection == direction
                || !(direction < 0 ? player.canSeekBackward() : player.canSeekForward())) return;
        mSeekOnlySession = dpad;
        if (dpad) showSeekOnly();
        else show(FLAG_SIDE_CONTROL_BAR | FLAG_SIDE_ACTION_BAR | FLAG_SIDE_SYSTEM_BAR, 0);
        mSeekKeyDirection = direction;
        onSeek(direction, true);
    }

    public boolean onKey(int keyCode, KeyEvent event) {
        if (log.isDebugEnabled()) log.debug("onKey()");
        if (mLastTouchEventTime == event.getEventTime()) {
            return true;
        }
        if(mIsLocked){
            showUnlockInstructions(true);
            return true;
        }
        if (mSwipePill != null && TVUtils.isTV(mContext)) {
            if (mSwipePillShowing) return handleSwipePillKey(keyCode, event);
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !isTVMenuDisplayed && !mIsStopped && swipePillEnabled()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) showSwipePill();
                return true;                                   // UP opens the pill instead of the TV menu
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !isTVMenuDisplayed && !mIsStopped
                    && !mControlBarShowing && swipePillEnabled()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // DOWN opens the control area and leaves the cursor on the button bar
                    show(FLAG_SIDE_CONTROL_BAR | FLAG_SIDE_ACTION_BAR | FLAG_SIDE_SYSTEM_BAR, SHOW_TIMEOUT);
                    mHandler.postDelayed(this::focusButtonBar, 150);
                }
                return true;
            }
        }
        if (!TVUtils.isTV(mContext) && !ArchosFeatures.isAndroidTV(mContext)
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                    setMusicVolume(0);
                    updateVolumeBar();
                    showPhoneVolumePill();
                } else {
                    changeVolumeBy(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
                }
            }
            return true;
        }
        if (TVUtils.isPhoneUiOnTv(mContext)) {
            // phone UI on a TV: the remote drives the phone controls, never the TV menu
            Boolean phoneResult = handlePhoneRemoteKey(keyCode, event);
            if (phoneResult != null) return phoneResult;
        } else {
            switchMode(true);
        }

        if (isTVMenuDisplayed) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && mTVMenuAdapter != null) {
                switch(keyCode) {
                    case KeyEvent.KEYCODE_ESCAPE:
                    case KeyEvent.KEYCODE_MENU:
                        showTVMenu(false);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:    
                        mTVMenuAdapter.goToPrevious();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        mTVMenuAdapter.goToNext();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return false;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return false;
                }
            }
        }

        if (mControllerViewLeft != null) {
            View overlay = mControllerViewLeft.findViewById(R.id.help_overlay);
    
            if (event.getAction() == KeyEvent.ACTION_DOWN && overlay != null && overlay.getVisibility() == View.VISIBLE) {
                sendOverlayFadeOut(0);
                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU))  {
                if (!mTVMenuAdapter.isCreated() && mContext instanceof PlayerActivity) {
                    ((PlayerActivity) mContext).createPlayerTVMenu();
                }
                if (!isTVMenuDisplayed) {
                    showTVMenu(true);
                    if (log.isDebugEnabled()) log.debug("onKey, showing menu");
                    return true;
                }
            }
        }

            if (mVolumeBarEnabled) {
                final boolean passThroughHardwareVolumeKeys = ArchosFeatures.isAndroidTV(mContext);
                switch(keyCode) {
                    case KeyEvent.KEYCODE_VOLUME_DOWN:
                        // Let framework handle TV+HDMI keys so CEC/ARC controls external sink.
                        if (passThroughHardwareVolumeKeys) return false;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            changeVolumeBy(-1);
                        }
                        return true;
                    case KeyEvent.KEYCODE_D:
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            changeVolumeBy(-1);
                        }
                        return true;
                    case KeyEvent.KEYCODE_VOLUME_UP:
                        if (passThroughHardwareVolumeKeys) return false;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            changeVolumeBy(1);
                        }
                        return true;
                    case KeyEvent.KEYCODE_U:
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            changeVolumeBy(1);
                        }
                        return true;
                    case KeyEvent.KEYCODE_VOLUME_MUTE:
                        if (passThroughHardwareVolumeKeys) return false;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            setMusicVolume(0);
                            // Show volume slider
                            show(FLAG_SIDE_VOLUME_BAR, Player.sPlayer.isPlaying() ? SHOW_TIMEOUT : 0);
                            return true;
                        }
                        return true;
                }
            }

            if (!mIsStopped && Player.sPlayer.isInPlaybackState()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch(keyCode) {
                        case KeyEvent.KEYCODE_0:
                        case KeyEvent.KEYCODE_1:
                        case KeyEvent.KEYCODE_2:
                        case KeyEvent.KEYCODE_3:
                        case KeyEvent.KEYCODE_4:
                        case KeyEvent.KEYCODE_5:
                        case KeyEvent.KEYCODE_6:
                        case KeyEvent.KEYCODE_7:
                        case KeyEvent.KEYCODE_8:
                        case KeyEvent.KEYCODE_9:
                            seekToPercent((keyCode - KeyEvent.KEYCODE_0)*10);
                            return true;

                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            // Don't want to start music if the VideoPlayer is running
                            return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            showControlBar();
                            if (log.isDebugEnabled()) log.debug("onKey: dpad down");
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            seekWithKey(1, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, true);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_REWIND:
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            seekWithKey(-1, keyCode == KeyEvent.KEYCODE_DPAD_LEFT, true);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            return true; // do nothing
                        case KeyEvent.KEYCODE_Z:
                            mSettings.setSubtitleDelay(-100);
                            return true;
                        case KeyEvent.KEYCODE_X:
                            mSettings.setSubtitleDelay(100);
                            return true;
                        case KeyEvent.KEYCODE_SLASH:
                            if (mVolumeBarEnabled)
                                changeVolumeBy(-1);
                            return true;
                        case KeyEvent.KEYCODE_STAR:
                            if (mVolumeBarEnabled)
                                changeVolumeBy(1);
                            return true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    switch(keyCode) {
                        case KeyEvent.KEYCODE_MENU:
                            if (isTVMode || TVUtils.isTV(mContext)) {
                                return true;
                            }
                            if (Player.sPlayer.isBusy()) {
                                /* Don't show the menu if the MediaPlayer is busy */
                                return true;
                            }
                            if (!mActionBarShowing) {
                                showActionBar(true);
                                // don't consume, needs to trigger menu
                                return false;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            if (log.isDebugEnabled()) log.debug("onKey: play");
                            if (!Player.sPlayer.isPlaying()) {
                                Player.sPlayer.start(PlayerController.STATE_NORMAL);
                                updatePausePlay();
                                show();
                            }
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            if (Player.sPlayer.isPlaying()) {
                                if (log.isDebugEnabled()) log.debug("onKey: pause");
                                Player.sPlayer.pause(PlayerController.STATE_NORMAL);
                                updatePausePlay();
                                show();
                            }
                            return true;
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_P:
                        case KeyEvent.KEYCODE_SPACE:
                            if (Player.sPlayer.isPlaying()) {
                                if (log.isDebugEnabled()) log.debug("onKey: play/pause: pause");
                                Player.sPlayer.pause(PlayerController.STATE_NORMAL);
                                updatePausePlay();
                                show(FLAG_SIDE_CONTROL_BAR|FLAG_SIDE_ACTION_BAR|FLAG_SIDE_SYSTEM_BAR, pauseTimeout);
                            } else {
                                if (log.isDebugEnabled()) log.debug("onKey: play/pause: play");
                                Player.sPlayer.start(PlayerController.STATE_NORMAL);
                                updatePausePlay();
                                hide();
                            }
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            if (Player.sPlayer.isPlaying()) {
                                if (log.isDebugEnabled()) log.debug("onKey: stop, thus pause");
                                Player.sPlayer.pause(PlayerController.STATE_NORMAL);
                                updatePausePlay();
                                show();
                                Player.sPlayer.finishActivity();
                            }
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            seekWithKey(1, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, false);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_REWIND:
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            seekWithKey(-1, keyCode == KeyEvent.KEYCODE_DPAD_LEFT, false);
                            return true;
                        case KeyEvent.KEYCODE_O:
                        case KeyEvent.KEYCODE_PROG_RED:
                            if (isShowing())
                                hide();
                            else
                                show(FLAG_SIDE_ALL_EXCEPT_UNLOCK_INSTRUCTIONS, 0);
                            return true;
                        case KeyEvent.KEYCODE_F:
                        case KeyEvent.KEYCODE_PROG_GREEN:
                            mSurfaceController.switchVideoFormat();
                            rememberShowVideoFormat();
                            return true;
                        case KeyEvent.KEYCODE_J:
                        case KeyEvent.KEYCODE_S:
                        case KeyEvent.KEYCODE_PROG_YELLOW:
                            mSettings.switchSubtitleTrack();
                            return true;
                        case KeyEvent.KEYCODE_POUND:
                        case KeyEvent.KEYCODE_A:
                        case KeyEvent.KEYCODE_PROG_BLUE:
                            mSettings.switchAudioTrack();
                            return true;
                        case KeyEvent.KEYCODE_CHANNEL_DOWN:
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        case KeyEvent.KEYCODE_G:
                            if (VideoPreferencesCommon.isAudioSpeedEnabled(mContext)) {
                                PlayerService.sPlayerService.decrementAudioSpeed();
                                showAudioSpeedOSD(PlayerService.sPlayerService.getAudioSpeed());
                            }
                            return true;
                        case KeyEvent.KEYCODE_CHANNEL_UP:
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                        case KeyEvent.KEYCODE_H:
                            if (VideoPreferencesCommon.isAudioSpeedEnabled(mContext)) {
                                PlayerService.sPlayerService.incrementAudioSpeed();
                                showAudioSpeedOSD(PlayerService.sPlayerService.getAudioSpeed());
                            }
                            return true;
                    }
                }
            }

        return false;
    }
    Toast mSeekToast = null;
    private void seekToPercent(int percent) {
        if(mSeekToast!=null)
            mSeekToast.cancel();
        if (Player.sPlayer.canSeekForward()&&Player.sPlayer.canSeekBackward()){
            Player.sPlayer.seekTo(Player.sPlayer.getDuration()/100*percent);
            mSeekToast = Toast.makeText(mContext, mContext.getString(R.string.seek_to_percentage, String.valueOf(percent)), Toast.LENGTH_SHORT);
            mSeekToast.show();
        }
    }

    private boolean hasCutout() {
        if (mContext instanceof PlayerActivity) {
            PlayerActivity playerActivity = (PlayerActivity) mContext;
            return (playerActivity.mCutoutLeft + playerActivity.mCutoutRight + playerActivity.mCutoutTop + playerActivity.mCutoutBottom > 0);
        }
        return false;
    }

    /**
     * Phone UI on a TV: D-pad / OK / Menu of the remote drive the phone controls.
     * - controls hidden: Up / Down show them and put the focus on play / pause; Left / Right still seek, OK still play / pause
     * - a control has the focus: Up / Down / Left / Right move the focus (a focused seek bar moves its position), OK clicks
     * - Menu opens "more playback options"
     * Returns null to keep the default handling of the key.
     */
    private Boolean handlePhoneRemoteKey(int keyCode, KeyEvent event) {
        if (mControllerViewLeft == null) return null;
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && mMiniUi && mControlBarShowing
                        && mControllerViewLeft.hasFocus()) {
                    // DOWN on the button bar (not on the seek bar) opens the episodes / movies strip
                    View focused = mControllerViewLeft.findFocus();
                    if (focused != null && focused.getId() != R.id.seek_progress) {
                        if (down && mContext instanceof PlayerActivity) {
                            ((PlayerActivity) mContext).showMobileVideoStrip();
                        }
                        return true;
                    }
                }
                if (mControlBarShowing && mControllerViewLeft.hasFocus()) {
                    // keep the controls on screen while the user navigates, then let the focus system / the button handle the key
                    if (down) show(FLAG_SIDE_CONTROL_BAR | FLAG_SIDE_ACTION_BAR | FLAG_SIDE_SYSTEM_BAR, SHOW_TIMEOUT);
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (down) {
                        showControlBar();
                        mHandler.post(this::focusButtonBar);
                    }
                    return true;
                }
                return null;
            case KeyEvent.KEYCODE_MENU:
                if (!down) {
                    showControlBar();
                    mHandler.post(() -> {
                        View more = mControllerViewLeft == null ? null : mControllerViewLeft.findViewById(R.id.mobile_more);
                        if (more != null) more.performClick();
                    });
                }
                return true;
            default:
                return null;
        }
    }

    /** Puts the D-pad cursor on the seek bar (retries once while the control area is still fading in). */
    private void focusSeekBar() {
        SeekBar seek = mProgress;
        if (seek == null) return;
        seek.setFocusable(true);
        if (!seek.isShown() || !seek.requestFocus()) {
            mHandler.postDelayed(() -> {
                if (mProgress != null && mProgress.isShown()) mProgress.requestFocus();
            }, 200);
        }
    }

    /** Puts the D-pad cursor on the play / pause button of the button bar (retries once while the controls fade in). */
    private void focusButtonBar() {
        if (mControllerViewLeft == null) return;
        View play = mControllerViewLeft.findViewById(R.id.pause);
        if (play == null) return;
        play.setFocusable(true);
        if (!play.isShown() || !play.requestFocus()) {
            mHandler.postDelayed(() -> {
                View p = mControllerViewLeft == null ? null : mControllerViewLeft.findViewById(R.id.pause);
                if (p != null && p.isShown()) p.requestFocus();
            }, 200);
        }
    }

    private void focusPhonePlayButton() {
        if (mControllerViewLeft == null) return;
        View play = mControllerViewLeft.findViewById(R.id.pause);
        if (play != null && play.getVisibility() == View.VISIBLE) play.requestFocus();
    }

    private void switchMode(boolean tv) {
        if (TVUtils.isPhoneUiOnTv(mContext)) {
            tv = false; // phone UI chosen on a TV: never fall back to the TV controls
        } else if (TVUtils.isTV(mContext)) {
            tv = true;
        }
        isTVMode=tv;
        //for view not to be split before window attached
        if(mControllerViewLeft!=null){
            if(tv) {
                if (!mControlBarShowing)
                    setUIMode(UIMode);
            } else {
                setNormalMode();
                showTVMenu(false);
            }
            //hide action menu
            //hide button (pause, etc)
            if( mControllerViewLeft.findViewById(R.id.pause)!=null)
                mControllerViewLeft.findViewById(R.id.pause).setVisibility(tv?View.INVISIBLE:View.VISIBLE);
            if( mControllerViewLeft.findViewById(R.id.backward)!=null)
                mControllerViewLeft.findViewById(R.id.backward).setVisibility(tv?View.GONE:View.VISIBLE);
            if( mControllerViewLeft.findViewById(R.id.forward)!=null)
                mControllerViewLeft.findViewById(R.id.forward).setVisibility(tv?View.GONE:View.VISIBLE);
            if( mControllerViewLeft.findViewById(R.id.format)!=null)
                mControllerViewLeft.findViewById(R.id.format).setVisibility(tv?View.INVISIBLE:View.VISIBLE);
            if( mControllerViewLeft.findViewById(R.id.fullscreen_cutouts)!=null)
                mControllerViewLeft.findViewById(R.id.fullscreen_cutouts).setVisibility((tv || !hasCutout())?View.GONE:View.VISIBLE);
            if(mControllerViewRight!=null){
                if( mControllerViewRight.findViewById(R.id.pause)!=null)
                    mControllerViewRight.findViewById(R.id.pause).setVisibility(tv?View.INVISIBLE:View.VISIBLE);
                if( mControllerViewRight.findViewById(R.id.backward)!=null)
                    mControllerViewRight.findViewById(R.id.backward).setVisibility(tv?View.GONE:View.VISIBLE);
                if( mControllerViewRight.findViewById(R.id.forward)!=null)
                    mControllerViewRight.findViewById(R.id.forward).setVisibility(tv?View.GONE:View.VISIBLE);
                if( mControllerViewRight.findViewById(R.id.format)!=null)
                    mControllerViewRight.findViewById(R.id.format).setVisibility(tv?View.INVISIBLE:View.VISIBLE);
                if( mControllerViewRight.findViewById(R.id.fullscreen_cutouts)!=null)
                    mControllerViewRight.findViewById(R.id.fullscreen_cutouts).setVisibility((tv || !hasCutout())?View.GONE:View.VISIBLE);
            }
            
        }

        if(mContext instanceof PlayerActivity)
            ((PlayerActivity)mContext).switchMode(tv);
    }
    
    private void setVolumeBarMuteUI(boolean mute) {
        if (mVolumeBar==null) return; //better safe than...
        if (mute) {
            mVolumeBar.setAlpha(0.5f);
        } else {
            mVolumeBar.setAlpha(1.0f);
        }
    }

    public void handleJoystickEvent(int joystickZone) {
        if (mControllerView == null)
            return;
        mJoystickZone = joystickZone;

        if ((mJoystickZone == MediaUtils.JOYSTICK_ZONE_RIGHT || mJoystickZone == MediaUtils.JOYSTICK_ZONE_FAR_RIGHT) && !mJoystickSeekingActive) {
            // Only call onSeek() once when starting to seek but set longPress=true so that
            // the seek event will be sent periodically until the joystick is released
            if (log.isDebugEnabled()) log.debug("handleJoystickEvent, Joystick moved to the right => start seeking forward");
            if(mControlBar.isFocused()){
                mJoystickSeekingActive = true;
                onSeek(1, true);
            }
        }
        else if ((mJoystickZone == MediaUtils.JOYSTICK_ZONE_LEFT || mJoystickZone == MediaUtils.JOYSTICK_ZONE_FAR_LEFT) && !mJoystickSeekingActive) {
            // Only call onSeek() once when starting to seek but set longPress=true so that
            // the seek event will be sent periodically until the joystick is released
            if (log.isDebugEnabled()) log.debug("handleJoystickEvent, Joystick moved to the left => start seeking backward");
            if(mControlBar.isFocused()){
                mJoystickSeekingActive = true;
                onSeek(-1, true);
            }
        }
        else if (mJoystickZone == MediaUtils.JOYSTICK_ZONE_CENTER && mJoystickSeekingActive) {
            // The joystick is released (i.e. is back in the dead zone)
            if (log.isDebugEnabled()) log.debug("handleJoystickEvent, Joystick released => stop seeking");
            mJoystickSeekingActive = false;
            mSeekKeyDirection = 0;
        }
    }

    // STATUS_BAR_DISABLE_* are internal system flags with no public API replacement;
    // they have no effect for regular (non-system) apps on modern Android.
    @SuppressWarnings("deprecation")
    public void enableAllNotifications() {
        if (log.isDebugEnabled()) log.debug("Enable all notifications");
        mSystemUiVisibility = mPlayerView.getSystemUiVisibility();
        mSystemUiVisibility &= ~STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
        mSystemUiVisibility &= ~STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
        mSystemUiVisibility &= ~STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;
        mPlayerView.setSystemUiVisibility(mSystemUiVisibility);
        manualVisibilityChange=true;
    }

    @SuppressWarnings("deprecation")
    public void enableNotificationAlerts() {
        if (log.isDebugEnabled()) log.debug("Enable notification alerts only");
        mSystemUiVisibility = mPlayerView.getSystemUiVisibility();
        mSystemUiVisibility |= STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
        mSystemUiVisibility |= STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
        mSystemUiVisibility &= ~STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;
        mPlayerView.setSystemUiVisibility(mSystemUiVisibility);
        manualVisibilityChange=true;
    }

    @SuppressWarnings("deprecation")
    public void disableNotifications() {
        if (log.isDebugEnabled()) log.debug("Disable all notifications");
        mSystemUiVisibility = mPlayerView.getSystemUiVisibility();
        mSystemUiVisibility |= STATUS_BAR_DISABLE_NOTIFICATION_ICONS;
        mSystemUiVisibility |= STATUS_BAR_DISABLE_NOTIFICATION_TICKER;
        mSystemUiVisibility |= STATUS_BAR_DISABLE_NOTIFICATION_ALERTS;
        mPlayerView.setSystemUiVisibility(mSystemUiVisibility);
        manualVisibilityChange=true;
    }

    private void setRecursiveOnTouchListener(OnTouchListener t, View v){
        for(int i=0; i<((ViewGroup)v).getChildCount(); ++i) {
            View nextChild = ((ViewGroup)v).getChildAt(i);
            nextChild.setOnTouchListener(t);
        }
    }

    @SuppressLint("InflateParams") // Controller view is inflated then added into playerControllersContainer
    private void inflateRightView(){
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mControllerViewRight= inflater.inflate(getControllerLayout(), null);
        if (mControllerViewRight != null) {
            mControllerViewRight.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
        initControllerView(mControllerViewRight, false);
        playerControllersContainer.addView( mControllerViewRight);
        mTVMenuView2 = mControllerViewRight.findViewById(R.id.my_recycler_view);
        if(mTVMenuAdapter!=null && !mTVMenuAdapter.hasSlaverView())
            mTVMenuAdapter.initializeSlaveView((FrameLayout)mTVMenuView2);
    }
    public void setSideBySide() {
        // TODO Auto-generated method stub 	
        if(mControllerViewLeft!=null){
            if(mControllerViewRight==null){
                inflateRightView();
                mTVMenuAdapter.refocus();
            }
            PlayerControlsRelativeLayout plc = (PlayerControlsRelativeLayout)mControllerViewLeft.findViewById(R.id.playerControllers);
            PlayerControlsRelativeLayout plc2 = (PlayerControlsRelativeLayout)mControllerViewRight.findViewById(R.id.playerControllers);
            //right menu initialization

            if(plc!=null&&plc2!=null){
                splitView=true;
                plc.setSideBySide(true, 1);
                plc2.setSideBySide(true, 2);
                if(mControlBar2!=null&&mControlBar!=null)
                    setVisibility(mControlBar2, mControlBar.getVisibility()==View.VISIBLE, true);
                if(mVolumeBar2!=null&&mVolumeBar!=null)
                    setVisibility(mVolumeBar2, mVolumeBar.getVisibility()==View.VISIBLE, true);
                if(mTVMenuView2!=null)
                    setVisibility(mTVMenuView2, isTVMenuDisplayed, true);
                if(mControllerViewRight!=null)
                    mControllerViewRight.setVisibility(View.VISIBLE);
               
            }
        }

    }
    public void setTopBottom(){
        if(mControllerViewLeft!=null){
            if(mControllerViewRight==null){
                inflateRightView();
                mTVMenuAdapter.refocus();
            }
            PlayerControlsRelativeLayout plc = (PlayerControlsRelativeLayout)mControllerViewLeft.findViewById(R.id.playerControllers);
            PlayerControlsRelativeLayout plc2 = (PlayerControlsRelativeLayout)mControllerViewRight.findViewById(R.id.playerControllers);
            if(plc!=null&&plc2!=null){
                splitView=true;
                plc.setTopBottom(true, 1);
                plc2.setTopBottom(true, 2);
                if(mControlBar2!=null&&mControlBar!=null)
                    setVisibility(mControlBar2, mControlBar.getVisibility()==View.VISIBLE, true);
                if(mVolumeBar2!=null&&mVolumeBar!=null)
                    setVisibility(mVolumeBar2, mVolumeBar.getVisibility()==View.VISIBLE, true);
                if(mTVMenuView2!=null)
                    setVisibility(mTVMenuView2, isTVMenuDisplayed, true);
                if(mControllerViewRight!=null)
                    mControllerViewRight.setVisibility(View.VISIBLE);
                
            }
        }
    }
    public void setNormalMode(){
        if(mControllerViewLeft!=null && mControllerViewRight!=null){
            PlayerControlsRelativeLayout plc = (PlayerControlsRelativeLayout)mControllerViewLeft.findViewById(R.id.playerControllers);
            PlayerControlsRelativeLayout plc2 = (PlayerControlsRelativeLayout)mControllerViewRight.findViewById(R.id.playerControllers);
            if(plc!=null&&plc2!=null){
                plc.setSideBySide(false, 1);
                plc2.setSideBySide(false, 2);
            }
            splitView=false;
            if(mControllerViewRight!=null){
                if(mControlBar2!=null)
                    mControlBar2.setVisibility(View.GONE);
                if(mVolumeBar2!=null)
                    mVolumeBar2.setVisibility(View.GONE);
                if(mTVMenuView2!=null)
                    mTVMenuView2.setVisibility(View.GONE);           
                if(mControllerViewRight!=null)
                   mControllerViewRight.setVisibility(View.GONE);   
                mControllerViewRight=null;
                mControlBar2=null;
                mVolumeBar2=null;
                mTVMenuView2=null;
                mTVMenuAdapter.removeSlaveViews();
                playerControllersContainer.removeView(mControllerViewRight);
                
            }
        }
    }

    public void setUIMode(int mode) {
        UIMode = mode;
        if (log.isDebugEnabled()) log.debug("setUIMode, setting ui mode {}", mode);
        // TODO Auto-generated method stub
        if(mode==VideoEffect.SBS_MODE){
        	  if(!mControlBarShowing)
        		  setSideBySide();
        }
        else if(mode==VideoEffect.TB_MODE){
        	  if(!mControlBarShowing)
        		  setTopBottom();
        }
        else{   
           setNormalMode();
        }   
    }

    public void showTVMenu(boolean show){
        if (show) clearSeekOnly();
        if (show && mContext instanceof PlayerActivity) {
            ((PlayerActivity) mContext).refreshPlayModeIntroSummary();
        }
        if (mTVMenuView != null) {
            mTVMenuView.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && !isTVMenuDisplayed) {
                isTVMenuDisplayed = show;
                showControlBar(false);
                mTVMenuAdapter.focusStart();
            }
            else if (!show && isTVMenuDisplayed) {
                mTVMenuAdapter.hideAnimation();
            }
            else if (show && isTVMenuDisplayed) {
                mTVMenuAdapter.refocus();
            }
            if (mClock!=null) {
                setVisibility(mClock, show && !splitView, true);
            }
        }
        if(mTVMenuView2!=null){  
            mTVMenuView2.setVisibility(show&&splitView?View.VISIBLE:View.GONE);
        }
        if(show){
            mSeekKeyDirection = 0;
            hide();
        }
        else{
            //destroy dialogs
            dismissTVCardDialog(true);
        }
        isTVMenuDisplayed=show;
    }

    private boolean dismissTVCardDialog() {
        return dismissTVCardDialog(false);
    }

    /**
     * @param force close the card whatever its levels (the TV menu is going away); otherwise Back
     *              first lets a card with several levels go up one, and the card stays active.
     */
    private boolean dismissTVCardDialog(boolean force) {
        if (tvCardDialog == null || tvCardDialog.getVisibility() != View.VISIBLE)
            return false;
        log.info("Back navigation: active TV card dialog found");
        TVCardDialog dialog = tvCardDialog;
        tvCardDialog = null;
        if (force) {
            dialog.handleBackPressed();
        } else if (dialog.handleBack()) {
            tvCardDialog = dialog;
        }
        return true;
    }

    public boolean handleBackPressed() {
        log.info("Back navigation: TV menu displayed={}, card dialog active={}",
                isTVMenuDisplayed,
                tvCardDialog != null && tvCardDialog.getVisibility() == View.VISIBLE);
        if (dismissTVCardDialog())
            return true;
        if (isTVMenuDisplayed) {
            showTVMenu(false);
            return true;
        }
        if (ArchosFeatures.isAndroidTV(mContext) || TVUtils.isTV(mContext)) {
            if (mSwipePillShowing) {
                dismissSwipePill(true);
                return true;
            }
            if (isCompactPillExpanded()) {
                setCompactPillExpanded(mControllerViewLeft, false);
                mControllerViewLeft.findViewById(R.id.mobile_controls_toggle).requestFocus();
                return true;
            }
            if (isShowing()) {
                hide();
                return true;
            }
        }
        return false;
    }

    public boolean isTVMenuDisplayed() {
        // TODO Auto-generated method stub
        return isTVMenuDisplayed;
    }
    public TVMenuAdapter getTVMenuAdapter(){
        return mTVMenuAdapter;
    }


    private SimpleDateFormat getDateFormat() {
        if (mDateFormat == null) {
            if (DateFormat.is24HourFormat(mContext)) {
                mDateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            } else {
                mDateFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
            }
        }
        return mDateFormat;
    }

    public void updateClock() {
        if (mClock != null) {
            long now = System.currentTimeMillis();
            String currentClockText = getDateFormat().format(new Date(now));
            if (Player.sPlayer != null && (Player.sPlayer.isPlaying() || Player.sPlayer.isPaused())) {
                int duration = Player.sPlayer.getDuration();
                int position = Player.sPlayer.getCurrentPosition();
                float speed = PlayerService.sPlayerService != null ? PlayerService.sPlayerService.getAudioSpeed() : 1.0f;
                if (speed <= 0f) speed = 1.0f;

                if (duration > 0 && duration > position) {
                    long remainingMs = (long) ((duration - position) / speed);
                    String endClockText = getDateFormat().format(new Date(now + remainingMs));
                    mClock.setText(Clock.formatTimeWithArrow(currentClockText, endClockText));
                    return;
                }
            }
            mClock.setText(currentClockText);
        }
    }

    private View mUnlockInstructions;
    private View mUnlockInstructions2;
    private boolean mIsLocked;

    public void lock(){
        mIsLocked = true;
        // Hide central play icon when locked
        if (mCentralPlayIcon != null) {
            mCentralPlayIcon.setVisibility(View.GONE);
            if (mPlayPauseTouchZone != null)
                mPlayPauseTouchZone.setVisibility(View.INVISIBLE);
        }
        hide();
        showUnlockInstructions(true);
    }
    public void showUnlockInstructions(boolean visible) {
        if(visible) {

            final View.OnLongClickListener listener = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    mUnlockInstructions.setVisibility(View.GONE);
                    mIsLocked = false;
                    // Restore central play icon if video is paused
                    updatePausePlay();
                    show();
                    return false;
                }
            };
            if (mUnlockInstructions != null) {
                mUnlockInstructions.setVisibility(View.VISIBLE);
                mUnlockInstructions.findViewById(R.id.unlock_button).setOnLongClickListener(listener);
                sendFadeOut(SHOW_TIMEOUT);
            }

            if(mUnlockInstructions2!=null){
                mUnlockInstructions2.setVisibility(View.VISIBLE);
                mUnlockInstructions2.findViewById(R.id.unlock_button).setOnLongClickListener(listener);
            }
        }
        else{
            if (mUnlockInstructions != null)
                mUnlockInstructions.setVisibility(View.GONE);
            if (mUnlockInstructions2 != null)
                mUnlockInstructions2.setVisibility(View.GONE);
        }
    }
    
    public void setFullscreenWithCutoutButtonIcon(boolean isFullscreenWithCutout) {
        if (mFullscreenWithCutoutButton != null) {
            mFullscreenWithCutoutButton.setImageResource(isFullscreenWithCutout ? R.drawable.video_fullscreen_cutout_on : mSurfaceController.mCutBothSidesX ? R.drawable.video_fullscreen_cutout_off_equal : R.drawable.video_fullscreen_cutout_off);
            mFullscreenWithCutoutButton.setVisibility((isTVMode || TVUtils.isTV(mContext) || !hasCutout()) ? View.GONE : View.VISIBLE);
        }
    }
}
