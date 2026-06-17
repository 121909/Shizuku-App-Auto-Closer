package com.example.lockguard.data;

import java.time.LocalTime;

public final class RuleConfig {
    public final boolean enabled;
    public final String packageName;
    public final String appLabel;
    public final int startMinuteOfDay;
    public final int endMinuteOfDay;
    public final int delayMinutes;
    public final boolean cancelOnUnlock;

    public RuleConfig(boolean enabled,
                      String packageName,
                      String appLabel,
                      int startMinuteOfDay,
                      int endMinuteOfDay,
                      int delayMinutes,
                      boolean cancelOnUnlock) {
        this.enabled = enabled;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.startMinuteOfDay = startMinuteOfDay;
        this.endMinuteOfDay = endMinuteOfDay;
        this.delayMinutes = delayMinutes;
        this.cancelOnUnlock = cancelOnUnlock;
    }

    public boolean hasTarget() {
        return packageName != null && !packageName.isEmpty();
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
}

