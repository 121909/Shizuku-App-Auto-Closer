# LockGuard (Shizuku-App-Auto-Closer)

基于 **Shizuku** 特权接口的 Android 锁屏自动关闭应用工具。无需 Root 权限，即可在指定时间段内、手机锁屏达到设定时长后，自动强行停止（`force-stop`）选定的后台应用，防止夜间或息屏期间毒瘤应用偷偷耗电、发热或唤醒。

---

## ✨ 核心特性

- **多应用组合管控**：支持勾选任意数量的已安装应用，内置**应用名/包名实时搜索**与**系统应用过滤开关**，勾选过程列表防跳动。
- **灵活的触发条件**：
  - **生效时间窗**：支持跨午夜时间段（例如 `23:00 - 07:00`）或日间时间段。
  - **锁屏延迟等待**：锁屏后等待指定分钟数（如 `10` 分钟）再执行清理，避免短暂息屏误杀正在使用的应用。
  - **解锁自动取消**：等待倒计时期间若用户亮屏解锁，自动取消本次清理任务。
- **深度休眠（Doze）准时触发**：
  - 采用 `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` 结合临时 `PARTIAL_WAKE_LOCK`，解决传统 `Handler.postDelayed` 在手机息屏进入 CPU 深度休眠后停表失准的问题。
  - 若锁屏发生在时间窗开始之前（如 `22:50` 锁屏，规则为 `23:00` 开始），会自动预约时间窗起点唤醒检查，无需高频轮询，极度省电。
- **毫秒级高效强杀**：
  - 在 Shizuku UserService 特权进程内直接通过 Binder 调用系统 `IActivityManager.forceStopPackage`，省去逐个启动 `/system/bin/am` 子进程的开销；遇到特殊定制 ROM 时自动无缝回退至 `/system/bin/am force-stop`。
- **单次锁屏防重复强杀**：同一锁屏周期内完成清理后自动标记，不会在整晚息屏期间反复唤醒强杀。
- **开机与覆盖更新自启**：支持监听系统开机（`BOOT_COMPLETED`）与应用覆盖更新（`MY_PACKAGE_REPLACED`）广播，自动恢复前台监控服务。
- **配置热重载与执行记录**：监控运行中修改勾选应用、时间窗或延迟参数会实时保存并热重载生效；主界面与常驻通知同步显示最近一次执行时间及成功关闭数量。

---

## 📦 下载与安装

### 1. 从 GitHub Releases 下载
前往 [Releases 页面](https://github.com/121909/Shizuku-App-Auto-Closer/releases/latest) 下载最新版本的 `app-release.apk` 并安装。

### 2. 通过 ADB 一键下载安装（Windows PowerShell）
```powershell
Invoke-WebRequest -Uri "https://github.com/121909/Shizuku-App-Auto-Closer/releases/latest/download/app-release.apk" -OutFile "$env:TEMP\app-release.apk"
adb install -r "$env:TEMP\app-release.apk"
```

---

## 🚀 使用指南

1. **准备 Shizuku**：
   - 在手机上安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku)（支持无线调试启动、电脑 ADB 启动或 Root 启动）。
2. **授予权限**：
   - 打开 **LockGuard**，点击 **「Shizuku 授权」** 按钮并在弹窗中允许。
   - 若提示「未收到 Shizuku 连接」，请确认 Shizuku 服务已运行，随后点击界面上的 **「重新连接」** 按钮。
   - （Android 13+）授予通知权限，以便常驻通知展示当前监控状态与最近执行记录。
3. **配置规则并启动**：
   - 设置生效的**开始时间**与**结束时间**（如 `23:00` 至 `07:00`）。
   - 输入锁屏后的**等待分钟数**（如 `10` 分钟），按需开启**「解锁取消」**。
   - 在下方应用列表中搜索并勾选需要锁屏自动强关的应用。
   - 点击 **「启动监控」**（监控运行期间随时修改配置会自动同步生效）。

> 💡 **省电策略建议**：为防止系统杀后台导致前台监控服务被回收，建议在系统「应用信息 -> 电池」中将 **LockGuard** 与 **Shizuku** 均设为「无限制 / 允许后台运行」。

---

## 🛠️ 构建说明

### 环境要求
- JDK 17
- Android SDK (`compileSdk 34`, `minSdk 24`)

### 本地 Gradle 构建
```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 构建 Release APK
./gradlew :app:assembleRelease
```
若需使用自定义签名构建 Release，可在项目根目录创建 `keystore.properties`：
```properties
storeFile=your_keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

### Docker 构建
项目内置 `Dockerfile`，可在无本地 Android SDK 环境下直接构建：
```bash
docker build -t lockguard-builder .
docker run --rm -v "$PWD":/workspace lockguard-builder
```

### GitHub Actions 自动构建与发布
仓库已配置 [`.github/workflows/build.yml`](.github/workflows/build.yml)：
- 已通过 GitHub Repository Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）固定正式签名密钥，每次构建的 `app-release.apk` 签名完全一致，支持直接覆盖升级。
- 每次推送到分支或提交 PR 时，自动编译 `app-debug.apk` 与 `app-release.apk` 并上传至 Actions Artifacts。
- 推送 `v*` 标签（如 `git tag v1.3 && git push origin v1.3`）时，自动创建 GitHub Release 并附带构建好的 APK 安装包及 SHA-256 校验值。

---

## 🔍 常用 ADB 调试命令

```bash
# 查看前台监控服务运行状态
adb shell dumpsys activity services com.example.lockguard

# 查看已注册的息屏唤醒定时器（AlarmManager）
adb shell dumpsys alarm | grep com.example.lockguard

# 查看实时运行与强杀日志
adb logcat -s MonitorService:V StopControllerService:V BootReceiver:V
```
