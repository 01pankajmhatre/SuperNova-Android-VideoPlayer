package com.archos.mediacenter.video.browser.dlna;

import com.archos.mediacenter.video.browser.adapters.object.Video;

/** What is being cast to a TV right now; read by the "DLNA Now" tiles and the casting page. */
public final class DlnaCastSession {
    private static volatile DlnaRendererControl sTv;
    private static volatile String sTitle;
    private static volatile Video sVideo;
    private static volatile boolean sActive;
    private static volatile Runnable sListener;

    private DlnaCastSession() {
    }

    static void begin(DlnaRendererControl tv, String title) {
        sTv = tv;
        sTitle = title;
        sActive = true;
        notifyChanged();
    }

    static void end() {
        sActive = false;
        sTv = null;
        notifyChanged();
    }

    public static void setVideo(Video video) {
        sVideo = video;
    }

    public static Video getVideo() {
        return sVideo;
    }

    static DlnaRendererControl getTv() {
        return sTv;
    }

    public static boolean isActive() {
        return sActive;
    }

    /** Name of the video on the TV, or "". */
    public static String getTitle() {
        return sTitle == null ? "" : sTitle;
    }

    /** One listener (the visible page); called on any thread. */
    public static void setListener(Runnable listener) {
        sListener = listener;
    }

    private static void notifyChanged() {
        Runnable l = sListener;
        if (l != null) l.run();
    }
}
