package com.example.lockguard;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.lockguard.data.AppRepository;
import com.example.lockguard.data.RuleConfig;
import com.example.lockguard.data.RuleStore;
import com.example.lockguard.data.TargetApp;
import com.example.lockguard.shizuku.ShizukuRemoteStopper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public final class MainActivity extends AppCompatActivity {
    private static final int REQ_SHIZUKU = 1001;

    private TextView statusView;
    private TextView shizukuView;
    private TextView selectedAppView;
    private MaterialButton startTimeButton;
    private MaterialButton endTimeButton;
    private EditText delayEdit;
    private MaterialSwitch cancelOnUnlockSwitch;
    private Button requestShizukuButton;
    private Button requestNotificationButton;
    private Button reconnectButton;
    private View reconnectSpacer;
    private Button startButton;
    private Button stopButton;
    private RecyclerView appList;
    private AppAdapter adapter;

    private final LinkedHashSet<String> selectedPackages = new LinkedHashSet<>();
    private final Map<String, String> selectedLabels = new HashMap<>();
    private int startMinute = 23 * 60;
    private int endMinute = 7 * 60;
    private int delayMinutes = 10;
    private boolean cancelOnUnlock = true;
    private boolean pendingStartAfterPermission;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::updateShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::updateShizukuStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = this::onShizukuPermissionResult;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    toast(getString(R.string.toast_notification_denied));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupRecycler();
        loadSavedConfig();
        loadApps();
        setupListeners();
        updateShizukuStatus();
        maybeRequestNotificationPermission();

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
    }

    private void bindViews() {
        statusView = findViewById(R.id.statusView);
        shizukuView = findViewById(R.id.shizukuView);
        selectedAppView = findViewById(R.id.selectedAppView);
        startTimeButton = findViewById(R.id.startTimeButton);
        endTimeButton = findViewById(R.id.endTimeButton);
        delayEdit = findViewById(R.id.delayEdit);
        cancelOnUnlockSwitch = findViewById(R.id.cancelOnUnlockSwitch);
        requestShizukuButton = findViewById(R.id.requestShizukuButton);
        requestNotificationButton = findViewById(R.id.requestNotificationButton);
        reconnectButton = findViewById(R.id.reconnectButton);
        reconnectSpacer = findViewById(R.id.reconnectSpacer);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        appList = findViewById(R.id.appList);
    }

    private void setupRecycler() {
        adapter = new AppAdapter(this::onAppSelected);
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setAdapter(adapter);
    }

    private void setupListeners() {
        startTimeButton.setOnClickListener(v -> pickTime(true));
        endTimeButton.setOnClickListener(v -> pickTime(false));
        requestShizukuButton.setOnClickListener(v -> onShizukuAction());
        requestNotificationButton.setOnClickListener(v -> maybeRequestNotificationPermission());
        reconnectButton.setOnClickListener(v -> restartForReconnect());
        startButton.setOnClickListener(v -> startMonitoring());
        stopButton.setOnClickListener(v -> stopMonitoring());

        delayEdit.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                delayMinutes = parseIntSafe(s.toString(), delayMinutes);
            }
        });

        cancelOnUnlockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> cancelOnUnlock = isChecked);
    }

    private void loadSavedConfig() {
        RuleConfig config = RuleStore.load(this);
        selectedPackages.clear();
        selectedPackages.addAll(config.packageNames);
        selectedLabels.clear();
        for (int i = 0; i < config.packageNames.size(); i++) {
            String packageName = config.packageNames.get(i);
            String label = i < config.appLabels.size() ? config.appLabels.get(i) : "";
            selectedLabels.put(packageName, label);
        }
        startMinute = config.startMinuteOfDay;
        endMinute = config.endMinuteOfDay;
        delayMinutes = config.delayMinutes;
        cancelOnUnlock = config.cancelOnUnlock;

        startTimeButton.setText(formatMinute(startMinute));
        endTimeButton.setText(formatMinute(endMinute));
        delayEdit.setText(String.valueOf(delayMinutes));
        cancelOnUnlockSwitch.setChecked(cancelOnUnlock);
        updateSelectedAppView();
    }

    private void loadApps() {
        new Thread(() -> {
            List<TargetApp> apps = AppRepository.loadLaunchableApps(this);
            runOnUiThread(() -> {
                for (TargetApp app : apps) {
                    if (selectedPackages.contains(app.packageName)) {
                        selectedLabels.put(app.packageName, app.label);
                    }
                }
                updateSelectedAppView();
                adapter.submit(apps, selectedPackages);
            });
        }).start();
    }

    private void onAppSelected(TargetApp app, boolean selected) {
        if (selected) {
            selectedPackages.add(app.packageName);
            selectedLabels.put(app.packageName, app.label);
        } else {
            selectedPackages.remove(app.packageName);
            selectedLabels.remove(app.packageName);
        }
        updateSelectedAppView();
        adapter.setSelectedPackages(selectedPackages);
        if (selectedPackages.isEmpty()) {
            statusView.setText(R.string.status_select_app);
        }
    }

    private void updateSelectedAppView() {
        if (selectedPackages.isEmpty()) {
            selectedAppView.setText(R.string.selected_none);
        } else if (selectedPackages.size() == 1) {
            String packageName = selectedPackages.iterator().next();
            String label = selectedLabels.get(packageName);
            selectedAppView.setText(String.format(Locale.getDefault(), "%s\n%s",
                    label == null || label.isEmpty() ? packageName : label,
                    packageName));
        } else {
            StringBuilder builder = new StringBuilder();
            builder.append(getString(R.string.selected_multi, selectedPackages.size()));
            int shown = 0;
            for (String packageName : selectedPackages) {
                if (shown >= 4) {
                    builder.append('\n').append(getString(R.string.selected_more));
                    break;
                }
                String label = selectedLabels.get(packageName);
                builder.append('\n').append(label == null || label.isEmpty() ? packageName : label);
                shown++;
            }
            selectedAppView.setText(builder);
        }
    }

    private void pickTime(boolean start) {
        int minute = start ? startMinute : endMinute;
        int hour = minute / 60;
        int min = minute % 60;
        new TimePickerDialog(this, (view, pickedHour, pickedMinute) -> {
            int total = pickedHour * 60 + pickedMinute;
            if (start) {
                startMinute = total;
                startTimeButton.setText(formatMinute(total));
            } else {
                endMinute = total;
                endTimeButton.setText(formatMinute(total));
            }
        }, hour, min, true).show();
    }

    private void startMonitoring() {
        delayMinutes = parseIntSafe(delayEdit.getText() == null ? "" : delayEdit.getText().toString(), delayMinutes);
        cancelOnUnlock = cancelOnUnlockSwitch.isChecked();

        if (!validateConfig()) {
            return;
        }

        if (!ensureShizukuPermission()) {
            pendingStartAfterPermission = true;
            return;
        }

        saveConfig(true);
        boolean bound = ShizukuRemoteStopper.ensureBound(this);
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        statusView.setText(bound ? R.string.status_monitor_started : R.string.status_monitor_connecting);
        pendingStartAfterPermission = false;
    }

    private void stopMonitoring() {
        saveConfig(false);
        ShizukuRemoteStopper.release(true);
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        ContextCompat.startForegroundService(this, intent);
        statusView.setText(R.string.status_monitor_stopped);
        pendingStartAfterPermission = false;
    }

    private void saveConfig(boolean enabled) {
        ArrayList<String> packages = new ArrayList<>(selectedPackages);
        ArrayList<String> labels = new ArrayList<>();
        for (String packageName : packages) {
            String label = selectedLabels.get(packageName);
            labels.add(label == null ? "" : label);
        }
        RuleConfig config = new RuleConfig(enabled, packages, labels, startMinute, endMinute, delayMinutes, cancelOnUnlock);
        RuleStore.save(this, config);
        adapter.setSelectedPackages(selectedPackages);
    }

    private boolean validateConfig() {
        if (selectedPackages.isEmpty()) {
            toast(getString(R.string.toast_select_first));
            return false;
        }
        if (startMinute == endMinute) {
            toast(getString(R.string.toast_same_time));
            return false;
        }
        if (delayMinutes < 1) {
            toast(getString(R.string.toast_delay_min));
            return false;
        }
        return true;
    }

    /** Shizuku 按钮的上下文动作：未连接时打开 Shizuku 应用，已连接时请求授权。 */
    private void onShizukuAction() {
        if (!Shizuku.pingBinder()) {
            if (!isShizukuInstalled()) {
                shizukuView.setText(R.string.shizuku_not_installed);
                return;
            }
            try {
                Intent intent = getPackageManager()
                        .getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID);
                if (intent != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Throwable ignore) {
            }
            shizukuView.setText(R.string.shizuku_open_failed);
            return;
        }
        ensureShizukuPermission();
    }

    /**
     * binder 未收到时，服务端只在应用进程启动时推送连接。
     * 重启自身进程可强制重新接收（配置已持久化，监控服务由 START_STICKY 恢复）。
     */
    private void restartForReconnect() {
        toast(getString(R.string.shizuku_restarting));
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finishAffinity();
        Runtime.getRuntime().exit(0);
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean ensureShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            shizukuView.setText(isShizukuInstalled()
                    ? R.string.shizuku_not_connected
                    : R.string.shizuku_not_installed);
            updateShizukuStatus();
            return false;
        }
        try {
            if (Shizuku.isPreV11()) {
                shizukuView.setText(R.string.shizuku_old_version);
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuView.setText(R.string.shizuku_granted);
                return true;
            }
            // 无论是否被拒绝过都再次请求：Shizuku 的弹窗可重复弹出，
            // 直接放弃会让用户以为“未激活”。
            Shizuku.requestPermission(REQ_SHIZUKU);
            shizukuView.setText(R.string.shizuku_requesting);
            return false;
        } catch (Throwable t) {
            shizukuView.setText(R.string.shizuku_waiting);
            return false;
        }
    }

    private void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQ_SHIZUKU) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            shizukuView.setText(R.string.shizuku_granted);
            if (pendingStartAfterPermission) {
                startMonitoring();
            }
        } else {
            pendingStartAfterPermission = false;
            shizukuView.setText(R.string.shizuku_denied);
        }
    }

    private void updateShizukuStatus() {
        boolean connected = Shizuku.pingBinder();
        if (connected) {
            try {
                if (Shizuku.isPreV11()) {
                    shizukuView.setText(R.string.shizuku_old_version);
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuView.setText(R.string.shizuku_granted);
                } else {
                    shizukuView.setText(R.string.shizuku_connected_needs_permission);
                }
            } catch (Throwable t) {
                shizukuView.setText(R.string.shizuku_waiting);
            }
        } else if (!isShizukuInstalled()) {
            shizukuView.setText(R.string.shizuku_not_installed);
        } else {
            shizukuView.setText(R.string.shizuku_not_connected);
        }
        reconnectButton.setVisibility(connected ? View.GONE : View.VISIBLE);
        reconnectSpacer.setVisibility(connected ? View.GONE : View.VISIBLE);
        requestShizukuButton.setText(connected
                ? R.string.btn_shizuku_grant
                : (isShizukuInstalled() ? R.string.btn_shizuku_open : R.string.btn_shizuku_grant));
    }

    /** 监控状态的唯一来源：服务存活标志 + 持久化配置。 */
    private void refreshMonitoringStatus() {
        if (MonitorService.isActive()) {
            statusView.setText(R.string.status_monitor_running);
            return;
        }
        if (selectedPackages.isEmpty()) {
            statusView.setText(R.string.status_select_app);
            return;
        }
        RuleConfig cfg = RuleStore.load(this);
        statusView.setText(cfg.enabled
                ? R.string.status_monitor_not_running
                : R.string.status_monitor_stopped);
    }

    /** 配置为启用但服务未存活（进程被杀且 sticky 未恢复）时，在前台自动拉起。返回是否应显示运行中。 */
    private boolean autoResumeMonitoring() {
        if (MonitorService.isActive()) {
            return true;
        }
        RuleConfig cfg = RuleStore.load(this);
        if (!cfg.enabled || !cfg.hasTarget()) {
            return false;
        }
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_RELOAD);
        ContextCompat.startForegroundService(this, intent);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus();
        maybeRequestNotificationPermission();
        if (autoResumeMonitoring()) {
            statusView.setText(R.string.status_monitor_running);
        } else {
            refreshMonitoringStatus();
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private static String formatMinute(int minuteOfDay) {
        int hour = minuteOfDay / 60;
        int minute = minuteOfDay % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private static int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
