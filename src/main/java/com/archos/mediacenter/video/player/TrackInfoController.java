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


import android.content.Context;
import android.graphics.Rect;
import androidx.core.text.HtmlCompat;
import android.text.SpannableString;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.ListPopupWindow;

import com.archos.mediacenter.video.R;

import java.util.ArrayList;

public class TrackInfoController implements OnMenuItemClickListener, OnItemClickListener {
    private MenuItem mMenuItem = null;
    private ListPopupWindow mPopup = null;
    private TrackInfoAdapter mTrackInfoAdapter = null;
    private int mTrackPosition = 0;
    private TrackInfoListener mTrackInfoListener = null;
    private TrackItemList mTrackItemList = new TrackItemList();
    private ActionBar mActionBar;
    private boolean mTopCentered;
    /** Text size (sp) of the popup rows: 14 in the compact playback UI, 12 otherwise. */
    private float mTextSize = 12;
    private View mPopupFooter;
    private Runnable mSubtitlePlaybackUnavailable;

    void setSubtitlePlaybackUnavailable(Runnable action) {
        mSubtitlePlaybackUnavailable = action;
    }

    private boolean mCompactSubtitles;
    private boolean mSubtitleTracksExpanded;
    private int mLastSubtitlePosition = 1;
    private View mAnchorView;
    private Context mContext;
    private boolean mIsAlwaysDisplayed;


    public interface TrackInfoListener {
        public boolean onTrackSelected(TrackInfoController trackInfoController, int position, CharSequence name, CharSequence summary);
        public boolean onSettingsSelected(TrackInfoController trackInfoController, int key, CharSequence name);
    }

    private static final int TRACK_INFO = 0;
    private static final int TRACK_SETTINGS = 1;
    private static final int TRACK_SEP = 2;

    private class TrackItem {
        public final int type;
        public TrackItem(int type) {
            this.type = type;
        }
    }

    private class TrackInfo extends TrackItem {
        public final CharSequence name;
        public final CharSequence summary;
        public final int position;
        public final boolean isExternal;
        public TrackInfo(int position, CharSequence name, boolean isExternal) {
            super(TRACK_INFO);
            this.position = position;
            this.name = name;
            this.summary = null;
            this.isExternal = isExternal;
        }
        public TrackInfo(int position, CharSequence name, CharSequence summary, boolean isExternal) {
            super(TRACK_INFO);
            this.position = position;
            this.name = name;
            this.summary = summary;
            this.isExternal = isExternal;
        }
        public String toString() {
            return this.name.toString();
        }
    }

    private class TrackSeparator extends TrackItem {
        public TrackSeparator() {
            super(TRACK_SEP);
        }
    }

    private class TrackSettings extends TrackItem {
        public final CharSequence name;
        public final int iconResId;
        public final int key;
        public boolean enabled = true;
        java.util.function.BooleanSupplier checked;

        public TrackSettings(CharSequence name, int iconResId, int key) {
            super(TRACK_SETTINGS);
            this.name = name;
            this.iconResId = iconResId;
            this.key = key;
        }
    }

    public class TrackItemList extends ArrayList<TrackItem> {
        private static final long serialVersionUID = 1L;
        private int mTrackCount = 0;

        public void addTrack(CharSequence name, CharSequence summary, boolean isExternal) {
            add(new TrackInfo(mTrackCount++, name, summary, isExternal));
        }

        public void addSeparator() {
            add(new TrackSeparator());
        }

        public void addSettings(CharSequence name, int iconResId, int key) {
            add(new TrackSettings(name, iconResId, key));
        }

        public int getTrackCount() {
            return mTrackCount;
        }

        public void clear() {
            super.clear();
            mTrackCount = 0;
        }
    }

    private static final int COMPACT_TOP_TRACKS = 4;
    private final Object COMPACT_CHECK = new Object();   // "Enable subtitles" switch row
    private final Object COMPACT_MORE = new Object();    // "More" / "Less" row under the track list

