package com.archos.mediacenter.video.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Check Latest Version": lists the APK files found at a (configurable) GitHub folder, downloads the picked
 * one to a temporary folder in the app cache, starts the installer and deletes the file afterwards.
 */
public final class AppUpdater {
    public static final String PREF_UPDATE_URL = "update_apk_list_url";
    public static final String DEFAULT_UPDATE_URL =
            "https://github.com/01pankajmhatre/SuperNova-Android-VideoPlayer/tree/main/build/outputs/apk/amazon/debug";

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final int STATE_NONE = 0, STATE_WAITING_PERMISSION = 1, STATE_INSTALLER_LAUNCHED = 2;
    private static int sState = STATE_NONE;
    private static File sPendingApk;

    private AppUpdater() {}

    private static final class Remote {
        final String name;
        final String url;
        final long size;
        Remote(String name, String url, long size) { this.name = name; this.url = url; this.size = size; }
    }

    // ---------------------------------------------------------------- url

    public static String getConfiguredUrl(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String url = prefs.getString(PREF_UPDATE_URL, DEFAULT_UPDATE_URL);
        return url == null || url.trim().isEmpty() ? DEFAULT_UPDATE_URL : url.trim();
    }

    /**
     * Turns a GitHub folder page (https://github.com/owner/repo/tree/branch/path) into the GitHub contents API
     * url that lists that folder. An api.github.com url is used as it is.
     */
    static String toListingUrl(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("api.github.com")) return url;
        if (!host.equals("github.com") && !host.equals("www.github.com")) return url;
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 4 || !"tree".equals(seg.get(2))) return null;
        StringBuilder path = new StringBuilder();
        for (int i = 4; i < seg.size(); i++) path.append(i > 4 ? "/" : "").append(Uri.encode(seg.get(i)));
        return "https://api.github.com/repos/" + seg.get(0) + "/" + seg.get(1) + "/contents/" + path
                + "?ref=" + Uri.encode(seg.get(3));
    }

    // ---------------------------------------------------------------- temp files

    private static File updatesDir(Context context) {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Deletes every downloaded APK. */
    public static void deleteDownloads(Context context) {
        File[] files = new File(context.getCacheDir(), "updates").listFiles();
        if (files != null) for (File f : files) f.delete();
        sPendingApk = null;
        sState = STATE_NONE;
    }

    /**
     * App start: removes the downloaded APKs that are already installed (same or older version) and the ones
     * that were left behind for more than a day.
     */
    public static void cleanupInstalled(Context context) {
        File[] files = new File(context.getCacheDir(), "updates").listFiles();
        if (files == null) return;
        long installed = 0;
        try {
            PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            installed = Build.VERSION.SDK_INT >= 28 ? own.getLongVersionCode() : own.versionCode;
        } catch (Exception ignored) { }
        for (File f : files) {
            boolean delete = System.currentTimeMillis() - f.lastModified() > 24L * 3600 * 1000;
            if (!delete) {
                PackageInfo info = context.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (info == null) {
                    delete = true;
                } else {
                    long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                    delete = version <= installed;
                }
            }
            if (delete) f.delete();
        }
    }

    /** Call from onResume of the screen that started the update (back from the installer / permission screen). */
    public static void onResume(Activity activity) {
        if (sState == STATE_INSTALLER_LAUNCHED) {
            // back from the installer (installed or cancelled): the downloaded file is not needed any more
            deleteDownloads(activity);
        } else if (sState == STATE_WAITING_PERMISSION && sPendingApk != null && sPendingApk.exists()
                && canInstall(activity)) {
            launchInstaller(activity, sPendingApk);
        }
    }

    // ---------------------------------------------------------------- check

    public static void checkLatestVersion(final Activity activity) {
        final String listing = toListingUrl(getConfiguredUrl(activity));
        if (listing == null) {
            Toast.makeText(activity, R.string.update_bad_url, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<Remote> found = new ArrayList<>();
            String error = null;
            try {
                HttpURLConnection c = open(listing);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                String body = readAll(c.getInputStream());
                c.disconnect();
                JSONArray array = new JSONArray(body);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    String name = o.optString("name");
                    String download = o.optString("download_url", "");
                    if ("file".equals(o.optString("type")) && name.toLowerCase(Locale.ROOT).endsWith(".apk")
                            && !download.isEmpty()) {
                        found.add(new Remote(name, download, o.optLong("size", -1)));
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            Collections.sort(found, (a, b) -> b.name.compareToIgnoreCase(a.name));   // newest names first
            final String failure = error;
            UI.post(() -> {
                if (activity.isFinishing()) return;
                if (failure != null) {
                    Toast.makeText(activity, activity.getString(R.string.update_failed, failure), Toast.LENGTH_LONG).show();
                } else if (found.isEmpty()) {
                    Toast.makeText(activity, R.string.update_none, Toast.LENGTH_LONG).show();
                } else {
                    showList(activity, found);
                }
            });
        }, "update-check").start();
    }

    private static void showList(final Activity activity, final List<Remote> apks) {
        CharSequence[] labels = new CharSequence[apks.size()];
        for (int i = 0; i < labels.length; i++) {
            Remote r = apks.get(i);
            labels[i] = r.size > 0 ? r.name + "\n" + Formatter.formatShortFileSize(activity, r.size) : r.name;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_pick_title)
                .setItems(labels, (dialog, which) -> confirmDownload(activity, apks.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmDownload(final Activity activity, final Remote apk) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_download_title)
                .setMessage(activity.getString(R.string.update_download_message, apk.name))
                .setPositiveButton(R.string.update_download_install, (d, w) -> download(activity, apk))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- download

    private static void download(final Activity activity, final Remote apk) {
        final float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        box.setPadding(pad, pad, pad, 0);
        final TextView text = new TextView(activity);
        text.setText(apk.name);
        box.addView(text);
        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(apk.size <= 0);
        bar.setMax(1000);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final boolean[] cancelled = {false};
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_downloading)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled[0] = true)
                .show();

        deleteDownloads(activity);   // only one downloaded APK at a time
        final File target = new File(updatesDir(activity), apk.name);
        new Thread(() -> {
            String error = null;
            try {
                HttpURLConnection c = open(apk.url);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLength() > 0 ? c.getContentLength() : apk.size;   // int: API 23 has no ...Long
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int n;
                    int lastShown = -1;
                    while (!cancelled[0] && (n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0) {
                            final int progress = (int) (done * 1000 / total);
                            if (progress != lastShown) {
                                lastShown = progress;
                                UI.post(() -> bar.setProgress(progress));
                            }
                        }
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String failure = error;
            UI.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (cancelled[0] || failure != null) {
                    target.delete();
                    if (failure != null && !activity.isFinishing()) {
                        Toast.makeText(activity, activity.getString(R.string.update_failed, failure), Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if (!activity.isFinishing()) install(activity, target);
            });
        }, "update-download").start();
    }

    // ---------------------------------------------------------------- install

    private static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    private static void install(Activity activity, File apk) {
        sPendingApk = apk;
        if (!canInstall(activity)) {
            sState = STATE_WAITING_PERMISSION;
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setPositiveButton(R.string.update_permission_open, (d, w) -> {
                        if (Build.VERSION.SDK_INT >= 26) {   // the permission only exists (and is only missing) from API 26
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> deleteDownloads(activity))
                    .setOnCancelListener(d -> deleteDownloads(activity))
                    .show();
            return;
        }
        launchInstaller(activity, apk);
    }

    private static void launchInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            sState = STATE_INSTALLER_LAUNCHED;
            activity.startActivity(intent);
        } catch (Exception e) {
            deleteDownloads(activity);
            Toast.makeText(activity, activity.getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- http

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SuperNova-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
