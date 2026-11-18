package com.example.lockguard;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private TextView statusBadgeView;
    private TextView shizukuView;
    private TextView lastExecView;
    private TextView selectedAppView;
    private MaterialButton toggleMonitorButton;
    private MaterialButton startTimeButton;
    private MaterialButton endTimeButton;
    private EditText delayEdit;
    private MaterialSwitch cancelOnUnlockSwitch;
    private View permissionActionRow;
    private MaterialButton requestShizukuButton;
    private MaterialButton requestNotificationButton;
    private View notificationSpacer;
    private MaterialButton reconnectButton;
    private View reconnectSpacer;
    private EditText searchEdit;
    private ImageButton clearSearchButton;
    private Chip onlySelectedChip;
    private Chip showSystemChip;
    private Chip selectVisibleChip;
    private Chip clearSelectedChip;
    private RecyclerView appList;
    private AppAdapter adapter;

    private final LinkedHashSet<String> selectedPackages = new LinkedHashSet<>();
    private final Map<String, String> selectedLabels = new HashMap<>();
    private int startMinute = 23 * 60;
    private int endMinute = 7 * 60;
    private int delayMinutes = 10;
    private boolean cancelOnUnlock = true;
    private boolean pendingStartAfterPermission;
    private boolean loadingConfig;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::updatePermissionUi;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::updatePermissionUi;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = this::onShizukuPermissionResult;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                updatePermissionUi();
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
        updatePermissionUi();
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
        statusBadgeView = findViewById(R.id.statusBadgeView);
        shizukuView = findViewById(R.id.shizukuView);
        lastExecView = findViewById(R.id.lastExecView);
        selectedAppView = findViewById(R.id.selectedAppView);
        toggleMonitorButton = findViewById(R.id.toggleMonitorButton);
        startTimeButton = findViewById(R.id.startTimeButton);
        endTimeButton = findViewById(R.id.endTimeButton);
        delayEdit = findViewById(R.id.delayEdit);
        cancelOnUnlockSwitch = findViewById(R.id.cancelOnUnlockSwitch);
        permissionActionRow = findViewById(R.id.permissionActionRow);
        requestShizukuButton = findViewById(R.id.requestShizukuButton);
        requestNotificationButton = findViewById(R.id.requestNotificationButton);
        notificationSpacer = findViewById(R.id.notificationSpacer);
        reconnectButton = findViewById(R.id.reconnectButton);
        reconnectSpacer = findViewById(R.id.reconnectSpacer);
        searchEdit = findViewById(R.id.searchEdit);
        clearSearchButton = findViewById(R.id.clearSearchButton);
        onlySelectedChip = findViewById(R.id.onlySelectedChip);
        showSystemChip = findViewById(R.id.showSystemChip);
        selectVisibleChip = findViewById(R.id.selectVisibleChip);
        clearSelectedChip = findViewById(R.id.clearSelectedChip);
        appList = findViewById(R.id.appList);
    }

    private void setupRecycler() {
        adapter = new AppAdapter(this::onAppSelected);
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setItemAnimator(null);
        appList.setAdapter(adapter);
    }

    private void setupListeners() {
        toggleMonitorButton.setOnClickListener(v -> {
            if (isMonitoringUiActive()) {
                stopMonitoring();
            } else {
                startMonitoring();
            }
        });
        startTimeButton.setOnClickListener(v -> pickTime(true));
        endTimeButton.setOnClickListener(v -> pickTime(false));
        requestShizukuButton.setOnClickListener(v -> onShizukuAction());
        requestNotificationButton.setOnClickListener(v -> maybeRequestNotificationPermission());
        reconnectButton.setOnClickListener(v -> restartForReconnect());

        delayEdit.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                int parsed = parseIntSafe(s.toString(), -1);
                if (parsed >= 1) {
                    delayMinutes = parsed;
                    syncRunningConfigIfActive();
                }
            }
        });

        cancelOnUnlockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            cancelOnUnlock = isChecked;
            syncRunningConfigIfActive();
        });

        searchEdit.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                boolean hasText = s != null && s.length() > 0;
                clearSearchButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                updateAppFilter();
            }
        });

        clearSearchButton.setOnClickListener(v -> searchEdit.setText(""));
        onlySelectedChip.setOnCheckedChangeListener((buttonView, isChecked) -> updateAppFilter());
        showSystemChip.setOnCheckedChangeListener((buttonView, isChecked) -> updateAppFilter());
        selectVisibleChip.setOnClickListener(v -> selectAllVisibleApps());
        clearSelectedChip.setOnClickListener(v -> confirmClearSelected());
    }

    private boolean isMonitoringUiActive() {
        if (MonitorService.isActive()) {
            return true;
        }
        RuleConfig cfg = RuleStore.load(this);
        return cfg.enabled && cfg.hasTarget();
    }

    private void updateAppFilter() {
        String query = searchEdit.getText() == null ? "" : searchEdit.getText().toString();
        adapter.setFilter(query, showSystemChip.isChecked(), onlySelectedChip.isChecked());
    }

    private void loadSavedConfig() {
        loadingConfig = true;
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

        startTimeButton.setText(getString(R.string.label_start_time_btn, formatMinute(startMinute)));
        endTimeButton.setText(getString(R.string.label_end_time_btn, formatMinute(endMinute)));
        delayEdit.setText(String.valueOf(delayMinutes));
        cancelOnUnlockSwitch.setChecked(cancelOnUnlock);
        updateSelectedSummaryUi();
        loadingConfig = false;
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
                updateSelectedSummaryUi();
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
        updateSelectedSummaryUi();
        adapter.setSelectedPackages(selectedPackages, false);
        if (selectedPackages.isEmpty()) {
            if (MonitorService.isActive()) {
                stopMonitoring();
            } else {
                refreshMonitoringStatus();
            }
        } else {
            syncRunningConfigIfActive();
        }
    }

    private void selectAllVisibleApps() {
        List<TargetApp> visible = adapter.getVisibleApps();
        if (visible.isEmpty()) {
            return;
        }
        int added = 0;
        for (TargetApp app : visible) {
            if (selectedPackages.add(app.packageName)) {
                selectedLabels.put(app.packageName, app.label);
                added++;
            }
        }
        updateSelectedSummaryUi();
        adapter.setSelectedPackages(selectedPackages, false);
        adapter.notifyDataSetChanged();
        if (added > 0) {
            syncRunningConfigIfActive();
        }
        toast(getString(R.string.toast_selected_visible, visible.size()));
    }

    private void confirmClearSelected() {
        if (selectedPackages.isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_clear_title)
                .setMessage(getString(R.string.dialog_clear_message, selectedPackages.size()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> clearAllSelected())
                .show();
    }

    private void clearAllSelected() {
        selectedPackages.clear();
        selectedLabels.clear();
        updateSelectedSummaryUi();
        adapter.setSelectedPackages(selectedPackages, false);
        adapter.notifyDataSetChanged();
        if (MonitorService.isActive() || RuleStore.load(this).enabled) {
            stopMonitoring();
        } else {
            refreshMonitoringStatus();
        }
        toast(getString(R.string.toast_cleared_all));
    }

    /** 若监控已处于运行中，用户修改参数后实时保存并通知服务重新加载。 */
    private void syncRunningConfigIfActive() {
        if (loadingConfig || !MonitorService.isActive()) {
            return;
        }
        if (selectedPackages.isEmpty() || startMinute == endMinute || delayMinutes < 1) {
            return;
        }
        saveConfig(true, false);
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_RELOAD);
        ContextCompat.startForegroundService(this, intent);
    }

    private void updateSelectedSummaryUi() {
        int count = selectedPackages.size();
        onlySelectedChip.setText(count > 0
                ? getString(R.string.label_only_selected_count, count)
                : getString(R.string.label_only_selected));
        clearSelectedChip.setEnabled(count > 0);

        if (count == 0) {
            selectedAppView.setText(R.string.selected_none);
            return;
        }
        if (count == 1) {
            String pkg = selectedPackages.iterator().next();
            String label = selectedLabels.get(pkg);
            String name = (label == null || label.isEmpty()) ? pkg : label;
            selectedAppView.setText(getString(R.string.selected_summary_one, name));
            return;
        }
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (String pkg : selectedPackages) {
            if (shown >= 3) {
                names.append(getString(R.string.selected_more));
                break;
            }
            if (shown > 0) {
                names.append("、");
            }
            String label = selectedLabels.get(pkg);
            names.append(label == null || label.isEmpty() ? pkg : label);
            shown++;
        }
        selectedAppView.setText(getString(R.string.selected_summary_multi, count, names.toString()));
    }

    private void pickTime(boolean start) {
        int minute = start ? startMinute : endMinute;
        int hour = minute / 60;
        int min = minute % 60;
        new TimePickerDialog(this, (view, pickedHour, pickedMinute) -> {
            int total = pickedHour * 60 + pickedMinute;
            if (start) {
                startMinute = total;
                startTimeButton.setText(getString(R.string.label_start_time_btn, formatMinute(total)));
            } else {
                endMinute = total;
                endTimeButton.setText(getString(R.string.label_end_time_btn, formatMinute(total)));
            }
            syncRunningConfigIfActive();
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

        saveConfig(true, true);
        boolean bound = ShizukuRemoteStopper.ensureBound(this);
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        applyMonitoringStatusUi(true, bound ? R.string.status_monitor_started : R.string.status_monitor_connecting);
        pendingStartAfterPermission = false;
    }

    private void stopMonitoring() {
        saveConfig(false, false);
        ShizukuRemoteStopper.release(true);
        Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_STOP);
        ContextCompat.startForegroundService(this, intent);
        applyMonitoringStatusUi(false, R.string.status_monitor_stopped);
        pendingStartAfterPermission = false;
    }

    private void saveConfig(boolean enabled, boolean resortList) {
        ArrayList<String> packages = new ArrayList<>(selectedPackages);
        ArrayList<String> labels = new ArrayList<>();
        for (String packageName : packages) {
            String label = selectedLabels.get(packageName);
            labels.add(label == null ? "" : label);
        }
        RuleConfig config = new RuleConfig(enabled, packages, labels, startMinute, endMinute, delayMinutes, cancelOnUnlock);
        RuleStore.save(this, config);
        adapter.setSelectedPackages(selectedPackages, resortList);
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
            updatePermissionUi();
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
        updatePermissionUi();
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            if (pendingStartAfterPermission) {
                startMonitoring();
            }
        } else {
            pendingStartAfterPermission = false;
            shizukuView.setText(R.string.shizuku_denied);
        }
    }

    private void updatePermissionUi() {
        boolean connected = Shizuku.pingBinder();
        boolean shizukuGranted = false;
        if (connected) {
            try {
                if (Shizuku.isPreV11()) {
                    shizukuView.setText(R.string.shizuku_old_version);
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuView.setText(R.string.shizuku_granted);
                    shizukuGranted = true;
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

        boolean notificationGranted = isNotificationPermissionGranted();
        boolean showShizukuBtn = !shizukuGranted;
        boolean showReconnectBtn = !connected && isShizukuInstalled();
        boolean showNotifBtn = !notificationGranted;

        requestShizukuButton.setVisibility(showShizukuBtn ? View.VISIBLE : View.GONE);
        requestShizukuButton.setText(connected
                ? R.string.btn_shizuku_grant
                : (isShizukuInstalled() ? R.string.btn_shizuku_open : R.string.btn_shizuku_grant));

        reconnectButton.setVisibility(showReconnectBtn ? View.VISIBLE : View.GONE);
        reconnectSpacer.setVisibility(showShizukuBtn && showReconnectBtn ? View.VISIBLE : View.GONE);

        requestNotificationButton.setVisibility(showNotifBtn ? View.VISIBLE : View.GONE);
        notificationSpacer.setVisibility((showShizukuBtn || showReconnectBtn) && showNotifBtn
                ? View.VISIBLE : View.GONE);

        permissionActionRow.setVisibility((showShizukuBtn || showReconnectBtn || showNotifBtn)
                ? View.VISIBLE : View.GONE);
    }

    /** 监控状态的唯一来源：服务存活标志 + 持久化配置。 */
    private void refreshMonitoringStatus() {
        if (MonitorService.isActive()) {
            applyMonitoringStatusUi(true, R.string.status_monitor_running);
            return;
        }
        if (selectedPackages.isEmpty()) {
            applyMonitoringStatusUi(false, R.string.status_select_app);
            return;
        }
        RuleConfig cfg = RuleStore.load(this);
        applyMonitoringStatusUi(false, cfg.enabled
                ? R.string.status_monitor_not_running
                : R.string.status_monitor_stopped);
    }

    private void applyMonitoringStatusUi(boolean running, int statusResId) {
        statusView.setText(statusResId);
        statusBadgeView.setText(running ? R.string.badge_running : R.string.badge_stopped);
        statusBadgeView.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this, running ? R.color.status_running_bg : R.color.status_stopped_bg)));
        statusBadgeView.setTextColor(ContextCompat.getColor(
                this, running ? R.color.status_running_text : R.color.status_stopped_text));

        if (running) {
            toggleMonitorButton.setText(R.string.btn_stop);
            toggleMonitorButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.btn_stop_bg)));
            toggleMonitorButton.setTextColor(ContextCompat.getColor(this, R.color.btn_stop_text));
        } else {
            toggleMonitorButton.setText(R.string.btn_start);
            toggleMonitorButton.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.md_theme_light_primary)));
            toggleMonitorButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        }

        String lastExec = RuleStore.getLastExecutionSummary(this);
        if (lastExec != null) {
            lastExecView.setText(getString(R.string.last_execution_prefix, lastExec));
            lastExecView.setVisibility(View.VISIBLE);
        } else {
            lastExecView.setVisibility(View.GONE);
        }
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
        updatePermissionUi();
        if (autoResumeMonitoring()) {
            applyMonitoringStatusUi(true, R.string.status_monitor_running);
        } else {
            refreshMonitoringStatus();
        }
    }

    private boolean isNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT < 33) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void maybeRequestNotificationPermission() {
        if (!isNotificationPermissionGranted()) {
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
