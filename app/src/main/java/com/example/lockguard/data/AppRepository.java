package com.example.lockguard.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class AppRepository {
    private AppRepository() {}

    public static List<TargetApp> loadLaunchableApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
        ArrayList<TargetApp> result = new ArrayList<>();
        String selfPackage = context.getPackageName();
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (selfPackage.equals(packageName)) continue;
            CharSequence label = info.loadLabel(pm);
            result.add(new TargetApp(label == null ? packageName : label.toString(), packageName, info.loadIcon(pm)));
        }
        Collections.sort(result, Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}
