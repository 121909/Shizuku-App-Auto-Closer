package com.example.lockguard;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
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
    public static final String ACTION_EXECUTE_STOP = "com.example.lockguard.action.EXECUTE_STOP";
    public static final String ACTION_WINDOW_START = "com.example.lockguard.action.WINDOW_START";

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "lockguard_monitor";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REQ_ALARM_STOP = 2001;
    private static final int REQ_ALARM_WINDOW = 2002;
    private static final long WAKELOCK_TIMEOUT_MS = 15_000L;

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
    private Runnable pendingWindowStart;
    private boolean receiverRegistered;
    private volatile boolean screenLocked;
    /** 标记当前锁屏周期内是否已执行过强行停止，避免同一次锁屏期间无限重复强杀。 */
    private volatile boolean stoppedInCurrentLockSession;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                onUserUnlocked();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                onScreenOn();
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

        if (ACTION_EXECUTE_STOP.equals(action)) {
            handler.post(this::executePendingStopNow);
            return START_STICKY;
        }

        if (ACTION_START.equals(action) || ACTION_RELOAD.equals(action)) {
            // 配置重新加载或重新启动时，允许当前锁屏周期重新评估
            stoppedInCurrentLockSession = false;
            cancelPendingStop();
            cancelWindowStartCheck();
        }

        evaluateCurrentLockState();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        cancelPendingStop();
        cancelWindowStartCheck();
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
        filter.addAction(Intent.ACTION_SCREEN_ON);
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
            stoppedInCurrentLockSession = false;
            evaluateLockedStateAndSchedule();
        });
    }

    private void onScreenOn() {
        handler.post(() -> {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            boolean locked = km != null && km.isKeyguardLocked();
            if (!locked) {
                handleUnlockedInternal();
            }
        });
    }

    private void onUserUnlocked() {
        handler.post(this::handleUnlockedInternal);
    }

    private void handleUnlockedInternal() {
        screenLocked = false;
        stoppedInCurrentLockSession = false;
        cancelWindowStartCheck();
        reloadConfig();
        if (config != null && config.enabled && config.cancelOnUnlock) {
            cancelPendingStop();
        }
        updateNotification();
    }

    private void evaluateCurrentLockState() {
        handler.post(() -> {
            KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
            PowerManager powerManager = getSystemService(PowerManager.class);
            boolean interactive = powerManager == null || powerManager.isInteractive();
            boolean keyguardLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();
            boolean locked = !interactive || keyguardLocked;
            screenLocked = locked;
            if (!locked) {
                stoppedInCurrentLockSession = false;
                cancelWindowStartCheck();
                updateNotification();
                return;
            }
            evaluateLockedStateAndSchedule();
        });
    }

    private void evaluateLockedStateAndSchedule() {
        reloadConfig();
        if (config == null || !config.enabled || !config.hasTarget() || !config.isWindowValid()) {
            updateNotification();
            return;
        }
        if (stoppedInCurrentLockSession) {
            updateNotification();
            return;
        }
        LocalTime now = LocalTime.now();
        if (!config.isWithinWindow(now)) {
            scheduleWindowStartCheck(now, config.startMinuteOfDay);
            updateNotification();
            return;
        }
        cancelWindowStartCheck();
        schedulePendingStop();
        updateNotification();
    }

    private void schedulePendingStop() {
        RuleConfig cfg = config;
        if (cfg == null || !cfg.hasTarget() || stoppedInCurrentLockSession) {
            return;
        }
        long delayMs = Math.max(1, cfg.delayMinutes) * 60L * 1000L;
        Runnable stopTask = this::executePendingStopNow;
        synchronized (lock) {
            if (pendingStop != null) {
                return;
            }
            pendingStop = stopTask;
            handler.postDelayed(stopTask, delayMs);
            scheduleStopAlarm(delayMs);
        }
    }

    private void executePendingStopNow() {
        synchronized (lock) {
            if (pendingStop != null) {
                handler.removeCallbacks(pendingStop);
                pendingStop = null;
            }
            cancelStopAlarm();
        }
        reloadConfig();
        if (config == null || !config.enabled || !config.hasTarget()) {
            updateNotification();
            return;
        }
        if (config.cancelOnUnlock && !screenLocked) {
            updateNotification();
            return;
        }
        // 执行时复查时间窗：与调度时的校验保持一致，窗口外不执行
        LocalTime now = LocalTime.now();
        if (!config.isWithinWindow(now)) {
            if (screenLocked) {
                scheduleWindowStartCheck(now, config.startMinuteOfDay);
            }
            updateNotification();
            return;
        }
        if (!ShizukuRemoteStopper.hasShizukuPermission()) {
            updateNotification();
            return;
        }

        PowerManager.WakeLock wakeLock = acquireTempWakeLock();
        try {
            int successCount = 0;
            int totalCount = config.packageNames.size();
            for (String packageName : config.packageNames) {
                boolean ok = ShizukuRemoteStopper.forceStop(this, packageName);
                if (ok) {
                    successCount++;
                }
                Log.i(TAG, "forceStop(" + packageName + ")=" + ok);
            }
            stoppedInCurrentLockSession = true;
            RuleStore.recordExecution(this, successCount, totalCount);
        } finally {
            releaseTempWakeLock(wakeLock);
        }
        updateNotification();
    }

    /**
     * 若锁屏发生时还未进入设定时间窗（例如 22:50 锁屏，规则为 23:00-07:00），
     * 则精准预约时间窗起点的唤醒检查，替代原先每 60 秒轮询。
     */
    private void scheduleWindowStartCheck(LocalTime now, int startMinuteOfDay) {
        cancelWindowStartCheck();
        int nowSeconds = now.toSecondOfDay();
        int startSeconds = startMinuteOfDay * 60;
        int diffSeconds = startSeconds - nowSeconds;
        if (diffSeconds <= 0) {
            diffSeconds += 24 * 3600;
        }
        long delayMs = (diffSeconds + 1L) * 1000L;
        pendingWindowStart = this::evaluateCurrentLockState;
        handler.postDelayed(pendingWindowStart, delayMs);

        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) {
            long triggerAt = SystemClock.elapsedRealtime() + delayMs;
            am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    getServicePendingIntent(ACTION_WINDOW_START, REQ_ALARM_WINDOW));
        }
    }

    private void cancelWindowStartCheck() {
        if (pendingWindowStart != null) {
            handler.removeCallbacks(pendingWindowStart);
            pendingWindowStart = null;
        }
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(getServicePendingIntent(ACTION_WINDOW_START, REQ_ALARM_WINDOW));
        }
    }

    private void scheduleStopAlarm(long delayMs) {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        PendingIntent pi = getServicePendingIntent(ACTION_EXECUTE_STOP, REQ_ALARM_STOP);
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
    }

    private void cancelStopAlarm() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(getServicePendingIntent(ACTION_EXECUTE_STOP, REQ_ALARM_STOP));
        }
    }

    private PendingIntent getServicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MonitorService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private PowerManager.WakeLock acquireTempWakeLock() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "LockGuard::ForceStopWakeLock");
                wl.setReferenceCounted(false);
                wl.acquire(WAKELOCK_TIMEOUT_MS);
                return wl;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void releaseTempWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private void cancelPendingStop() {
        synchronized (lock) {
            if (pendingStop != null) {
                handler.removeCallbacks(pendingStop);
                pendingStop = null;
            }
            cancelStopAlarm();
        }
    }

    private void stopMonitoring() {
        cancelPendingStop();
        cancelWindowStartCheck();
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
            boolean hasPending;
            synchronized (lock) {
                hasPending = pendingStop != null;
            }
            if (hasPending && screenLocked && config != null && config.enabled && config.hasTarget()) {
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
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
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
        String lastExec = RuleStore.getLastExecutionSummary(this);
        if (lastExec != null) {
            text = text + "\n" + getString(R.string.last_execution_prefix, lastExec);
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
