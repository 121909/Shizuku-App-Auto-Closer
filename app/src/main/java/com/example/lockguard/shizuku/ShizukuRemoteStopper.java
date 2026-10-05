package com.example.lockguard.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import com.example.lockguard.BuildConfig;
import com.example.lockguard.remote.IStopController;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public final class ShizukuRemoteStopper {
    private static final Object LOCK = new Object();
    /** 绑定无回调时的超时，超时后允许重新发起绑定，避免永久卡在 binding 状态。 */
    private static final long BIND_TIMEOUT_MS = 8_000L;
    private static IStopController controller;
    private static boolean bound;
    private static boolean binding;
    private static long bindStartedAt;
    private static CountDownLatch connectedLatch;

    private static final Shizuku.UserServiceArgs ARGS = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfig.APPLICATION_ID, StopControllerService.class.getName()))
            .daemon(false)
            .tag("stop-controller")
            .version(1)
            .processNameSuffix("stop-controller")
            .debuggable(BuildConfig.DEBUG);

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                controller = IStopController.Stub.asInterface(service);
                bound = true;
                binding = false;
                if (connectedLatch != null) {
                    connectedLatch.countDown();
                    connectedLatch = null;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                controller = null;
                bound = false;
                binding = false;
                if (connectedLatch != null) {
                    connectedLatch.countDown();
                    connectedLatch = null;
                }
            }
        }
    };

    private ShizukuRemoteStopper() {}

    public static boolean ensureBound(Context context) {
        if (!hasShizukuPermission()) {
            return false;
        }
        synchronized (LOCK) {
            if (bound) {
                return true;
            }
            if (binding && SystemClock.elapsedRealtime() - bindStartedAt < BIND_TIMEOUT_MS) {
                return true;
            }
            binding = true;
            bindStartedAt = SystemClock.elapsedRealtime();
            connectedLatch = new CountDownLatch(1);
        }
        try {
            Shizuku.bindUserService(ARGS, CONNECTION);
            return true;
        } catch (Throwable t) {
            synchronized (LOCK) {
                binding = false;
                if (connectedLatch != null) {
                    connectedLatch.countDown();
                    connectedLatch = null;
                }
            }
            return false;
        }
    }

    public static boolean forceStop(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (!ensureBound(context)) {
            return false;
        }
        IStopController local = getController();
        if (local == null) {
            waitForConnection();
            local = getController();
        }
        if (local == null) {
            return false;
        }
        try {
            return local.forceStop(packageName);
        } catch (RemoteException e) {
            // 远端服务可能已死但回调未到，主动清理以便下次重新绑定
            synchronized (LOCK) {
                controller = null;
                bound = false;
            }
            return false;
        }
    }

    private static IStopController getController() {
        synchronized (LOCK) {
            return controller;
        }
    }

    private static void waitForConnection() {
        CountDownLatch latch;
        synchronized (LOCK) {
            latch = connectedLatch;
        }
        if (latch == null) {
            return;
        }
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                // 超时后清空，避免后续每个包都重复等待 5 秒
                synchronized (LOCK) {
                    if (connectedLatch == latch) {
                        connectedLatch = null;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void release(boolean remove) {
        synchronized (LOCK) {
            controller = null;
            bound = false;
            binding = false;
            bindStartedAt = 0;
        }
        try {
            Shizuku.unbindUserService(ARGS, CONNECTION, remove);
        } catch (Throwable ignore) {
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}
