<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="PuddingPods Icon"/>

# PuddingPods

**为 HyperOS 设备提供系统级 MOONDROP 水月雨 PUDDING 耳机控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS%203-orange?style=flat-square)](https://hyperos.mi.com)

**[繁體中文](README.md)** | **简体中文** | **[English](README_EN.md)**

</div>

为小米 HyperOS 设备提供系统级 **MOONDROP 水月雨 PUDDING / MD-TWS-056** 耳机控制的 Xposed 模块。

当前版本：**3.47**  
应用 ID：`rongyi.puddingpods`

### 耳机功能

- **降噪控制** — 支持关闭 / 通透 / 基础降噪 / 自适应降噪 / 抗风噪。
- **三路电量** — 直接读取左耳、右耳和充电盒的 PUDDING GAIA 电量。
- **耳机端模式同步** — 耳机长按切换 ANC 后，通过主动回报或约 3 秒状态轮询同步模式，并一并刷新三路电量。
- **增益控制** — 支持低 / 中 / 高三档输出增益。
- **指示灯控制** — 支持耳机指示灯开关。

### HyperOS 集成

- **超级岛 / Focus Island** — 通过 HyperOS Focus Notification 显示耳机状态，点击进入耳机控制弹窗。
- **融合设备中心** — 支持 HyperOS 设备中心耳机卡片入口。
- **系统设置集成** — 接入 HyperOS 高级耳机页面与系统蓝牙设备设置。
- **型号兼容** — 内部固定使用兼容 Device ID `01010607`，无需用户手动设置伪装型号。

### 模块功能

- **快捷弹窗** — 点击超级岛或耳机入口，显示电量、降噪和快捷控制。
- **独立详情页** — 提供 PUDDING 产品图、电量、ANC、增益、指示灯和应用设置。
- **多语言** — 支持繁體中文、简体中文、English 和跟随系统；首次安装默认繁體中文。

### 水月雨 PUDDING 适配

PuddingPods 直接通过 Classic Bluetooth RFCOMM / SPP 与耳机通信，并使用 Qualcomm GAIA v4 控制 PUDDING；不依赖水月雨官方 App 常驻。

协议、命令、电量解析和 HyperOS 映射见 [PUDDING_ADAPTATION.md](PUDDING_ADAPTATION.md)。

### 系统要求

- 小米设备，运行 **HyperOS**（Android 15+；超级岛相关功能以 HyperOS 3 为主要适配目标）。
- **LSPosed** API 版本 >= 101。

### 使用

1. 安装 APK。
2. 在 LSPosed 中启用模块并勾选推荐作用域。
3. 重启所需作用域或直接重启手机。
4. 通过蓝牙连接 MOONDROP PUDDING。

### 致谢

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始项目基础。
- [OppoPods](https://github.com/1812z/OppoPods) — HyperOS 耳机集成与实现参考。
- **HyperOriG** — HyperOS 多进程接入、Focus Island 与设备卡路线参考。
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件。
- 更改者：**融億**  
  酷安：`http://www.coolapk.com/u/30640322`

### 许可证

GPL-3.0
