package com.nanodesk.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Фоновый режим: пока идёт сеанс, приложение работает как служба переднего плана.
 * Система не усыпляет сеть и процесс, поэтому можно свернуть NanoDesk и сеанс не оборвётся.
 */
public final class SessionService extends Service {
    private static final String CH = "session", ACT_STOP = "com.nanodesk.mobile.STOP";
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifi;
    private final Link.StateListener sl = new Link.StateListener() {
        public void onState(int state, String message, int error) {
            if (state == Link.IDLE) { stopSelf(); return; }
            update();
        }
    };

    public IBinder onBind(Intent i) { return null; }

    public void onCreate() {
        super.onCreate();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nanodesk:session");
            wl.setReferenceCounted(false);
            wl.acquire(6 * 60 * 60 * 1000L);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nanodesk:wifi");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        } catch (Exception ignore) { }
    }

    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACT_STOP.equals(i.getAction())) {
            Link.I.disconnect();
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Link.I.addStateListener(sl);
        return START_NOT_STICKY;
    }

    private void update() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(7, build()); } catch (Exception ignore) { }
    }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "Сеанс удалённого доступа", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(this, SessionActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent po = PendingIntent.getActivity(this, 0, open, pf);
        Intent stop = new Intent(this, SessionService.class).setAction(ACT_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, pf);
        String name = Link.I.hostName.length() > 0 ? Link.I.hostName : (Link.I.dev != null ? Link.I.dev.name : "компьютер");
        String text = Link.I.state == Link.RECONNECTING ? "Восстанавливаю связь…" : "Сеанс идёт — нажмите, чтобы вернуться";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("NanoDesk — " + name).setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(po).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", ps).build());
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PRIVATE);
        return b.build();
    }

    public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignore) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignore) { }
        Link.I.removeStateListener(sl);
        super.onDestroy();
    }
}
