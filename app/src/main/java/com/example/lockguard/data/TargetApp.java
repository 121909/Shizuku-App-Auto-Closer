package com.example.lockguard.data;

import android.graphics.drawable.Drawable;

public final class TargetApp {
    public final String label;
    public final String packageName;
    public final Drawable icon;
    public final boolean isSystemApp;

    public TargetApp(String label, String packageName, Drawable icon, boolean isSystemApp) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
        this.isSystemApp = isSystemApp;
    }
}
