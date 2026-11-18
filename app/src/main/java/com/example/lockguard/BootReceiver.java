package com.example.lockguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.lockguard.data.RuleConfig;
import com.example.lockguard.data.RuleStore;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        RuleConfig config = RuleStore.load(context);
        if (config.enabled && config.hasTarget()) {
            Log.i(TAG, "Auto-starting MonitorService on " + action);
            Intent serviceIntent = new Intent(context, MonitorService.class)
                    .setAction(MonitorService.ACTION_RELOAD);
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to start MonitorService on " + action, t);
            }
        }
    }
}
