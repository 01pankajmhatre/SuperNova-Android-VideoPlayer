package com.archos.mediacenter.video.browser.dlna;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remote control of the TV / player that plays from our server (UPnP AVTransport + RenderingControl).
 * Works only if the device accepts control commands from other apps; every call fails quietly otherwise.
 * All methods block on the network: call them from a background thread.
 */
public final class DlnaRendererControl {

    public static final class State {
        public String transport = "";     // PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING ...
        public int positionSec = -1;
        public int durationSec = -1;
        public int volume = -1;
    }

    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";

    private final String mClient;
    private String mName;
    private DlnaGoogleCast mGoogle;          // set for a Google Cast device (Google TV, Chromecast)
    private String mAvTransportUrl;
    private String mAvTransportType = AVT;
    private String mRenderingUrl;
    private String mRenderingType = RC;
    private long mLastSearch;
    private volatile String mStatus = "";
    private volatile String mLastError = "";
    private volatile boolean mSearching;

    /** Short explanation of what is (not) working, for the tile. */
    public String getStatus() {
        return mStatus;
    }

    public boolean isSearching() {
        return mSearching;
    }

    public DlnaRendererControl(String clientIp) {
        mClient = clientIp;
    }

    /** A Google Cast device (Google TV, Chromecast ...), controlled with the Cast protocol instead of DLNA. */
    private static DlnaRendererControl googleCast(DlnaGoogleCast.Device device) {
        DlnaRendererControl c = new DlnaRendererControl(device.host);
        c.mGoogle = new DlnaGoogleCast(device.host, device.port);
        c.mName = device.name + " (Google Cast)";
        return c;
    }

    /** All players (TVs, speakers) of the network that can be told what to play; blocks about 5 s. */
    public static java.util.List<DlnaRendererControl> discoverAll() {
        return discoverAll(null);
    }

