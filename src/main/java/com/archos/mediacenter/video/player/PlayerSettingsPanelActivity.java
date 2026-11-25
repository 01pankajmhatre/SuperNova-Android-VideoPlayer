package com.archos.mediacenter.video.player;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.archos.mediacenter.video.browser.PhoneHomeSettingsFragment;

/**
 * The app UI settings page shown over the player as a full height panel, cropped to a narrow width and
 * aligned to the right edge. Tapping outside the panel closes it.
 */
public class PlayerSettingsPanelActivity extends AppCompatActivity {
    private static final int CONTAINER_ID = 0x7f0e5e77;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenW - (int) (32 * d), (int) (360 * d));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);   // dim the player behind
        root.setOnClickListener(v -> finish());

        TypedValue bg = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
        FrameLayout panel = new FrameLayout(this);
        panel.setId(CONTAINER_ID);
        panel.setBackgroundColor(bg.type >= TypedValue.TYPE_FIRST_COLOR_INT && bg.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? bg.data : Color.parseColor("#202020"));
        panel.setClickable(true);   // taps inside must not reach the closing root
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        root.addView(panel, lp);
        setContentView(root);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(CONTAINER_ID, new PhoneHomeSettingsFragment())
                    .commit();
        }
    }
}
