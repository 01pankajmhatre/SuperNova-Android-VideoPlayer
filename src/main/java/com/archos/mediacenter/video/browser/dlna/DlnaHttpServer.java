package com.archos.mediacenter.video.browser.dlna;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal HTTP server of the DLNA host: device description, ContentDirectory / ConnectionManager
 * (SOAP), and streaming of the shared files with Range support.
 */
final class DlnaHttpServer {
    private static final String CD = "urn:schemas-upnp-org:service:ContentDirectory:1";
    private static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String DLNA_FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

    private final Context mContext;
    private final DlnaServerPrefs mPrefs;
    private final ExecutorService mPool = Executors.newCachedThreadPool();
    private ServerSocket mServer;
    private volatile boolean mRunning;
    private volatile int mUpdateId = 1;

    DlnaHttpServer(Context context, DlnaServerPrefs prefs) {
        mContext = context.getApplicationContext();
        mPrefs = prefs;
    }

    /** Starts listening; returns the port really used (the wanted one, or a free one if it is taken). */
    int start(int wantedPort) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(wantedPort));
        } catch (IOException busy) {
            socket.close();
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
        }
        mServer = socket;
        mRunning = true;
        mPool.execute(() -> {
            while (mRunning) {
                try {
                    final Socket client = mServer.accept();
                    mPool.execute(() -> handle(client));
                } catch (IOException e) {
                    if (!mRunning) return;
                }
            }
        });
        return socket.getLocalPort();
    }

    void stop() {
        mRunning = false;
        try {
            if (mServer != null) mServer.close();
        } catch (IOException ignored) {
            // closing
        }
        mPool.shutdownNow();
    }

    void bumpUpdateId() {
        mUpdateId++;
    }

    // --------------------------------------------------------------------------------- requests

    private static final class Request {
        String method;
        String path;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
        String client;          // IP address of the device that sent the request
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (mRunning) {
                Request request = readRequest(in);
                if (request == null) return;
                request.client = s.getInetAddress() == null ? null : s.getInetAddress().getHostAddress();
                boolean keepAlive = route(request, out);
                out.flush();
                if (!keepAlive) return;
            }
        } catch (IOException ignored) {
            // client went away
        } catch (RuntimeException ignored) {
            // malformed request
        }
    }

    private Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;      // the last four bytes read, to spot the blank line that ends the headers
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) break;       // \r\n\r\n
            if (head.size() > 32768) return null;
        }
        String[] lines = head.toString("UTF-8").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.ROOT);
        r.path = first[1];
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        String length = r.headers.get("content-length");
        if (length != null) {
            int n = Integer.parseInt(length);
            byte[] body = new byte[n];
            int read = 0;
            while (read < n) {
                int c = in.read(body, read, n - read);
                if (c < 0) break;
                read += c;
            }
            r.body = new String(body, 0, read, StandardCharsets.UTF_8);
        }
        return r;
    }

    /** Answers a request. Returns true if the connection may be reused. */
    private boolean route(Request r, OutputStream out) throws IOException {
        String path = r.path;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        String host = r.headers.get("host");

        if (path.equals("/description.xml")) return sendText(out, r, 200, "text/xml; charset=\"utf-8\"", description());
        if (path.equals("/cd.xml")) return sendText(out, r, 200, "text/xml; charset=\"utf-8\"", CD_SCPD);
        if (path.equals("/cm.xml")) return sendText(out, r, 200, "text/xml; charset=\"utf-8\"", CM_SCPD);
        if (path.equals("/cd/event") || path.equals("/cm/event")) {
            if (r.method.equals("SUBSCRIBE") || r.method.equals("UNSUBSCRIBE")) {
                String headers = "SID: uuid:" + mPrefs.getUuid() + "-sub\r\nTIMEOUT: Second-1800\r\n";
                return sendRaw(out, 200, "OK", headers, null, 0, false);
            }
        }
        if (path.equals("/cd/control") && r.method.equals("POST")) return sendSoap(out, r, contentDirectory(r, host));
        if (path.equals("/cm/control") && r.method.equals("POST")) return sendSoap(out, r, connectionManager(r));
        if (path.startsWith("/media/") || path.startsWith("/cast/")) {
            try {
                return streamMedia(out, r, path);
            } finally {
                if (!"HEAD".equals(r.method)) DlnaNowPlaying.end(r.client);
            }
        }
        return sendText(out, r, 404, "text/plain", "Not found");
    }

    // ------------------------------------------------------------------------------ descriptions

    private String description() {
        String name = xml(mPrefs.getName());
        String uuid = mPrefs.getUuid();
        return "<?xml version=\"1.0\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>"
                + "<friendlyName>" + name + "</friendlyName>"
                + "<manufacturer>SuperNova</manufacturer>"
                + "<modelName>SuperNova DLNA Server</modelName>"
                + "<modelNumber>1</modelNumber>"
                + "<UDN>uuid:" + uuid + "</UDN>"
                + "<serviceList>"
                + "<service><serviceType>" + CD + "</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>"
                + "<SCPDURL>/cd.xml</SCPDURL><controlURL>/cd/control</controlURL><eventSubURL>/cd/event</eventSubURL></service>"
                + "<service><serviceType>" + CM + "</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>"
                + "<SCPDURL>/cm.xml</SCPDURL><controlURL>/cm/control</controlURL><eventSubURL>/cm/event</eventSubURL></service>"
                + "</serviceList></device></root>";
    }

    private static final String SCPD_HEAD = "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
            + "<specVersion><major>1</major><minor>0</minor></specVersion>";

    private static String arg(String name, String direction, String variable) {
        return "<argument><name>" + name + "</name><direction>" + direction + "</direction>"
                + "<relatedStateVariable>" + variable + "</relatedStateVariable></argument>";
    }

    private static final String CD_SCPD = SCPD_HEAD + "<actionList>"
            + "<action><name>GetSearchCapabilities</name><argumentList>" + arg("SearchCaps", "out", "SearchCapabilities") + "</argumentList></action>"
            + "<action><name>GetSortCapabilities</name><argumentList>" + arg("SortCaps", "out", "SortCapabilities") + "</argumentList></action>"
            + "<action><name>GetSystemUpdateID</name><argumentList>" + arg("Id", "out", "SystemUpdateID") + "</argumentList></action>"
            + "<action><name>Browse</name><argumentList>"
            + arg("ObjectID", "in", "A_ARG_TYPE_ObjectID") + arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag")
            + arg("Filter", "in", "A_ARG_TYPE_Filter") + arg("StartingIndex", "in", "A_ARG_TYPE_Index")
            + arg("RequestedCount", "in", "A_ARG_TYPE_Count") + arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria")
            + arg("Result", "out", "A_ARG_TYPE_Result") + arg("NumberReturned", "out", "A_ARG_TYPE_Count")
            + arg("TotalMatches", "out", "A_ARG_TYPE_Count") + arg("UpdateID", "out", "A_ARG_TYPE_UpdateID")
            + "</argumentList></action></actionList><serviceStateTable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"yes\"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>"
            + "</serviceStateTable></scpd>";

    private static final String CM_SCPD = SCPD_HEAD + "<actionList>"
            + "<action><name>GetProtocolInfo</name><argumentList>" + arg("Source", "out", "SourceProtocolInfo")
            + arg("Sink", "out", "SinkProtocolInfo") + "</argumentList></action>"
            + "<action><name>GetCurrentConnectionInfo</name><argumentList>" + arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID")
            + arg("RcsID", "out", "A_ARG_TYPE_RcsID") + arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID")
            + arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo") + arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager")
            + arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID") + arg("Direction", "out", "A_ARG_TYPE_Direction")
            + arg("Status", "out", "A_ARG_TYPE_ConnectionStatus") + "</argumentList></action>"
            + "<action><name>GetCurrentConnectionIDs</name><argumentList>" + arg("ConnectionIDs", "out", "CurrentConnectionIDs") + "</argumentList></action>"
            + "</actionList><serviceStateTable>"
            + "<stateVariable sendEvents=\"yes\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"yes\"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType></stateVariable>"
            + "<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType></stateVariable>"
            + "</serviceStateTable></scpd>";

    // -------------------------------------------------------------------------------- SOAP

    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + "[^>]*>(.*?)</" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? unxml(m.group(1)) : null;
    }

    private static String soapAction(Request r) {
        String action = r.headers.get("soapaction");
        if (action == null) return "";
        action = action.replace("\"", "");
        int hash = action.indexOf('#');
        return hash >= 0 ? action.substring(hash + 1) : action;
    }

    private static String envelope(String service, String action, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + "Response xmlns:u=\"" + service + "\">" + inner + "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    private String connectionManager(Request r) {
        String action = soapAction(r);
        switch (action) {
            case "GetProtocolInfo":
                return envelope(CM, action, "<Source>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/x-msvideo:*,"
                        + "http-get:*:video/mpeg:*,http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:image/jpeg:*</Source><Sink></Sink>");
            case "GetCurrentConnectionIDs":
                return envelope(CM, action, "<ConnectionIDs>0</ConnectionIDs>");
            case "GetCurrentConnectionInfo":
                return envelope(CM, action, "<RcsID>-1</RcsID><AVTransportID>-1</AVTransportID><ProtocolInfo></ProtocolInfo>"
                        + "<PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID>"
                        + "<Direction>Output</Direction><Status>OK</Status>");
            default:
                return envelope(CM, action, "");
        }
    }

    private String contentDirectory(Request r, String host) {
        String action = soapAction(r);
        switch (action) {
            case "GetSearchCapabilities":
                return envelope(CD, action, "<SearchCaps></SearchCaps>");
            case "GetSortCapabilities":
                return envelope(CD, action, "<SortCaps></SortCaps>");
            case "GetSystemUpdateID":
                return envelope(CD, action, "<Id>" + mUpdateId + "</Id>");
            case "Browse":
                return browse(r, host);
            default:
                return envelope(CD, action, "");
        }
    }

    private String browse(Request r, String host) {
        String objectId = tag(r.body, "ObjectID");
        String flag = tag(r.body, "BrowseFlag");
        int start = parseInt(tag(r.body, "StartingIndex"), 0);
        int count = parseInt(tag(r.body, "RequestedCount"), 0);
        if (objectId == null) objectId = DlnaMediaTree.ROOT_ID;

        DlnaMediaTree tree = new DlnaMediaTree(mContext, mPrefs.getFolders(), mPrefs.isFilesInRoot());
        List<DlnaMediaTree.Node> nodes;
        int total;
        if ("BrowseMetadata".equals(flag)) {
            DlnaMediaTree.Node node = tree.get(objectId);
            nodes = new java.util.ArrayList<>();
            if (node != null) nodes.add(node);
            total = nodes.size();
        } else {
            List<DlnaMediaTree.Node> all = tree.children(objectId);
            total = all.size();
            int from = Math.min(start, total);
            int to = count <= 0 ? total : Math.min(total, from + count);
            nodes = all.subList(from, to);
        }

        StringBuilder didl = new StringBuilder("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" "
                + "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">");
        for (DlnaMediaTree.Node n : nodes) {
            if (n.container) {
                didl.append("<container id=\"").append(xml(n.id)).append("\" parentID=\"").append(xml(n.parentId == null ? "0" : n.parentId))
                        .append("\" restricted=\"1\" searchable=\"0\"><dc:title>").append(xml(n.title))
                        .append("</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>");
            } else {
                String upnpClass = n.mediaKind == 1 ? "object.item.videoItem"
                        : n.mediaKind == 2 ? "object.item.audioItem.musicTrack" : "object.item.imageItem.photo";
                String url = "http://" + (host != null ? host : "localhost") + "/media/" + opaqueId(n.id) + "/"
                        + android.net.Uri.encode(n.fileName != null ? n.fileName : n.title);
                didl.append("<item id=\"").append(xml(n.id)).append("\" parentID=\"").append(xml(n.parentId == null ? "0" : n.parentId))
                        .append("\" restricted=\"1\"><dc:title>").append(xml(n.title)).append("</dc:title><upnp:class>")
                        .append(upnpClass).append("</upnp:class><res protocolInfo=\"http-get:*:").append(xml(n.mime)).append(':')
                        .append(DLNA_FLAGS).append('"');
                if (n.size >= 0) didl.append(" size=\"").append(n.size).append('"');
                didl.append('>').append(xml(url)).append("</res></item>");
            }
        }
        didl.append("</DIDL-Lite>");
        return envelope(CD, "Browse", "<Result>" + xml(didl.toString()) + "</Result><NumberReturned>" + nodes.size()
                + "</NumberReturned><TotalMatches>" + total + "</TotalMatches><UpdateID>" + mUpdateId + "</UpdateID>");
    }

    // -------------------------------------------------------------------------------- media

    /** The object id as one URL-safe token ("~" + base64): ids hold "/" and "%", which some players re-encode or normalise. */
    private static String opaqueId(String id) {
        return "~" + android.util.Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static String idFromUrl(String token) {
        if (token.startsWith("~")) {
            try {
                return new String(android.util.Base64.decode(token.substring(1),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return android.net.Uri.decode(token);   // links made by older versions
    }

    private boolean streamMedia(OutputStream out, Request r, String path) throws IOException {
        // /media/<id>/<name>
        String rest = path.substring(path.indexOf('/', 1) + 1);
        int slash = rest.indexOf('/');
        String id = idFromUrl(slash >= 0 ? rest.substring(0, slash) : rest);
        DlnaMediaTree.Node node = path.startsWith("/cast/") ? DlnaCast.get(id)
                : new DlnaMediaTree(mContext, mPrefs.getFolders()).get(id);
        final boolean cast = path.startsWith("/cast/");
        if (cast) DlnaCast.onRequest(r.method, r.headers.get("range"), node != null);
        if (node == null || node.container || node.documentUri == null) return sendText(out, r, 404, "text/plain", "Not found");
        if (!"HEAD".equals(r.method)) DlnaNowPlaying.begin(r.client, node.title, false, 0);

        ParcelFileDescriptor pfd;
        try {
            pfd = mContext.getContentResolver().openFileDescriptor(node.documentUri, "r");
        } catch (Exception e) {
            pfd = null;
        }
        if (pfd == null) return sendText(out, r, 404, "text/plain", "Not found");
        try (ParcelFileDescriptor file = pfd; FileInputStream fis = new FileInputStream(file.getFileDescriptor())) {
            long size = file.getStatSize() >= 0 ? file.getStatSize() : node.size;
            long start = 0, end = size - 1;
            boolean partial = false;
            String range = r.headers.get("range");
            if (range != null && range.startsWith("bytes=") && size > 0) {
                String[] parts = range.substring(6).split("-", 2);
                try {
                    if (!parts[0].isEmpty()) {
                        start = Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                    } else if (parts.length > 1 && !parts[1].isEmpty()) {   // last N bytes
                        start = Math.max(0, size - Long.parseLong(parts[1]));
                    }
                    partial = true;
                } catch (NumberFormatException ignored) {
                    start = 0;
                }
                if (start >= size) return sendRaw(out, 416, "Range Not Satisfiable", "Content-Range: bytes */" + size + "\r\n", null, 0, false);
            }
            long length = size > 0 ? end - start + 1 : 0;
            StringBuilder h = new StringBuilder();
            h.append("Content-Type: ").append(node.mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("transferMode.dlna.org: Streaming\r\n");
            h.append("contentFeatures.dlna.org: ").append(DLNA_FLAGS).append("\r\n");
            if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
            writeHead(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", h.toString(), length, false);
            if (r.method.equals("HEAD") || length <= 0) return true;

            FileChannel channel = fis.getChannel();
            channel.position(start);
            byte[] buffer = new byte[64 * 1024];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buffer);
            long remaining = length;
            while (remaining > 0 && mRunning) {
                bb.clear();
                bb.limit((int) Math.min(buffer.length, remaining));
                int n = channel.read(bb);
                if (n < 0) break;
                out.write(buffer, 0, n);
                if (cast) DlnaCast.sent(n);
                remaining -= n;
            }
            return false;       // the client may have stopped reading; close the connection
        }
    }

    // -------------------------------------------------------------------------------- output

    private boolean sendSoap(OutputStream out, Request r, String xml) throws IOException {
        return sendText(out, r, 200, "text/xml; charset=\"utf-8\"", xml);
    }

    private boolean sendText(OutputStream out, Request r, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        return sendRaw(out, code, code == 200 ? "OK" : "Error", "Content-Type: " + type + "\r\n",
                r.method.equals("HEAD") ? null : data, data.length, true);
    }

    private boolean sendRaw(OutputStream out, int code, String reason, String headers, byte[] body, long length, boolean keepAlive) throws IOException {
        writeHead(out, code, reason, headers, length, keepAlive);
        if (body != null) out.write(body);
        return keepAlive;
    }

    private void writeHead(OutputStream out, int code, String reason, String headers, long length, boolean keepAlive) throws IOException {
        java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        date.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Server: Android UPnP/1.0 SuperNova/1.0\r\n"
                + "Date: " + date.format(new java.util.Date()) + "\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n"
                + "EXT:\r\n"
                + headers
                + "Content-Length: " + length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------------- helpers

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unxml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
