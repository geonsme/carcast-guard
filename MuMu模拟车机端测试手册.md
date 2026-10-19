# 用 MuMu 模拟器搭车机端 · 本地测试手册

目标：在没有真车机的情况下，用电脑上的 MuMu 模拟器充当「车机投屏宝」的车机端，与一加12 上的手机端做本地联调。
环境：MuMu Player 12（v6.4.6.0，Hyper-V 引擎，Android 15，x86_64）· 样本 APK `com.sven.mycar` v23.9.70
日期：2026-10-09

---

## 一、已验证可行的部分（全部实测）

| 环节 | 结论 | 证据 |
|---|---|---|
| SecShell 壳是否拦模拟器 | **不拦** | App 正常启动，无闪退，进程常驻 |
| ARM 原生库能否加载 | **能** | `ro.product.cpu.abilist = x86_64,arm64-v8a,x86`，MuMu 自带 ARM 转译 |
| App 自判角色 | **判成手机端** | 冷启动落地 `PhoneHomeActivity` |
| 车机端界面能否强开 | **能，但需 root** | root 后 `am start CarMainActivity` 成功，界面完整渲染 |
| 车机端服务端口 | TCP **10248** / UDP **8100** | `netstat -ltnp` 归属 `com.sven.mycar` |
| 模拟器出站连通 | **通** | guest ping 网关 10.201.11.1 往返 1.6ms |

## 二、关键认知纠正

最初我根据"车机显示二维码（含车机自己的 IP）+ 手机扫码"推断**车机是服务端、手机连过来**。
实测车机首页的 **「手动连接」→「手动连接投屏：手动输入手机IP地址完成连接投屏」** 证明：
**车机端可以主动去连手机的 IP。** 这个方向差异决定了 NAT 模式是否可用 —— 出站方向 NAT 是支持的，
所以**不需要桥接也有机会打通**。（二维码那条是另一种配对流程，两者并存。）

App 自己在校验提示里写：「确保车机和手机的IP地址前面两个数字相同（车机当前IP地址：10.0.2.15）」
—— 这是它的启发式检查，不是硬限制，手动输入框可以填任意 IP。

## 三、踩过的坑与结论

### 桥接模式（已放弃）
1. `net_bridge_open` 是可写键，`net_bridge.enabled` 不可写（GUID 与网卡名两种写法都试过）。
2. 未装驱动前，`net_bridge_card.list` 恒为 `[]`，设置 `net_bridge_open=true` 会被**静默回滚**。
3. 管理员执行 `MuMuManager.exe driver install -n lwf` 后，全局 `net_bridge.enabled` 变 true，
   但 `net_bridge_card.list` **仍然为空**（机器上 TAP-Windows Adapter V9 处于 Disconnected，MuMu 不认）。
4. 强行开启桥接后 **Hyper-V 引擎的 VM 卡死**：`is_process_started=true` 而 `is_android_started=false`。
   关闭桥接后恢复正常。
5. 额外风险：本机是域内办公机（主机名 `CORPDESK`，`Get-VMSwitch` 被授权策略拒绝），
   PC 在 `10.201.11.0/24`、手机曾在 `10.201.102.0/24`，跨 VLAN 通常有客户端隔离。

**结论：不要走桥接。** 走"车机主动连手机"的出站方向即可。

### 未导出的车机端 Activity
清单里 `CarMainActivity` 等 20 个车机 Activity **均未导出、也没有 deeplink**（scheme 只有微信/淘宝/https）。
Android 15 的 shell 已不再默认持有 `START_ANY_PERMISSION`，普通 adb 直接起会被拒：
```
java.lang.SecurityException: Permission Denial: starting Intent ... not exported from uid 10044
```
MuMu 的 `root_permission` 默认为 true，`adb root` 后以 uid=0 即可绕过。

## 四、操作手册

### 0. 变量
```bash
MM="/d/Software/MUMU模拟器/nx_main/MuMuManager.exe"
```

