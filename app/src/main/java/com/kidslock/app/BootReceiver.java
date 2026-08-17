package com.kidslock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启动接收器。
 * - 如果当前处于锁屏状态 → 直接弹锁屏界面
 * - 如果计时器正在运行 → 恢复计时（检查是否已超时）
 * - 如果启用了开机自启 → 启动计时服务
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Boot received: " + action);
        if (action == null) return;

        boolean isBoot = action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals("android.intent.action.QUICKBOOT_POWERON")
                || action.equals("com.android.intent.action.BOOT_COMPLETED");

        if (!isBoot) return;

        PrefManager pref = new PrefManager(context);

        // 如果已经处于锁定状态，直接弹锁屏
        if (pref.isLocked()) {
            Log.i(TAG, "Device was locked, showing lock screen");
            pref.setHomeAliasEnabled(context, true);
            // 启动守护服务，防止按 HOME / 切换应用绕过锁屏
            startLockService(context);
            Intent lockIntent = new Intent(context, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            context.startActivity(lockIntent);
            return;
        }

        // 如果计时器正在运行
        if (pref.isTimerActive()) {
            if (pref.isTimerExpired()) {
                // 计时已超时 → 锁屏
                Log.i(TAG, "Timer expired during boot, locking");
                pref.setLocked(true);
                pref.setHomeAliasEnabled(context, true);
                pref.stopTimer();
                Intent lockIntent = new Intent(context, LockScreenActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(lockIntent);
            } else {
                // 计时未超时 → 恢复计时服务
                Log.i(TAG, "Timer still running, resuming service");
                startLockService(context);
            }
            return;
        }

        // 开机自启 → 启动计时
        if (pref.isAutoStart()) {
            Log.i(TAG, "Auto start enabled, starting timer");
            int minutes = pref.getWatchLimitMinutes();
            pref.startTimer(minutes);
            startLockService(context);
        }
    }

    private void startLockService(Context context) {
        Intent serviceIntent = new Intent(context, LockService.class);
        serviceIntent.putExtra("action", "start_timer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
