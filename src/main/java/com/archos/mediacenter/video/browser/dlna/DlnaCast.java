package com.archos.mediacenter.video.browser.dlna;

import android.net.Uri;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Files handed to a TV on request ("Cast on TV"); served by {@link DlnaHttpServer} under /cast/. */
public final class DlnaCast {
    private static final Map<String, DlnaMediaTree.Node> sFiles = new ConcurrentHashMap<>();

    private static volatile int sRequests;
    private static volatile long sBytes;
    private static volatile String sLast = "";

    private DlnaCast() {
    }

    static void onRequest(String method, String range, boolean found) {
        sRequests++;
        sLast = method + (range != null ? " " + range : "") + (found ? "" : " (file not found)");
    }

    static void sent(int n) {
        sBytes += n;
    }

    /** What the TV did with the address it was given, for the casting page. */
    public static String report() {
        if (sRequests == 0) return "The TV has not opened the video yet.";
        return "TV requests: " + sRequests + ", last: " + sLast + ", sent "
                + String.format(java.util.Locale.ROOT, "%.1f MB", sBytes / 1048576.0);
    }

    /** @return the path part of the address the TV must load, e.g. "/cast/3f9a/Movie.mkv" */
    static String register(Uri file, String title, String mime, long size) {
        DlnaMediaTree.Node n = new DlnaMediaTree.Node();
        n.id = Integer.toHexString(file.toString().hashCode());
        n.title = title;
        n.mime = mime;
        n.size = size;
        n.documentUri = file;
        n.mediaKind = 1;
        sFiles.put(n.id, n);
        sRequests = 0;
        sBytes = 0;
        sLast = "";
        // the address ends with the real file name (with its extension): TVs decide the type from it
        String name = file.getLastPathSegment();
        if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name == null || !name.contains(".")) {
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            name = title + "." + (ext == null ? "mp4" : ext);
        }
        return "/cast/" + n.id + "/" + Uri.encode(name);
    }

    /** For other packages. */
    public static String registerPublic(Uri file, String title, String mime, long size) {
        return register(file, title, mime, size);
    }

    static DlnaMediaTree.Node get(String id) {
        return sFiles.get(id);
    }
}