### 1. 启动模拟器并拿 root
```bash
cd "/d/Software/MUMU模拟器/nx_main"
./MuMuManager.exe control -v 0 launch
./MuMuManager.exe adb -v 0 -c "root"          # restarting adbd as root
```

### 2. 安装同一个 APK
```bash
./MuMuManager.exe adb -v 0 -c "push C:\\temp\\apk\\carcast.apk /data/local/tmp/carcast.apk"
./MuMuManager.exe adb -v 0 -c "install -r -t C:\\temp\\apk\\carcast.apk"
```
首次启动会走隐私政策 + 穿山甲开屏广告，需要点「同意」并跳过广告
（广告是 SurfaceView 自绘，uiautomator 读不到节点，只能按截图像素比例估坐标点）。

### 3. 强制进入车机端界面
```bash
./MuMuManager.exe adb -v 0 -c \
  "shell am start -n com.sven.mycar/com.sven.mycar.car.view.CarMainActivity"
```
落地后应看到：标题「车机投屏宝」、二维码、`IP地址: 10.0.2.15`、
按钮「等待双端连接 / 连接教程 / 手动连接 / 手机APP / 更多设置 / 联系我们 / 退出应用」。

### 4. 组网（推荐拓扑，绕开企业网 VLAN 隔离）
让**手机开热点、电脑连进去**，这样两端必然同网段且出站可达：
- 手机热点 IP 通常是 `192.168.43.1`
- PC 连上该热点
- 模拟器出站经 PC NAT → 可直接访问 `192.168.43.1`

然后在车机端点 **「手动连接」**，四段输入框填 `192 . 168 . 43 . 1` → 「开始连接」。

### 5. 读取手机端 IP（需要手机插 USB）
```bash
adb shell "ip -4 addr show wlan0 | grep inet"
# 或直接在手机上：车机投屏宝 首页 → 局域网IP
```

## 五、顺带挖到的两个高价值结论（比测试本身更有用）

### 1. 「画面小到离谱」的正主开关在车机端
车机端 **更多设置 → 画面设置** 完整选项（实测读出）：

| 选项 | 当前值 / 说明 |
|---|---|
| 画质和延迟设置 | **高清（推荐）**；"画质越高画面越清晰，但性能损耗越大，延迟越高" |
| 投屏窗口底部按键样式大小/隐藏 | 自定义返回/首页/后台等按键样式或隐藏 |
| **自定义车机屏幕DPI** | **当前 340**；"设置App的字体大小，数值越大字体越大" |
| 显示车机顶部状态栏 | 打开后顶部状态栏始终显示 |
| 显示车机底部导航栏 | 打开后底部操作栏始终显示 |
| 悬浮方向盘大小 | 自定义悬浮方向盘显示大小 |

**→ 把「自定义车机屏幕DPI」调大，就是解决画面过小的直接手段。** 之前我们只能推断"设置项在车机端"，
现在拿到了具体项名、当前值和语义。

### 2. 车机端「连接设置」全项（实测）

| 选项 | 说明 |
|---|---|
| 无线调试激活手机ADB | **手动输入 IP、端口和配对码**来完成激活手机 ADB 服务 |
| 数据线激活手机ADB | 借助 USB 数据线，车机一键激活手机 ADB |
| USB设备管理 | 禁用车机自带 USB 设备，避免干扰连接导致投屏失败 |
| **低功耗模式** | 耗电小、延迟低，但**无法悬浮窗**；官方建议"打开该模式重新投屏试试看" |
| 停用USB有线连接 | 解决部分车机自带 USB 配件反复弹权限框（开启后无法有线投屏） |

「无线调试激活手机ADB」这一项意味着**激活动作可以由车机端独立完成**（输入手机的 IP + 配对码），
不必依赖那个 `adb tcpip 5555` 的电脑脚本 —— 这对安全性是好消息。

## 六、还没做完的

- 端到端连接未验证：需要手机插 USB 读当前 IP，并把 PC 连进手机热点。
- `其他设置` 与 `声音设置` 两个子菜单未展开读取。
- 手机端在 MuMu 场景下的配对（扫码）无法用摄像头完成，只能走「手动连接」这条路。
