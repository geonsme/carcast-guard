# carcast-guard

> 面向「车机投屏宝」（`com.sven.mycar`）的双端逆向分析，以及在 ColorOS 上让投屏授权**重启后自动恢复**，并同时守护「哪吒汽车」（`com.hezhong.nezha`）蓝牙钥匙后台连接的自研守护 App。

本仓库只包含**我自己编写的代码与文档**。
「车机投屏宝」是闭源商业应用，**本仓库不包含它的源码、APK、 dex、资源或图标**——仓库中没有它的源代码可找，只有基于公开制品（用户自有设备上已安装的 APK）做的分析与自研工具。

---

## 目录

- [1. 背景：一个被忽略的小问题](#1-背景一个被忽略的小问题)
- [2. 仓库内容](#2-仓库内容)
- [3. 车控守护 App](#3-车控守护-app)
- [4. 逆向分析：三份报告](#4-逆向分析三份报告)
- [5. 核心结论速查](#5-核心结论速查)
- [6. 构建与安装](#6-构建与安装)
- [7. 已知限制](#7-已知限制)
- [8. 方法论与证据分级](#8-方法论与证据分级)
- [9. 免责声明](#9-免责声明)

---

## 1. 背景：一个被忽略的小问题

在 OnePlus 12 / ColorOS 16（Android 16）上使用「车机投屏宝」时遇到两个现象：

1. **投屏偶尔完全连不上**，App 不报错，只是卡住。排查发现投屏授权弹窗曾在某次被点了「拒绝」，`PROJECT_MEDIA` app-op 被打回 `ignore`，之后所有投屏请求都被**静默掐断**。
2. **手机没电自动重启后**，无障碍总开关 `accessibility_enabled` 被复位为 `0`，导致依赖无障碍的自动点框能力失效，下次投屏又会卡在授权环节。
3. **哪吒汽车在后台被系统清掉、或从最近任务里手滑划掉后，蓝牙钥匙随之断开**，必须重新打开 App 才能恢复；车已经解锁/上锁到一半时尤其麻烦。

于是做了三件事：

- 把投屏宝 App 的结构与权限链路完整逆向了一遍，搞清楚"为什么"；
- 写了一个**开机自愈**的小型无障碍守护 App，解决"重启后没人自动恢复"的问题；
- 在同一守护 App 中加入**哪吒汽车保活通道**：显式绑定其导出的 MQTT 服务、周期心跳、掉线重绑，并配合 Doze/待机桶/蓝牙权限钉权。

## 2. 仓库内容

```
.
├── README.md
├── .gitignore
├── .github/workflows/android.yml  # push 构建 APK；打 v* tag 自动发布 Release
├── guardapp/                      # 自研守护 App 源码（Java，无 Gradle 手工构建）
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── layout/                # 仪表盘、目标卡、条件行、确认对话框
│   │   ├── drawable/              # 卡片/胶囊/按钮/图标等矢量资源
│   │   ├── font/rajdhani_*.ttf    # Rajdhani 数字与拉丁小标（OFL）
│   │   ├── mipmap-anydpi-v26/     # 自适应图标
│   │   ├── values/{colors,strings,styles}.xml
│   │   └── xml/guard_config.xml   # 无障碍服务配置
│   ├── src/cn/luohui/mpguard/
│   │   ├── GuardService.java      # 无障碍服务：识别并关闭投屏授权框
│   │   ├── Rearm.java             # 自愈核心：写回无障碍开关
│   │   ├── RearmJobService.java   # 持久化 JobScheduler（开机由框架恢复）
│   │   ├── RearmAlarm.java        # 10 分钟双路 AlarmManager 兜底（自愈 + 哪吒直连心跳）
│   │   ├── RearmAlarmReceiver.java# 闹钟触发：自愈 + 重绑 + 续期
│   │   ├── BootReceiver.java      # BOOT_COMPLETED 兜底
│   │   ├── NetaKeeper.java        # 哪吒 MQTT 绑定/心跳/掉线重绑
│   │   ├── GuardWidgetProvider.java # 2×1 桌面状态磁贴
│   │   ├── ProtectionGaugeView.java # 270° 自定义守护指数仪表
│   │   ├── BackdropView.java      # 深炭黑底 + 光晕 + 网格背景
│   │   └── StatusActivity.java    # 状态页：守护指数/目标卡/系统条件/动作
│   ├── build.sh                   # 跨平台构建：本地 Windows / Actions Linux
│   ├── render_preview.js          # 图标矢量栅格化预览
│   └── mp-guard.apk               # 已签名成品（v1.14，约 0.5 MB，含字体）
├── mp_guard.sh                    # shell 级兜底守护（设备端，需 adb）
├── rearm.sh                       # 电脑端一条命令恢复（含哪吒钉权）
├── verify_after_reboot.sh         # 重启后逐项核对（双通道 38 项）
├── 车机投屏宝-逆向剖析报告.md       # 完整逆向分析
├── 手机端与车机端实现差异报告.md    # 同一 APK 双角色对比
├── 守护部署与自愈说明.md           # 守护 App 的部署与排障
├── MuMu模拟车机端测试手册.md       # 用模拟器充当车机端的搭建记录
└── 技术分析报告.html               # 可视化版（单文件、离线可开）
```

## 3. 车控守护 App

包名 `cn.luohui.mpguard`，自签，约 0.5 MB（v1.14 新增直连哪吒心跳的第二路闹钟与 2×1 桌面磁贴），**无网络权限**。
v1.12 起同时承担两条链路：投屏宝无障碍自愈 + 哪吒汽车后台保活；v1.13 加入 Doze 兜底；
v1.14 把兜底拆成“守护自愈”与“直连哪吒”两路，并实测排除了 `BIND_ABOVE_CLIENT` 方案。

### 3.1 它解决的问题

**投屏宝**：ColorOS 16 在重启后会把 `accessibility_enabled` 置 `0`，虽然启用列表还在，但没有任何无障碍服务被绑定。
而重启后 shell 身份也拿不到 `GRANT_RUNTIME_PERMISSIONS` / `MANAGE_APP_OPS_MODES`，所以"用 adb 修一下"在车里不可行。

**哪吒汽车**：App 在后台被系统回收、或从最近任务里划掉后，MQTT 长连接断开，蓝牙钥匙随之失效。
该 App 本身已在 Doze 白名单、待机桶 5，`RUN_ANY_IN_BACKGROUND` 为 allow，且有自己的
`HzTboeKeepAliveService` 前台服务；守护补的是**跨应用外部绑定、被回收后的重建能力与 Doze 闹钟兜底**。

### 3.2 机制一：无障碍自愈

```
开机
 └─ JobScheduler 恢复持久化任务（绕过被 ColorOS 拦截的 BOOT_COMPLETED）
     └─ Rearm.rearm()
         ├─ 读 enabled_accessibility_services，按 ComponentName 归一化去重
         ├─ 缺失则补写（车机投屏宝 + 本应用两条）
         ├─ 仅在列表变化 / 总开关关闭 / 服务未加载时才强制 0→1
         └─ 约 30 秒内两个无障碍服务重新被系统 bind
```

关键点：

- **`WRITE_SECURE_SETTINGS` 是 `signature|privileged|development` 级权限**，其中 `development` 位允许用 `adb shell pm grant` 一次性授予普通应用，且授予后随包持久化。这是整个方案能免 root 成立的基础。
- **用户意图与系统漂移要分开**：用户主动「停用守护」时写入 SharedPreferences 标记，并且检查下沉在 `Rearm.rearm()` 内部，避免 `GuardService.onServiceConnected()` 之类的其他入口把它自作主张恢复。
- **`ComponentName` 归一化是必须的**：设置列表里是全写 `pkg/pkg.Cls`，而 `AccessibilityServiceInfo.getId()` 常返回缩写 `pkg/.Cls`，直接字符串比对必然失配（且会导致列表重复条目）。
- **不要重复触发自己**：周期任务在 `onStartJob()` 内再次 `schedule()` 会立刻再触发一次，形成秒级死循环；v1.12 已移除。无障碍总开关也只在确有缺失时 toggle，避免系统反复重绑。

### 3.3 机制二：哪吒汽车保活

```
GuardService（系统绑定，常驻）
 ├─ NetaKeeper.start()
 │   ├─ bindService(BIND_AUTO_CREATE | BIND_IMPORTANT)
 │   │    → com.hezhong.nezha/com.digital.mqttlib.service.DMqttService
 │   │    └─ dumpsys 连接记录：CR IMP / flags=0x41
 │   │    └─ onServiceDisconnected / onBindingDied → 2.5s 重绑；onNullBinding → 30s 后重试
 │   ├─ watchdog 每 30s 复核绑定，断开即重绑
 │   ├─ 心跳广播：每 2 分钟 + 每次亮屏
 │   │    → ACTION_SDK_HEARTBEAT（显式组件 + INCLUDE_STOPPED_PACKAGES）
 │   ├─ 解锁补发 USER_PRESENT
 │   │    → 促使数字钥匙重新判断可用性（哪吒自身也注册了该广播）
 │   └─ 会话遥测：绑定次数 / 最近心跳 / 最近绑定时间 / 最近错误
 ├─ RearmJobService（15 分钟）→ 自愈 + 再次 NetaKeeper.start()
 └─ RearmAlarm（10 分钟，RTC_WAKEUP，双路）
     ├─ 9001 → 守护自己：自愈 + 重绑 + 心跳 + 续期
     └─ 9002 → 哪吒 BootBroadcastReceiver：ACTION_SDK_HEARTBEAT（晚 30 秒）
```

- 只使用 Neta **已导出**的服务与接收器（`DMqttService`、`BootBroadcastReceiver`），显式组件绑定，不做反射/hook，不读取其数据。
- `BIND_IMPORTANT` 让这条跨应用绑定在系统的 `dumpsys` 中记为 `CR IMP`、`flags=0x41`；目标服务被回收时 `BIND_AUTO_CREATE` 会重新创建它。
- 实测哪吒主进程 `curProcState=4`（`FOREGROUND_SERVICE`），来源是它自己的 `HzTboeKeepAliveService`；守护自身是 `curProcState=5`（`BOUND_FOREGROUND_SERVICE`，由系统绑定无障碍服务）。也就是说主进程平时靠哪吒自己的前台服务顶住，守护绑定是**第二根外部锚**：它拉住的是 MQTT 服务与主进程，在无最近任务、熄屏、进程被回收后负责重建。
- 实测熄屏 + 最近任务划掉后，主进程的 GATT 客户端仍在约 30 秒周期地注册/反注册，说明蓝牙栈仍在活动（不代表 GATT 连接一直保持）。
- Manifest 里的 `BootReceiver` 收 `USER_PRESENT` 会被 ColorOS 以「Background execution not allowed」跳过；
  实际可靠的是 `NetaKeeper` 在进程内注册的 `SCREEN_ON` / `USER_PRESENT` 动态接收器（`dumpsys activity broadcasts` 已确认在册）。
- Doze 下另有 10 分钟 `RTC_WAKEUP` 精确闹钟兜底（`SCHEDULE_EXACT_ALARM` 已授权，`dumpsys` 显示 `exactAllowReason=permission`）。强制 deep idle 实测：17:59:51 排定、18:00:10 实际触发（延迟 19 秒），完成自愈、心跳并续期到 18:10:10；期间 GATT 仍按 30 秒周期注册，绑定保持 `flags=0x41`。
- v1.14 增加第二路 10 分钟闹钟（比第一路晚 30 秒），显式指向哪吒的 `BootBroadcastReceiver` + `ACTION_SDK_HEARTBEAT`。守护正常时，第一路闹钟在 10 分钟节点先触发并把它续到下一轮；只有守护在 10 分钟节点没被拉起时，它才会在 30 秒后落到哪吒。因此它不是每 10 分钟都响的第二条心跳，而是“守护失效”探针；`dumpsys alarm` 已确认两条都在册（`REARM_ALARM` 与 `ACTION_SDK_HEARTBEAT`，后者 component 为哪吒接收器）。投递路径已用加速实测确认：18:15:02.817 由 system_server 发出，`dumpsys activity broadcasts` 记录 `enqueue 18:15:02.818 → dispatch 18:15:02.829`，终态 `DELIVERED ... #0: (manifest)`，接收者为 `BootBroadcastReceiver`。
- 评估过 `BIND_ABOVE_CLIENT`：实测会让守护自身的 `oom adj` 从 100 退到 200（`hasAboveClient=true`），而哪吒仍是 100，收益不成立，已回退；最终保留 `BIND_AUTO_CREATE | BIND_IMPORTANT`（`CR IMP / flags=0x41`）。
- 哪吒的蓝牙权限（`BLUETOOTH_SCAN/CONNECT/ADVERTISE`）、后台定位、appops、Doze 白名单与待机桶由 `rearm.sh` / `mp_guard.sh` 复核钉住。
- 用户点「停用守护」时 `NetaKeeper.stop()` 会解绑并停止心跳，不会自行恢复。

### 3.4 状态页（v1.12）

一屏式汽车仪表盘：270° 守护指数、双目标卡、系统条件、最近动作，底部常驻三个动作。

- **守护指数**：无障碍总开关、自愈权限、定时任务、投屏宝已加载、哪吒已绑定等 7 项加权，0–100 动态动画
- **目标卡**：投屏宝（无障碍通道 / 投屏弹窗策略 / 本次放行次数）与哪吒汽车（MQTT 绑定 / 心跳唤醒 / 双路闹钟）
- **系统条件**：无障碍总开关、`WRITE_SECURE_SETTINGS`、JobScheduler、三应用 Doze 白名单汇总
- **交互**：每 3 秒自动刷新；「立即自愈」「检测」「重绑哪吒」「停用/启用守护」均有 Toast + 事件日志 + 整页重算
- **权限引导**：缺 `WRITE_SECURE_SETTINGS` 时弹出可复制的 ADB 命令，不把用户丢进设置页

### 3.5 桌面 2×1 状态磁贴（v1.14）

`GuardWidgetProvider` 提供一个标准 App Widget，占桌面 **2×1** 格（ColorOS 16 常规卡片档位），保留盾牌图标 + 一行状态，点击进入控制台；背景 90% 不透明度，透出少量壁纸。

- 状态优先级：已停用（灰）→ 待授权（珊瑚）→ 待激活（珊瑚）→ 仅投屏（琥珀）→ 重连中（琥珀）→ 守护中（柠檬绿）。
- 刷新：系统 `updatePeriodMillis` 每 30 分钟一次；App 内每次状态重绘、自愈、启停、重绑，以及哪吒绑定连上/断开时都会主动 `refresh()`。
- 添加方式：长按桌面空白处 → 「卡片」→ 在「全部卡片」里找到「车控守护」添加（卡片中心搜索按中文匹配，拼音搜不到）。
- 约束：RemoteViews 不支持自定义表盘、字体和动画，所以磁贴只做状态摘要；系统对桌面刷新有节流，状态最多滞后一个刷新周期。
- 1×1 版本已在 ColorOS 16 实机添加并验证：点击进入 `StatusActivity`，安装后核对 38/38；2×1 版本已重新构建，待实机安装确认。尺寸变更后需移除旧磁贴再重新添加。

### 3.6 图标与视觉

深炭黑 `#080A0A` + 柠檬绿 `#C7F86F` 主色，琥珀 `#FFC46B` 与珊瑚 `#FF7A6B` 分级告警；
数字与拉丁小标使用 Rajdhani（OFL），背景为网格 + 双光晕，母题为盾牌 + 对勾。

### 3.7 REA 对哪吒 APK 的静态核对

用 REA 6.7.0（JADX headless 0.7.1 解码 Manifest）对本地包体 `com.hezhong.nezha.apk`
（6.4.3 / versionCode 163，targetSdk 32，SHA-256 `80eed409…f6d79f`）做了核对。
包体只在本机分析，未入库、未分发。

| 结论 | 证据 |
|---|---|
| 代码被加固壳包裹 | `application=com.stub.StubApp`，`assets/libjiagu.so` / `libjiagu_a64.so`；JADX 只解出 6 个壳类 |
| Manifest 完整可读 | REA 解码出 93,915 字节的 `rea-manifest.xml`，组件、权限、进程名均可见 |
| MQTT 服务可绑定 | `com.digital.mqttlib.service.DMqttService`，`exported=true`，无组件权限，主进程，action `com.fuxi.iovcloud.agent.action.MqttService.START` |
| 数字钥匙服务不能直接绑 | `com.ingeek.vck.alive.service.DigitalKeyService` 虽 `exported=true`，但被同名自定义权限保护，且当前未运行；2026-10-19 用 `am start-service`（uid 2000）实测返回 `Requires permission com.ingeek.vck.alive.service.DigitalKeyService`，且 `pm list permissions` 中没有任何包定义该权限，守护声明也无法获得 |
| 心跳接收器可用 | `com.ingeek.vck.alive.receiver.BootBroadcastReceiver`，`exported=true`，要求发送方持有 `RECEIVE_BOOT_COMPLETED`（守护已声明） |
| BLE 栈在主进程 | `cn.com.heaton.blelibrary.ble.BleRequestImpl`、`com.inuker.bluetooth.library.BluetoothService` 都在主进程，不在 `:alive` |

因此守护选择**锚定主进程 + 绑定 MQTT 服务**，而不是去碰受保护的 `:alive`。
证据 ID：`ev_dd3eeb09ca17826c6c086563c45ab149691dc050a6eb716e391c795f4085da7b`（包检查）、
`ev_a75aea8ad16392d5114097a92aab707f68f5eaa1f8601e018906ec603d3e1f86`（类检索）。

## 4. 逆向分析：三份报告

| 报告 | 内容 |
|---|---|
| [`车机投屏宝-逆向剖析报告.md`](车机投屏宝-逆向剖析报告.md) | 加固方式、模块地图、双端角色分发、投屏链路、ADB 提权、产品本质、商业模型、风险评估 |
| [`手机端与车机端实现差异报告.md`](手机端与车机端实现差异报告.md) | 同一 APK 的双角色对比：178 个组件统计、launchMode/configChanges 取向、功能矩阵、编解码归属、提权链路方向 |
| [`守护部署与自愈说明.md`](守护部署与自愈说明.md) | 守护 App 的部署步骤、实测结论、排障、踩坑记录 |
| [`MuMu模拟车机端测试手册.md`](MuMu模拟车机端测试手册.md) | 用 MuMu 模拟器充当车机端的完整搭建记录与失败分析 |
| [`技术分析报告.html`](技术分析报告.html) | 可视化版，单文件、无外部依赖、含 SVG 架构图与「只看已证实」筛选 |

## 5. 核心结论速查

**① 它不是投屏工具，是网约车司机的车机多开接单工作台。**
证据：字段 `taxiPackageName`、硬编码 `com.sdu.didi.gsui`（滴滴车主）与 `com.szzc.ucar.ptdriver`、`RobotOrderOcrBean` 与独立 `ocrlib`、载客 TTS 文案（"请系好安全带""您的行程即将到达目的地"）。

**② 同一 APK，手机端编码、车机端解码。**
`phone.cast.PhoneCastVideoCoder` 编码，`car.cast.MyVideoDecoder` 解码。触控反向回注。

**③ 它移植了 scrcpy。**
`com.sven.commoncast.scrcpy` 包内是 scrcpy 的 Java 侧代码（`AffineMatrix` / `PointersState` / `Position` / `Size` / `Orientation` / `TouchEventHelper` / `VideoFilter` / `oldcontrol`），这也解释了为什么必须拿 shell 身份。

**④ "激活 ADB"的电脑工具只执行一行 `adb tcpip 5555`。**
之后 adbd 无网络侧认证，而 App 的连接指引正是"车机连接手机热点"——同网段即等于交出手机 shell。
**更安全的替代**：车机端「连接设置 → 无线调试激活手机ADB」走的是 Android 11+ 配对流程（IP + 端口 + 配对码）。

**⑤ 画面过小的正主开关在车机端，不在手机端。**
车机端「更多设置 → 画面设置 → 自定义车机屏幕DPI」（实测默认 340，"数值越大字体越大"）。
底层原因是单窗口模式把 1440×3168 / 640dpi 整屏等比压进车机屏，缩放比约 0.227。

**⑥ 投屏授权框只存在于单窗口路径。**
多窗口模式的采集发生在 shell 进程，与 `screenrecord` 同理，**不需要 MediaProjection 授权**。

## 6. 构建与安装

### 6.1 依赖

- JDK 17（`javac -source 1.8 -target 1.8`）
- Android SDK：`platforms;android-33`（`android.jar`）+ `build-tools;33.0.1`（`aapt2` / `d8` / `zipalign` / `apksigner`）
- 无需 Gradle
- 注：build-tools 33 的 `d8.bat` 在 JDK 17 下会因 `java.ext.dirs` 报错，`build.sh` 已改为直接调用
  `java -cp build-tools/33.0.1/lib/d8.jar com.android.tools.r8.D8`

### 6.2 构建

```bash
cd guardapp
bash build.sh
# 产物：guardapp/mp-guard.apk（约 0.5 MB，已签名）
```

`build.sh` 会自动读取 `ANDROID_SDK_ROOT` / `ANDROID_HOME`，也兼容本机默认 SDK 路径与
`build-tools/33.0.1`；可用 `ANDROID_BUILD_TOOLS`、`ANDROID_JAR` 显式覆盖。
Windows 与 Linux 共用同一脚本，本机与 GitHub Actions 的构建命令一致。

签名使用本地 `guardapp/mpguard.ks`（已在 `.gitignore` 中）。密码可用
`MPGUARD_KS_PASS`、`MPGUARD_KEY_PASS`、`MPGUARD_KEY_ALIAS` 覆盖。
**换签名后无法覆盖安装旧包**，`adb install -r` 报签名不一致时需先卸载。

### 6.3 安装与一次性授权

```bash
adb install -r guardapp/mp-guard.apk

# 一条命令完成：无障碍列表、投屏宝 appops、哪吒 BLE/后台权限、Doze 白名单/待机桶
bash rearm.sh
```

其中守护 App 自身的关键授权：

```bash
adb shell pm grant cn.luohui.mpguard android.permission.WRITE_SECURE_SETTINGS
adb shell cmd appops set cn.luohui.mpguard BACKGROUND_START_ACTIVITY allow
adb shell cmd appops set cn.luohui.mpguard SCHEDULE_EXACT_ALARM allow
for op in RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
  adb shell cmd appops set cn.luohui.mpguard $op allow
done
adb shell dumpsys deviceidle whitelist +cn.luohui.mpguard
adb shell am start -n cn.luohui.mpguard/.StatusActivity
```

哪吒侧的权限与保活条件（`rearm.sh` 会自动执行）：

```bash
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE \
         ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION; do
  adb shell pm grant com.hezhong.nezha android.permission.$p
done
for op in RUN_ANY_IN_BACKGROUND START_FOREGROUND WAKE_LOCK; do
  adb shell cmd appops set com.hezhong.nezha $op allow
done
adb shell dumpsys deviceidle whitelist +com.hezhong.nezha
adb shell am set-standby-bucket com.hezhong.nezha 5
```

> 首次写回无障碍开关时，ColorOS 安全中心可能弹「检测到"车控守护"获取无障碍权限」，
> 选择 **保持开启**。这个门控也正是重启后无障碍条目被剪掉的来源。

### 6.4 兜底与核对

- 手机端：点一下桌面「车控守护」图标（Activity 启动即自愈并重新绑定哪吒）
- 电脑端：`bash rearm.sh`
- 重启后体检：`bash verify_after_reboot.sh`（38 项，覆盖双通道）

### 6.5 GitHub Actions 与发布

工作流：`.github/workflows/android.yml`，在 Ubuntu runner 上安装 JDK 17 与 Android SDK
（`platforms;android-33` + `build-tools;33.0.1`），直接调用同一个 `guardapp/build.sh`。

- **每次 push**（任意分支）：构建 APK，上传为 Actions artifact，命名 `mp-guard-<短 SHA>.apk`。
- **打 tag**（`v1.15`、`v1.15.1`）：额外创建 GitHub Release，附件为 `mp-guard-<tag>.apk`，
  并用 tag 自动改写 `versionName` 与 `versionCode`（`1.15` → `11500`），无需手工改 Manifest。
- Release 产物使用仓库 Secrets 中的同一把签名密钥，签名与已安装版本一致，可直接覆盖安装：
  - `MPGUARD_KS_BASE64`：`guardapp/mpguard.ks` 的 Base64
  - `MPGUARD_KS_PASS` / `MPGUARD_KEY_PASS`：keystore 与 key 密码
  - `MPGUARD_KEY_ALIAS` 可选，默认 `mpguard`
- 触发发布：`git tag v1.15 && git push origin v1.15`。

## 7. 已知限制

- **依赖 ColorOS 的 `development` 级权限可用性**。若该 adb 授权通道被关闭，`pm grant` 会失败，此时只能退回 `rearm.sh`（需要电脑）。
- **哪吒保活依赖其导出组件名**：`DMqttService` / `BootBroadcastReceiver` 若在 App 升级后改名或改为不导出，绑定会失败；跑一次 `verify_after_reboot.sh` 可立刻发现。
- **`:alive` 数字钥匙服务不能直接绑定**：它被同名自定义权限保护，守护未持有；2026-10-19 实测 `am start-service` 被 `Permission Denial` 拒绝，且系统中没有该权限的定义。当前 BLE 栈在主进程，所以锚定主进程成立；若未来版本把 BLE 彻底搬进 `:alive`，需要重新分析。
- **强行停止无法被任何用户态方案恢复**：设置里的「强行停止」、卸载、或系统级冻结会解除绑定；这不是重绑逻辑能覆盖的。
- **绑定提升的是优先级，不是永生**：ColorOS 极端省电、内存压力极大时仍可能回收，但会由 `onBindingDied` + 30s watchdog 自动重连。
- **10 分钟闹钟依赖 `SCHEDULE_EXACT_ALARM`**：该 appop 若被系统收回，会退化为非精确闹钟，兜底时间窗变长。
- **第二路直连闹钟是单向兜底**：它仍由守护排定并续期；守护被强行停止、卸载或重启后会失效。它只覆盖“守护进程被回收、第一路闹钟没能拉起守护”的窗口，不是独立于守护的永生通道。
- **尚无真车端到端验证**：本文的结论来自 `dumpsys`、logcat 与静态 Manifest；能证明进程/绑定/蓝牙扫描在锁屏和 Doze 下仍活着，但不能替代走到车前实测一次解锁时延。
- **`adb shell` 在部分 ColorOS 版本上不再持有 `START_ANY_ACTIVITY`**，非导出的车机端 Activity 需要 root 才能启动。
- **`PROJECT_MEDIA` 一旦被点「拒绝」即回到 `ignore`**，守护会把开关补回去，但那个动作本身要避免。
- 守护 App 持有 `WRITE_SECURE_SETTINGS` 且作为无障碍服务**有能力读取全屏内容**——这是实现开机自愈的必要代价。不需要时直接卸载，不影响「车机投屏宝」与「哪吒汽车」本身。
- 哪吒 App 未安装时，其目标卡显示未安装，守护逻辑自动跳过，不影响投屏宝链路。
- MuMu 模拟器作为车机端**未打通端到端连接**：桥接在 Hyper-V 引擎下会使 VM 无法启动；且办公网跨 VLAN 存在隔离。详见测试手册。

## 8. 方法论与证据分级

制品受 **SecShell** 加固：`classes.dex` 仅 640 个 class_def 且不含 `com.sven.*`，真实类定义被剥离，字符串/类型/方法表保留。
因此静态分析能拿到"有哪些类、哪些字段、哪些文案"，但拿不到分支逻辑。

本仓库所有结论标注了两级证据：

- **已证实**：有原始字符串 / smali / 设备 `dumpsys` 实测支撑
- **推断**：未拿到分支代码

典型例子：`res/hh.jar`（提权服务）**未加固**，2430 个类可完整反编译，其中的隐藏 API 调用是已证实；
而"谁是 TCP 服务端"最初只能推断，后来通过车机端「手动连接」的界面文案实测确认为**车机主动连手机**。

哪吒包体则相反：代码被加固，但 Manifest 没有被加密。REA 6.7.0 解出的组件表是**已证实**，
BLE 实现在哪个进程由 JADX 类枚举与设备上的 `dumpsys` 交叉确认；加固壳内部的业务流程仍属**未知**，本文不对其做推断。

## 9. 免责声明

- 本项目仅用于**分析用户自有设备上已安装应用的行为与安全风险**，以及在此基础上的自用工具开发。
- 不包含、不再分发「车机投屏宝」的任何代码、二进制或美术资源；报告中的类名、字段名与文案属于分析性引用。
- 逆向得到的提权链路与自动化接单能力**未在本仓库中实现**，也不建议用于违反平台服务协议的场景（自动抢单/多开存在账号封禁风险）。
- 请自行评估在自有设备上授予 `WRITE_SECURE_SETTINGS` 与无障碍权限的风险。
