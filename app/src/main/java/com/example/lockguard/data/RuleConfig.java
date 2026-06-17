package com.example.lockguard.data;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuleConfig {
    public final boolean enabled;
    public final List<String> packageNames;
    public final List<String> appLabels;
    public final int startMinuteOfDay;
    public final int endMinuteOfDay;
    public final int delayMinutes;
    public final boolean cancelOnUnlock;

    public RuleConfig(boolean enabled,
                      List<String> packageNames,
                      List<String> appLabels,
                      int startMinuteOfDay,
                      int endMinuteOfDay,
                      int delayMinutes,
                      boolean cancelOnUnlock) {
        this.enabled = enabled;
        this.packageNames = immutableNonEmptyCopy(packageNames);
        this.appLabels = immutableLabelCopy(appLabels, this.packageNames.size());
        this.startMinuteOfDay = startMinuteOfDay;
        this.endMinuteOfDay = endMinuteOfDay;
        this.delayMinutes = delayMinutes;
        this.cancelOnUnlock = cancelOnUnlock;
    }

    public boolean hasTarget() {
        return !packageNames.isEmpty();
    }

    public String formatTargets() {
        int count = packageNames.size();
        if (count == 0) {
            return "未选择应用";
        }
        if (count == 1) {
            String label = appLabels.isEmpty() ? "" : appLabels.get(0);
            return label == null || label.isEmpty() ? packageNames.get(0) : label;
        }
        return count + " 个应用";
    }

    public boolean isWindowValid() {
        return startMinuteOfDay >= 0 && startMinuteOfDay < 1440
                && endMinuteOfDay >= 0 && endMinuteOfDay < 1440
                && startMinuteOfDay != endMinuteOfDay;
    }

    public boolean isWithinWindow(LocalTime time) {
        if (!isWindowValid()) {
            return false;
        }
        int now = time.getHour() * 60 + time.getMinute();
        if (startMinuteOfDay < endMinuteOfDay) {
            return now >= startMinuteOfDay && now < endMinuteOfDay;
        }
        return now >= startMinuteOfDay || now < endMinuteOfDay;
    }

    public String formatWindow() {
        return formatMinute(startMinuteOfDay) + " - " + formatMinute(endMinuteOfDay);
    }

    public String formatDelay() {
        return delayMinutes + " 分钟";
    }

    private static String formatMinute(int minuteOfDay) {
        int hour = minuteOfDay / 60;
        int minute = minuteOfDay % 60;
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private static List<String> immutableNonEmptyCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<String> copy = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isEmpty() && !copy.contains(value)) {
                copy.add(value);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<String> immutableLabelCopy(List<String> values, int targetSize) {
        if (targetSize == 0) {
            return Collections.emptyList();
        }
        ArrayList<String> copy = new ArrayList<>(targetSize);
        for (int i = 0; i < targetSize; i++) {
            String label = values != null && i < values.size() ? values.get(i) : "";
            copy.add(label == null ? "" : label);
        }
        return Collections.unmodifiableList(copy);
    }
}
