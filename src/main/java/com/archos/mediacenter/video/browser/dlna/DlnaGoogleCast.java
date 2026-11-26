package com.archos.mediacenter.video.browser.dlna;

import android.annotation.SuppressLint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Google Cast (Google TV, Chromecast, Android TV with Cast built in): finds the devices with mDNS and plays a
 * video address on them with the "Default Media Receiver" (CASTV2: TLS on port 8009, protobuf frames, JSON payloads).
 * All methods block on the network: call them from a background thread.
 */
final class DlnaGoogleCast {
    static final class Device {
        final String name;
        final String host;
        final int port;

        Device(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private static final String DEFAULT_MEDIA_RECEIVER = "CC1AD845";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String SENDER = "sender-0";
    private static final String RECEIVER = "receiver-0";

    // ------------------------------------------------------------------------------ discovery (mDNS)

    /** Sends the mDNS question for Cast devices for about {@code millis} ms and reports each device once. */
    static List<Device> discover(Consumer<Device> onFound, long millis) {
        List<Device> found = new ArrayList<>();
        Set<String> hosts = new HashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(400);
            byte[] query = buildQuery("_googlecast._tcp.local");
            long end = System.currentTimeMillis() + millis;
            long nextSend = 0;
            int bursts = 0;
            byte[] buffer = new byte[9000];
            while (System.currentTimeMillis() < end) {
                if (bursts < 3 && System.currentTimeMillis() >= nextSend) {
                    bursts++;
                    nextSend = System.currentTimeMillis() + 1500;
                    try {
                        socket.send(new DatagramPacket(query, query.length, InetAddress.getByName("224.0.0.251"), 5353));
                    } catch (Exception ignored) {
                        // no multicast on this network
                    }
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Device d = parseResponse(packet.getData(), packet.getLength(), packet.getAddress().getHostAddress());
                if (d != null && hosts.add(d.host)) {
                    found.add(d);
                    if (onFound != null) onFound.accept(d);
                }
            }
        } catch (Exception ignored) {
            // network trouble: nothing found
        }
        return found;
    }

    private static byte[] buildQuery(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12);
        out.write(0x34);          // id (not 0: the answer then comes back to our port, as unicast)
        out.write(0);
        out.write(0);             // flags: standard query
        out.write(0);
        out.write(1);             // one question
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
        out.write(0);
        out.write(12);            // PTR
        out.write(0);
        out.write(1);             // class IN
        return out.toByteArray();
    }

