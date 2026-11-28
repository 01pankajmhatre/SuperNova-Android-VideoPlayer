package com.archos.mediacenter.video.browser.presenter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextView;

import androidx.palette.graphics.Palette;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;

/** Shared look of the phone Movies / TV shows grid: Home background, ticket-style poster cards. */
public final class PhoneCardStyle {
    private static final String HOME_BACKGROUND_COLOR = "classic_home_background_color";
    private static final int DEFAULT_HOME_BACKGROUND = 0xFF0B1220;
    private static final int DEFAULT_PANEL = 0xFF2B3A4F;
    /** Minimum column width: 3 columns on typical phones. */
    private static final int COLUMN_DP = 120;

    private PhoneCardStyle() {}

    public static int pageColor(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(HOME_BACKGROUND_COLOR, DEFAULT_HOME_BACKGROUND);
    }

    /** Grid layout + Home page background. */
    public static void styleGrid(GridView grid, View root) {
        final Context context = grid.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final int columns = Math.max(3, context.getResources().getConfiguration().screenWidthDp / COLUMN_DP);
        grid.setNumColumns(columns);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Math.round(6 * density));
        grid.setVerticalSpacing(Math.round(8 * density));
        grid.setPadding(Math.round(12 * density), Math.round(12 * density),
                Math.round(12 * density), Math.round(16 * density));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        final int page = pageColor(context);
        root.setBackgroundColor(page);
        grid.setBackgroundColor(page);
    }

    /** Poster height (2:3) from the column width, and the notches placed at the poster / panel junction. */
    public static void sizePoster(View card, ViewGroup parent) {
        final Context context = card.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final int screenWidth = context.getResources().getConfiguration().screenWidthDp;
        final int columns = Math.max(3, screenWidth / COLUMN_DP);
        int width = Math.round((screenWidth - 24 - (columns - 1) * 6) * density / columns);
        if (parent instanceof GridView && ((GridView) parent).getColumnWidth() > 0)
            width = ((GridView) parent).getColumnWidth();
        final int height = Math.round(width * 1.5f);
        final View poster = card.findViewById(R.id.phone_movie_poster);
        final ViewGroup.LayoutParams size = poster.getLayoutParams();
        size.height = height;
        poster.setLayoutParams(size);
        // plain poster style: rounded poster, name and details below on the page background
        final GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFF1F2B44);
        pbg.setCornerRadius(0f);
        poster.setBackground(pbg);
        poster.setClipToOutline(true);
        if (card instanceof androidx.cardview.widget.CardView) {
            ((androidx.cardview.widget.CardView) card).setCardBackgroundColor(Color.TRANSPARENT);
            ((androidx.cardview.widget.CardView) card).setRadius(0f);
        }
        card.findViewById(R.id.phone_card_notch_start).setVisibility(View.GONE);
        card.findViewById(R.id.phone_card_notch_end).setVisibility(View.GONE);
        final View panel = card.findViewById(R.id.phone_card_panel);
        panel.setBackgroundColor(Color.TRANSPARENT);
        panel.setPadding(Math.round(2 * density), Math.round(6 * density), Math.round(2 * density), 0);
        final TextView name = card.findViewById(R.id.name);
        name.setTextSize(12);
        name.setLines(2);   // fixed height: rows keep their size when the text is bound later
        final TextView info = card.findViewById(R.id.info);
        info.setTextSize(10);
        info.setTextColor(0xFF9FB3D1);
        info.setSingleLine(true);
        info.setText(" ");
        info.setVisibility(View.VISIBLE);   // always reserves its line
        final View rows = (View) card.findViewById(R.id.phone_card_k1).getParent().getParent();
        rows.setVisibility(View.GONE);
    }

    /** Details line below the name: both values joined. */
    public static void setRow(View card, int key1, String value1, int key2, String value2) {
        final StringBuilder sb = new StringBuilder();
        if (value1 != null && !value1.isEmpty()) sb.append(card.getContext().getString(key1)).append(' ').append(value1);
        if (value2 != null && !value2.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(card.getContext().getString(key2)).append(' ').append(value2);
        }
        final TextView info = card.findViewById(R.id.info);
        info.setText(sb.length() > 0 ? sb : " ");
        info.setVisibility(View.VISIBLE);
    }

    private static void setPair(View card, int kId, int vId, int keyRes, String value) {
        final TextView k = card.findViewById(kId);
        final TextView v = card.findViewById(vId);
        final boolean show = value != null && !value.isEmpty();
        k.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        v.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            k.setText(keyRes);
            v.setText(value);
        }
    }

    /** Plain style: nothing to tint (kept so the presenters stay unchanged). */
    public static void tint(final View card, Bitmap poster) { }
}