    /**
     * Compact (phone subtitle) rows: the enable switch, then - while subtitles are on - the first four
     * tracks directly and a More row for the rest (expanded, the list scrolls), then the other items.
     */
    private ArrayList<Object> compactEntries() {
        ArrayList<Object> entries = new ArrayList<>();
        int count = getTrackCount();                      // position 0 is "none"
        boolean tracksShown = mTrackPosition != 0 && count > 1;
        // "More options" (the settings rows) first, then the "Enable Subtitles" switch, then the streams
        for (int i = count; i < mTrackItemList.size(); i++) {
            TrackItem item = mTrackItemList.get(i);
            if (item.type != TRACK_SEP) entries.add(item);
        }
        entries.add(COMPACT_CHECK);
        if (tracksShown) {
            for (int i = count; i < mTrackItemList.size(); i++) {
                TrackItem item = mTrackItemList.get(i);
                if (item.type == TRACK_SEP) entries.add(item);
            }
            int real = count - 1;
            int shown = mSubtitleTracksExpanded ? real : Math.min(COMPACT_TOP_TRACKS, real);
            for (int i = 1; i <= shown; i++) entries.add(mTrackItemList.get(i));
            if (real > COMPACT_TOP_TRACKS) entries.add(COMPACT_MORE);
        }
        return entries;
    }

    /** Audio popup order: the settings rows ("More options") first, then the separator, then the streams. */
    private ArrayList<TrackItem> displayItems() {
        ArrayList<TrackItem> others = new ArrayList<>();
        ArrayList<TrackItem> separators = new ArrayList<>();
        ArrayList<TrackItem> tracks = new ArrayList<>();
        for (TrackItem item : mTrackItemList) {
            if (item.type == TRACK_INFO) tracks.add(item);
            else if (item.type == TRACK_SEP) separators.add(item);
            else others.add(item);
        }
        ArrayList<TrackItem> result = new ArrayList<>(others);
        result.addAll(separators);
        result.addAll(tracks);
        return result;
    }

    /** List position of the first stream (the TV cursor starts there), or -1. */
    private int firstTrackListPosition() {
        int count = mTrackInfoAdapter.getCount();
        for (int i = 0; i < count; i++) {
            if (mPopupFooter != null && i == count - 1) break;
            if (mCompactSubtitles) {
                if (compactEntry(i) instanceof TrackInfo) return i;
            } else if (getPopupItem(i) instanceof TrackInfo) {
                return i;
            }
        }
        if (mCompactSubtitles) {                      // no streams listed: the cursor starts on "Enable Subtitles"
            for (int i = 0; i < count; i++) if (compactEntry(i) == COMPACT_CHECK) return i;
        }
        return -1;
    }

    private Object compactEntry(int position) {
        ArrayList<Object> entries = compactEntries();
        return position >= 0 && position < entries.size() ? entries.get(position) : null;
    }

