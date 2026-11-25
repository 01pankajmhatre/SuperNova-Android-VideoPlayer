package com.archos.mediacenter.video.browser.dlna;

import android.os.Build;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** SSDP: announces the DLNA host on the network and answers the searches of DLNA players. */
final class DlnaSsdp {
    private static final String GROUP = "239.255.255.250";
    private static final int PORT = 1900;
    private static final String SERVER = "Android/" + Build.VERSION.RELEASE + " UPnP/1.0 SuperNova/1.0";

    private final String mUuid;
    private final String mLocation;
    private final InetAddress mAddress;
    private final NetworkInterface mInterface;
    private final ScheduledExecutorService mScheduler = Executors.newScheduledThreadPool(2);
    private final Random mRandom = new Random();
    private MulticastSocket mSocket;
    private volatile boolean mRunning;

    /** Types announced: {NT, USN suffix}. */
    private final List<String[]> mTypes = new ArrayList<>();

    DlnaSsdp(String uuid, InetAddress address, NetworkInterface networkInterface, int port) {
        mUuid = uuid;
        mAddress = address;
        mInterface = networkInterface;
        mLocation = "http://" + address.getHostAddress() + ":" + port + "/description.xml";
        mTypes.add(new String[]{"upnp:rootdevice", "uuid:" + uuid + "::upnp:rootdevice"});
        mTypes.add(new String[]{"uuid:" + uuid, "uuid:" + uuid});
        for (String t : new String[]{"urn:schemas-upnp-org:device:MediaServer:1",
                "urn:schemas-upnp-org:service:ContentDirectory:1", "urn:schemas-upnp-org:service:ConnectionManager:1"}) {
            mTypes.add(new String[]{t, "uuid:" + uuid + "::" + t});
        }
    }

    /** The first usable IPv4 address of the device (Wi-Fi / Ethernet), or null. */
    static Object[] findLocalAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || !ni.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return new Object[]{a, ni};
                    }
                }
            }
        } catch (IOException ignored) {
            // no network
        }
        return null;
    }

    void start() throws IOException {
        mSocket = new MulticastSocket(null);
        mSocket.setReuseAddress(true);
        mSocket.bind(new InetSocketAddress(PORT));
        mSocket.setNetworkInterface(mInterface);
        mSocket.joinGroup(new InetSocketAddress(GROUP, PORT), mInterface);
        mRunning = true;

        Thread listener = new Thread(this::listen, "DlnaSsdp");
        listener.setDaemon(true);
        listener.start();

        // Announce three times at the start (UDP is not reliable), then again regularly.
        mScheduler.schedule(this::notifyAlive, 0, TimeUnit.MILLISECONDS);
        mScheduler.schedule(this::notifyAlive, 400, TimeUnit.MILLISECONDS);
        mScheduler.schedule(this::notifyAlive, 1200, TimeUnit.MILLISECONDS);
        mScheduler.scheduleAtFixedRate(this::notifyAlive, 600, 600, TimeUnit.SECONDS);
    }

    void stop() {
        if (!mRunning) return;
        mRunning = false;
        try {
            for (String[] t : mTypes) send(notifyMessage(t, "ssdp:byebye"), InetAddress.getByName(GROUP), PORT);
        } catch (IOException ignored) {
            // leaving anyway
        }
        mScheduler.shutdownNow();
        if (mSocket != null) {
            try {
                mSocket.leaveGroup(new InetSocketAddress(GROUP, PORT), mInterface);
            } catch (IOException ignored) {
                // closing
            }
            mSocket.close();
        }
    }

    private void listen() {
        byte[] buffer = new byte[2048];
        while (mRunning) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                mSocket.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (text.startsWith("M-SEARCH")) answerSearch(text, packet.getAddress(), packet.getPort());
            } catch (IOException e) {
                if (!mRunning) return;
            }
        }
    }

    private static String header(String message, String name) {
        for (String line : message.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    private void answerSearch(String message, final InetAddress to, final int port) {
        final String st = header(message, "ST");
        if (st == null) return;
        int mx = 1;
        try {
            String m = header(message, "MX");
            if (m != null) mx = Math.max(1, Math.min(5, Integer.parseInt(m)));
        } catch (NumberFormatException ignored) {
            // default
        }
        for (final String[] type : mTypes) {
            if (st.equals("ssdp:all") || st.equals(type[0])) {
                long delay = mRandom.nextInt(mx * 1000);
                mScheduler.schedule(() -> {
                    String response = "HTTP/1.1 200 OK\r\n"
                            + "CACHE-CONTROL: max-age=1800\r\n"
                            + "EXT:\r\n"
                            + "LOCATION: " + mLocation + "\r\n"
                            + "SERVER: " + SERVER + "\r\n"
                            + "ST: " + type[0] + "\r\n"
                            + "USN: " + type[1] + "\r\n\r\n";
                    send(response, to, port);
                }, delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    private String notifyMessage(String[] type, String subtype) {
        StringBuilder sb = new StringBuilder("NOTIFY * HTTP/1.1\r\n");
        sb.append("HOST: ").append(GROUP).append(':').append(PORT).append("\r\n");
        sb.append("NT: ").append(type[0]).append("\r\n");
        sb.append("NTS: ").append(subtype).append("\r\n");
        sb.append("USN: ").append(type[1]).append("\r\n");
        if (subtype.equals("ssdp:alive")) {
            sb.append("CACHE-CONTROL: max-age=1800\r\n");
            sb.append("LOCATION: ").append(mLocation).append("\r\n");
            sb.append("SERVER: ").append(SERVER).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private void notifyAlive() {
        try {
            for (String[] t : mTypes) send(notifyMessage(t, "ssdp:alive"), InetAddress.getByName(GROUP), PORT);
        } catch (IOException ignored) {
            // network down: the next round tries again
        }
    }

    private void send(String message, InetAddress to, int port) {
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            MulticastSocket socket = mSocket;
            if (socket != null && !socket.isClosed()) socket.send(new DatagramPacket(data, data.length, to, port));
        } catch (IOException ignored) {
            // lost packet
        }
    }

    String locale() {
        return String.format(Locale.ROOT, "%s", mAddress.getHostAddress());
    }
}
