package com.archos.mediacenter.video.browser.dlna;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Volume of an LG webOS TV through its own remote-control service (the same one the LG phone app uses).
 * Unlike the DLNA volume command, this one also reaches a soundbar connected over HDMI ARC.
 * The first time, the TV shows a "allow this device" question that must be accepted with the TV remote.
 * Every method blocks: call it from a background thread.
 */
public final class DlnaTvRemote {
    private static final String PREFS = "lg_tv_remote";

    private final SharedPreferences mPrefs;
    private final String mIp;

    public DlnaTvRemote(Context context, String ip) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mIp = ip;
    }

    /**
     * @param onPairingQuestion called when the TV asks to allow this device (accept it with the TV remote)
     * @return null when the TV changed its volume, else why it did not
     */
    public String volumeStep(boolean up, Runnable onPairingQuestion) {
        String error = send("ws://" + mIp + ":3000", false, up, onPairingQuestion);
        if (error == null) return null;
        String secure = send("wss://" + mIp + ":3001", true, up, onPairingQuestion);
        return secure == null ? null : error;
    }

    private String send(String url, boolean secure, boolean up, Runnable onPairingQuestion) {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] result = {"no answer"};
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS);
        if (secure) trustTv(builder);
        OkHttpClient client = builder.build();
        WebSocket socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                ws.send(registerMessage());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    String type = m.optString("type");
                    JSONObject payload = m.optJSONObject("payload");
                    if ("registered".equals(type)) {
                        if (payload != null && payload.has("client-key")) {
                            mPrefs.edit().putString(mIp, payload.getString("client-key")).apply();
                        }
                        ws.send("{\"id\":\"vol\",\"type\":\"request\",\"uri\":\"ssap://audio/"
                                + (up ? "volumeUp" : "volumeDown") + "\"}");
                    } else if ("response".equals(type) && "register_0".equals(m.optString("id"))) {
                        if (payload != null && "PROMPT".equals(payload.optString("pairingType")) && onPairingQuestion != null) {
                            onPairingQuestion.run();
                        }
                    } else if ("response".equals(type) && "vol".equals(m.optString("id"))) {
                        boolean ok = payload == null || payload.optBoolean("returnValue", true);
                        result[0] = ok ? null : "the TV refused the volume command";
                        done.countDown();
                    } else if ("error".equals(type)) {
                        result[0] = m.optString("error", "TV error");
                        done.countDown();
                    }
                } catch (Exception e) {
                    result[0] = e.toString();
                    done.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                result[0] = t.toString();
                done.countDown();
            }
        });
        try {
            // the first time the TV waits for the question to be accepted: allow 40 s
            if (!done.await(40, TimeUnit.SECONDS)) result[0] = "the TV did not answer (was the pairing question accepted?)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        socket.close(1000, null);
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        return result[0];
    }

    private String registerMessage() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            String key = mPrefs.getString(mIp, null);
            if (key != null) payload.put("client-key", key);
            JSONObject manifest = new JSONObject();
            manifest.put("manifestVersion", 1);
            manifest.put("permissions", new JSONArray().put("CONTROL_AUDIO").put("LAUNCH").put("CONTROL_INPUT_MEDIA_PLAYBACK"));
            payload.put("manifest", manifest);
            return new JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** The TV uses a self-signed certificate on its secure port: accepted for the TV's own address only. */
    private void trustTv(OkHttpClient.Builder builder) {
        try {
            final X509TrustManager all = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{all}, new SecureRandom());
            builder.sslSocketFactory(ssl.getSocketFactory(), all);
            builder.hostnameVerifier((host, session) -> host.equals(mIp));
        } catch (Exception ignored) {
            // the secure port then simply fails
        }
    }
}
