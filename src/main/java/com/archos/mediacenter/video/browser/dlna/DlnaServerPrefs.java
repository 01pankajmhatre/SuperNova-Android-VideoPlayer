package com.archos.mediacenter.video.browser.dlna;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Settings of the built-in DLNA (UPnP MediaServer) host: name, port, shared folders, auto start. */
public final class DlnaServerPrefs {
    private static final String FILE = "dlna_server";
    private static final String KEY_NAME = "name";
    private static final String KEY_PORT = "port";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_FOLDERS = "folders";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_FILES_IN_ROOT = "files_in_root";

    public static final int DEFAULT_PORT = 8200;

    /** A shared folder: a SAF tree the user picked, and the name it is shown with. */
    public static final class Folder {
        public final Uri uri;
        public final String name;

        Folder(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private final SharedPreferences mPrefs;

    public DlnaServerPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getName() {
        String fallback = (Build.MODEL != null && !Build.MODEL.isEmpty()) ? Build.MODEL : "Android";
        return mPrefs.getString(KEY_NAME, fallback + " (SuperNova)");
    }

    public void setName(String name) {
        mPrefs.edit().putString(KEY_NAME, name.trim()).apply();
    }

    public int getPort() {
        return mPrefs.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        mPrefs.edit().putInt(KEY_PORT, port).apply();
    }

    /** Stable identity of this server (UDN), created once. */
    public String getUuid() {
        String uuid = mPrefs.getString(KEY_UUID, null);
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
            mPrefs.edit().putString(KEY_UUID, uuid).apply();
        }
        return uuid;
    }

    public boolean isAutoStart() {
        return mPrefs.getBoolean(KEY_AUTO_START, false);
    }

    public void setAutoStart(boolean autoStart) {
        mPrefs.edit().putBoolean(KEY_AUTO_START, autoStart).apply();
    }

    /** When on, the server's root lists the content of the shared folders directly (their videos and sub folders). */
    public boolean isFilesInRoot() {
        return mPrefs.getBoolean(KEY_FILES_IN_ROOT, false);
    }

    public void setFilesInRoot(boolean filesInRoot) {
        mPrefs.edit().putBoolean(KEY_FILES_IN_ROOT, filesInRoot).apply();
    }

    /** Whether the user left the server switched on (it is started again at the next app start if auto start is on). */
    public boolean isRunningRequested() {
        return mPrefs.getBoolean(KEY_RUNNING, false);
    }

    public void setRunningRequested(boolean running) {
        mPrefs.edit().putBoolean(KEY_RUNNING, running).apply();
    }

    public synchronized List<Folder> getFolders() {
        List<Folder> folders = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPrefs.getString(KEY_FOLDERS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                folders.add(new Folder(Uri.parse(o.getString("uri")), o.getString("name")));
            }
        } catch (JSONException ignored) {
            // corrupt value: treated as empty
        }
        return folders;
    }

    public synchronized boolean addFolder(Uri treeUri, String name) {
        List<Folder> folders = getFolders();
        for (Folder f : folders) {
            if (f.uri.equals(treeUri)) return false;
        }
        folders.add(new Folder(treeUri, name));
        save(folders);
        return true;
    }

    public synchronized void removeFolder(int index) {
        List<Folder> folders = getFolders();
        if (index >= 0 && index < folders.size()) {
            folders.remove(index);
            save(folders);
        }
    }

    private void save(List<Folder> folders) {
        JSONArray array = new JSONArray();
        try {
            for (Folder f : folders) {
                array.put(new JSONObject().put("uri", f.uri.toString()).put("name", f.name));
            }
        } catch (JSONException ignored) {
            return;
        }
        mPrefs.edit().putString(KEY_FOLDERS, array.toString()).apply();
    }
}
