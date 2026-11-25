/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.archos.mediacenter.video.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.player.SubtitleSettingsModel.ColorTarget;

/**
 * The phone's subtitle settings, in the player's usual dialog: an AlertDialog hung from the top, a
 * translucent surface and no dim, so the video and the live subtitle stay visible while adjusting.
 * What the rows are comes from SubtitleSettingsModel (shared with the TV), drawn by
 * SubtitleSettingsPhoneRows; every change is applied and saved as it is made.
 *
 * Colors have a page of their own (presets, then R / G / B steppers). Back goes from there to the
 * settings, at the scroll position it was left at, and only from the settings does it dismiss.
 */
public class SubtitleSettingsDialog extends AlertDialog
        implements SubtitleSettingsPhoneRows.Callbacks, SubtitleSettingsModel.Listener {

    private final SubtitleSettingsModel mModel;
    private final Runnable mReturnToTracks;
    private final int mTrackPopupHeight;
    private SubtitleSettingsPhoneRows mRows;
    private ScrollView mScroll;
    private View mBack;                 // top-left back button: up to the settings, or closes the panel
    private ColorTarget mColorPage;     // null: the settings themselves
    private int mSettingsScrollY;

    public SubtitleSettingsDialog(Context context, SubtitleManager subtitleManager, Runnable returnToTracks, int trackPopupHeight) {
        super(context);
        mReturnToTracks = returnToTracks;
        mTrackPopupHeight = trackPopupHeight;
        mModel = new SubtitleSettingsModel(context, subtitleManager,
                PreferenceManager.getDefaultSharedPreferences(context), false);
        mModel.setListener(this);
        init(context);
    }

    @SuppressLint("InflateParams") // Custom content view for AlertDialog.setView() has no parent container at inflation
    private void init(Context context) {
        final Context themed = new androidx.appcompat.view.ContextThemeWrapper(
            context, R.style.ThemeOverlay_SubtitlePanel_Phone);

        getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        getWindow().setBackgroundDrawable(new ColorDrawable(PlayerPopup.PANEL_BACKGROUND));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        final View view = LayoutInflater.from(themed).inflate(R.layout.subtitle_settings_dialog, null);
        mScroll = view.findViewById(R.id.sp_scroll);
        if (mTrackPopupHeight > 0) {
            android.widget.LinearLayout.LayoutParams scrollParams =
                    (android.widget.LinearLayout.LayoutParams) mScroll.getLayoutParams();
            scrollParams.height = 0;
            scrollParams.weight = 1;
            mScroll.setLayoutParams(scrollParams);
        }
        mBack = view.findViewById(R.id.sp_back);
        mBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mColorPage != null) {
                    showPage(null);   // up to the settings
                } else {
                    returnToTracks();
                }
            }
        });
        mRows = new SubtitleSettingsPhoneRows((ViewGroup) view.findViewById(R.id.sp_rows), mModel, this);
        setView(view);

        setCancelable(true);
        setCanceledOnTouchOutside(true);
        mRows.show(null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mColorPage != null) {
                    showPage(null);   // one level up: the settings
                } else {
                    returnToTracks();
                }
            }
        });
    }

    private void returnToTracks() {
        dismiss();
        if (getOwnerActivity() == null || !getOwnerActivity().isFinishing()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(mReturnToTracks);
        }
    }

    private void showPage(final ColorTarget target) {
        if (target != null && mColorPage == null) mSettingsScrollY = mScroll.getScrollY();
        mColorPage = target;
        mRows.show(target);
        mScroll.post(new Runnable() {
            @Override
            public void run() {
                mScroll.scrollTo(0, target == null ? mSettingsScrollY : 0);
                mRows.focusFirst();
            }
        });
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mModel.open();
        if (getWindow() != null) { // no status bar over the video while adjusting
            float density = getContext().getResources().getDisplayMetrics().density;
            int width = Math.min(getContext().getResources().getDimensionPixelSize(R.dimen.phone_subtitles_popup_width),
                    getContext().getResources().getDisplayMetrics().widthPixels - Math.round(28 * density));
            int maxHeight = Math.max(1, getContext().getResources().getDisplayMetrics().heightPixels
                    - Math.round(24 * density));
            int seekTopLimit = PlayerPopup.seekBarTopOnScreen(getContext());
            if (seekTopLimit > 0) maxHeight = Math.max(1, Math.min(maxHeight, seekTopLimit - Math.round(16 * density)));
            int height = mTrackPopupHeight > 0
                    ? Math.min(mTrackPopupHeight + Math.round(96 * density), maxHeight)
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            getWindow().setLayout(width, height);
            getWindow().setGravity(Gravity.BOTTOM | Gravity.END);
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.x = Math.round(12 * density); // bottom right, like the audio and subtitle popups
            params.y = PlayerPopup.aboveSeekBarOffset(getContext()); // sits on top of the seek bar
            getWindow().setAttributes(params);
            mScroll.post(this::limitPopupHeight);
            mScroll.post(mRows::focusFirst); // D-pad / remote: start on the first row
            final WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.statusBars());
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (getWindow() != null) {
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).show(WindowInsetsCompat.Type.statusBars());
        }
        mRows.release();
        mModel.release();
        super.onDetachedFromWindow();
    }

    // ---- SubtitleSettingsPhoneRows.Callbacks / SubtitleSettingsModel.Listener -------------------

    @Override
    public void onOpenColor(ColorTarget target) {
        showPage(target);
    }

    @Override
    public void onRefreshed() {
        if (mScroll != null) mScroll.post(this::limitPopupHeight);
    }

    private void limitPopupHeight() {
        if (mTrackPopupHeight > 0 || getWindow() == null || !isShowing() || mScroll.getChildCount() == 0) return;
        float density = getContext().getResources().getDisplayMetrics().density;
        int bottom = (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.65f);
        Context context = getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof android.app.Activity)) {
            Context next = ((android.content.ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            View anchor = activity.findViewById(R.id.mobile_title_row);
            if (anchor != null && anchor.getParent() instanceof View parent
                    && (parent instanceof PlayerControlsRelativeLayout || parent.getId() == R.id.button_bar)) {
                anchor = activity.findViewById(R.id.control_bar);
            }
            if (anchor != null && anchor.getHeight() > 0) {
                int[] location = new int[2];
                anchor.getLocationOnScreen(location);
                if (location[1] > 0) bottom = location[1];
            }
        }
        int seekTop = PlayerPopup.seekBarTopOnScreen(getContext());
        if (seekTop > 0) bottom = seekTop;
        // the window grows upwards from the seek bar: what is left is the space above it minus the dialog chrome
        int chrome = Math.max(0, getWindow().getDecorView().getHeight() - mScroll.getHeight());
        int available = Math.max(1, bottom - chrome - Math.round(16 * density));
        View rows = mScroll.getChildAt(0);
        rows.measure(View.MeasureSpec.makeMeasureSpec(mScroll.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(rows.getMeasuredHeight(), available);
        ViewGroup.LayoutParams params = mScroll.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            mScroll.setLayoutParams(params);
        }
    }

    @Override
    public void onModelChanged() { // the Reset confirmation timed out
        mRows.refresh();
    }
}
