package com.archos.mediacenter.video.browser;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.AppUpdater;
import com.archos.mediacenter.video.player.PlaybackNotificationSettings;
import com.archos.mediacenter.video.player.PlayerBrightnessManager;
import com.archos.mediacenter.video.player.PlayerController;
import com.archos.mediascraper.AutoScrapeService;

public class PhoneHomeSettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // null when shown in the player's side panel (no home screen behind it): home-only actions are left out then
        final MainActivity activity = requireActivity() instanceof MainActivity ? (MainActivity) requireActivity() : null;
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        // Re-scan lives at the top of Settings
        if (activity != null) {
            androidx.preference.Preference rescan = new androidx.preference.Preference(requireContext());
            rescan.setTitle(R.string.rescan);
            rescan.setOnPreferenceClickListener(p -> { activity.showHomeRescan(); return true; });
            screen.addPreference(rescan);
        }

        // Preferences (app settings) live inside Settings
        if (activity != null) {
            androidx.preference.Preference prefs = new androidx.preference.Preference(requireContext());
            prefs.setTitle(R.string.preferences);
            prefs.setOnPreferenceClickListener(p -> { activity.startPreference(); return true; });
            screen.addPreference(prefs);
        }

        PreferenceCategory decoder = addCategory(screen, R.string.decoder_category);
        ListPreference engine = new ListPreference(requireContext());
        engine.setKey(com.archos.mediacenter.video.player.Player.PREF_PLAYBACK_ENGINE);
        engine.setTitle(R.string.decoder_engine);
        engine.setEntries(new CharSequence[]{getText(R.string.decoder_avos), getText(R.string.decoder_android)});
        engine.setEntryValues(new String[]{com.archos.mediacenter.video.player.Player.ENGINE_AVOS,
                com.archos.mediacenter.video.player.Player.ENGINE_ANDROID});
        engine.setDefaultValue(com.archos.mediacenter.video.player.Player.ENGINE_AVOS);
        engine.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        decoder.addPreference(engine);
        if ("hybrid".equals(engine.getValue())) {
            engine.setValue(com.archos.mediacenter.video.player.Player.ENGINE_AVOS);
        }

        androidx.preference.CheckBoxPreference fallback = new androidx.preference.CheckBoxPreference(requireContext());
        fallback.setKey(com.archos.mediacenter.video.player.Player.PREF_ENGINE_FALLBACK);
        fallback.setTitle(R.string.decoder_enable_fallback);
        fallback.setDefaultValue(true);
        decoder.addPreference(fallback);

        PreferenceCategory appearance = addCategory(screen, R.string.phone_settings_appearance);
        addAction(appearance, R.string.home_background_theme, activity == null ? null : () -> activity.showHomeColorPicker(false));
        addAction(appearance, R.string.home_card_color, activity == null ? null : () -> activity.showHomeColorPicker(true));
        addAction(appearance, R.string.phone_details_style, activity == null ? null : activity::showDetailsStylePicker);

        SwitchPreferenceCompat pin = new SwitchPreferenceCompat(requireContext());
        pin.setKey(BrowserCategory.KEEP_HOME_SOURCES_VISIBLE);
        pin.setTitle(R.string.phone_pin_sources);
        pin.setSummaryOn(R.string.phone_sources_pinned);
        pin.setSummaryOff(R.string.phone_sources_scroll);
        pin.setDefaultValue(false);
        pin.setOnPreferenceClickListener(preference -> {
            if (activity != null) activity.updateHomeSourcePin();
            return true;
        });
        appearance.addPreference(pin);

        SwitchPreferenceCompat posterPage = new SwitchPreferenceCompat(requireContext());
        posterPage.setKey(BrowserCategory.HOME_POSTER_PAGE);
        posterPage.setTitle(R.string.home_poster_page);
        posterPage.setSummaryOn(R.string.home_poster_page_on);
        posterPage.setSummaryOff(R.string.home_poster_page_off);
        posterPage.setDefaultValue(true);
        appearance.addPreference(posterPage);
        addAction(appearance, R.string.phone_home_rows, activity == null ? null : activity::showHomeRowsPicker);

        PreferenceCategory playback = addCategory(screen, R.string.phone_settings_playback);
        if (PlaybackNotificationSettings.isSupported(requireActivity())) {
            addAction(playback, R.string.notification_mode, () -> PlaybackNotificationSettings.show(requireActivity()));
        }
        // Seek bar color (Compact playback UI)
        ListPreference ytSeekColor = new ListPreference(requireContext());
        ytSeekColor.setKey(PlayerController.PREF_YTUI_SEEK_COLOR);
        ytSeekColor.setTitle(R.string.phone_ytui_seek_color);
        ytSeekColor.setEntries(R.array.phone_ytui_seek_color_entries);
        ytSeekColor.setEntryValues(R.array.phone_ytui_seek_color_values);
        ytSeekColor.setDefaultValue("#2B2BE0");
        ytSeekColor.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        playback.addPreference(ytSeekColor);

        SwitchPreferenceCompat swipeSeek = new SwitchPreferenceCompat(requireContext());
        swipeSeek.setKey(PlayerController.PREF_GESTURE_SWIPE_SEEK);
        swipeSeek.setTitle(R.string.phone_gesture_swipe_seek);
        swipeSeek.setSummary(R.string.phone_gesture_swipe_seek_summary);
        swipeSeek.setDefaultValue(true);
        playback.addPreference(swipeSeek);

        SwitchPreferenceCompat doubleTap = new SwitchPreferenceCompat(requireContext());
        doubleTap.setKey(PlayerController.PREF_STANDARD_TAP_ZONES);
        doubleTap.setTitle(R.string.phone_standard_tap_zones);
        doubleTap.setSummary(R.string.phone_standard_tap_zones_summary);
        doubleTap.setDefaultValue(true);
        playback.addPreference(doubleTap);
        SwitchPreferenceCompat buttonPill = new SwitchPreferenceCompat(requireContext());
        buttonPill.setKey(PlayerController.PREF_BUTTON_PILL);
        buttonPill.setTitle(R.string.phone_button_pill);
        buttonPill.setSummary(R.string.phone_button_pill_summary);
        buttonPill.setDefaultValue(true);
        playback.addPreference(buttonPill);

        // the buttons pill (swipe up / UP on the remote) has the other buttons: the control area can keep only playback
        final SwitchPreferenceCompat hideExtraButtons = new SwitchPreferenceCompat(requireContext());
        hideExtraButtons.setKey(PlayerController.PREF_HIDE_EXTRA_BUTTONS);
        hideExtraButtons.setTitle(R.string.phone_hide_extra_buttons);
        hideExtraButtons.setSummary(R.string.phone_hide_extra_buttons_summary);
        hideExtraButtons.setDefaultValue(false);
        playback.addPreference(hideExtraButtons);

        final SwitchPreferenceCompat titleRowButtons = new SwitchPreferenceCompat(requireContext());
        titleRowButtons.setKey(PlayerController.PREF_TITLE_ROW_BUTTONS);
        titleRowButtons.setTitle(R.string.phone_title_row_buttons);
        titleRowButtons.setSummary(R.string.phone_title_row_buttons_summary);
        titleRowButtons.setDefaultValue(false);
        playback.addPreference(titleRowButtons);

        final SwitchPreferenceCompat titleOnTop = new SwitchPreferenceCompat(requireContext());
        titleOnTop.setKey(PlayerController.PREF_MINI_TITLE_ON_TOP);
        titleOnTop.setTitle(R.string.phone_mini_title_on_top);
        titleOnTop.setSummary(R.string.phone_mini_title_on_top_summary);
        titleOnTop.setDefaultValue(false);
        playback.addPreference(titleOnTop);

        final SwitchPreferenceCompat titleCenter = new SwitchPreferenceCompat(requireContext());
        titleCenter.setKey(PlayerController.PREF_MINI_TITLE_CENTER);
        titleCenter.setTitle(R.string.phone_mini_title_center);
        titleCenter.setSummary(R.string.phone_mini_title_center_summary);
        titleCenter.setDefaultValue(false);
        playback.addPreference(titleCenter);

        final SwitchPreferenceCompat hideTitleRow = new SwitchPreferenceCompat(requireContext());
        hideTitleRow.setKey(PlayerController.PREF_HIDE_TITLE_ROW);
        hideTitleRow.setTitle(R.string.phone_hide_title_row);
        hideTitleRow.setSummary(R.string.phone_hide_title_row_summary);
        hideTitleRow.setDefaultValue(false);
        playback.addPreference(hideTitleRow);

        // Settings that do not apply right now are greyed out, never hidden.
        // state: 0 = pill, 1 = hide extra buttons, 2 = buttons in title row, 3 = title on top
        final android.content.SharedPreferences saved =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        final boolean[] state = {
                saved.getBoolean(PlayerController.PREF_BUTTON_PILL, true),
                saved.getBoolean(PlayerController.PREF_HIDE_EXTRA_BUTTONS, false),
                saved.getBoolean(PlayerController.PREF_TITLE_ROW_BUTTONS, false),
                saved.getBoolean(PlayerController.PREF_MINI_TITLE_ON_TOP, false)};
        final Runnable updateStates = () -> {
            boolean hidden = state[0] && state[1];            // extra buttons live in the pill only
            boolean buttonsInTitle = state[2] && !hidden;     // buttons really sit in the title row
            hideExtraButtons.setEnabled(state[0]);
            titleRowButtons.setEnabled(!hidden);
            titleCenter.setEnabled(state[3] && !buttonsInTitle);
            hideTitleRow.setEnabled(!buttonsInTitle);
        };
        updateStates.run();
        final androidx.preference.Preference[] prefs = {buttonPill, hideExtraButtons, titleRowButtons, titleOnTop};
        for (int i = 0; i < prefs.length; i++) {
            final int index = i;
            prefs[i].setOnPreferenceChangeListener((preference, value) -> {
                state[index] = Boolean.TRUE.equals(value);
                updateStates.run();
                return true;
            });
        }

        addAction(playback, R.string.phone_poster_tap, activity == null ? null : activity::showPosterTapPicker);

        SwitchPreferenceCompat rememberBrightness = new SwitchPreferenceCompat(requireContext());
        rememberBrightness.setKey(PlayerBrightnessManager.PREF_REMEMBER_BRIGHTNESS);
        rememberBrightness.setTitle(R.string.phone_remember_brightness);
        rememberBrightness.setSummary(R.string.phone_remember_brightness_summary);
        rememberBrightness.setDefaultValue(true);
        playback.addPreference(rememberBrightness);

        PreferenceCategory library = addCategory(screen, R.string.phone_settings_library);
        SwitchPreferenceCompat metadata = new SwitchPreferenceCompat(requireContext());
        metadata.setKey(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP);
        metadata.setTitle(R.string.phone_metadata_enabled);
        metadata.setSummary(R.string.phone_metadata_enabled_summary);
        metadata.setDefaultValue(true);
        metadata.setOnPreferenceClickListener(preference -> {
            if (metadata.isChecked()) AutoScrapeService.startService(requireContext());
            return true;
        });
        library.addPreference(metadata);

        Preference refresh = addAction(library, R.string.phone_refresh_metadata, activity == null ? null : activity::refreshHomeMetadata);
        if (refresh != null) refresh.setDependency(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP);

        PreferenceCategory updates = addCategory(screen, R.string.phone_settings_updates);
        Preference check = new Preference(requireContext());
        check.setTitle(R.string.update_check_latest);
        check.setSummary(R.string.update_check_latest_summary);
        check.setOnPreferenceClickListener(item -> {
            AppUpdater.checkLatestVersion(requireActivity());
            return true;
        });
        updates.addPreference(check);
        androidx.preference.EditTextPreference updateUrl = new androidx.preference.EditTextPreference(requireContext());
        updateUrl.setKey(AppUpdater.PREF_UPDATE_URL);
        updateUrl.setTitle(R.string.update_url_title);
        updateUrl.setDialogMessage(R.string.update_url_dialog);
        updateUrl.setDefaultValue(AppUpdater.DEFAULT_UPDATE_URL);
        updateUrl.setSummaryProvider(pref -> AppUpdater.getConfiguredUrl(requireContext()));
        updates.addPreference(updateUrl);
    }

    @Override
    public void onResume() {
        super.onResume();
        AppUpdater.onResume(requireActivity());   // back from the installer: delete the downloaded APK
    }

    private void addSkipDuration(PreferenceCategory category, String key, int title) {
        String[] values = {"5", "10", "15", "20", "30", "60"};
        CharSequence[] labels = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = getString(R.string.phone_skip_duration_seconds, Integer.parseInt(values[i]));
        }
        ListPreference duration = new ListPreference(requireContext());
        duration.setKey(key);
        duration.setTitle(title);
        duration.setEntries(labels);
        duration.setEntryValues(values);
        duration.setDefaultValue(PlayerController.DEFAULT_COMPACT_SKIP_SECONDS);
        duration.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        category.addPreference(duration);
    }

    private PreferenceCategory addCategory(PreferenceScreen screen, int title) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    private Preference addAction(PreferenceCategory category, int title, Runnable action) {
        if (action == null) return null;   // not available in the player side panel
        Preference preference = new Preference(requireContext());
        preference.setTitle(title);
        preference.setOnPreferenceClickListener(item -> {
            action.run();
            return true;
        });
        category.addPreference(preference);
        return preference;
    }
}
