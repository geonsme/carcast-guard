# carcast-guard

> 面向「车机投屏宝」（`com.sven.mycar`）的双端逆向分析，以及在 ColorOS 上让投屏授权**重启后自动恢复**的自研守护 App。

本仓库只包含**我自己编写的代码与文档**。
「车机投屏宝」是闭源商业应用，**本仓库不包含它的源码、APK、 dex、资源或图标**——仓库中没有它的源代码可找，只有基于公开制品（用户自有设备上已安装的 APK）做的分析与自研工具。

---

## 目录

- [1. 背景：一个被忽略的小问题](#1-背景一个被忽略的小问题)
- [2. 仓库内容](#2-仓库内容)
- [3. 投屏授权守护 App](#3-投屏授权守护-app)
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

于是做了两件事：

- 把 App 的结构与权限链路完整逆向了一遍，搞清楚"为什么"；
- 写了一个**开机自愈**的小型无障碍守护 App，解决"重启后没人自动恢复"的问题。

## 2. 仓库内容

```
.
├── README.md
├── .gitignore
├── guardapp/                      # 自研守护 App 源码（Java，无 Gradle 手工构建）
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── drawable/ic_guard_foreground.xml   # 矢量图标（深炭黑 + 绿→黄绿渐变）
│   │   ├── mipmap-anydpi-v26/ic_launcher.xml  # 自适应图标
│   │   ├── values/{colors,strings}.xml
│   │   └── xml/guard_config.xml               # 无障碍服务配置
│   ├── src/cn/luohui/mpguard/
│   │   ├── GuardService.java      # 无障碍服务：识别并关闭投屏授权框
│   │   ├── Rearm.java             # 自愈核心：写回无障碍开关
│   │   ├── RearmJobService.java   # 持久化 JobScheduler（开机由框架恢复）
│   │   ├── BootReceiver.java      # BOOT_COMPLETED 兜底
│   │   └── StatusActivity.java    # 状态页：服务名/加载状态/操作反馈
│   ├── build.sh                   # aapt2 + javac + d8 + zipalign + apksigner
│   ├── render_preview.js          # 图标矢量栅格化预览
│   └── mp-guard.apk               # 已签名成品（v1.11，约 25 KB）
├── mp_guard.sh                    # shell 级兜底守护（设备端，需 adb）
├── rearm.sh                       # 电脑端一条命令恢复
├── verify_after_reboot.sh         # 重启后逐项核对脚本
├── 车机投屏宝-逆向剖析报告.md       # 完整逆向分析
├── 手机端与车机端实现差异报告.md    # 同一 APK 双角色对比
├── 守护部署与自愈说明.md           # 守护 App 的部署与排障
├── MuMu模拟车机端测试手册.md       # 用模拟器充当车机端的搭建记录
└── 技术分析报告.html               # 可视化版（单文件、离线可开）
```

## 3. 投屏授权守护 App

包名 `cn.luohui.mpguard`，自签，约 25 KB，**无网络权限**。

### 3.1 它解决的问题

ColorOS 16 在重启后会把 `accessibility_enabled` 置 `0`，虽然启用列表还在，但没有任何无障碍服务被绑定。
而重启后 shell 身份也拿不到 `GRANT_RUNTIME_PERMISSIONS` / `MANAGE_APP_OPS_MODES`，所以"用 adb 修一下"在车里不可行。

### 3.2 机制

```
开机
 └─ JobScheduler 恢复持久化任务（绕过被 ColorOS 拦截的 BOOT_COMPLETED）
     └─ Rearm.rearm()
         ├─ 读 enabled_accessibility_services，按 ComponentName 归一化去重
         ├─ 缺失则补写（车机投屏宝 + 本应用两条）
         ├─ accessibility_enabled 0→1（强制触发 AccessibilityManagerService 重读）
         └─ 约 30 秒内两个无障碍服务重新被系统 bind
```

关键点：

- **`WRITE_SECURE_SETTINGS` 是 `signature|privileged|development` 级权限**，其中 `development` 位允许用 `adb shell pm grant` 一次性授予普通应用，且授予后随包持久化。这是整个方案能免 root 成立的基础。
- **用户意图与系统漂移要分开**：用户主动「停用守护」时写入 SharedPreferences 标记，并且检查下沉在 `Rearm.rearm()` 内部，避免 `GuardService.onServiceConnected()` 之类的其他入口把它自作主张恢复。
- **`ComponentName` 归一化是必须的**：设置列表里是全写 `pkg/pkg.Cls`，而 `AccessibilityServiceInfo.getId()` 常返回缩写 `pkg/.Cls`，直接字符串比对必然失配（且会导致列表重复条目）。

### 3.3 状态页

- 解析每个无障碍条目对应的**真实 App 名**（`PackageManager.getApplicationLabel`）
- 区分 **已启用**（列表里有）与 **已被系统加载**（`getEnabledAccessibilityServiceList` 确认已 bind）
- 显示定时任务是否在册、`WRITE_SECURE_SETTINGS` 是否已授予
- 每个操作四重反馈：按钮态 → Toast → 带时间戳的结果行 → 整页重算；页面每 3 秒自动刷新
- 提供「停用守护 / 启用守护」开关

### 3.4 图标

与「车机投屏宝」同一套视觉语言：深炭黑 `#1E2022` 底 + 绿→黄绿渐变描边
（`#84D87B → #BAF974 → #5B8557`，取自其图标的实测色值），母题为盾牌 + 对勾。

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

- JDK 21
- Android SDK：`platforms;android-34`（提供 `android.jar`）+ `build-tools;34.0.0`（提供 `aapt2` / `d8` / `zipalign` / `apksigner`）
- 无需 Gradle

### 6.2 构建

```bash
cd guardapp
# 修改 build.sh 顶部的 AJ / BT 两个路径为本机 SDK 路径
bash build.sh
# 产物：guardapp/mp-guard.apk
```

### 6.3 安装与一次性授权

```bash
adb install -r guardapp/mp-guard.apk

# 关键：development 级权限，可被 adb 一次性授予
adb shell pm grant cn.luohui.mpguard android.permission.WRITE_SECURE_SETTINGS
adb shell cmd appops set cn.luohui.mpguard BACKGROUND_START_ACTIVITY allow
adb shell cmd appops set cn.luohui.mpguard POST_NOTIFICATION allow
adb shell dumpsys deviceidle whitelist +cn.luohui.mpguard

# 打开一次以登记持久化任务（之后由框架在开机时自行恢复）
adb shell am start -n cn.luohui.mpguard/.StatusActivity
```

> 首次写回无障碍开关时，ColorOS 安全中心会弹「检测到"投屏授权守护"获取无障碍权限」，
> 选择 **保持开启**。这个门控也正是重启后无障碍条目被剪掉的来源。

### 6.4 兜底

- 手机端：点一下「投屏授权守护」桌面图标（Activity 启动即自愈）
- 电脑端：`bash rearm.sh`

## 7. 已知限制

- **依赖 ColorOS 的 `development` 级权限可用性**。若该 adb 授权通道被关闭，`pm grant` 会失败，此时只能退回 `rearm.sh`（需要电脑）。
- **`adb shell` 在部分 ColorOS 版本上不再持有 `START_ANY_ACTIVITY`**，非导出的车机端 Activity 需要 root 才能启动。
- **`PROJECT_MEDIA` 一旦被点「拒绝」即回到 `ignore`**，守护会把开关补回去，但那个动作本身要避免。
- 守护 App 持有 `WRITE_SECURE_SETTINGS` 且作为无障碍服务**有能力读取全屏内容**——这是实现开机自愈的必要代价。不需要时直接卸载，不影响「车机投屏宝」本身。
- MuMu 模拟器作为车机端**未打通端到端连接**：桥接在 Hyper-V 引擎下会使 VM 无法启动；且办公网跨 VLAN 存在隔离。详见测试手册。

## 8. 方法论与证据分级

制品受 **SecShell** 加固：`classes.dex` 仅 640 个 class_def 且不含 `com.sven.*`，真实类定义被剥离，字符串/类型/方法表保留。
因此静态分析能拿到"有哪些类、哪些字段、哪些文案"，但拿不到分支逻辑。

本仓库所有结论标注了两级证据：

- **已证实**：有原始字符串 / smali / 设备 `dumpsys` 实测支撑
- **推断**：未拿到分支代码

典型例子：`res/hh.jar`（提权服务）**未加固**，2430 个类可完整反编译，其中的隐藏 API 调用是已证实；
而"谁是 TCP 服务端"最初只能推断，后来通过车机端「手动连接」的界面文案实测确认为**车机主动连手机**。

## 9. 免责声明

- 本项目仅用于**分析用户自有设备上已安装应用的行为与安全风险**，以及在此基础上的自用工具开发。
- 不包含、不再分发「车机投屏宝」的任何代码、二进制或美术资源；报告中的类名、字段名与文案属于分析性引用。
- 逆向得到的提权链路与自动化接单能力**未在本仓库中实现**，也不建议用于违反平台服务协议的场景（自动抢单/多开存在账号封禁风险）。
- 请自行评估在自有设备上授予 `WRITE_SECURE_SETTINGS` 与无障碍权限的风险。
