# XDS Display · 骑行显示终端

> 装一根旧手机上就能当码表用的安卓骑行**显示终端**：蓝牙功率计 / GPS / 车身传感器 → 车把上的仪表。
> 采集与转发遥测的中继端（另一台手机或硬件）不在本仓库内。

纯 Java + 原生 View 手绘，只有 `appcompat` 和 `material` 两个依赖，没有第三方图表/地图库。

---

## 功能

**数字卡**（等宽字型，数值跳动时字距不抖）
- 功率 / 速度 / 里程 / 均速 / 踏频 / 骑行时间
- 速度卡右侧「航空 HUD 式」竖直速度带（当前车速固定在中间一行，上下滚动）
- 功率折线 + 速度折线图，各自按窗口内最大值自适应量程
- 功率卡下沿的左右脚功率平衡条（来自骑行功率量测的 Balance 字段）

**地图**
- OpenStreetMap 瓦片（Web Mercator）+ 实时轨迹 + 当前位置
- GPX 路书导入与贴合、按区域下载离线瓦片（没网也能出图，无瓦片时退化成网格 + 轨迹）
- 单指拖动 / 双指缩放 / 一键跟随

**车身传感**
- 道路颠簸条：z 轴加速度的局部方差（量程与灵敏度可调）
- G 力圆盘（已做重力补偿的姿态融合）
- 风噪电平条：麦克风 200–1500 Hz 频段能量，直接当"风阻表"用

**其他**
- 挡位 / 齿比估算（预置喜德盛 AD350 2025：1×12 单盘 48T + 11-48T 飞轮、700c 周长 2.146 m；换车只改齿数表与轮周长）
- 中 / 英界面切换（AppCompat per-app locale，不改系统语言）
- 界面配色可用 Hex 自定义；全屏 / 窗口、横竖屏切换
- 设置 → 关于：作者与开源仓库 Issues 入口

## 按键

骑车时手不用离开车把：

| 键 | 作用 |
| --- | --- |
| 音量上 | 折线图 ⇄ 地图（与屏幕上 VIEW 键同效） |
| 音量下 | G 力球 ⇄ WIND/BUMP 卡片：这两块在布局里共用同一格位，切走的那块会把整行让出来 |

两个开关（`main_view_chart` / `main_show_ball`）都记在本地，下次启动照旧。

数据区自上而下按权重排布：**功率 / 速度**（最大）→ 里程 / 踏频+挡位 → 风噪⇄G 力 → 底部「骑行时间 / 均速」双卡，
让 Power / Speed / RPM 这些骑行中真正要看的数字占满屏幕。

## 数据来源

| 链路 | 说明 |
| --- | --- |
| BLE（推荐） | 扫描并连接中继广播的标准蓝牙骑行功率服务（`0x1818` / `0x1819`），订阅 `0x2A63` 骑行功率量测；带位置信息时一并取用。掉线自动重连 |
| BLE（备选） | 部分中转机型的 GATT 服务端不回 CCCD 写请求，因此另有「只扫描广播」与「本机当 GATT 服务端」两条备用链路 |
| WiFi UDP | 中继开热点 → 本机连上热点 → 中继用 UDP 把遥测打过来 |
| 本机传感器 | GPS（速度 / 里程 / 轨迹）、加速度计 + 陀螺（颠簸 / G 力）、麦克风（风噪） |

## 构建

- JDK 17、Android SDK（`compileSdk 34` / `minSdk 26` / `targetSdk 34`），Android Gradle Plugin 8.4.0
- 命令行：

  ```bash
  echo "sdk.dir=/path/to/Android/Sdk" > local.properties
  ./gradlew assembleDebug
  ```

  产物：`app/build/outputs/apk/debug/app-debug.apk`
- 也可以直接用 Android Studio 打开仓库根目录。

## 自检（不需要手机）

`verify/` 下是**纯算法部分的 PC 端测试**：这些类刻意不带 Android 依赖，`javac` 直接编译就能跑，用来在没有真机的情况下回归挡位估算、功率解码、姿态融合、颠簸度量、风噪滤波：

```bash
cd verify/gear/_pc
javac GearRatio.java GearRatioTest.java && java GearRatioTest
```

（运行输出是中间产物，已在 `.gitignore` 里排除。）

## 开源许可

代码以 **MIT** 发布，见 [LICENSE](LICENSE)。
内置数字字体 Orbitron Black 为 **SIL OFL 1.1**，许可全文见 [licenses/OFL-Orbitron.txt](licenses/OFL-Orbitron.txt)。

## 反馈

问题与建议请到 [Issues](https://github.com/NoraNekowww/XdsDisplay/issues) 提；也欢迎直接 PR。
[English README](README.en.md)

## 免责声明

骑行中操作设备有风险，请把手机固定在车把支架上、骑行中不要触摸屏幕；本项目不对任何骑行事故负责。