    /** The track / settings / separator item at a list position, or null for the compact switch and More rows. */
    private TrackItem getPopupItem(int position) {
        if (mCompactSubtitles) {
            Object entry = compactEntry(position);
            return entry instanceof TrackItem ? (TrackItem) entry : null;
        }
        ArrayList<TrackItem> items = displayItems();
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    private void applyCompactSubtitleSpacing(View row) {
        float density = mContext.getResources().getDisplayMetrics().density;
        int padding = Math.round(2 * density);
        row.setPaddingRelative(row.getPaddingStart(), padding, row.getPaddingEnd(), padding);
        row.setMinimumHeight(Math.round(32 * density));
    }

    public class TrackInfoAdapter extends BaseAdapter {
        private LayoutInflater mInflater;
        private CheckedHolder mCheckedHolder = new CheckedHolder();

        private class CheckedHolder {
            private View mView = null;
            public void setView(View view, boolean update) {
                if (update)
                    setChecked(false);
                mView = view;
                if (update)
                    setChecked(true);
            }
            private void setChecked(boolean checked) {
                if (mView != null) {
                    RadioButton radio = (RadioButton)mView.findViewById(R.id.radio);
                    if (radio != null)
                        radio.setChecked(checked);
                }
            }
        }

        public TrackInfoAdapter(LayoutInflater inflater) {
            super();
            mInflater = inflater;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (position == getCount() - 1 && mPopupFooter != null) return mPopupFooter;
            View v = null;
            final Object compact = mCompactSubtitles ? compactEntry(position) : null;
            if (compact == COMPACT_CHECK) {
                android.widget.CheckBox checkbox = new androidx.appcompat.widget.AppCompatCheckBox(mContext);
                checkbox.setText(R.string.player_enable_subs);
                checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
                checkbox.setTextColor(android.graphics.Color.WHITE);
                checkbox.setTextSize(mTextSize);
                checkbox.setChecked(mTrackPosition != 0);
                checkbox.setEnabled(getTrackCount() > 1 || mSubtitlePlaybackUnavailable != null);
                checkbox.setClickable(false);
                checkbox.setFocusable(false);
                applyCompactSubtitleSpacing(checkbox);
                return checkbox;
            }

            if (compact == COMPACT_MORE) {
                View row = mInflater.inflate(R.layout.menu_track_settings_view, parent, false);
                TextView label = row.findViewById(R.id.name);
                label.setText(mSubtitleTracksExpanded ? R.string.phone_subtitle_show_less : R.string.phone_subtitle_show_more);
                label.setTextSize(mTextSize);
                label.setEnabled(true);
                View toggleSwitch = row.findViewById(R.id.track_settings_switch);
                if (toggleSwitch != null) toggleSwitch.setVisibility(View.GONE);
                View moreIcon = row.findViewById(R.id.icon);
                if (moreIcon != null) moreIcon.setVisibility(View.GONE);
                applyCompactSubtitleSpacing(row);
                return row;
            }

            TrackItem item = getPopupItem(position);
            if (convertView == null) {
                switch (item.type) {
                    case TRACK_INFO:
                        TrackInfo trackInfo = (TrackInfo) item;
                        v = mInflater.inflate(isScrollingTrack(trackInfo) ? R.layout.menu_audio_track_phone :
                                trackInfo.summary == null ?
                                R.layout.menu_track_info_view :
                                (com.archos.mediacenter.video.utils.ThemeManager.getInstance(mContext).isPhoneMode(mContext)
                                        ? R.layout.menu_audio_track_phone : R.layout.menu_track_info_summary_view),
                                parent, false);
                        break;
                    case TRACK_SETTINGS:
                        v = mInflater.inflate(R.layout.menu_track_settings_view, parent, false);
                        break;
                    case TRACK_SEP:
                        v = mInflater.inflate(R.layout.menu_track_separator_view, parent, false);
                        break;
                }
            } else {
                v = convertView;
            }
            switch (item.type) {
                case TRACK_INFO:
                    TrackInfo trackInfo = (TrackInfo) item;
                    RadioButton radio;
                    SpannableString spannableText;
                    // Apply SpannableString only for checkable items (RadioButtons)

                    if (trackInfo.name != null) {
                        spannableText = new SpannableString(HtmlCompat.fromHtml(trackInfo.name.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY));
                        TextView name = v.findViewById(R.id.name);
                        name.setText(isScrollingTrack(trackInfo) ? trackInfo.name : spannableText);
                        if (mTopCentered) name.setTextSize(mTextSize);
                    }
                    if (trackInfo.summary != null) {
                        TextView textView = (TextView)v.findViewById(R.id.summary);
                        if (textView != null)
                            textView.setText(trackInfo.summary);
                        if (textView != null) textView.setVisibility(isScrollingTrack(trackInfo) ? View.GONE : View.VISIBLE);
                    }
                    radio = (RadioButton)v.findViewById(R.id.radio);
                    if (radio != null)
                        radio.setChecked(trackInfo.position == mTrackPosition);
                    if (trackInfo.position == mTrackPosition)
                        mCheckedHolder.setView(v, false);
                    break;
                case TRACK_SETTINGS:
                    TrackSettings trackSettings = (TrackSettings) item;
                    if (trackSettings.name != null) {
                        TextView tv = (TextView) v.findViewById(R.id.name);
                        tv.setText(trackSettings.name);
                        if (mTopCentered) tv.setTextSize(mTextSize);
                        tv.setEnabled(trackSettings.enabled);
                    }
                    androidx.appcompat.widget.SwitchCompat toggle = v.findViewById(R.id.track_settings_switch);
                    boolean switchRow = trackSettings.checked != null;
                    float density = mContext.getResources().getDisplayMetrics().density;
                    int verticalPadding = Math.round((switchRow ? 2 : 6) * density);
                    v.setPaddingRelative(v.getPaddingStart(), verticalPadding, v.getPaddingEnd(), verticalPadding);
                    v.setMinimumHeight(switchRow ? Math.round(32 * density)
                            : mContext.getResources().getDimensionPixelSize(R.dimen.track_menu_layout_width));
                    toggle.setMinimumHeight(0);
                    if (mTopCentered && switchRow) {
                        toggle.setScaleX(0.92f);
                        toggle.setScaleY(0.92f);
                    }
                    toggle.setVisibility(switchRow ? View.VISIBLE : View.GONE);
                    if (trackSettings.checked != null) {
                        toggle.setEnabled(trackSettings.enabled);
                        toggle.setChecked(trackSettings.checked.getAsBoolean());
                    }
                    ImageView icon = v.findViewById(R.id.icon);
                    icon.setVisibility(mCompactSubtitles ? View.GONE : View.VISIBLE);
                    icon.setImageResource(trackSettings.iconResId);
                    break;
                case TRACK_SEP:
                    break;
            }
            if (mCompactSubtitles && item.type != TRACK_SEP) applyCompactSubtitleSpacing(v);
            return v;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == getCount() - 1 && mPopupFooter != null) return 5;
            if (mCompactSubtitles) {
                Object entry = compactEntry(position);
                if (entry == COMPACT_CHECK) return 3;
                if (entry == COMPACT_MORE) return 4;
            }
            return getPopupItem(position).type;
        }

        @Override
        public int getViewTypeCount() {
            return 6;
        }

        /**
         * Some rows (separators, disabled settings, footer) are not enabled. Without this the list treats every
         * row as selectable, so the D-pad stops on a separator first and reaching the next option takes two presses.
         */
        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            if (position == getCount() - 1 && mPopupFooter != null) return false;
            if (mCompactSubtitles) {
                Object entry = compactEntry(position);
                if (entry == COMPACT_CHECK) return getTrackCount() > 1 || mSubtitlePlaybackUnavailable != null;
                if (entry == COMPACT_MORE) return true;
            }
            switch (getPopupItem(position).type) {
                case TRACK_INFO:
                    return true;
                case TRACK_SETTINGS:
                    return ((TrackSettings) getPopupItem(position)).enabled;
                case TRACK_SEP:
                    return false;
            }
            return super.isEnabled(position);
        }

