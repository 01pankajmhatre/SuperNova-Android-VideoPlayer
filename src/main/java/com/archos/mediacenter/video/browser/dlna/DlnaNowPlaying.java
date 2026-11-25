package com.archos.mediacenter.video.browser.dlna;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the DLNA server is streaming right now, per client device (filled in by the HTTP server). */
public final class DlnaNowPlaying {

    public static final class Stream {
        public final String client;          // IP address of the TV / player
        public volatile String title;
        public volatile boolean converted;
        public volatile long durationUs;     // known for converted streams, otherwise 0
        volatile int open;                   // requests being served at the moment
        volatile long lastSeen;

        Stream(String client) {
            this.client = client;
        }
    }

    public interface Listener {
        void onChanged();
    }

    /** A TV that stops reading (pause, buffering) keeps its stream for this long. */
    private static final long KEEP_MS = 25_000;

    private static final Map<String, Stream> sStreams = new LinkedHashMap<>();
    private static volatile Listener sListener;

    private DlnaNowPlaying() {
    }

    public static void setListener(Listener listener) {
        sListener = listener;
    }

    static void begin(String client, String title, boolean converted, long durationUs) {
        if (client == null) return;
        boolean changed;
        synchronized (sStreams) {
            Stream s = sStreams.get(client);
            changed = s == null || !title.equals(s.title) || s.converted != converted || !isActive(s);
            if (s == null) {
                s = new Stream(client);
                sStreams.put(client, s);
            }
            s.title = title;
            s.converted = converted;
            s.durationUs = durationUs;
            s.open++;
            s.lastSeen = System.currentTimeMillis();
        }
        if (changed) notifyChanged();
    }

    static void setDuration(String client, long durationUs) {
        synchronized (sStreams) {
            Stream s = sStreams.get(client);
            if (s != null) s.durationUs = durationUs;
        }
    }

    static void end(String client) {
        if (client == null) return;
        synchronized (sStreams) {
            Stream s = sStreams.get(client);
            if (s != null) {
                s.open = Math.max(0, s.open - 1);
                s.lastSeen = System.currentTimeMillis();
            }
        }
    }

    /** Streams that are being served or were served a moment ago. */
    public static List<Stream> active() {
        List<Stream> list = new ArrayList<>();
        synchronized (sStreams) {
            for (Stream s : sStreams.values()) if (isActive(s)) list.add(s);
        }
        return list;
    }

    private static boolean isActive(Stream s) {
        return s.title != null && (s.open > 0 || System.currentTimeMillis() - s.lastSeen < KEEP_MS);
    }

    private static void notifyChanged() {
        Listener l = sListener;
        if (l != null) l.onChanged();
    }
}
