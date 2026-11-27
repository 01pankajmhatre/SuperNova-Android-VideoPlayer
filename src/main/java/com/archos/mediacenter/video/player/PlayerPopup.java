package com.archos.mediacenter.video.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.archos.mediacenter.video.R;

import java.util.List;

/**
 * VideoPlayer-style popup: small, square, semi-transparent dark panel in the top-right corner,
 * optional blue-grey title, white rows, a dot in front of the checked row.
 * A PopupWindow (not a Dialog) so the player's immersive mode is not disturbed.
 */
public final class PlayerPopup {
    static final int PANEL_BACKGROUND = 0xD9000000;
    /** Distance from the screen's right edge to the right end of the phone seek bar (24 + 48 + 14 dp). */
    public static final int SEEK_BAR_END_INSET_DP = 86;

    public static final class Entry {
        public final CharSequence text;
        public final boolean enabled, checkable, checked;
        public Entry(CharSequence text, boolean enabled, boolean checkable, boolean checked) {
            this.text = text;
            this.enabled = enabled;
            this.checkable = checkable;
            this.checked = checked;
        }
    }

    public interface OnPick {
        void onPick(int index);
    }

    /**
     * Compact playback UI: the subtitle, audio and video popups (and their sub popups) open top centred, with
     * 2 larger text, because the control area stays on screen. Set before opening them.
     */
    private static boolean sTopCenter;

    static void setTopCenter(boolean on) {
        sTopCenter = on;
    }

    private PlayerPopup() {}

    /** Panel that turns the remote's BACK key into the popup's Back action (the previous popup) when it has one. */
    private static final class BackPanel extends LinearLayout {
        Runnable onBack;

