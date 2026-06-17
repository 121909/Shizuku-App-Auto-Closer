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
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        return runForceStop(packageName);
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

