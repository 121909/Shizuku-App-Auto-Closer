package com.example.lockguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.lockguard.data.RuleConfig;
import com.example.lockguard.data.RuleStore;
import com.example.lockguard.shizuku.ShizukuRemoteStopper;

import java.time.LocalTime;
import java.util.Locale;

public final class MonitorService extends Service {
    public static final String ACTION_START = "com.example.lockguard.action.START";
    public static final String ACTION_STOP = "com.example.lockguard.action.STOP";
    public static final String ACTION_RELOAD = "com.example.lockguard.action.RELOAD";

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "lockguard_monitor";
    private static final int NOTIFICATION_ID = 1001;

    /** 服务存活标志，供同进程内的 Activity 读取（volatile：主线程读、服务生命周期线程写）。 */
    private static volatile boolean active;

    public static boolean isActive() {
        return active;
    }

    private final Object lock = new Object();
    private HandlerThread handlerThread;
    private Handler handler;
    private volatile RuleConfig config;
    private Runnable pendingStop;
    private Runnable periodicCheck;
    private boolean receiverRegistered;
    private volatile boolean screenLocked;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                onUserUnlocked();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        active = true;
        handlerThread = new HandlerThread("LockGuardMonitor");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        registerScreenReceiver();
        startAsForeground(getString(R.string.monitor_preparing));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_RELOAD;
        if (ACTION_STOP.equals(action)) {
            stopMonitoring();
            return START_NOT_STICKY;
        }

        reloadConfig();
        if (config == null || !config.enabled) {
            stopMonitoring();
            return START_NOT_STICKY;
        }

        ShizukuRemoteStopper.ensureBound(this);
        ensurePeriodicCheck();
        evaluateCurrentLockState();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        cancelPendingStop();
        cancelPeriodicCheck();
        unregisterScreenReceiver();
        ShizukuRemoteStopper.release(true);
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void reloadConfig() {
        config = RuleStore.load(this);
    }

    private void registerScreenReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignore) {
        }
        receiverRegistered = false;
    }

    private void onScreenOff() {
        handler.post(() -> {
            screenLocked = true;
            reloadConfig();
            if (config == null || !config.enabled || !config.hasTarget() || !config.isWindowValid()) {
                return;
            }
            if (!config.isWithinWindow(LocalTime.now())) {
                updateNotification();
                return;
            }
            if (!ShizukuRemoteStopper.hasShizukuPermission()) {
                updateNotification();
                return;
            }
            schedulePendingStop();
            updateNotification();
        });
    }

    private void onUserUnlocked() {
        handler.post(() -> {
            screenLocked = false;
            reloadConfig();
            if (config != null && config.enabled && config.cancelOnUnlock) {
                cancelPendingStop();
            }
            updateNotification();
        });
    }

    private void evaluateCurrentLockState() {
        handler.post(() -> {
            KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
            boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
            screenLocked = locked;
            if (!locked) {
                return;
            }
            reloadConfig();
            if (config == null || !config.enabled || !config.hasTarget() || !config.isWindowValid()) {
                return;
            }
            if (!config.isWithinWindow(LocalTime.now())) {
                return;
            }
            if (!ShizukuRemoteStopper.hasShizukuPermission()) {
                return;
            }
            schedulePendingStop();
        });
    }

    private void schedulePendingStop() {
        RuleConfig cfg = config;
        if (cfg == null || !cfg.hasTarget()) {
            return;
        }
        long delayMs = Math.max(1, cfg.delayMinutes) * 60L * 1000L;
        Runnable stopTask = () -> {
            synchronized (lock) {
                pendingStop = null;
            }
            reloadConfig();
            if (config == null || !config.enabled || !config.hasTarget()) {
                updateNotification();
                return;
            }
            // 执行时复查时间窗：与调度时的校验保持一致，窗口外不执行
            if (!config.isWithinWindow(LocalTime.now())) {
                updateNotification();
                return;
            }
            for (String packageName : config.packageNames) {
                boolean ok = ShizukuRemoteStopper.forceStop(this, packageName);
                Log.i(TAG, "forceStop(" + packageName + ")=" + ok);
            }
            updateNotification();
        };
        // 检查、赋值、注册必须在同一临界区内，避免与主线程的 cancelPendingStop 竞态
        synchronized (lock) {
            if (pendingStop != null) {
                return;
            }
            pendingStop = stopTask;
            handler.postDelayed(stopTask, delayMs);
        }
    }

    private void ensurePeriodicCheck() {
        cancelPeriodicCheck();
        periodicCheck = new Runnable() {
            @Override
            public void run() {
                reloadConfig();
                if (config == null || !config.enabled) {
                    return;
                }
                evaluateCurrentLockState();
                handler.postDelayed(this, 60_000L);
            }
        };
        handler.postDelayed(periodicCheck, 60_000L);
    }

    private void cancelPeriodicCheck() {
        if (periodicCheck != null) {
            handler.removeCallbacks(periodicCheck);
            periodicCheck = null;
        }
    }

    private void cancelPendingStop() {
        synchronized (lock) {
            if (pendingStop != null) {
                handler.removeCallbacks(pendingStop);
                pendingStop = null;
            }
        }
    }

    private void stopMonitoring() {
        cancelPendingStop();
        cancelPeriodicCheck();
        stopForeground(true);
        stopSelf();
    }

    private void startAsForeground(String status) {
        createChannel();
        Notification notification = buildNotification(status);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            String status = buildStatusText();
            if (screenLocked && config != null && config.enabled && config.hasTarget() && config.isWithinWindow(LocalTime.now())) {
                status = status + " | " + getString(R.string.monitor_waiting_stop);
            }
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    private Notification buildNotification(String status) {
        String title = getString(R.string.monitor_notification_title);
        String text = status == null ? buildStatusText() : status;
        Intent openApp = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String buildStatusText() {
        RuleConfig cfg = RuleStore.load(this);
        if (!cfg.enabled) {
            return getString(R.string.monitor_status_closed);
        }
        String text = String.format(Locale.getDefault(), getString(R.string.monitor_status_format),
                cfg.formatTargets(),
                cfg.formatWindow(),
                cfg.formatDelay(),
                cfg.cancelOnUnlock ? "是" : "否");
        if (!ShizukuRemoteStopper.hasShizukuPermission()) {
            text = text + " | " + getString(R.string.monitor_shizuku_missing);
        }
        return text;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }
}