    /** @return the Cast device described by an mDNS answer, or null when it is something else. */
    private static Device parseResponse(byte[] b, int length, String sourceHost) {
        try {
            if (length < 12 || (b[2] & 0x80) == 0) return null;          // not a response
            int questions = u16(b, 4);
            int records = u16(b, 6) + u16(b, 8) + u16(b, 10);
            int[] pos = {12};
            for (int i = 0; i < questions; i++) {
                readName(b, pos);
                pos[0] += 4;
            }
            boolean cast = false;
            String friendly = null;
            String instance = null;
            int port = 8009;
            String address = null;
            for (int i = 0; i < records && pos[0] < length; i++) {
                String name = readName(b, pos);
                int type = u16(b, pos[0]);
                int rdLength = u16(b, pos[0] + 8);
                int data = pos[0] + 10;
                pos[0] = data + rdLength;
                if (name.toLowerCase().contains("_googlecast._tcp")) cast = true;
                if (type == 12) {                    // PTR: instance name
                    int[] p = {data};
                    String target = readName(b, p);
                    if (target.toLowerCase().contains("_googlecast._tcp")) {
                        cast = true;
                        instance = target.substring(0, target.indexOf("._googlecast"));
                    }
                } else if (type == 33 && name.toLowerCase().contains("_googlecast._tcp")) {   // SRV
                    port = u16(b, data + 4);
                } else if (type == 16 && name.toLowerCase().contains("_googlecast._tcp")) {   // TXT: fn=Friendly name
                    int p = data;
                    while (p < data + rdLength) {
                        int n = b[p] & 0xFF;
                        String entry = new String(b, p + 1, Math.min(n, data + rdLength - p - 1), StandardCharsets.UTF_8);
                        if (entry.startsWith("fn=")) friendly = entry.substring(3);
                        p += n + 1;
                    }
                } else if (type == 1 && rdLength == 4) {          // A
                    address = (b[data] & 0xFF) + "." + (b[data + 1] & 0xFF) + "." + (b[data + 2] & 0xFF) + "." + (b[data + 3] & 0xFF);
                }
            }
            if (!cast) return null;
            String host = address != null ? address : sourceHost;
            String name = friendly != null ? friendly : instance != null ? instance : host;
            return new Device(name, host, port);
        } catch (RuntimeException e) {
            return null;                              // not a well formed answer
        }
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static String readName(byte[] b, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        boolean jumped = false;
        int guard = 0;
        while (guard++ < 128) {
            int len = b[p] & 0xFF;
            if (len == 0) {
                p++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {            // compression pointer
                int target = ((len & 0x3F) << 8) | (b[p + 1] & 0xFF);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                p = target;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(b, p + 1, len, StandardCharsets.UTF_8));
            p += len + 1;
        }
        if (!jumped) pos[0] = p;
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------ session

    private final String mHost;
    private final int mPort;
    private SSLSocket mSocket;
    private OutputStream mOut;
    private volatile boolean mOpen;
    private volatile String mTransportId;
    private volatile String mSessionId;
    private volatile int mMediaSessionId = -1;
    private volatile String mPlayerState = "";
    private volatile double mCurrentTime = -1;
    private volatile double mDuration = -1;
    private volatile double mVolume = -1;
    private volatile String mError = "";
    private int mRequestId = 1;
    private final Object mWriteLock = new Object();

    DlnaGoogleCast(String host, int port) {
        mHost = host;
        mPort = port;
    }

    String getLastError() {
        return mError;
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager", "BadHostnameVerifier"})
    private synchronized String connect() {
        if (mOpen) return null;
        try {
            // Cast devices use their own certificate: it cannot be checked against the usual authorities
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, null);
            java.net.Socket plain = new java.net.Socket();
            plain.connect(new InetSocketAddress(mHost, mPort), 5000);
            mSocket = (SSLSocket) context.getSocketFactory().createSocket(plain, mHost, mPort, true);
            mSocket.setSoTimeout(0);
            mSocket.startHandshake();
            mOut = mSocket.getOutputStream();
            mOpen = true;
            final DataInputStream in = new DataInputStream(mSocket.getInputStream());
            Thread reader = new Thread(() -> readLoop(in), "CastReader");
            reader.setDaemon(true);
            reader.start();
            Thread heartbeat = new Thread(() -> {
                while (mOpen) {
                    try {
                        Thread.sleep(5000);
                        send(NS_HEARTBEAT, RECEIVER, "{\"type\":\"PING\"}");
                    } catch (Exception e) {
                        return;
                    }
                }
            }, "CastHeartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();
            send(NS_CONNECTION, RECEIVER, "{\"type\":\"CONNECT\"}");
            return null;
        } catch (Exception e) {
            closeQuietly();
            return "could not connect to " + mHost + ": " + e.getMessage();
        }
    }

    /** Starts the Default Media Receiver and plays the address. @return null when it worked, else the reason. */
    synchronized String cast(String url, String title, String mime) {
        mError = "";
        String error = connect();
        if (error != null) return error;
        try {
            mTransportId = null;
            mMediaSessionId = -1;
            mPlayerState = "";
            send(NS_RECEIVER, RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + DEFAULT_MEDIA_RECEIVER + "\",\"requestId\":" + (mRequestId++) + "}");
            for (int i = 0; i < 60 && mTransportId == null && mOpen && mError.isEmpty(); i++) Thread.sleep(250);
            if (mTransportId == null) return mError.isEmpty() ? "the TV did not start its media player" : mError;
            send(NS_CONNECTION, mTransportId, "{\"type\":\"CONNECT\"}");
            JSONObject metadata = new JSONObject().put("type", 0).put("metadataType", 0).put("title", title);
            JSONObject media = new JSONObject().put("contentId", url).put("streamType", "BUFFERED")
                    .put("contentType", mime).put("metadata", metadata);
            JSONObject load = new JSONObject().put("type", "LOAD").put("requestId", mRequestId++)
                    .put("sessionId", mSessionId).put("media", media).put("autoplay", true).put("currentTime", 0);
            send(NS_MEDIA, mTransportId, load.toString());
            for (int i = 0; i < 100 && mOpen; i++) {
                String s = mPlayerState;
                if ("PLAYING".equals(s) || "BUFFERING".equals(s) || "PAUSED".equals(s)) return null;
                if (!mError.isEmpty()) return mError;
                Thread.sleep(250);
            }
            return mError.isEmpty() ? "the TV did not start the video (check the file type is supported)" : mError;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    DlnaRendererControl.State poll() {
        DlnaRendererControl.State state = new DlnaRendererControl.State();
        if (!mOpen) return state;
        try {
            if (mTransportId != null) {
                send(NS_MEDIA, mTransportId, "{\"type\":\"GET_STATUS\",\"requestId\":" + (mRequestId++) + "}");
            }
        } catch (IOException ignored) {
            // the reader notices a broken connection
        }
        switch (mPlayerState) {
            case "PLAYING": state.transport = "PLAYING"; break;
            case "PAUSED": state.transport = "PAUSED_PLAYBACK"; break;
            case "BUFFERING": state.transport = "TRANSITIONING"; break;
            default: state.transport = "STOPPED"; break;
        }
        if (mCurrentTime >= 0) state.positionSec = (int) mCurrentTime;
        if (mDuration > 0) state.durationSec = (int) mDuration;
        if (mVolume >= 0) state.volume = (int) Math.round(mVolume * 100);
        return state;
    }

    void play() {
        mediaCommand("PLAY", null);
    }

    void pause() {
        mediaCommand("PAUSE", null);
    }

    void seekTo(int seconds) {
        try {
            mediaCommand("SEEK", new JSONObject().put("currentTime", Math.max(0, seconds)).put("resumeState", "PLAYBACK_START"));
        } catch (Exception ignored) {
            // not built
        }
    }

    private void mediaCommand(String type, JSONObject extra) {
        if (!mOpen || mTransportId == null || mMediaSessionId < 0) return;
        try {
            JSONObject m = extra != null ? extra : new JSONObject();
            m.put("type", type).put("mediaSessionId", mMediaSessionId).put("requestId", mRequestId++);
            send(NS_MEDIA, mTransportId, m.toString());
        } catch (Exception e) {
            mError = String.valueOf(e.getMessage());
        }
    }

    /** @return the volume that was set (0 - 100), or -1 */
    int changeVolume(int delta) {
        if (!mOpen) return -1;
        try {
            double level = mVolume >= 0 ? mVolume : 0.5;
            double target = Math.max(0, Math.min(1, level + delta / 100.0));
            send(NS_RECEIVER, RECEIVER, new JSONObject().put("type", "SET_VOLUME").put("requestId", mRequestId++)
                    .put("volume", new JSONObject().put("level", target)).toString());
            mVolume = target;
            return (int) Math.round(target * 100);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Stops the video and closes the media player of the TV. */
    synchronized void stop() {
        try {
            if (mOpen) {
                mediaCommand("STOP", null);
                if (mSessionId != null) {
                    send(NS_RECEIVER, RECEIVER, new JSONObject().put("type", "STOP").put("requestId", mRequestId++)
                            .put("sessionId", mSessionId).toString());
                }
            }
        } catch (Exception ignored) {
            // leaving anyway
        }
        closeQuietly();
    }

    private void closeQuietly() {
        mOpen = false;
        try {
            if (mSocket != null) mSocket.close();
        } catch (IOException ignored) {
            // closed
        }
        mSocket = null;
        mOut = null;
    }

    // ------------------------------------------------------------------------------------ frames

    private void readLoop(DataInputStream in) {
        try {
            while (mOpen) {
                int length = in.readInt();
                if (length <= 0 || length > 1 << 20) throw new IOException("bad frame");
                byte[] frame = new byte[length];
                in.readFully(frame);
                handle(frame);
            }
        } catch (Exception e) {
            if (mOpen) mError = "the connection to the TV was lost";
        } finally {
            mOpen = false;
        }
    }

    private void handle(byte[] frame) {
        String source = null;
        String namespace = null;
        String payload = null;
        int p = 0;
        while (p < frame.length) {
            int[] r = {p};
            long key = varint(frame, r);
            int field = (int) (key >> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                varint(frame, r);
            } else if (wire == 2) {
                int n = (int) varint(frame, r);
                String s = new String(frame, r[0], n, StandardCharsets.UTF_8);
                if (field == 2) source = s;
                else if (field == 4) namespace = s;
                else if (field == 6) payload = s;
                r[0] += n;
            } else {
                return;
            }
            p = r[0];
        }
        if (namespace == null || payload == null) return;
        try {
            JSONObject json = new JSONObject(payload);
            String type = json.optString("type");
            if (NS_HEARTBEAT.equals(namespace)) {
                if ("PING".equals(type)) send(NS_HEARTBEAT, source == null ? RECEIVER : source, "{\"type\":\"PONG\"}");
            } else if (NS_RECEIVER.equals(namespace) && "RECEIVER_STATUS".equals(type)) {
                JSONObject status = json.optJSONObject("status");
                if (status == null) return;
                JSONObject volume = status.optJSONObject("volume");
                if (volume != null && volume.has("level")) mVolume = volume.optDouble("level", mVolume);
                JSONArray apps = status.optJSONArray("applications");
                String transport = null;
                if (apps != null) {
                    for (int i = 0; i < apps.length(); i++) {
                        JSONObject app = apps.getJSONObject(i);
                        if (DEFAULT_MEDIA_RECEIVER.equals(app.optString("appId"))) {
                            transport = app.optString("transportId", null);
                            mSessionId = app.optString("sessionId", mSessionId);
                        }
                    }
                }
                if (transport == null && mTransportId != null) mPlayerState = "IDLE";   // the TV closed the player
                mTransportId = transport;
            } else if (NS_MEDIA.equals(namespace)) {
                if ("MEDIA_STATUS".equals(type)) {
                    JSONArray list = json.optJSONArray("status");
                    if (list != null && list.length() > 0) {
                        JSONObject s = list.getJSONObject(0);
                        mMediaSessionId = s.optInt("mediaSessionId", mMediaSessionId);
                        mPlayerState = s.optString("playerState", mPlayerState);
                        if (s.has("currentTime")) mCurrentTime = s.optDouble("currentTime", mCurrentTime);
                        JSONObject media = s.optJSONObject("media");
                        if (media != null && media.has("duration")) mDuration = media.optDouble("duration", mDuration);
                    }
                } else if ("LOAD_FAILED".equals(type) || "INVALID_REQUEST".equals(type) || "LOAD_CANCELLED".equals(type)) {
                    mError = "the TV could not play this video (" + type.toLowerCase().replace('_', ' ') + ")";
                }
            }
        } catch (Exception ignored) {
            // a message that is not for us
        }
    }

    private static long varint(byte[] b, int[] pos) {
        long v = 0;
        int shift = 0;
        while (pos[0] < b.length) {
            int x = b[pos[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return v;
    }

    private void send(String namespace, String destination, String payload) throws IOException {
        OutputStream out = mOut;
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(0x08);
        m.write(0);                                   // protocol_version = CASTV2_1_0
        writeString(m, 0x12, SENDER);                 // source_id
        writeString(m, 0x1A, destination);            // destination_id
        writeString(m, 0x22, namespace);              // namespace
        m.write(0x28);
        m.write(0);                                   // payload_type = STRING
        writeString(m, 0x32, payload);                // payload_utf8
        byte[] body = m.toByteArray();
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        synchronized (mWriteLock) {
            out.write(frame);
            out.flush();
        }
    }

    private static void writeString(ByteArrayOutputStream out, int tag, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(tag);
        int n = b.length;
        while (n >= 0x80) {
            out.write((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        out.write(n);
        out.write(b, 0, b.length);
    }
}