        public int getCount() {
            if (!mCompactSubtitles) return mTrackItemList.size() + (mPopupFooter == null ? 0 : 1);
            return compactEntries().size();
        }

        public Object getItem(int position) {
            if (position == getCount() - 1 && mPopupFooter != null) return mPopupFooter;
            return getPopupItem(position);
        }

        public long getItemId(int position) {
            return 0;
        }

        public void setChecked(View view) {
            mCheckedHolder.setView((ViewGroup)view, true);
        }
    }

    public TrackInfoController(Context context, LayoutInflater inflater, View anchor, ActionBar actionBar) {
        mContext = context;
        mTrackInfoAdapter = new TrackInfoAdapter(inflater);

        mAnchorView = anchor;
        mActionBar = actionBar;
    }

    public void attachMenu(Menu menu, int resId) {
        mMenuItem = menu.add(null);
        mMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        mMenuItem.setIcon(resId);
        mMenuItem.setOnMenuItemClickListener(this);
        setVisible();
    }

    public void setListener(TrackInfoListener listener) {
        mTrackInfoListener = listener;
    }

    public boolean onMenuItemClick(MenuItem item) {
        if (mTrackInfoAdapter != null && mPopup != null) {
            // little hack: put the popup below the action bar
            View actionBarView = mActionBar.getCustomView();
            int actionBarHeight = actionBarView.getBottom();

            Rect windowFrame = new Rect();
            actionBarView.getWindowVisibleDisplayFrame(windowFrame);
            int statusbarHeight = windowFrame.top;

            mAnchorView.setY(actionBarHeight + statusbarHeight);
            mPopup.setOnDismissListener(null);
            mPopup.setAnchorView(mAnchorView);
            mPopup.show();
            styleListSelector();
            return true;
        }
        return false;
    }

    /** Remote: the row selected with the D-pad gets a light highlight on the dark popup. */
    private void styleListSelector() {
        android.widget.ListView list = mPopup == null ? null : mPopup.getListView();
        if (list != null) {
            PlayerController.applyNightModeTo(mContext, list);       // night mode dims the audio / subtitle popup
            list.setSelector(new android.graphics.drawable.ColorDrawable(0x66FFFFFF));
            list.setItemsCanFocus(mPopupFooter != null);
            list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    stopTrackScroll();
                    Object item = mTrackInfoAdapter.getItem(position);
                    if (view != null && item instanceof TrackInfo && isScrollingTrack((TrackInfo) item)) {
                        mScrollingTrackLabel = view.findViewById(R.id.name);
                        mScrollingTrackLabel.postDelayed(mStartTrackScroll, 3000);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    stopTrackScroll();
                }
            });
        }
    }

    private TextView mScrollingTrackLabel;
    private final Runnable mStartTrackScroll = () -> {
        if (mScrollingTrackLabel != null && mScrollingTrackLabel.isShown()) {
            mScrollingTrackLabel.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            mScrollingTrackLabel.setSelected(true);
        }
    };

    private boolean isScrollingTrack(TrackInfo track) {
        return track.summary != null && track.summary.length() == 0;
    }

    private void stopTrackScroll() {
        if (mScrollingTrackLabel == null) return;
        mScrollingTrackLabel.removeCallbacks(mStartTrackScroll);
        mScrollingTrackLabel.setSelected(false);
        mScrollingTrackLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mScrollingTrackLabel = null;
    }

    int getVisibleHeight() {
        View list = mPopup == null ? null : mPopup.getListView();
        return list == null || !mPopup.isShowing() ? 0 : list.getHeight();
    }

    public void showAtTopCenter(View anchor, View footer, Runnable onDismiss) {
        mPopupFooter = footer;
        showAtTopEnd(anchor, PlayerPopup.SEEK_BAR_END_INSET_DP, onDismiss);
    }

    /** Track list popup on the left side of the screen. */
    public void showAtTopStart(View anchor, float startInsetDp, Runnable onDismiss) {
        showAtTopStart(anchor, startInsetDp, false, onDismiss);
    }

    /** Same, {@code compact} keeping the subtitle layout (enable switch, collapsible language list). */
    public void showAtTopStart(View anchor, float startInsetDp, boolean compact, Runnable onDismiss) {
        mTextSize = 12;
        mPopupFooter = null;
        mTopCentered = true;
        mCompactSubtitles = compact;
        mSubtitleTracksExpanded = false;
        mTrackInfoAdapter.notifyDataSetChanged();
        if (mPopup != null && anchor != null) {
            mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PlayerPopup.PANEL_BACKGROUND));
            float density = mContext.getResources().getDisplayMetrics().density;
            int inset = Math.round(startInsetDp * density);
            int wanted = mContext.getResources().getDimensionPixelSize(R.dimen.phone_subtitles_popup_width);
            int width = Math.min(wanted, Math.max(1, anchor.getWidth() - inset - Math.round(16 * density)));
            mPopup.setWidth(width);
            mPopup.setHorizontalOffset(inset);
            mPopup.setVerticalOffset(Math.round(4 * density));
        }
        showAt(anchor, onDismiss);
    }

    /**
     * Track list popup on the right side, sitting just above {@code anchor} (a thin view placed on
     * the top edge of the seek bar). The list is shown "below" the anchor, which has no room, so the
     * framework flips it above, growing upwards.
     */
    public void showAtBottomEnd(View anchor, float endInsetDp, boolean compact, Runnable onDismiss) {
        mTextSize = 12;
        mPopupFooter = null;
        mTopCentered = true;
        mCompactSubtitles = compact;
        mSubtitleTracksExpanded = false;
        mTrackInfoAdapter.notifyDataSetChanged();
        if (mPopup != null && anchor != null) {
            mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PlayerPopup.PANEL_BACKGROUND));
            float density = mContext.getResources().getDisplayMetrics().density;
            int inset = Math.round(endInsetDp * density);
            int wanted = mContext.getResources().getDimensionPixelSize(R.dimen.phone_subtitles_popup_width);
            int width = Math.min(wanted, Math.max(1, anchor.getWidth() - inset - Math.round(16 * density)));
            mPopup.setWidth(width);
            mPopup.setHorizontalOffset(anchor.getWidth() - width - inset);
            mPopup.setVerticalOffset(0);
        }
        showAt(anchor, onDismiss);
    }

    /** Compact UI: the track list opens centred, sitting just above {@code anchor} (placed above the buttons pill). */
    public void showAtBottomCentered(View anchor, boolean compact, Runnable onDismiss) {
        mTextSize = 14;
        mPopupFooter = null;
        mTopCentered = true;
        mCompactSubtitles = compact;
        mSubtitleTracksExpanded = false;
        mFitAbove = true;
        mFitAnchor = anchor;
        mTrackInfoAdapter.notifyDataSetChanged();
        if (mPopup != null && anchor != null) {
            mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PlayerPopup.PANEL_BACKGROUND));
            fitTopPopup();   // sized to its rows and put right above the anchor (the bottom edge of the popup sits on it)
        }
        showAt(anchor, onDismiss);
    }

    public void showAtTopEnd(View anchor, float endInsetDp, Runnable onDismiss) {
        showAtTopEnd(anchor, endInsetDp, false, onDismiss);
    }

    /**
     * Compact playback UI: the track list opens at the top of the screen, centred, with 2 larger text, and
     * is kept above the control area (it scrolls when the list is longer). {@code anchor} sits at the top.
     */
    public void showAtTopCentered(View anchor, boolean compact, Runnable onDismiss) {
        mTextSize = 14;
        mPopupFooter = null;
        mTopCentered = true;
        mCompactSubtitles = compact;
        mSubtitleTracksExpanded = false;
        mTrackInfoAdapter.notifyDataSetChanged();
        if (mPopup != null && anchor != null) {
            mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PlayerPopup.PANEL_BACKGROUND));
            float density = mContext.getResources().getDisplayMetrics().density;
            int wanted = mContext.getResources().getDimensionPixelSize(R.dimen.phone_subtitles_popup_width);
            int width = Math.min(wanted, Math.max(1, anchor.getWidth() - Math.round(32 * density)));
            mPopup.setVerticalOffset(Math.round(4 * density));
            mFitAbove = false;
            mFitAnchor = anchor;
            fitTopPopup();
        }
        showAt(anchor, onDismiss);
    }

    private View mFitAnchor;   // set while the top centred popup is sized to its content
    private boolean mFitAbove;   // the popup sits above the anchor (bottom centred) instead of below it

    /** Sizes the height of the top centred popup to its rows: no empty space below them. */
    private void fitTopPopup() {
        View anchor = mFitAnchor;
        if (mPopup == null || anchor == null) return;
        float density = mContext.getResources().getDisplayMetrics().density;
        int wanted = mContext.getResources().getDimensionPixelSize(R.dimen.phone_subtitles_popup_width);
        int maxWidth = Math.min(wanted, Math.max(1, anchor.getWidth() - Math.round(32 * density)));
        android.widget.FrameLayout holder = new android.widget.FrameLayout(mContext);
        int count = mTrackInfoAdapter.getCount();
        int width = maxWidth;   // width stays as before; only the height follows the content
        int total = 0;
        for (int i = 0; i < count; i++) {
            View row = mTrackInfoAdapter.getView(i, null, holder);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            total += row.getMeasuredHeight();
        }
        int seekTop = PlayerPopup.seekBarTopOnScreen(mContext);
        int available = seekTop > 0 ? seekTop - Math.round(60 * density) : -1;
        if (mFitAbove) {   // bottom centred: the popup ends on the bottom edge of the anchor, above the buttons pill
            int[] location = new int[2];
            anchor.getLocationOnScreen(location);
            available = location[1] + anchor.getHeight() - Math.round(24 * density);
        }
        int height = available > 0 && total > available ? available : Math.max(1, total);
        mPopup.setWidth(width);
        mPopup.setHorizontalOffset((anchor.getWidth() - width) / 2);
        mPopup.setHeight(height);
        if (mFitAbove) mPopup.setVerticalOffset(-height);
    }

    public void showAtTopEnd(View anchor, float endInsetDp, boolean square, Runnable onDismiss) {
        mFitAnchor = null;
        mTextSize = 12;
        mTopCentered = true;
        mCompactSubtitles = square;
        mSubtitleTracksExpanded = false;
        mTrackInfoAdapter.notifyDataSetChanged();
        if (mPopup != null) {
            mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PlayerPopup.PANEL_BACKGROUND));
            float density = mContext.getResources().getDisplayMetrics().density;
            int inset = Math.round(endInsetDp * density);
            int wanted = mContext.getResources().getDimensionPixelSize(R.dimen.phone_subtitles_popup_width);
            int width = Math.min(wanted, Math.max(1, anchor.getWidth() - (square ? inset + Math.round(16 * density) : Math.round(32 * density))));
            mPopup.setWidth(width);
            mPopup.setHorizontalOffset(square ? anchor.getWidth() - width - inset : (anchor.getWidth() - width) / 2);
            if (!square) mPopup.setVerticalOffset(Math.round(4 * density));
        }
        showAt(anchor, onDismiss);
    }

    public void showAt(View anchor, Runnable onDismiss) {
        if (mPopup != null) {
            mPopup.setOnDismissListener(() -> {
                stopTrackScroll();
                mFitAnchor = null;
                mPopupFooter = null;
                mTrackInfoAdapter.notifyDataSetChanged();
                onDismiss.run();
            });
            mPopup.setAnchorView(anchor);
            mPopup.show();
            styleListSelector();
            // remote: the cursor starts on the first stream, not on the "More options" row above it
            if (com.archos.mediacenter.video.player.tvmenu.TVUtils.isTV(mContext)
                    || com.archos.environment.ArchosFeatures.isAndroidTV(mContext)) {
                int first = firstTrackListPosition();
                if (first >= 0) mPopup.setSelection(first);
            }
        } else {
            onDismiss.run();
        }
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (view == mPopupFooter) return;
        final Object compactEntry = mCompactSubtitles ? compactEntry(position) : null;
        if (mCompactSubtitles && mSubtitlePlaybackUnavailable != null
                && (compactEntry == COMPACT_CHECK || compactEntry == COMPACT_MORE
                    || compactEntry instanceof TrackInfo)) {
            mPopup.dismiss();
            mSubtitlePlaybackUnavailable.run();
            return;
        }
        if (compactEntry == COMPACT_CHECK) {
            if (getTrackCount() <= 1 || mTrackInfoListener == null) return;
            if (mTrackPosition > 0) mLastSubtitlePosition = mTrackPosition;
            int selected = mTrackPosition == 0 ? Math.min(mLastSubtitlePosition, getTrackCount() - 1) : 0;
            TrackInfo track = (TrackInfo) mTrackItemList.get(selected);
            if (mTrackInfoListener.onTrackSelected(this, selected, track.name, track.summary)) {
                mTrackPosition = selected;
                mTrackInfoAdapter.notifyDataSetChanged();
                resizePopup();   // the track list appears / disappears with the switch
            }
            return;
        }
        if (compactEntry == COMPACT_MORE) {
            mSubtitleTracksExpanded = !mSubtitleTracksExpanded;
            mTrackInfoAdapter.notifyDataSetChanged();
            resizePopup();
            return;
        }
        boolean dismiss = false;
        TrackItem item = getPopupItem(position);
        if (item == null) return;
        switch (item.type) {
            case TRACK_INFO:
                dismiss = false;
                if (mTrackInfoListener != null) {
                    TrackInfo trackInfo = (TrackInfo) item;
                    if (mTrackInfoListener.onTrackSelected(this, trackInfo.position, trackInfo.name, trackInfo.summary)) {
                        mTrackPosition = trackInfo.position;
                        if (mCompactSubtitles) {
                            mTrackInfoAdapter.notifyDataSetChanged();
                        } else {
                            mTrackInfoAdapter.setChecked(view);
                        }
                    }
                }
                break;
            case TRACK_SETTINGS:
                TrackSettings settings = (TrackSettings) item;
                dismiss = settings.checked == null;
                if (settings.enabled && mTrackInfoListener != null) {
                    mTrackInfoListener.onSettingsSelected(this, settings.key, settings.name);
                    if (!dismiss) mTrackInfoAdapter.notifyDataSetChanged();
                }
                break;
            case TRACK_SEP:
                break;
        }
        if (dismiss)
            mPopup.dismiss();
    }

    /** The compact list changed length: let the popup measure itself again (it keeps growing upwards). */
    private void resizePopup() {
        if (mFitAnchor != null) fitTopPopup();
        if (mPopup != null && mPopup.isShowing()) mPopup.show();
    }

    public void setTrack(int position) {
        mTrackPosition = position;
    }

    public int getTrack() {
        return mTrackPosition;
    }

    public int getTrackCount() {
        return mTrackItemList.getTrackCount();
    }
    
    public CharSequence getTrackNameAt(int position) {
        TrackItem item = mTrackItemList.get(position);
        if (item instanceof TrackInfo) {
            return ((TrackInfo)item).name;
        } else {
            return "";
        }
    }

    public void addTrack(CharSequence name, boolean isExternal) {
        addTrack(name, null, isExternal);
    }

    public void addTrack(CharSequence name, CharSequence summary, boolean isExternal) {
        mTrackItemList.addTrack(name, summary, isExternal);
        setVisible();
    }

    public void addSeparator() {
        mTrackItemList.addSeparator();
    }

    public void addSettings(CharSequence name, int iconResId, int key) {
        mTrackItemList.addSettings(name, iconResId, key);
    }

    public void addSwitch(CharSequence name, int key, java.util.function.BooleanSupplier checked) {
        TrackSettings settings = new TrackSettings(name, 0, key);
        settings.checked = checked;
        mTrackItemList.add(settings);
    }

    public void enableSettings(int key, boolean enabled, boolean invalidate) {
        for (int i=0 ; i < mTrackItemList.size() ; i++) {
            TrackItem item = mTrackItemList.get(i);
            if (item.type == TRACK_SETTINGS) {
                TrackSettings trackSettings = (TrackSettings)item;
                if (trackSettings.key == key) {
                    trackSettings.enabled = enabled;
                    if (mPopup != null && invalidate && mPopup.getListView()!=null)
                        mPopup.getListView().invalidateViews();
                    break;
                }
            }
        }
    }

    public void resetPopup() {
        // Make sure to close the popup first
        if (mPopup != null) {
            mPopup.dismiss();
        }

        // Build and initialize a new popup
        mPopup = new ListPopupWindow(mContext, null);
        mPopup.setAdapter(mTrackInfoAdapter);
        mPopup.setAnchorView(mAnchorView);
        mPopup.setModal(true);
        mPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xD9000000)); // VideoPlayer-style panel
        mPopup.setOnItemClickListener(this);
        int popupContentWidth = mContext.getResources().getDimensionPixelSize(R.dimen.player_subtitles_popup_width);
        mPopup.setContentWidth(popupContentWidth);
        setVisible();
    }

    public void clear() {
        mTrackItemList.clear();
        mTrackPosition = 0;
        mLastSubtitlePosition = 1;
        setVisible();
    }

    public void setAlwayDisplay(boolean alwaysDisplay) {
        mIsAlwaysDisplayed = alwaysDisplay;
    }
    private void setVisible() {
        if (mMenuItem != null)
            mMenuItem.setVisible(getTrackCount()>1||mIsAlwaysDisplayed);
    }

    public boolean isExternal(int position) {
        TrackItem item = mTrackItemList.get(position);
        if (item instanceof TrackInfo) {
            return ((TrackInfo)item).isExternal;
        } else {
            return false;
        }
    }
}