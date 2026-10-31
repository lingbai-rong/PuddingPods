<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="PuddingPods Icon"/>

# PuddingPods

**為 HyperOS 裝置提供系統級 MOONDROP 水月雨 PUDDING 耳機控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS%203-orange?style=flat-square)](https://hyperos.mi.com)

**繁體中文** | **[简体中文](README_CN.md)** | **[English](README_EN.md)**

</div>

為小米 HyperOS 裝置提供系統級 **MOONDROP 水月雨 PUDDING / MD-TWS-056** 耳機控制的 Xposed 模組。

目前版本：**3.47**  
應用程式 ID：`rongyi.puddingpods`

### 耳機功能

- **降噪控制** — 支援關閉 / 通透 / 基礎降噪 / 自適應降噪 / 抗風噪。
- **三路電量** — 直接讀取左耳、右耳和充電盒的 PUDDING GAIA 電量。
- **耳機端模式同步** — 耳機長按切換 ANC 後，透過主動回報或約 3 秒狀態輪詢同步模式，並一併刷新三路電量。
- **增益控制** — 支援低 / 中 / 高三檔輸出增益。
- **指示燈控制** — 支援耳機指示燈開關。

### HyperOS 整合

- **超級島 / Focus Island** — 透過 HyperOS Focus Notification 顯示耳機狀態，點擊進入耳機控制彈窗。
- **融合裝置中心** — 支援 HyperOS 裝置中心耳機卡片入口。
- **系統設定整合** — 接入 HyperOS 高級耳機頁面與系統藍牙裝置設定。
- **型號相容** — 內部固定使用相容 Device ID `01010607`，無需使用者手動設定偽裝型號。

### 模組功能

- **快捷彈窗** — 點擊超級島或耳機入口，顯示電量、降噪和快捷控制。
- **獨立詳情頁** — 提供 PUDDING 產品圖、電量、ANC、增益、指示燈和應用程式設定。
- **多語言** — 支援繁體中文、简体中文、English 和跟隨系統；首次安裝預設繁體中文。

### 水月雨 PUDDING 適配

PuddingPods 直接透過 Classic Bluetooth RFCOMM / SPP 與耳機通訊，並使用 Qualcomm GAIA v4 控制 PUDDING；不依賴水月雨官方 App 常駐。

協定、命令、電量解析和 HyperOS 映射請見 [PUDDING_ADAPTATION.md](PUDDING_ADAPTATION.md)。

### 系統需求

- 小米裝置，執行 **HyperOS**（Android 15+；超級島相關功能以 HyperOS 3 為主要適配目標）。
- **LSPosed** API 版本 >= 101。

### 使用

1. 安裝 APK。
2. 在 LSPosed 中啟用模組並勾選建議作用域。
3. 重新啟動所需作用域或直接重新啟動手機。
4. 透過藍牙連線 MOONDROP PUDDING。

### 致謝

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始專案基礎。
- [OppoPods](https://github.com/1812z/OppoPods) — HyperOS 耳機整合與實作參考。
- **HyperOriG** — HyperOS 多程序接入、Focus Island 與裝置卡路線參考。
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 風格 Compose UI 元件。
- 更改者：**融億**  
  酷安：`http://www.coolapk.com/u/30640322`

### 授權條款

GPL-3.0
