package com.example.lockguard.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class RuleStore {
    private static final String PREFS = "lockguard_rules";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PACKAGES = "packages";
    private static final String KEY_LABELS = "labels";
    private static final String KEY_START = "start";
    private static final String KEY_END = "end";
    private static final String KEY_DELAY = "delay";
    private static final String KEY_CANCEL = "cancel";

    private RuleStore() {}

    public static RuleConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ArrayList<String> packageNames = readStringArray(prefs.getString(KEY_PACKAGES, null));
        ArrayList<String> appLabels = readStringArray(prefs.getString(KEY_LABELS, null));
        return new RuleConfig(
                prefs.getBoolean(KEY_ENABLED, false),
                packageNames,
                appLabels,
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
                .putString(KEY_PACKAGES, writeStringArray(config.packageNames))
                .putString(KEY_LABELS, writeStringArray(config.appLabels))
                .putInt(KEY_START, clampMinute(config.startMinuteOfDay))
                .putInt(KEY_END, clampMinute(config.endMinuteOfDay))
                .putInt(KEY_DELAY, Math.max(1, config.delayMinutes))
                .putBoolean(KEY_CANCEL, config.cancelOnUnlock)
                .apply();
    }

    private static int clampMinute(int minute) {
        if (minute < 0) return 0;
        if (minute >= 1440) return 1439;
        return minute;
    }

    private static ArrayList<String> readStringArray(String json) {
        ArrayList<String> values = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return values;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                values.add(value == null ? "" : value);
            }
        } catch (JSONException ignore) {
        }
        return values;
    }

    private static String writeStringArray(List<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) {
                array.put(value == null ? "" : value);
            }
        }
        return array.toString();
    }
}
