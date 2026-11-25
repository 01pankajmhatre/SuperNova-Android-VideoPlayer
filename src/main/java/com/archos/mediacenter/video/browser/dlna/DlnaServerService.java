package com.archos.mediacenter.video.browser.dlna;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.archos.mediacenter.video.R;

import java.net.InetAddress;
import java.net.NetworkInterface;

/** Runs the DLNA host (HTTP + SSDP) as a foreground service while it is switched on. */
public class DlnaServerService extends Service {
    public static final String ACTION_START = "com.archos.mediacenter.video.dlna.START";
    public static final String ACTION_STOP = "com.archos.mediacenter.video.dlna.STOP";
    private static final String CHANNEL_ID = "dlna_server";
    private static final int NOTIFICATION_ID = 0xD1A;

    private static volatile boolean sRunning;
    private static volatile String sUrl;
    private static volatile String sError;

    public static boolean isRunning() {
        return sRunning;
    }

    /** Address of the running server, e.g. "http://192.168.1.5:8200", or null. */
    public static String getUrl() {
        return sUrl;
    }

    public static String getError() {
        return sError;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, DlnaServerService.class).setAction(ACTION_START);
        androidx.core.content.ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, DlnaServerService.class).setAction(ACTION_STOP));
    }

    private DlnaHttpServer mHttp;
    private DlnaSsdp mSsdp;
    private WifiManager.MulticastLock mLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(null));
        if (!sRunning) startServer();
        return START_STICKY;
    }

    private void startServer() {
        sError = null;
        DlnaServerPrefs prefs = new DlnaServerPrefs(this);
        Object[] local = DlnaSsdp.findLocalAddress();
        if (local == null) {
            sError = getString(R.string.dlna_error_no_network);
            prefs.setRunningRequested(false);
            stopForeground(true);
            stopSelf();
            return;
        }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                mLock = wifi.createMulticastLock("dlna_server");
                mLock.setReferenceCounted(false);
                mLock.acquire();
            }
            mHttp = new DlnaHttpServer(this, prefs);
            int port = mHttp.start(prefs.getPort());
            mSsdp = new DlnaSsdp(prefs.getUuid(), (InetAddress) local[0], (NetworkInterface) local[1], port);
            mSsdp.start();
            sUrl = "http://" + ((InetAddress) local[0]).getHostAddress() + ":" + port;
            sRunning = true;
            prefs.setRunningRequested(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(sUrl));
        } catch (Exception e) {
            sError = e.getMessage();
            shutdown();
            prefs.setRunningRequested(false);
            stopForeground(true);
            stopSelf();
        }
    }

    private void shutdown() {
        sRunning = false;
        sUrl = null;
        final DlnaSsdp ssdp = mSsdp;
        final DlnaHttpServer http = mHttp;
        mSsdp = null;
        mHttp = null;
        if (mLock != null && mLock.isHeld()) mLock.release();
        mLock = null;
        // Network calls (goodbye announcement, closing sockets) must not run on the main thread.
        if (ssdp != null || http != null) {
            Thread t = new Thread(() -> {
                if (ssdp != null) ssdp.stop();
                if (http != null) http.stop();
            }, "DlnaShutdown");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private Notification buildNotification(String url) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.dlna_notification_channel), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Intent stop = new Intent(this, DlnaServerService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop, flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_dlna_server)
                .setContentTitle(getString(R.string.dlna_server_title))
                .setContentText(url != null ? url : getString(R.string.dlna_status_starting))
                .setOngoing(true)
                .addAction(0, getString(R.string.dlna_stop), stopIntent)
                .build();
    }
}
