package com.archos.mediacenter.video.player;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.util.List;

/**
 * Compact UI strip: centred carousel of thumbnails (episodes) or posters (movies) at the bottom of the picture.
 * The card in the middle is the selected one (seek bar colour outline), the others overlap it, smaller and dimmer.
 * Swipe to scroll (snaps to a card), tap a card to bring it to the middle; tap the card in the middle (or OK on the remote)
 * to play that video. D-pad left / right moves the selection.
 * Above the cards: movie name, or episode number and name. Below: file name, or show name.
 */
final class PlayerVideoStrip {

    private static final String DEFAULT_COLOR = "#2B2BE0";

    /** The "Seek bar color" setting. */
    private static int selectedColor(Activity activity) {
        try {
            return Color.parseColor(androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                    .getString(PlayerController.PREF_YTUI_SEEK_COLOR, DEFAULT_COLOR));
        } catch (IllegalArgumentException e) {
            return Color.parseColor(DEFAULT_COLOR);
        }
    }

    static final class Item {
        final Uri image;
        final String top;
        final String bottom;
        final boolean playing;

        Item(Uri image, String top, String bottom, boolean playing) {
            this.image = image;
            this.top = top;
            this.bottom = bottom;
            this.playing = playing;
        }
    }

    interface OnPick {
        void onPick(int index);
    }

    private PlayerVideoStrip() {
    }

