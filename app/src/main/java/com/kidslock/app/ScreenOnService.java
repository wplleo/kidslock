package com.kidslock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 「亮屏自动开始倒计时」的常驻服务。
 *
 * Android 不允许在 Manifest 里静态注册 {@link Intent#ACTION_SCREEN_ON}，
 * 只能由常驻服务动态注册，所以单独开一个前台服务。它只在开关打开时运行，
 * 只依赖 {@link PrefManager} 和 {@link LockService}，与手机控制完全解耦。
 *
 * 防绕过规则（必须保持）：
 * 1. 已锁屏时亮屏 —— 什么都不做，绝不能变相解锁；
 * 2. 计时中亮屏 —— 不重置倒计时，孩子按电源键息屏/亮屏无法续命；
 * 3. 只有「未锁屏 + 未计时」时亮屏，才开启新一轮倒计时。
 */
public class ScreenOnService extends Service {

    private static final String TAG = "ScreenOnService";
    private static final String CHANNEL_ID = "kids_lock_screen_on";
    private static final int NOTIFICATION_ID = 1003;

    /** 两次亮屏广播（SCREEN_ON / USER_PRESENT）可能连着来，做 2 秒去重。 */
    private static final long TRIGGER_DEBOUNCE_MS = 2000;
    private static volatile long lastTriggerAt = 0L;

    private PrefManager pref;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) return;
            if (!Intent.ACTION_SCREEN_ON.equals(action)
                    && !Intent.ACTION_USER_PRESENT.equals(action)) return;
            // 开关可能刚被关掉、服务还没走完 onDestroy，兜一层
            if (!new PrefManager(context).isScreenOnStart()) return;
            trigger(context, action);
        }
    };

    /** 启动亮屏监听（兼容各 Android 版本）。 */
    public static void start(Context context) {
        Intent i = new Intent(context, ScreenOnService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    /** 关闭亮屏监听。直接 stopService，START_STICKY 不会自动把它拉回来。 */
    public static void stop(Context context) {
        context.stopService(new Intent(context, ScreenOnService.class));
    }

    /**
     * 亮屏的核心动作，抽成静态方法供开机广播、设置页开关、手机端 API 复用。
     * 调用方自行判断是否满足「开关已开」；这里只负责防绕过规则。
     */
    public static void trigger(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MS) return;
        lastTriggerAt = now;

        PrefManager p = new PrefManager(context);

        // 1. 锁屏状态：绝不动作
        if (p.isLocked()) {
            Log.i(TAG, "Screen on while locked, ignore (" + reason + ")");
            return;
        }

        // 2. 计时中：绝不重置。若屏幕熄灭期间服务被系统杀掉且时间已到，
        //    这里补一次拉起让 LockService 执行锁屏，仍然不重置倒计时。
        if (p.isTimerActive()) {
            if (p.isTimerExpired()) {
                Log.i(TAG, "Screen on, timer expired -> resume lock service (" + reason + ")");
                startTimerService(context);
            } else {
                Log.i(TAG, "Screen on while timing, keep countdown (" + reason + ")");
            }
            return;
        }

        // 3. 未计时：开启新一轮倒计时
        int minutes = p.getWatchLimitMinutes();
        Log.i(TAG, "Screen on, start new " + minutes + "min countdown (" + reason + ")");
        p.startTimer(minutes);
        startTimerService(context);
    }

    private static void startTimerService(Context context) {
        Intent i = new Intent(context, LockService.class);
        i.putExtra("action", "start_timer");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable t) {
            // 计时状态已经写进 SharedPreferences，LockService 下次被拉起会自行补上
            Log.w(TAG, "start LockService failed, state is persisted", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pref = new PrefManager(this);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        registerScreenReceiver();
        Log.i(TAG, "ScreenOnService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次进 onStartCommand 都确认在前台，避免 ROM 以 startForegroundService 拉起时超时
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!pref.isScreenOnStart()) {
            Log.i(TAG, "Setting disabled, stopping");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 服务被系统重建后 onCreate 会注册；这里再兜一次，防止注册丢失
        registerScreenReceiver();
        return START_STICKY;
    }

    private void registerScreenReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "register screen receiver failed", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "亮屏自动计时", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("屏幕点亮时自动开始观看倒计时");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("亮屏自动计时已开启")
                .setContentText("息屏后重新亮屏会自动开始倒计时")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        Log.i(TAG, "ScreenOnService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
