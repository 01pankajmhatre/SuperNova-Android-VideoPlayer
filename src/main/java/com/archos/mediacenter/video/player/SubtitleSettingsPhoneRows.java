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

package com.archos.mediacenter.video.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.player.SubtitleSettingsModel.ColorTarget;
import com.archos.mediacenter.video.player.SubtitleSettingsModel.Row;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Draws the rows of SubtitleSettingsModel for touch: the dialog's content. Views are created once
 * per row id and re-bound from the model after every change, so a change that adds or removes rows
 * (the style mode, the background, the Advanced switch) is just a refresh().
 */
final class SubtitleSettingsPhoneRows {

    interface Callbacks {
        /** A color row was tapped: the dialog shows that color's page. */
        void onOpenColor(ColorTarget target);

        /** The rows were re-read from the model: the sample strip is redrawn. */
        void onRefreshed();
    }

    private final Context mContext;
    private final ViewGroup mRows;
    private final SubtitleSettingsModel mModel;
    private final Callbacks mCallbacks;
    private final HashMap<String, View> mViews = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ColorTarget mColorPage;
    private boolean mBinding;

    SubtitleSettingsPhoneRows(ViewGroup rows, SubtitleSettingsModel model, Callbacks callbacks) {
        mContext = rows.getContext();
        mRows = rows;
        mModel = model;
        mCallbacks = callbacks;
    }

    /** Shows a page from scratch: the settings (null) or the page of a color. */
    void show(ColorTarget colorPage) {
        mColorPage = colorPage;
        mViews.clear();
        mRows.removeAllViews();
        refresh();
    }

    void release() {
        mHandler.removeCallbacksAndMessages(null);
    }