        BackPanel(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEventPreIme(KeyEvent event) {
            if (onBack != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBack.run();
                return true;
            }
            return super.dispatchKeyEventPreIme(event);
        }
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Row background: nothing normally, light highlight when pressed or focused (D-pad). */
    private static StateListDrawable rowBackground() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x55FFFFFF));
        d.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(0x55FFFFFF));
        d.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    private static class MaxHeightScroll extends ScrollView {
        private final int mMaxHeight;
        MaxHeightScroll(Context c, int maxHeight) {
            super(c);
            mMaxHeight = maxHeight;
        }
        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.AT_MOST));
        }
    }

    /** Pick-one / toggle list. Returns the popup so callers can dismiss it. */
    public static PopupWindow list(final Activity activity, CharSequence title, List<Entry> entries,
                                   final OnPick onPick, final Runnable onDismiss) {
        return list(activity, title, entries, onPick, onDismiss, false, false, null, false, false);
    }

    /** Same list as a full height panel flush against the right edge of the screen; the rows scroll vertically. */
    public static PopupWindow sideList(final Activity activity, CharSequence title, List<Entry> entries,
                                       final OnPick onPick, final Runnable onDismiss) {
        return list(activity, title, entries, onPick, onDismiss, false, false, null, true, false);
    }

    static PopupWindow videoList(Activity activity, CharSequence title, List<Entry> entries,
                                 OnPick onPick, boolean keepOpen, Runnable onBack) {
        return videoList(activity, title, entries, onPick, keepOpen, onBack, null);
    }

    static PopupWindow videoList(Activity activity, CharSequence title, List<Entry> entries,
                                 OnPick onPick, boolean keepOpen, Runnable onBack, Runnable onDismiss) {
        return list(activity, title, entries, onPick, onDismiss, true, keepOpen, onBack, false, sTopCenter);
    }

    private static PopupWindow list(Activity activity, CharSequence title, List<Entry> entries,
                                    OnPick onPick, Runnable onDismiss, boolean animate,
                                    boolean keepOpen, Runnable onBack, boolean side, boolean topCenter) {
        final Context ctx = activity;
        final boolean anim = animate && !topCenter;   // top centred popups just appear
        final float textSize = side || topCenter ? 14f : 12f;
        boolean anyCheckable = false;
        for (Entry e : entries) anyCheckable |= e.checkable;

        BackPanel panel = new BackPanel(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL_BACKGROUND);
        panel.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));

        final PopupWindow popup = new PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (onBack != null) {
            panel.onBack = () -> {
                if (!popup.isShowing()) return;
                popup.dismiss();
                onBack.run();
            };
        }

        if (title != null) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextColor(0xFF9FB3D1);
            t.setTextSize(textSize);
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
            t.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 4));
            if (onBack == null) {
                panel.addView(t);
            } else {
                LinearLayout header = new LinearLayout(ctx);
                header.setGravity(Gravity.CENTER_VERTICAL);
                TextView back = new TextView(ctx);
                back.setText(R.string.mobile_player_back);
                back.setTextColor(Color.WHITE);
                back.setTextSize(textSize);
                back.setMinHeight(dp(ctx, 44));
                back.setGravity(Gravity.CENTER_VERTICAL);
                back.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
                back.setFocusable(true);
                back.setBackground(rowBackground());
                back.setOnClickListener(view -> {
                    if (!popup.isShowing()) return;
                    popup.dismiss();
                    onBack.run();
                });
                header.addView(back);
                header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                panel.addView(header);
            }
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        View focusTarget = null; // remote: start on the checked row, else the first enabled one
        boolean focusIsChecked = false;
        for (int i = 0; i < entries.size(); i++) {
            final int index = i;
            Entry e = entries.get(i);
            TextView row = new TextView(ctx);
            setRowSelection(row, e, anyCheckable, e.checked);
            row.setTextColor(Color.WHITE);
            row.setTextSize(textSize);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(dp(ctx, 12), dp(ctx, side ? 14 : 10), dp(ctx, 12), dp(ctx, side ? 14 : 10));
            row.setBackground(rowBackground());
            row.setEnabled(e.enabled);
            row.setAlpha(e.enabled ? 1f : 0.45f);
            row.setFocusable(e.enabled);
            row.setClickable(e.enabled);
            if (e.enabled) {
                row.setOnClickListener(v -> {
                    if (!popup.isShowing()) return;
                    if (keepOpen) {
                        if (onPick != null) onPick.onPick(index);
                        for (int j = 0; j < entries.size(); j++) {
                            setRowSelection((TextView) box.getChildAt(j), entries.get(j), true, j == index);
                        }
                        return;
                    }
                    if (anim) {
                        if (!panel.isEnabled()) return;
                        panel.setEnabled(false);
                        panel.animate().scaleY(0f).setDuration(150).withEndAction(() -> {
                            if (!popup.isShowing()) return;
                            popup.dismiss();
                            if (onPick != null) onPick.onPick(index);
                        }).start();
                        return;
                    }
                    popup.dismiss();
                    if (onPick != null) onPick.onPick(index);
                });
            }
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (e.enabled && (focusTarget == null || (e.checked && !focusIsChecked))) {
                focusTarget = row;
                focusIsChecked = e.checked;
            }
        }
        int maxHeight = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.6f);
        int videoSeekTop = animate ? seekBarTopOnScreen(ctx) : -1;
        if (topCenter) {   // from the top of the screen down to just above the control area
            int seekTop = seekBarTopOnScreen(ctx);
            if (seekTop > 0) maxHeight = Math.max(dp(ctx, 96), Math.min(maxHeight, seekTop - dp(ctx, 60)));
        } else if (videoSeekTop > 0) maxHeight = Math.max(dp(ctx, 96), Math.min(maxHeight, videoSeekTop - dp(ctx, 72)));
        ScrollView scroll = side ? new ScrollView(ctx) : new MaxHeightScroll(ctx, maxHeight);
        scroll.addView(box);
        if (side) panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));   // takes the full height, scrolls
        else panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 32), dp(ctx, side ? 320 : 300));
        PlayerController.applyNightModeTo(ctx, panel);   // night mode dims the popups too
        popup.setContentView(panel);
        popup.setWidth(width);
        if (side) {
            popup.setHeight(ViewGroup.LayoutParams.MATCH_PARENT);
            popup.setClippingEnabled(false);   // touches the screen edges
        }
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new GradientDrawable()); // needed so a tap outside dismisses
        popup.setOnDismissListener(() -> { if (onDismiss != null) onDismiss.run(); });

        View parent = activity.getWindow().getDecorView();
        if (anim) {
            panel.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> panel.setPivotY(bt - t)); // grows upwards from the bottom
            panel.setScaleY(0f);
        }
        if (side) popup.showAtLocation(parent, Gravity.TOP | Gravity.END, 0, 0);   // full height, right aligned, touching the right edge
        else if (topCenter) popup.showAtLocation(parent, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 12));   // compact UI
        else popup.showAtLocation(parent, anim ? (Gravity.BOTTOM | Gravity.END) : (Gravity.TOP | Gravity.CENTER_HORIZONTAL),
                anim ? dp(ctx, 12) : 0, anim ? aboveSeekBarOffset(ctx) : dp(ctx, 16)); // video lists: bottom right, above the seek bar
        if (anim) panel.animate().scaleY(1f).setDuration(180).start();
        dimBehind(ctx, popup, 0.25f);
        if (focusTarget != null) {
            final View target = focusTarget;
            target.post(target::requestFocus); // no effect in touch mode, which is what we want
        }
        return popup;
    }

    /** Activity behind a (possibly wrapped) context, or null. */
    static Activity activityOf(Context context) {
        while (context instanceof android.content.ContextWrapper && !(context instanceof Activity)) {
            Context next = ((android.content.ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    /** Top edge of the seek bar on screen, or -1 when it is not laid out. */
    static int seekBarTopOnScreen(Context context) {
        Activity activity = activityOf(context);
        if (activity == null) return -1;
        View seek = activity.findViewById(R.id.mobile_seek_row);
        if (seek == null || seek.getHeight() == 0) seek = activity.findViewById(R.id.seek_progress);
        if (seek == null || seek.getHeight() == 0) seek = activity.findViewById(R.id.control_bar);
        if (seek == null || seek.getHeight() == 0) return -1;
        int[] location = new int[2];
        seek.getLocationOnScreen(location);
        return location[1];
    }

    /** Distance from the bottom of the screen up to just above the seek bar (a small gap included). */
    static int aboveSeekBarOffset(Context context) {
        int top = seekBarTopOnScreen(context);
        int screen = context.getResources().getDisplayMetrics().heightPixels;
        return top < 0 ? dp(context, 96) : Math.max(0, screen - top) + dp(context, 4);
    }

    /** One row of an options panel: a toggle when {@code checked} is set, otherwise a plain action. */
    public static final class Option {
        final CharSequence text;
        final boolean enabled;
        final java.util.function.BooleanSupplier checked;
        final Runnable action;
        public Option(CharSequence text, boolean enabled, java.util.function.BooleanSupplier checked, Runnable action) {
            this.text = text;
            this.enabled = enabled;
            this.checked = checked;
            this.action = action;
        }
    }

    /**
     * Panel on the left side of the screen with a Back button, toggles / actions and an optional view
     * (sliders...) below them. Back dismisses the panel and runs {@code onBack} without {@code onDismiss}.
     */
    static PopupWindow optionsPanel(Activity activity, CharSequence title, List<Option> options, View footer,
                                    Runnable onBack, Runnable onDismiss) {
        final Context ctx = activity;
        final boolean top = sTopCenter;
        final float textSize = top ? 14f : 12f;
        final boolean[] leaving = {false};
        BackPanel panel = new BackPanel(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL_BACKGROUND);
        panel.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        final PopupWindow popup = new PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (onBack != null) {
            panel.onBack = () -> {
                if (!popup.isShowing()) return;
                leaving[0] = true;
                popup.dismiss();
                onBack.run();
            };
        }

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(ctx);
        back.setText(R.string.mobile_player_back);
        back.setTextColor(Color.WHITE);
        back.setTextSize(textSize);
        back.setMinHeight(dp(ctx, 44));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        back.setFocusable(true);
        back.setBackground(rowBackground());
        back.setOnClickListener(view -> {
            if (!popup.isShowing()) return;
            leaving[0] = true;
            popup.dismiss();
            if (onBack != null) onBack.run();
        });
        header.addView(back);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0xFF9FB3D1);
        t.setTextSize(textSize);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 4));
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(header);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView[] rows = new TextView[options.size()];
        View firstEnabled = null;
        for (int i = 0; i < options.size(); i++) {
            final Option option = options.get(i);
            TextView row = new TextView(ctx);
            rows[i] = row;
            setOptionText(row, option);
            row.setTextColor(Color.WHITE);
            row.setTextSize(textSize);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            row.setBackground(rowBackground());
            row.setEnabled(option.enabled);
            row.setAlpha(option.enabled ? 1f : 0.45f);
            row.setFocusable(option.enabled);
            row.setClickable(option.enabled);
            if (option.enabled) {
                row.setOnClickListener(v -> {
                    if (!popup.isShowing()) return;
                    if (option.checked != null) {
                        if (option.action != null) option.action.run();
                        for (int j = 0; j < rows.length; j++) setOptionText(rows[j], options.get(j));
                    } else {
                        popup.dismiss();
                        if (option.action != null) option.action.run();
                    }
                });
                if (firstEnabled == null) firstEnabled = row;
            }
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (footer != null) {
            if (footer.getParent() instanceof ViewGroup) ((ViewGroup) footer.getParent()).removeView(footer);
            box.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        int maxHeight = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.6f);
        int seekTop = seekBarTopOnScreen(ctx);
        if (seekTop > 0) maxHeight = Math.max(dp(ctx, 96), Math.min(maxHeight, seekTop - dp(ctx, 12) - dp(ctx, 60)));
        MaxHeightScroll scroll = new MaxHeightScroll(ctx, maxHeight);
        scroll.addView(box);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(ctx, 32), dp(ctx, 300));
        PlayerController.applyNightModeTo(ctx, panel);   // night mode dims the popups too
        popup.setContentView(panel);
        popup.setWidth(width);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new GradientDrawable()); // needed so a tap outside dismisses
        popup.setOnDismissListener(() -> { if (!leaving[0] && onDismiss != null) onDismiss.run(); });
        if (top) popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 12));   // compact UI
        else popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.BOTTOM | Gravity.END, dp(ctx, 12), aboveSeekBarOffset(ctx)); // bottom right, on the seek bar
        final View target = firstEnabled != null ? firstEnabled : back;
        target.post(target::requestFocus); // no effect in touch mode
        return popup;
    }

    private static void setOptionText(TextView row, Option option) {
        row.setText(option.checked == null ? option.text
                : (option.checked.getAsBoolean() ? "\u25CF  " : "     ") + option.text);
    }

    private static void setRowSelection(TextView row, Entry entry, boolean showMarker, boolean checked) {
        boolean selected = entry.checkable && checked;
        String marker = showMarker ? (selected ? "\u25CF  " : "     ") : "";
        row.setText(marker + entry.text);
        row.setSelected(selected);
    }

    private static void dimBehind(Context ctx, PopupWindow popup, float amount) {
        try {
            View container = popup.getContentView().getRootView();
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) container.getLayoutParams();
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = amount;
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(container, lp);
        } catch (RuntimeException ignored) {
            // dimming is cosmetic only
        }
    }
}
