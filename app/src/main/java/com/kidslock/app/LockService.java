package com.kidslock.app;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * 前台服务，三种工作：
 * 1. 计时模式：每秒检查观看时长，到时间触发锁屏。
 * 2. 锁屏守护模式：检测锁屏状态，必要时拉起 LockScreenActivity。
 * 3. 悬浮窗覆盖层：锁屏时覆盖桌面，拦截孩子操作，引导回到锁屏界面。
 *
 * 悬浮窗是解决 Android 10+ 后台启动 Activity 受限的核心方案：
 * 孩子在桌面看到的不是普通桌面，而是一个半透明覆盖层，
 * 点击/遥控确定后会打开锁屏 Activity（带用户交互，系统不拦截）。
 */
public class LockService extends Service {

    private static final String TAG = "LockService";
    private static final String CHANNEL_ID = "kids_lock_timer";
    private static final int NOTIFICATION_ID = 1001;

    private PrefManager pref;
    private Handler handler;
    private Runnable tickRunnable;
    private Runnable watchdogRunnable;

    private WindowManager windowManager;
    private View overlayView;

    @Override
    public void onCreate() {
        super.onCreate();
        pref = new PrefManager(this);
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createNotificationChannel();
        Log.i(TAG, "LockService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        // 已锁屏 → 进入锁屏守护模式
        if (pref.isLocked()) {
            Log.i(TAG, "Device locked, starting lock watchdog");
            startWatchdog();
            showOverlayIfPermitted();
            return START_STICKY;
        }

        // 计时器未激活 → 停止服务
        if (!pref.isTimerActive()) {
            Log.i(TAG, "Timer not active, stopping service");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 如果已经超时，直接锁屏
        if (pref.isTimerExpired()) {
            Log.i(TAG, "Timer expired, locking now");
            triggerLock();
            return START_STICKY;
        }

        // 开始计时循环
        startTicking();
        return START_STICKY;
    }

    // ==================== 计时模式 ====================

    private void startTicking() {
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                if (pref.isLocked()) {
                    // 已被锁屏接管，切换为守护模式
                    startWatchdog();
                    showOverlayIfPermitted();
                    return;
                }
                if (pref.isTimerExpired()) {
                    triggerLock();
                    return;
                }
                // 更新通知
                updateNotification();
                // 每秒检查
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tickRunnable);
    }

    private void triggerLock() {
        Log.i(TAG, "Triggering lock screen!");
        pref.setLocked(true);
        pref.setHomeAliasEnabled(this, true);
        pref.stopTimer();

        // 尝试直接拉起锁屏界面；在 Android 10+ 上可能被系统阻止，
        // 因此同时启动悬浮窗兜底。
        startLockScreenActivity();
        showOverlayIfPermitted();

        // 转为锁屏守护模式
        startWatchdog();
    }

    // ==================== 锁屏守护模式 ====================

    private void startWatchdog() {
        if (tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
        if (watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!pref.isLocked()) {
                    // 已解锁，守护任务结束
                    Log.i(TAG, "Unlocked, watchdog stopping");
                    hideOverlay();
                    stopForeground(true);
                    stopSelf();
                    return;
                }

                // 如果锁屏界面本身已经跑到前台，就不需要重复拉起，避免刷屏
                if (!isLockScreenOnTop()) {
                    // 只有悬浮窗权限没开、且锁屏不在前台时，才尝试直接 startActivity。
                    // 否则靠悬浮窗拦截即可。
                    if (!canDrawOverlays()) {
                        Log.i(TAG, "Lock screen not on top, trying direct pull");
                        startLockScreenActivity();
                    }
                }

                // 悬浮窗应该一直显示，防止被桌面覆盖
                showOverlayIfPermitted();

                handler.postDelayed(this, 800);
            }
        };
        handler.post(watchdogRunnable);
    }

    private boolean isLockScreenOnTop() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ComponentName top = tasks.get(0).topActivity;
            if (top == null) return false;
            return top.getClassName().contains("LockScreen");
        } catch (Exception e) {
            Log.e(TAG, "getRunningTasks failed", e);
            return false;
        }
    }

    // ==================== 悬浮窗覆盖层 ====================

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    /**
     * 显示全屏悬浮窗覆盖层。锁屏状态下只要权限已开就一直显示，
     * 拦截孩子的所有触摸/按键操作，引导回到锁屏界面。
     */
    private void showOverlayIfPermitted() {
        if (!canDrawOverlays()) {
            Log.d(TAG, "Overlay permission not granted, skip overlay");
            return;
        }
        if (overlayView != null) {
            // 已显示则不再重复添加
            return;
        }

        try {
            overlayView = new LockOverlayView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    // 悬浮窗可聚焦，拦截 TV 遥控器焦点
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.dimAmount = 0.7f;
            windowManager.addView(overlayView, params);
            Log.i(TAG, "Overlay shown");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay", e);
        }
    }

    public void hideOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove overlay", e);
            }
            overlayView = null;
        }
    }

    /**
     * 从悬浮窗点击/按键等用户交互场景启动锁屏 Activity。
     */
    private void startLockScreenActivity() {
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.e(TAG, "Start LockScreenActivity failed", e);
        }
    }

    /**
     * 解锁时由 LockScreenActivity 调用，彻底移除悬浮窗和停止服务。
     */
    public static void requestUnlock(Context context) {
        Intent intent = new Intent(context, LockService.class);
        intent.setAction("com.kidslock.app.ACTION_UNLOCK");
        context.startService(intent);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        if (intent != null && "com.kidslock.app.ACTION_UNLOCK".equals(intent.getAction())) {
            unlockAndStop();
        }
    }

    private void unlockAndStop() {
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        pref.stopTimer();
        pref.resetPinFailures();
        hideOverlay();
        stopForeground(true);
        stopSelf();
    }

    // ==================== 通知 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "儿童锁屏计时",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("观看时间计时通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        String contentText;
        if (pref.isLocked()) {
            contentText = "电视已锁定，答对汉字即可解锁";
        } else {
            long remaining = pref.getRemainingMillis();
            contentText = "剩余观看时间：" + formatTime(remaining);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("儿童锁屏运行中")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void onDestroy() {
        if (tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
        }
        if (watchdogRunnable != null) {
            handler.removeCallbacks(watchdogRunnable);
        }
        hideOverlay();
        Log.i(TAG, "LockService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== 悬浮窗内部视图 ====================

    /**
     * 全屏悬浮窗视图：大字提示 + 解锁按钮。
     * 点击按钮或按遥控器确定键都会打开 LockScreenActivity。
     */
    public class LockOverlayView extends FrameLayout {

        public LockOverlayView(Context context) {
            super(context);
            LayoutInflater.from(context).inflate(R.layout.overlay_lock, this, true);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();

            Button btnUnlock = findViewById(R.id.btnOverlayUnlock);
            if (btnUnlock != null) {
                btnUnlock.setFocusable(true);
                btnUnlock.setFocusableInTouchMode(true);
                btnUnlock.requestFocus();
                btnUnlock.setOnClickListener(v -> startLockScreenActivity());
            }

            // 整个覆盖层点击也打开锁屏（对触屏更友好）
            setOnClickListener(v -> startLockScreenActivity());
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                int keyCode = event.getKeyCode();
                if (keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_SPACE) {
                    startLockScreenActivity();
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
