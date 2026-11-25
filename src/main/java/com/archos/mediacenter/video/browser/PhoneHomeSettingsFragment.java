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
        addAction(appearance, R.string.phone_poster_shape, activity == null ? null : activity::showHomePosterShapePicker);
        addAction(appearance, R.string.phone_movie_title_position, activity == null ? null : activity::showHomeMovieTitlePicker);
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

        SwitchPreferenceCompat netSources = new SwitchPreferenceCompat(requireContext());
        netSources.setKey(BrowserCategory.SHOW_NETWORK_SOURCES_IN_ROW);
        netSources.setTitle(R.string.phone_home_network_sources);
        netSources.setSummaryOn(R.string.phone_home_network_sources_on);
        netSources.setSummaryOff(R.string.phone_home_network_sources_off);
        netSources.setDefaultValue(false);
        netSources.setOnPreferenceClickListener(preference -> {
            if (activity != null) activity.updateHomeNetworkSources();
            return true;
        });
        appearance.addPreference(netSources);

        PreferenceCategory playback = addCategory(screen, R.string.phone_settings_playback);
        if (PlaybackNotificationSettings.isSupported(requireActivity())) {
            addAction(playback, R.string.notification_mode, () -> PlaybackNotificationSettings.show(requireActivity()));
        }
        // Playback UI style: standard / compact / YouTube style (replaces the old compact on/off switch).
        ListPreference playerStyle = new ListPreference(requireContext());
        playerStyle.setKey(PlayerController.PREF_PHONE_PLAYER_STYLE);
        playerStyle.setTitle(R.string.phone_player_style);
        playerStyle.setEntries(new CharSequence[]{
                getText(R.string.phone_player_style_standard),
                getText(R.string.phone_player_style_compact),
                getText(R.string.phone_player_style_youtube)});
        playerStyle.setEntryValues(new String[]{
                PlayerController.PHONE_STYLE_STANDARD,
                PlayerController.PHONE_STYLE_MINI,
                PlayerController.PHONE_STYLE_YOUTUBE});
        // keep what the former compact switch was set to until a style is picked
        playerStyle.setDefaultValue(PlayerController.getPhonePlayerStyle(getPreferenceManager().getSharedPreferences()));
        playerStyle.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        playback.addPreference(playerStyle);
        Preference[] standardOptions = addStandardOptions(playback);

        // YouTube style options
        SwitchPreferenceCompat ytCenter = new SwitchPreferenceCompat(requireContext());
        ytCenter.setKey(PlayerController.PREF_YTUI_CENTER_BUTTONS);
        ytCenter.setTitle(R.string.phone_ytui_center_buttons);
        ytCenter.setSummary(R.string.phone_ytui_center_buttons_summary);
        ytCenter.setDefaultValue(false);
        playback.addPreference(ytCenter);

        androidx.preference.SeekBarPreference ytOpacity = new androidx.preference.SeekBarPreference(requireContext());
        ytOpacity.setKey(PlayerController.PREF_YTUI_PILL_OPACITY);
        ytOpacity.setTitle(R.string.phone_ytui_pill_opacity);
        ytOpacity.setSummary(R.string.phone_ytui_pill_opacity_summary);
        ytOpacity.setMin(0);
        ytOpacity.setMax(100);
        ytOpacity.setSeekBarIncrement(5);
        ytOpacity.setShowSeekBarValue(true);
        ytOpacity.setDefaultValue(PlayerController.DEFAULT_YTUI_PILL_OPACITY);
        playback.addPreference(ytOpacity);

        ListPreference ytSeekColor = new ListPreference(requireContext());
        ytSeekColor.setKey(PlayerController.PREF_YTUI_SEEK_COLOR);
        ytSeekColor.setTitle(R.string.phone_ytui_seek_color);
        ytSeekColor.setEntries(R.array.phone_ytui_seek_color_entries);
        ytSeekColor.setEntryValues(R.array.phone_ytui_seek_color_values);
        ytSeekColor.setDefaultValue(PlayerController.DEFAULT_YTUI_SEEK_COLOR);
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
        // Show the Standard settings only when that style is selected.
        updateStyleOptions(playerStyle.getValue(), standardOptions, ytCenter, ytOpacity, ytSeekColor);
        playerStyle.setOnPreferenceChangeListener((preference, value) -> {
            updateStyleOptions(value, standardOptions, ytCenter, ytOpacity, ytSeekColor);
            return true;
        });

        SwitchPreferenceCompat swipePill = new SwitchPreferenceCompat(requireContext());
        swipePill.setKey(PlayerController.PREF_SWIPE_PILL);
        swipePill.setTitle(R.string.phone_swipe_pill);
        swipePill.setSummary(R.string.phone_swipe_pill_summary);
        swipePill.setDefaultValue(false);
        playback.addPreference(swipePill);

        addAction(playback, R.string.phone_poster_tap, activity == null ? null : activity::showPosterTapPicker);

        SwitchPreferenceCompat rememberBrightness = new SwitchPreferenceCompat(requireContext());
        rememberBrightness.setKey(PlayerBrightnessManager.PREF_REMEMBER_BRIGHTNESS);
        rememberBrightness.setTitle(R.string.phone_remember_brightness);
        rememberBrightness.setSummary(R.string.phone_remember_brightness_summary);
        rememberBrightness.setDefaultValue(true);
        playback.addPreference(rememberBrightness);

        PreferenceCategory library = addCategory(screen, R.string.phone_settings_library);
        SwitchPreferenceCompat banner = new SwitchPreferenceCompat(requireContext());
        banner.setKey(PosterGridFragment.PREF_BANNER_LIST);
        banner.setTitle(R.string.library_banner_list);
        banner.setSummary(R.string.library_banner_list_summary);
        banner.setDefaultValue(false);
        library.addPreference(banner);
        ListPreference bannerAlign = new ListPreference(requireContext());
        bannerAlign.setKey(PosterGridFragment.PREF_BANNER_TEXT_ALIGN);
        bannerAlign.setTitle(R.string.library_banner_text_align);
        bannerAlign.setEntries(new CharSequence[]{getText(R.string.align_left), getText(R.string.align_center), getText(R.string.align_right)});
        bannerAlign.setEntryValues(new String[]{"left", "center", "right"});
        bannerAlign.setDefaultValue("center");
        bannerAlign.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        library.addPreference(bannerAlign);
        bannerAlign.setDependency(PosterGridFragment.PREF_BANNER_LIST);   // only after both are in the screen
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

    private Preference[] addStandardOptions(PreferenceCategory playback) {
        ListPreference position = new ListPreference(requireContext());
        position.setKey(PlayerController.PREF_STANDARD_TITLE_POSITION);
        position.setTitle(R.string.phone_standard_title_position);
        position.setEntries(new CharSequence[]{
                getText(R.string.phone_standard_title_above_seekbar),
                getText(R.string.phone_standard_title_button_row),
                getText(R.string.phone_standard_title_top),
                getText(R.string.phone_standard_title_below)});
        position.setEntryValues(new String[]{
                PlayerController.STANDARD_TITLE_ABOVE_SEEKBAR,
                PlayerController.STANDARD_TITLE_BUTTON_ROW,
                PlayerController.STANDARD_TITLE_TOP,
                PlayerController.STANDARD_TITLE_BELOW});
        position.setDefaultValue(PlayerController.STANDARD_TITLE_ABOVE_SEEKBAR);
        position.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        playback.addPreference(position);

        SwitchPreferenceCompat outline = new SwitchPreferenceCompat(requireContext());
        outline.setKey(PlayerController.PREF_STANDARD_TITLE_OUTLINE);
        outline.setTitle(R.string.phone_standard_title_outline);
        outline.setDefaultValue(false);
        playback.addPreference(outline);

        SwitchPreferenceCompat shadow = new SwitchPreferenceCompat(requireContext());
        shadow.setKey(PlayerController.PREF_STANDARD_TITLE_SHADOW);
        shadow.setTitle(R.string.phone_standard_title_shadow);
        shadow.setSummary(R.string.phone_standard_playback_shadow_summary);
        shadow.setDefaultValue(false);
        playback.addPreference(shadow);

        SeekBarPreference size = new SeekBarPreference(requireContext());
        size.setKey(PlayerController.PREF_STANDARD_TITLE_SIZE);
        size.setTitle(R.string.phone_standard_title_size);
        size.setMin(PlayerController.MIN_STANDARD_TITLE_SIZE);
        size.setMax(PlayerController.MAX_STANDARD_TITLE_SIZE);
        size.setDefaultValue(PlayerController.DEFAULT_STANDARD_TITLE_SIZE);
        size.setShowSeekBarValue(true);
        playback.addPreference(size);

        SwitchPreferenceCompat status = new SwitchPreferenceCompat(requireContext());
        status.setKey(PlayerController.PREF_STANDARD_TITLE_STATUS);
        status.setTitle(R.string.phone_standard_title_status);
        status.setDefaultValue(false);
        playback.addPreference(status);

        SwitchPreferenceCompat background = new SwitchPreferenceCompat(requireContext());
        background.setKey(PlayerController.PREF_STANDARD_TITLE_BACKGROUND);
        background.setTitle(R.string.phone_standard_title_background);
        background.setDefaultValue(false);
        playback.addPreference(background);

        SwitchPreferenceCompat noPill = new SwitchPreferenceCompat(requireContext());
        noPill.setKey(PlayerController.PREF_STANDARD_NO_BUTTON_PILL);
        noPill.setTitle(R.string.phone_standard_no_button_pill);
        noPill.setSummary(R.string.phone_standard_no_button_pill_summary);
        noPill.setDefaultValue(false);
        playback.addPreference(noPill);

        return new Preference[]{position, outline, shadow, size, status, background, noPill};
    }

    private static void updateStyleOptions(Object style, Preference[] standardOptions,
                                           Preference ytCenter,
                                           Preference ytOpacity, Preference ytSeekColor) {
        boolean standard = PlayerController.PHONE_STYLE_STANDARD.equals(style);
        for (Preference option : standardOptions) option.setVisible(standard);
        boolean youtube = PlayerController.PHONE_STYLE_YOUTUBE.equals(style);
        ytCenter.setEnabled(youtube);
        ytOpacity.setEnabled(youtube);
        ytSeekColor.setEnabled(youtube || PlayerController.PHONE_STYLE_MINI.equals(style));
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
