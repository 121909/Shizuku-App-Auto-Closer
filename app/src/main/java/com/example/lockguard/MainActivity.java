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

public final class MainActivity extends AppCompatActivity {
    private static final int REQ_SHIZUKU = 1001;

    private TextView statusView;
    private TextView selectedAppView;
    private MaterialButton startTimeButton;
    private MaterialButton endTimeButton;
    private EditText delayEdit;
    private MaterialSwitch cancelOnUnlockSwitch;
    private Button requestShizukuButton;
    private Button requestNotificationButton;
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
                    toast("通知权限未授予，前台服务通知可能不可见");
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
        selectedAppView = findViewById(R.id.selectedAppView);
        startTimeButton = findViewById(R.id.startTimeButton);
        endTimeButton = findViewById(R.id.endTimeButton);
        delayEdit = findViewById(R.id.delayEdit);
        cancelOnUnlockSwitch = findViewById(R.id.cancelOnUnlockSwitch);
        requestShizukuButton = findViewById(R.id.requestShizukuButton);
        requestNotificationButton = findViewById(R.id.requestNotificationButton);
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
        requestShizukuButton.setOnClickListener(v -> ensureShizukuPermission());
        requestNotificationButton.setOnClickListener(v -> maybeRequestNotificationPermission());
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
                if (selectedPackages.isEmpty()) {
                    if (statusView.getText() == null || statusView.getText().toString().startsWith("等待")) {
                        statusView.setText("请选择应用");
                    }
                }
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
    }

    private void updateSelectedAppView() {
        if (selectedPackages.isEmpty()) {
            selectedAppView.setText("未选择应用");
        } else if (selectedPackages.size() == 1) {
            String packageName = selectedPackages.iterator().next();
            String label = selectedLabels.get(packageName);
            selectedAppView.setText(String.format(Locale.getDefault(), "%s\n%s",
                    label == null || label.isEmpty() ? packageName : label,
                    packageName));
        } else {
            StringBuilder builder = new StringBuilder();
            builder.append("已选择 ").append(selectedPackages.size()).append(" 个应用");
            int shown = 0;
            for (String packageName : selectedPackages) {
                if (shown >= 4) {
                    builder.append("\n...");
                    break;
                }
                String label = selectedLabels.get(packageName);
                builder.append('\n').append(label == null || label.isEmpty() ? packageName : label);
                shown++;
            }
            selectedAppView.setText(builder.toString());
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
        statusView.setText(bound ? "监控已启动" : "监控已启动，Shizuku 还在连接");
        pendingStartAfterPermission = false;
    }

    private void stopMonitoring() {
        saveConfig(false);
        ShizukuRemoteStopper.release(true);
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        ContextCompat.startForegroundService(this, intent);
        statusView.setText("监控已停止");
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
            toast("先选择至少一个应用");
            return false;
        }
        if (startMinute == endMinute) {
            toast("开始时间和结束时间不能相同");
            return false;
        }
        if (delayMinutes < 1) {
            toast("等待分钟数必须大于 0");
            return false;
        }
        return true;
    }

    private boolean ensureShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                statusView.setText("当前 Shizuku 版本过旧");
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                statusView.setText("Shizuku 已授权");
                return true;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                statusView.setText("Shizuku 权限被拒绝，请在 Shizuku 中重新授权");
                return false;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
            statusView.setText("正在请求 Shizuku 权限");
            return false;
        } catch (Throwable t) {
            statusView.setText("Shizuku 还没有连接");
            return false;
        }
    }

    private void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQ_SHIZUKU) {
            return;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            statusView.setText("Shizuku 已授权");
            if (pendingStartAfterPermission) {
                startMonitoring();
            }
        } else {
            pendingStartAfterPermission = false;
            statusView.setText("Shizuku 权限被拒绝");
        }
    }

    private void updateShizukuStatus() {
        try {
            if (Shizuku.isPreV11()) {
                statusView.setText("Shizuku 版本过旧");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                statusView.setText("Shizuku 已连接且已授权");
            } else {
                statusView.setText("Shizuku 已连接，等待授权");
            }
        } catch (Throwable t) {
            statusView.setText("等待 Shizuku 连接");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus();
        maybeRequestNotificationPermission();
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
