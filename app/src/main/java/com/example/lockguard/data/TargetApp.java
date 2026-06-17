package com.example.lockguard.data;

import android.graphics.drawable.Drawable;

public final class TargetApp {
    public final String label;
    public final String packageName;
    public final Drawable icon;

    public TargetApp(String label, String packageName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }
}
