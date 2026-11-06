package com.kidslock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 手机控制前台服务：常驻运行，持有 {@link ControlHttpServer}。
 *
 * 独立于计时/锁屏服务，这样即使没在计时、也没锁屏，家长随时都能连上电视。
 * 通知栏会直接显示手机要访问的地址。
 */
public class RemoteControlService extends Service {

    private static final String TAG = "RemoteControlService";
    private static final String CHANNEL_ID = "kids_lock_remote";
    private static final int NOTIFICATION_ID = 1002;
    private static final int[] PORTS = {8080, 8081, 8082, 8083};

    public static final String ACTION_STOP = "com.kidslock.app.ACTION_STOP_REMOTE";

    private PrefManager pref;
    private ControlHttpServer server;
    private String ip;
    private int port;

    /** 启动手机控制服务（兼容各 Android 版本）。 */
    public static void start(Context context) {
        Intent i = new Intent(context, RemoteControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    /** 关闭手机控制服务。 */
    public static void stop(Context context) {
        Intent i = new Intent(context, RemoteControlService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pref = new PrefManager(this);
        createChannel();
        // 5 秒内必须进入前台，先用占位通知，服务器起来后再更新地址
        startForeground(NOTIFICATION_ID, buildNotification(null));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "Stop requested");
            shutdownServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!pref.isRemoteControlEnabled()) {
            Log.i(TAG, "Remote control disabled in settings, stopping");
            shutdownServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 已经跑起来了就只刷新通知，避免重复绑定端口
        if (server == null) {
            startServer();
        }
        updateNotification();
        return START_STICKY;
    }

    private void startServer() {
        ip = ControlHttpServer.getLocalIpAddress();
        for (int p : PORTS) {
            ControlHttpServer candidate = new ControlHttpServer(this, pref, p, ip);
            try {
                candidate.start();
                server = candidate;
                port = p;
                pref.setRemotePort(p);
                Log.i(TAG, "Remote control ready at http://" + ip + ":" + p);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Port " + p + " unavailable: " + e.getMessage());
            }
        }
        Log.e(TAG, "All ports failed, remote control unavailable");
    }

    private void shutdownServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "手机控制", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("手机远程控制电视锁屏的服务状态");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String url) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = url != null
                ? "手机浏览器打开 " + url
                : "正在启动手机控制…";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("手机控制已开启")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        text + "\n需与电视连接同一个 WiFi"))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        String url = (server != null && ip != null)
                ? "http://" + ip + ":" + port
                : "启动失败，请重启电视或稍后再试";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(url));
    }

    @Override
    public void onDestroy() {
        shutdownServer();
        Log.i(TAG, "RemoteControlService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
