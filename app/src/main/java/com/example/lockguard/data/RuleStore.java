package com.example.lockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class RuleStore {
    private static final String PREFS = "lockguard_rules";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_LABEL = "label";
    private static final String KEY_START = "start";
    private static final String KEY_END = "end";
    private static final String KEY_DELAY = "delay";
    private static final String KEY_CANCEL = "cancel";

    private RuleStore() {}

    public static RuleConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new RuleConfig(
                prefs.getBoolean(KEY_ENABLED, false),
                prefs.getString(KEY_PACKAGE, ""),
                prefs.getString(KEY_LABEL, ""),
                clampMinute(prefs.getInt(KEY_START, 23 * 60)),
                clampMinute(prefs.getInt(KEY_END, 7 * 60)),
                Math.max(1, prefs.getInt(KEY_DELAY, 10)),
                prefs.getBoolean(KEY_CANCEL, true)
        );
    }

    public static void save(Context context, RuleConfig config) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_PACKAGE, config.packageName)
                .putString(KEY_LABEL, config.appLabel)
                .putInt(KEY_START, clampMinute(config.startMinuteOfDay))
                .putInt(KEY_END, clampMinute(config.endMinuteOfDay))
                .putInt(KEY_DELAY, Math.max(1, config.delayMinutes))
                .putBoolean(KEY_CANCEL, config.cancelOnUnlock)
                .apply();
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    private static int clampMinute(int minute) {
        if (minute < 0) return 0;
        if (minute >= 1440) return 1439;
        return minute;
    }
}