    /** Same search, but each player is reported to onFound (on a background thread) as soon as it answers. */
    public static java.util.List<DlnaRendererControl> discoverAll(final java.util.function.Consumer<DlnaRendererControl> onFound) {
        final java.util.Set<String> reported = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        final java.util.List<DlnaRendererControl> result = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        java.util.List<Thread> fetchers = new java.util.ArrayList<>();
        // Google TV / Chromecast do not answer the DLNA search: ask for Google Cast devices at the same time
        Thread castSearch = new Thread(() -> DlnaGoogleCast.discover(device -> {
            DlnaRendererControl c = googleCast(device);
            result.add(c);
            if (onFound != null && reported.add(c.mClient + "|" + c.getName())) onFound.accept(c);
        }, 5000), "CastSearch");
        castSearch.start();
        fetchers.add(castSearch);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            long end = System.currentTimeMillis() + 5000;
            long nextSend = 0;
            int bursts = 0;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < end) {
                // the search is sent three times (UDP can be lost, slow TVs answer late)
                if (bursts < 3 && System.currentTimeMillis() >= nextSend) {
                    bursts++;
                    nextSend = System.currentTimeMillis() + 1200;
                    // some TVs only answer the general searches
                    for (String st : new String[]{AVT, "urn:schemas-upnp-org:device:MediaRenderer:1", "upnp:rootdevice"}) {
                        byte[] data = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                                + st + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                        try {
                            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("239.255.255.250"), 1900));
                        } catch (Exception ignored) {
                            // no multicast
                        }
                    }
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(text);
                if (!m.find() || !seen.add(m.group(1))) continue;
                final String location = m.group(1);
                final String ip = packet.getAddress().getHostAddress();
                // descriptions are read in parallel: one slow TV does not hide the others
                Thread t = new Thread(() -> {
                    DlnaRendererControl c = new DlnaRendererControl(ip);
                    if (c.loadDescription(location)) {
                        result.add(c);
                        if (onFound != null && reported.add(c.mClient + "|" + c.getName())) onFound.accept(c);
                    }
                });
                t.start();
                fetchers.add(t);
            }
        } catch (Exception ignored) {
            // network trouble
        }
        for (Thread t : fetchers) {
            try {
                t.join(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // one entry per device: a TV can answer for several of its services
        java.util.List<DlnaRendererControl> unique = new java.util.ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (DlnaRendererControl c : result) {
            if (keys.add(c.mClient + "|" + c.getName())) unique.add(c);
        }
        return unique;
    }

    /** Network address of the player. */
    public String getAddress() {
        return mClient;
    }

    /** Tells the player to load this address and play it. @return null when it worked, else the reason. */
    public String cast(String url, String title, String mime) {
        if (mGoogle != null) return mGoogle.cast(url, title, mime);
        if (mAvTransportUrl == null) return "no player";
        String full = didl(url, title, "http-get:*:" + mime
                + ":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
        String simple = didl(url, title, "http-get:*:" + mime + ":*");
        sLastErrorHolder.set("");
        // a TV that is still busy with something else answers "transition not available" (701):
        // stop it, wait until it is idle, and try again with simpler descriptions of the file
        prepareIdle();
        String[] descriptions = {full, simple, ""};
        String lastError = "";
        for (int attempt = 0; attempt < descriptions.length; attempt++) {
            sLastErrorHolder.set("");
            if (soap(mAvTransportUrl, mAvTransportType, "SetAVTransportURI",
                    "<CurrentURI>" + esc(url) + "</CurrentURI><CurrentURIMetaData>" + esc(descriptions[attempt])
                            + "</CurrentURIMetaData>", 25000) != null) {
                if (soap(mAvTransportUrl, mAvTransportType, "Play", "<Speed>1</Speed>", 25000) != null) return null;
                lastError = sLastErrorHolder.get();
                return isPlayingNow() ? null : lastError;
            }
            lastError = sLastErrorHolder.get();
            if (isPlayingNow()) return null;
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            prepareIdle();
        }
        return lastError + " (the TV could not open the video; check that it is on the same Wi-Fi and that the file type is supported)";
    }

    private String didl(String url, String title, String protocolInfo) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"" + esc(protocolInfo) + "\">"
                + esc(url) + "</res></item></DIDL-Lite>";
    }

    /** Stops what the TV is doing and waits (up to about 4 s) until it reports stopped / nothing loaded. */
    private void prepareIdle() {
        String info = soap(mAvTransportUrl, mAvTransportType, "GetTransportInfo", "");
        String state = tag(info, "CurrentTransportState");
        if ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state)) return;
        soap(mAvTransportUrl, mAvTransportType, "Stop", "");
        for (int i = 0; i < 8; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            state = tag(soap(mAvTransportUrl, mAvTransportType, "GetTransportInfo", ""), "CurrentTransportState");
            if (state == null || "STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state)) return;
        }
    }

    /** True when the TV reports playing / starting (checked up to 3 times, 1.5 s apart). */
    private boolean isPlayingNow() {
        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            String info = soap(mAvTransportUrl, mAvTransportType, "GetTransportInfo", "");
            String state = tag(info, "CurrentTransportState");
            if ("PLAYING".equals(state) || "TRANSITIONING".equals(state)) return true;
        }
        return false;
    }

    public void stop() {
        if (mGoogle != null) {
            mGoogle.stop();
            return;
        }
        if (mAvTransportUrl != null) soap(mAvTransportUrl, mAvTransportType, "Stop", "");
    }

    private static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public String getName() {
        return mName != null ? mName : mClient;
    }

    /** True for a Google TV / Chromecast (controlled with the Cast protocol). */
    public boolean isGoogleCast() {
        return mGoogle != null;
    }

    public boolean isFound() {
        return mGoogle != null || mAvTransportUrl != null;
    }

    // ------------------------------------------------------------------------------ discovery

    /** Looks for the device (SSDP search, answered by the device itself); retried at most every 10 s. */
    public synchronized boolean find() {
        if (mGoogle != null || mAvTransportUrl != null) return true;
        long now = System.currentTimeMillis();
        if (now - mLastSearch < 8_000) return false;
        mLastSearch = now;
        mSearching = true;
        try {
            return findNow();
        } finally {
            mSearching = false;
        }
    }

    private boolean findNow() {
        String location = search();
        if (location == null) {
            mStatus = "no answer to the network search (the player may not support remote control)";
            return false;
        }
        return loadDescription(location);
    }

    private boolean loadDescription(String location) {
        try {
            String xml = httpGet(location);
            if (xml == null) {
                mStatus = "found the player but could not read " + location;
                return false;
            }
            Matcher name = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.DOTALL).matcher(xml);
            if (name.find()) mName = name.group(1).trim();
            Matcher service = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL).matcher(xml);
            URL base = new URL(location);
            while (service.find()) {
                String block = service.group(1);
                String type = tag(block, "serviceType");
                String control = tag(block, "controlURL");
                if (type == null || control == null) continue;
                String url = new URL(base, control).toString();
                if (type.contains("AVTransport")) {
                    mAvTransportUrl = url;
                    mAvTransportType = type;
                } else if (type.contains("RenderingControl")) {
                    mRenderingUrl = url;
                    mRenderingType = type;
                }
            }
        } catch (Exception e) {
            mStatus = "could not read the player description: " + e;
            return false;
        }
        if (mAvTransportUrl == null) mStatus = "the player has no play / pause / seek service";
        else mStatus = "";
        return mAvTransportUrl != null;
    }

    private String search() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1000);
            // several search types: devices answer different ones
            for (String st : new String[]{AVT, "urn:schemas-upnp-org:device:MediaRenderer:1", "upnp:rootdevice", "ssdp:all"}) {
                String request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                        + st + "\r\n\r\n";
                byte[] data = request.getBytes(StandardCharsets.UTF_8);
                // the device is known: ask it directly, and also the whole network as a fallback
                try {
                    socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(mClient), 1900));
                } catch (Exception ignored) {
                    // not reachable directly
                }
                try {
                    socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("239.255.255.250"), 1900));
                } catch (Exception ignored) {
                    // no multicast
                }
            }
            long end = System.currentTimeMillis() + 3500;
            byte[] buffer = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (!mClient.equals(packet.getAddress().getHostAddress())) continue;
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(text);
                if (m.find()) {
                    // the answer must be the player's own description (not a router or another device)
                    String found = m.group(1);
                    String xml = httpGet(found);
                    if (xml != null && xml.contains("AVTransport")) return found;
                }
            }
        } catch (Exception ignored) {
            // network trouble
        }
        return null;
    }

    // -------------------------------------------------------------------------------- actions

    public State poll(boolean withVolume) {
        if (mGoogle != null) return mGoogle.poll();
        State state = new State();
        if (!find()) return state;
        String info = soap(mAvTransportUrl, mAvTransportType, "GetTransportInfo", "");
        if (info != null) {
            String s = tag(info, "CurrentTransportState");
            if (s != null) state.transport = s;
        }
        String pos = soap(mAvTransportUrl, mAvTransportType, "GetPositionInfo", "");
        if (pos != null) {
            state.positionSec = parseTime(tag(pos, "RelTime"));
            if (state.positionSec < 0) state.positionSec = parseTime(tag(pos, "AbsTime"));
            state.durationSec = parseTime(tag(pos, "TrackDuration"));
        }
        if (withVolume && mRenderingUrl != null) {
            String v = soap(mRenderingUrl, mRenderingType, "GetVolume", "<Channel>Master</Channel>");
            if (v != null) {
                try {
                    state.volume = Integer.parseInt(tag(v, "CurrentVolume").trim());
                } catch (Exception ignored) {
                    state.volume = -1;
                }
            }
        }
        return state;
    }

    public void play() {
        if (mGoogle != null) {
            mGoogle.play();
            return;
        }
        if (find()) soap(mAvTransportUrl, mAvTransportType, "Play", "<Speed>1</Speed>");
    }

    public void pause() {
        if (mGoogle != null) {
            mGoogle.pause();
            return;
        }
        if (find()) soap(mAvTransportUrl, mAvTransportType, "Pause", "");
    }

    public void seekTo(int seconds) {
        if (mGoogle != null) {
            mGoogle.seekTo(seconds);
            return;
        }
        if (!find()) return;
        int s = Math.max(0, seconds);
        String padded = formatTime(s);
        String plain = (s / 3600) + ":" + String.format(Locale.ROOT, "%02d:%02d", (s / 60) % 60, s % 60);
        // devices differ in unit and time format: try them all, and once more after a short wait
        // (a TV that is still opening the stream answers "action failed")
        String[][] tries = {{"REL_TIME", padded}, {"REL_TIME", plain}, {"ABS_TIME", padded}, {"ABS_TIME", plain},
                {"REL_TIME", padded + ".000"}};
        String firstError = "";
        for (int round = 0; round < 2; round++) {
            for (String[] t : tries) {
                sLastErrorHolder.set("");
                if (soap(mAvTransportUrl, mAvTransportType, "Seek",
                        "<Unit>" + t[0] + "</Unit><Target>" + t[1] + "</Target>") != null) {
                    sLastErrorHolder.set("");
                    return;
                }
                if (firstError.isEmpty()) firstError = sLastErrorHolder.get();
            }
            try {
                Thread.sleep(900);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        sLastErrorHolder.set(firstError + " (all time formats tried; this TV may not allow seeking in this file)");
    }

    /** @return the volume that was set, or -1 */
    public int changeVolume(int delta) {
        if (mGoogle != null) return mGoogle.changeVolume(delta);
        if (!find() || mRenderingUrl == null) return -1;
        String v = soap(mRenderingUrl, mRenderingType, "GetVolume", "<Channel>Master</Channel>");
        int current;
        try {
            current = Integer.parseInt(tag(v, "CurrentVolume").trim());
        } catch (Exception e) {
            return -1;
        }
        int target = Math.max(0, Math.min(100, current + delta));
        soap(mRenderingUrl, mRenderingType, "SetVolume",
                "<Channel>Master</Channel><DesiredVolume>" + target + "</DesiredVolume>");
        return target;
    }

    // ---------------------------------------------------------------------------------- SOAP

    private static String soap(String url, String serviceType, String action, String arguments) {
        return soap(url, serviceType, action, arguments, 4000);
    }

    private static String soap(String url, String serviceType, String action, String arguments, int readTimeoutMs) {
        if (url == null) return null;
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                    + "<u:" + action + " xmlns:u=\"" + serviceType + "\"><InstanceID>0</InstanceID>" + arguments
                    + "</u:" + action + "></s:Body></s:Envelope>";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() >= 400) {
                String fault = "";
                try {
                    fault = readAll(c.getErrorStream());
                } catch (Exception ignored) {
                    // no body
                }
                String code = tag(fault, "errorCode");
                String text = tag(fault, "errorDescription");
                sLastErrorHolder.set(action + ": HTTP " + c.getResponseCode()
                        + (code != null ? ", UPnP error " + code : "") + (text != null ? " " + text : ""));
                return null;
            }
            return readAll(c.getInputStream());
        } catch (Exception e) {
            sLastErrorHolder.set(action + ": " + e);
            return null;
        }
    }

    private static final ThreadLocal<String> sErr = new ThreadLocal<>();
    private static final Holder sLastErrorHolder = new Holder();

    /** Error text kept per thread, so a status poll never hides the error of a button press. */
    private static final class Holder {
        void set(String v) {
            sErr.set(v);
        }

        String get() {
            String v = sErr.get();
            return v == null ? "" : v;
        }
    }

    /** The last command the player refused or did not answer, e.g. "Pause: HTTP 500, UPnP error 701". */
    public String getLastError() {
        if (mGoogle != null) return mGoogle.getLastError();
        return sLastErrorHolder.get();
    }

    private static String httpGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(4000);
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String tag(String xml, String name) {
        if (xml == null) return null;
        Matcher m = Pattern.compile("<" + name + "[^>]*>(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** "H:MM:SS(.mmm)" to seconds; -1 when empty or not a time. */
    static int parseTime(String text) {
        if (text == null || text.isEmpty() || text.contains("NOT_IMPLEMENTED")) return -1;
        try {
            String[] parts = text.trim().split(":");
            double seconds = 0;
            for (String part : parts) seconds = seconds * 60 + Double.parseDouble(part);
            return (int) seconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String formatTime(int seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
