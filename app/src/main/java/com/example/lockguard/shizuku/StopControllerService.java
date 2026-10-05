package com.example.lockguard.shizuku;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Keep;

import com.example.lockguard.remote.IStopController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class StopControllerService extends IStopController.Stub {
    private static final String TAG = "StopControllerService";

    public StopControllerService() {
    }

    @Keep
    public StopControllerService(Context context) {
        Log.d(TAG, "created with context " + context);
    }

    @Override
    public boolean forceStop(String packageName) throws RemoteException {
        if (!isValidPackageName(packageName)) {
            return false;
        }
        return runForceStop(packageName);
    }

    /** 只允许合法包名字符，防止以 - 开头的参数被 /system/bin/am 解析为 flag。 */
    private static boolean isValidPackageName(String packageName) {
        if (packageName == null || packageName.isEmpty() || packageName.length() > 255) {
            return false;
        }
        for (int i = 0; i < packageName.length(); i++) {
            char c = packageName.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void destroy() {
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    private boolean runForceStop(String packageName) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/am", "force-stop", packageName)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "force-stop failed: " + packageName + " exit=" + exitCode + " output=" + output);
            }
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "force-stop error for " + packageName, e);
            if (process != null) {
                process.destroy();
            }
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