    private static int dp(Activity a, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics()));
    }

    private static void setText(TextView t, String text) {
        t.setText(text == null ? "" : text);
        t.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    static PopupWindow show(final Activity activity, final boolean episodes, final List<Item> items,
                            final int currentIndex, final OnPick onPick, final Runnable onDismiss) {
        final int itemW = dp(activity, episodes ? 124 : 76);
        final int itemH = dp(activity, episodes ? 88 : 114);
        final int pad = dp(activity, 3);
        final int cardW = itemW + 2 * pad;
        final float slot = cardW * 0.8f;                  // distance between two card centres: cards overlap
        final int overlap = Math.round(cardW * 0.1f);
        final int lift = dp(activity, 8);
        final int selectedColor = selectedColor(activity);
        final float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xE6000000}));
        panel.setPadding(0, dp(activity, 24), 0, dp(activity, 12));

        final TextView topText = new TextView(activity);
        topText.setTextColor(Color.WHITE);
        topText.setTextSize(15f);
        topText.setTypeface(topText.getTypeface(), Typeface.BOLD);
        topText.setSingleLine(true);
        topText.setEllipsize(TextUtils.TruncateAt.END);
        topText.setGravity(Gravity.CENTER);
        topText.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        topText.setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 4));
        panel.addView(topText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final RecyclerView list = new RecyclerView(activity);
        final LinearLayoutManager lm = new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false);
        list.setLayoutManager(lm);
        list.setClipToPadding(false);
        list.setItemAnimator(null);
        final int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        final int side = Math.max(0, (screenW - cardW) / 2) + overlap;
        list.setPadding(side, lift + dp(activity, 4), side, dp(activity, 4));
        new LinearSnapHelper().attachToRecyclerView(list);
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                itemH + 2 * pad + lift + dp(activity, 14)));

        final TextView bottomText = new TextView(activity);
        bottomText.setTextColor(0xFFDDDDDD);
        bottomText.setTextSize(12f);
        bottomText.setSingleLine(true);
        bottomText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bottomText.setGravity(Gravity.CENTER);
        bottomText.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        bottomText.setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), 0);
        panel.addView(bottomText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);

        final int[] selected = {-1};

        // centre card: border, full size; the others: overlapped, smaller, dimmer and a bit higher
        final Runnable update = () -> {
            final int w = list.getWidth();
            if (w == 0) return;
            final float cx = w / 2f;
            View closest = null;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < list.getChildCount(); i++) {
                View v = list.getChildAt(i);
                float d = (v.getLeft() + v.getRight()) / 2f - cx;
                float f = Math.abs(d) / slot;                 // 0 = centre card, 1 = next card, ...
                float scale = Math.max(0.6f, 1f - 0.14f * f);
                v.setScaleX(scale);
                v.setScaleY(scale);
                v.setTranslationY(-lift * Math.min(1f, f));
                v.setAlpha(Math.max(0.55f, 1f - 0.12f * f));
                v.setTranslationZ(Math.max(0f, 10f - 3f * f) * density);   // centre card on top of its neighbours
                if (Math.abs(d) < best) {
                    best = Math.abs(d);
                    closest = v;
                }
            }
            for (int i = 0; i < list.getChildCount(); i++) {
                View v = list.getChildAt(i);
                int p = list.getChildAdapterPosition(v);
                boolean playing = p != RecyclerView.NO_POSITION && items.get(p).playing;
                boolean on = v == closest;
                Object state = (on ? 1 : 0) + (playing ? 2 : 0) + (p << 2);
                if (!state.equals(v.getTag())) {
                    GradientDrawable border = new GradientDrawable();
                    border.setColor(Color.TRANSPARENT);
                    if (on) border.setStroke(pad, selectedColor);
                    else if (playing) border.setStroke(dp(activity, 2), 0xE6FFFFFF);   // the playing video: thin white outline
                    v.setBackground(border);
                    v.setTag(state);
                }
            }
            if (closest != null) {
                int pos = list.getChildAdapterPosition(closest);
                if (pos != RecyclerView.NO_POSITION && pos != selected[0]) {
                    selected[0] = pos;
                    setText(topText, items.get(pos).top);
                    setText(bottomText, items.get(pos).bottom);
                }
            }
        };

        final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                FrameLayout cell = new FrameLayout(activity);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cardW, itemH + 2 * pad);
                lp.setMargins(-overlap, 0, -overlap, 0);
                cell.setLayoutParams(lp);
                cell.setPadding(pad, pad, pad, pad);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setOutlineProvider(null);          // no shadow, only the draw order of translationZ

                ImageView image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(0xFF2A2F3A);
                cell.addView(image, new FrameLayout.LayoutParams(itemW, itemH));

                TextView name = new TextView(activity);
                name.setTextColor(Color.WHITE);
                name.setTextSize(11f);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(4);
                name.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
                name.setVisibility(View.GONE);
                cell.addView(name, new FrameLayout.LayoutParams(itemW, itemH));

                TextView play = new TextView(activity);
                play.setText("\u25B6");
                play.setTextColor(Color.WHITE);
                play.setTextSize(10f);
                play.setGravity(Gravity.CENTER);
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(0x33000000);
                dot.setStroke(dp(activity, 2), Color.WHITE);
                play.setBackground(dot);
                play.setPadding(dp(activity, 1), 0, 0, 0);
                FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(activity, 24), dp(activity, 24),
                        Gravity.TOP | Gravity.START);
                plp.setMargins(dp(activity, 8), dp(activity, 8), 0, 0);
                cell.addView(play, plp);
                return new RecyclerView.ViewHolder(cell) {
                };
            }

            @android.annotation.SuppressLint("RecyclerView")
            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                final Item item = items.get(position);
                final FrameLayout cell = (FrameLayout) holder.itemView;
                final ImageView image = (ImageView) cell.getChildAt(0);
                final TextView name = (TextView) cell.getChildAt(1);
                TextView play = (TextView) cell.getChildAt(2);
                play.setVisibility(item.playing ? View.VISIBLE : View.GONE);
                name.setText(item.top);
                name.setVisibility(View.GONE);
                Picasso.get().cancelRequest(image);
                image.setImageDrawable(null);
                if (item.image != null) {
                    Picasso.get().load(item.image).resize(itemW, itemH).centerCrop().into(image, new Callback() {
                        @Override
                        public void onSuccess() {
                        }

                        @Override
                        public void onError(Exception e) {
                            name.setVisibility(View.VISIBLE);
                        }
                    });
                } else {
                    name.setVisibility(View.VISIBLE);
                }
                cell.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    float d = Math.abs((cell.getLeft() + cell.getRight()) / 2f - list.getWidth() / 2f);
                    if (pos == selected[0] || d < slot / 2f) {
                        // the card in the middle: play it
                        popup.dismiss();
                        if (!items.get(pos).playing) onPick.onPick(pos);
                    } else {
                        centre(list, cell);
                    }
                });
                cell.setOnFocusChangeListener((v, hasFocus) -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (hasFocus && pos != RecyclerView.NO_POSITION && pos != selected[0]) centre(list, cell);
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                update.run();
            }
        });
        list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                list.post(update);
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
            }
        });
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> update.run());
        lm.scrollToPositionWithOffset(currentIndex, 0);

        popup.setOnDismissListener(() -> {
            if (onDismiss != null) onDismiss.run();
        });
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0);
        list.post(update);
        list.postDelayed(() -> {
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(currentIndex);
            if (vh != null) vh.itemView.requestFocus();
        }, 200);
        return popup;
    }

    /** Scroll so that the card is in the middle. */
    private static void centre(RecyclerView list, View cell) {
        float d = (cell.getLeft() + cell.getRight()) / 2f - list.getWidth() / 2f;
        list.smoothScrollBy(Math.round(d), 0);
    }
}