    /** Re-reads everything from the model. */
    void refresh() {
        final List<Row> rows = mModel.rows(mColorPage);
        mBinding = true;
        try {
            final HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                final Row r = rows.get(i);
                ids.add(r.id);
                View v = mViews.get(r.id);
                if (v == null) {
                    v = createView(r);
                    mViews.put(r.id, v);
                }
                v.setTag(r);
                bind(r, v);
                if (mRows.getChildAt(i) != v) {
                    final ViewGroup parent = (ViewGroup) v.getParent();
                    if (parent != null) parent.removeView(v);
                    mRows.addView(v, i);
                }
            }
            while (mRows.getChildCount() > rows.size()) mRows.removeViewAt(rows.size());
            mViews.keySet().retainAll(ids);
        } finally {
            mBinding = false;
        }
        mCallbacks.onRefreshed();
    }

    // ---- views --------------------------------------------------------------------------------

    private View inflate(int layout) {
        return LayoutInflater.from(mContext).inflate(layout, mRows, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createView(Row row) {
        final View v;
        switch (row.type) {
            case PILLS: {
                v = inflate(R.layout.subtitle_row_pills);
                final ViewGroup pills = v.findViewById(R.id.sp_pills);
                for (int i = 0; i < row.options.length; i++) {
                    final int index = i;
                    final View seg;
                    if ("bg".equals(row.id)) {
                        androidx.appcompat.widget.AppCompatRadioButton radio =
                                new androidx.appcompat.widget.AppCompatRadioButton(mContext);
                        radio.setTextSize(12);
                        radio.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        seg = radio;
                    } else {
                        seg = LayoutInflater.from(mContext).inflate(R.layout.subtitle_row_pill_segment, pills, false);
                    }
                    seg.setFocusable(true);
                    seg.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View s) {
                            onUserSet((Row) v.getTag(), index);
                        }
                    });
                    pills.addView(seg);
                }
                // D-pad / TV remote: the row is the focus stop, Left / Right pick the previous / next choice,
                // Center / Enter goes to the next one (and back to the first after the last)
                v.setOnKeyListener(new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View row, int keyCode, KeyEvent event) {
                        final Row r = (Row) row.getTag();
                        if (r == null || r.setting == null || r.options == null) return false;
                        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                        final int current = r.setting.get();
                        final int last = r.options.length - 1;
                        switch (keyCode) {
                            case KeyEvent.KEYCODE_DPAD_LEFT:
                                if (down && current > 0) onUserSet(r, current - 1);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_RIGHT:
                                if (down && current < last) onUserSet(r, current + 1);
                                return true;
                            case KeyEvent.KEYCODE_DPAD_CENTER:
                            case KeyEvent.KEYCODE_ENTER:
                            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                                if (down && event.getRepeatCount() == 0) onUserSet(r, current < last ? current + 1 : 0);
                                return true;
                            default:
                                return false;
                        }
                    }
                });
                break;
            }
            case STEPPER: {
                v = inflate(R.layout.subtitle_row_stepper);
                setupRepeat(v, v.findViewById(R.id.sp_minus), -1);
                setupRepeat(v, v.findViewById(R.id.sp_plus), 1);
                v.findViewById(R.id.sp_reset).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View b) {
                        resetRow(v);
                    }
                });
                // D-pad / TV remote: the row is the focus stop, Left / Right change the value (hold to
                // accelerate), Center / Enter restores the default
                v.setOnKeyListener(new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View row, int keyCode, KeyEvent event) {
                        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                        switch (keyCode) {
                            case KeyEvent.KEYCODE_DPAD_LEFT:
                                if (down) step(row, -1, event.getRepeatCount());
                                return true;
                            case KeyEvent.KEYCODE_DPAD_RIGHT:
                                if (down) step(row, 1, event.getRepeatCount());
                                return true;
                            case KeyEvent.KEYCODE_DPAD_CENTER:
                            case KeyEvent.KEYCODE_ENTER:
                            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                                if (down && event.getRepeatCount() == 0) resetRow(row);
                                return true;
                            default:
                                return false;
                        }
                    }
                });
                break;
            }
            case TOGGLE: {
                v = inflate(R.layout.subtitle_row_toggle);
                v.setOnKeyListener(new View.OnKeyListener() { // Center / Enter flips it
                    @Override
                    public boolean onKey(View row, int keyCode, KeyEvent event) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                                ((SwitchCompat) row.findViewById(R.id.sp_toggle)).toggle();
                            }
                            return true;
                        }
                        return false;
                    }
                });
                ((SwitchCompat) v.findViewById(R.id.sp_toggle)).setOnCheckedChangeListener(
                        new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton b, boolean checked) {
                                if (!mBinding) onUserSet((Row) v.getTag(), checked ? 1 : 0);
                            }
                        });
                break;
            }
            case SLIDER: {
                v = inflate(R.layout.subtitle_row_slider);
                ((SeekBar) v.findViewById(R.id.sp_slider)).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (fromUser && !mBinding) {
                            final Row r = (Row) v.getTag();
                            onUserSet(r, r.setting.min + progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        if ("vpos".equals(((Row) v.getTag()).id)) mModel.setDraggingVpos(true);
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        if ("vpos".equals(((Row) v.getTag()).id)) mModel.setDraggingVpos(false);
                    }
                });
                break;
            }
            case COLOR: {
                v = inflate(R.layout.subtitle_row_color);
                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View c) {
                        mCallbacks.onOpenColor(((Row) v.getTag()).color);
                    }
                });
                break;
            }
            case ACTION: {
                v = inflate(R.layout.subtitle_row_action);
                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View c) {
                        mModel.onResetPressed();
                        refresh();
                    }
                });
                break;
            }
            case NOTE:
                v = inflate(R.layout.subtitle_row_note);
                break;
            case SECTION:
                v = inflate(R.layout.subtitle_row_section);
                break;
            default: { // GRID: the preset colors, as they always were
                final android.widget.LinearLayout palette = new android.widget.LinearLayout(mContext);
                palette.setOrientation(android.widget.LinearLayout.VERTICAL);
                final SubtitleColorPicker grid = new SubtitleColorPicker(mContext);
                palette.addView(grid);
                View preview = inflate(R.layout.subtitle_row_color);
                palette.addView(preview);
                grid.setColorPickListener(new SubtitleColorPicker.ColorPickListener() {
                    @Override
                    public void onColorPicked(int color) {
                        ((Row) palette.getTag()).color.applyRgb(color);
                        refresh();
                    }
                });
                v = palette;
                break;
            }
        }
        return v;
    }

    private void bind(Row r, View v) {
        final TextView label = v.findViewById(R.id.sp_label);
        if (label != null && r.labelRes != 0) label.setText(r.labelRes);
        switch (r.type) {
            case PILLS: {
                final ViewGroup pills = v.findViewById(R.id.sp_pills);
                final int current = r.setting.get();
                for (int i = 0; i < pills.getChildCount(); i++) {
                    final TextView seg = (TextView) pills.getChildAt(i);
                    seg.setText(r.options[i]);
                    if (seg instanceof android.widget.RadioButton) {
                        ((android.widget.RadioButton) seg).setChecked(i == current);
                    } else {
                        seg.setSelected(i == current);
                    }
                }
                break;
            }
            case STEPPER: {
                final int value = r.setting.get();
                ((TextView) v.findViewById(R.id.sp_value)).setText(
                        r.fmt == null ? String.valueOf(value) : r.fmt.format(value));
                v.findViewById(R.id.sp_minus).setAlpha(value > r.setting.min ? 1f : 0.35f);
                v.findViewById(R.id.sp_plus).setAlpha(value < r.setting.max ? 1f : 0.35f);
                final View resetButton = v.findViewById(R.id.sp_reset);
                resetButton.setVisibility(r.setting.hasDefault() ? View.VISIBLE : View.INVISIBLE);
                resetButton.setAlpha(r.setting.isDefault() ? 0.35f : 1f);
                break;
            }
            case TOGGLE: {
                final SwitchCompat sw = v.findViewById(R.id.sp_toggle);
                sw.setText(r.labelRes);
                sw.setChecked(r.setting.get() != 0);
                break;
            }
            case SLIDER: {
                final SeekBar bar = v.findViewById(R.id.sp_slider);
                bar.setMax(r.setting.max - r.setting.min);
                bar.setProgress(r.setting.get() - r.setting.min);
                int value = r.setting.get();
                ((TextView) v.findViewById(R.id.sp_value)).setText(
                        r.fmt == null ? String.valueOf(value) : r.fmt.format(value));
                break;
            }
            case COLOR: {
                final int argb = r.color.get();
                setChip(v.findViewById(R.id.sp_chip), argb);
                ((TextView) v.findViewById(R.id.sp_value)).setText(String.format("#%06X", argb & 0xFFFFFF));
                break;
            }
            case ACTION:
                if (label != null) label.setText(mModel.getResetLabelRes());
                break;
            case NOTE:
            case SECTION:
                ((TextView) v).setText(r.labelRes);
                break;
            case GRID:
                ViewGroup palette = (ViewGroup) v;
                int selectedColor = r.color.get();
                ((SubtitleColorPicker) palette.getChildAt(0)).setCurrentColor(selectedColor);
                View preview = palette.getChildAt(1);
                ((TextView) preview.findViewById(R.id.sp_label)).setText(r.color.labelRes);
                setChip(preview.findViewById(R.id.sp_chip), selectedColor);
                ((TextView) preview.findViewById(R.id.sp_value)).setText(
                        String.format("#%06X", selectedColor & 0xFFFFFF));
                break;
            default:
                break;
        }
    }

    private void setChip(View chip, int argb) {
        final LayerDrawable d = (LayerDrawable) chip.getBackground().mutate();
        ((GradientDrawable) d.findDrawableByLayerId(R.id.swatch_fill)).setColor(0xFF000000 | (argb & 0xFFFFFF));
    }

    // ---- input --------------------------------------------------------------------------------

    private void onUserSet(Row r, int value) {
        if (r == null || r.setting == null) return;
        r.setting.apply(value);
        refresh();
    }

    private void resetRow(View row) {
        final Row r = (Row) row.getTag();
        if (r == null || r.setting == null) return;
        r.setting.reset();
        refresh();
    }

    /** Focus on the first row, for D-pad / remote use (a no-op in touch mode). */
    void focusFirst() {
        for (int i = 0; i < mRows.getChildCount(); i++) {
            if (mRows.getChildAt(i).isFocusable() && mRows.getChildAt(i).requestFocus()) return;
        }
    }

    private static int accel(int repeat) {
        return repeat < 5 ? 1 : repeat < 15 ? 2 : 5;
    }

    /** - / + on a stepper row. */
    private void step(View row, int direction, int repeat) {
        final Row r = (Row) row.getTag();
        if (r == null || r.setting == null) return;
        onUserSet(r, r.setting.get() + direction * r.setting.step * accel(repeat));
    }

    /** Hold a - or + button: one step at once, then repeating, faster the longer it is held. */
    @SuppressLint("ClickableViewAccessibility")
    private void setupRepeat(final View row, final View button, final int direction) {
        final Runnable[] repeater = new Runnable[1];
        final int[] count = new int[1];
        repeater[0] = new Runnable() {
            @Override
            public void run() {
                step(row, direction, count[0]++);
                mHandler.postDelayed(this, 120);
            }
        };
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        count[0] = 0;
                        step(row, direction, count[0]++);
                        mHandler.postDelayed(repeater[0], 400);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        mHandler.removeCallbacks(repeater[0]);
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        // touch is consumed above; this is what an accessibility service's "click" runs
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(row, direction, 0);
            }
        });
    }
}
