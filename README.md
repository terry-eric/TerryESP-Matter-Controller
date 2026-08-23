# TerryESP Controller

TerryESP Controller 是一套用於 ESP32-C3 與 WS2812 燈條的 Android App／Matter 韌體。App 可用藍牙完成第一次 Wi-Fi 設定，在區網內控制完整燈效，也可把裝置加入 Matter／Google Home，使用 Google Home App、語音與遠端控制基本燈光功能。

> 目前是開發測試版，不是可直接量產販售的版本。請先閱讀「目前限制與量產注意事項」。

## 功能

- Material 3／Material You 介面，依房間收合顯示裝置
- 裝置開關、亮度、RGB 顏色與色溫
- 內建燈效、10 段速度、呼吸與呼吸速度
- 自訂 LED 數量（預設 20 顆）、逐顆選色、向前／向後輪動及單色呼吸
- 多裝置統一控制
- 藍牙首次配網，不必先知道 ESP 的 IP 或控制金鑰
- mDNS／UDP 自動搜尋同一 Wi-Fi 的 TerryESP 裝置
- Matter／Google Home 配對、家庭與房間同步
- 純本地模式不必登入 Google；只有 Matter／Google Home 功能需要 Google 帳號

## 控制方式

| 功能 | 同一 Wi-Fi | 外部網路 |
| --- | --- | --- |
| 開關、亮度、顏色、色溫 | 優先使用 ESP 本地 API | Matter 裝置改由 Google Home 控制 |
| Google Home App／語音 | 可用 | 可用，實際路徑由 Google Home 生態系處理 |
| 內建燈效、跑馬、呼吸、逐顆顏色 | 可用 | 目前不可用 |
| 僅區網裝置 | 可用，卡片標示「僅區網」 | 不可用 |

手機改用行動網路時，本地 API 無法連到家中的 ESP 是正常現象；已加入 Matter 的裝置仍可透過 Google Home 控制基本燈光。自訂動畫不是標準 Matter 燈具功能，目前必須與 ESP 在同一個 Wi-Fi。

## 快速開始

### 1. 安裝 Android App

需求：Android 10（API 29）以上，建議使用已安裝最新版 Google Play 服務的手機。

下載並安裝：

- `release/TerryESP-Controller-v0.4.1-debug.apk`

若使用 ADB：

```powershell
adb install -r release\TerryESP-Controller-v0.4.1-debug.apk
```

這是 debug APK。Android 可能要求允許「安裝未知應用程式」，Google 登入測試也必須把實際簽章 SHA-1／SHA-256 設定到對應的 Google Cloud／Google Home 專案。

### 2. 第一次以藍牙設定 ESP

1. ESP 正常開機後，按住 `BOOT` 約 1 秒再放開。
2. 等燈條以青藍色閃爍，表示藍牙配網模式已開啟。
3. 在 App 點右上角新增裝置，選擇「藍牙本地配對」。
4. 搜尋並選取 `TERRY_XXXXXX`。
5. App 會先帶入手機目前連線的 Wi-Fi 名稱；確認密碼後才會送給 ESP。
6. ESP 連線完成後會重新啟動，App 會在區網搜尋並自動加入裝置。

不需要手動輸入 IP 或本地金鑰。若搜尋不到，請確認手機已開啟藍牙、附近裝置／定位權限，並使用 2.4 GHz Wi-Fi；手機與 ESP 完成設定後也必須在同一個區網才能使用完整燈效。

### 3. 加入 Matter／Google Home

有兩種入口：

- 「Matter／Google Home 配對」：直接開啟 Google Matter 配對流程。
- 「將區網裝置加入 Matter」：先選擇已用藍牙加入的本地裝置，再交由 Google 完成 Matter 配對；完成後保留本地燈效控制。

若系統顯示裝置已新增過，但 Google Home 或 TerryESP App 看不到：

1. 先在 Google Home 移除舊的同名裝置。
2. 長按 ESP 的 `BOOT` 5 秒，看到藍燈快速閃爍後放開。
3. 等 ESP 重新啟動，再重新配對。

Matter 裝置的家庭與房間以 Google Home 為準，App 會同步顯示；純本地裝置的房間則由 TerryESP App 管理。

## Google Home 開發測試

一般開發測試可先用 Google Home App 的「新增 > 裝置 > 支援 Matter 的裝置」配對。若要建立正式整合與執行測試：

1. 進入 [Google Home Developer Console](https://console.home.google.com/)。
2. 建立專案、公司資料與 Matter integration，填入和韌體一致的 VID／PID。
3. 先把測試裝置配對到同一個 Google 帳號的 Google Home 家庭。
4. 在 Developer Console 開啟 `Matter > Test`，建立 Development test plan 並執行 Google Home Test Suite。

詳細步驟以 Google 官方的 [Test a Matter integration](https://developers.home.google.com/matter/test) 與 [Matter development checklist](https://developers.home.google.com/matter/checklist) 為準。Development test 不能當成正式認證結果。

## ESP32-C3 韌體

### 硬體

- ESP32-C3（開發板 `BOOT` 通常接 GPIO9）
- WS2812 資料腳預設 GPIO10
- WS2812 使用獨立 5V 電源，ESP32 與燈條必須共地
- 建議資料線加入 3.3V → 5V 邏輯電平轉換器
- 韌體把最大輸出限制為 60%，降低燈條瞬間電流；仍須依 LED 數量配置足夠電源、線徑、保險絲與散熱

### 使用 release 韌體燒錄

先安裝 Python 與 esptool：

```powershell
py -m pip install esptool
```

全新 ESP 或需要完整重建分割區時，在 `release\firmware-v0.4.0` 執行：

```powershell
py -m esptool --chip esp32c3 --port COM7 --baud 460800 write-flash `
  0x0 bootloader.bin `
  0x8000 partition-table.bin `
  0x10000 matter_ws2812.bin `
  0x3b0000 ota_data_initial.bin
```

把 `COM7` 改成裝置管理員顯示的序列埠。若 ESP 已經完整燒錄過，只更新 App 分割區可保留 NVS 內的 Wi-Fi、Matter fabric 與本地設定：

```powershell
py -m esptool --chip esp32c3 --port COM7 --baud 460800 write-flash 0x10000 matter_ws2812.bin
```

需要真正恢復成乾淨狀態時才執行 `erase-flash`；這會刪除 Wi-Fi、Matter 配對與裝置設定。

### 從原始碼編譯

已驗證的開發環境：

- ESP-IDF 5.5.4
- ESP-Matter `release/v1.5`
- Android Studio／JDK 17，Android compile SDK 35

Android：

```powershell
cd android
.\gradlew.bat assembleDebug
```

ESP-Matter：

```bash
source /path/to/esp-idf/export.sh
cd /path/to/esp-matter
source ./export.sh
export ESP_MATTER_PATH="$PWD"
cd /path/to/TerryESP-Controller/firmware/matter_ws2812
idf.py set-target esp32c3
idf.py build
idf.py flash monitor
```

更多 ESP-Matter 環境初始化與本地 API 說明請見 `firmware/matter_ws2812/README.md`。

## 專案結構

```text
android/                         Android Compose App
firmware/matter_ws2812/          ESP32-C3 ESP-Matter 韌體
release/                         可直接安裝／燒錄的測試檔
  TerryESP-Controller-v0.4.1-debug.apk
  firmware-v0.4.0/
README.md
```

## 目前限制與量產注意事項

- App 與韌體目前使用共同的開發用 Matter 手動配對碼 `34970112332`，只適合自己的測試設備。
- 正式產品必須為每台裝置建立唯一 setup payload、passcode、discriminator、DAC／PAI 憑證，使用正確 VID／PID，並依販售市場完成 CSA Matter、Google Home、無線與電氣安全等流程。
- 不要把正式 DAC 私鑰、Wi-Fi 密碼、Google 憑證、簽章檔或真實裝置金鑰提交到 GitHub。
- 自訂動畫目前只走區網。若要在外網控制，需另外設計安全的雲端通道，或評估 Matter vendor-specific cluster 與各生態系支援度。
- release 內是 debug APK 與開發韌體，尚未簽署成 Play Store／量產發行版本。

## 已驗證項目

- Android `assembleDebug` 建置成功
- ESP32-C3 ESP-Matter 韌體建置成功
- App 已在 Samsung Android 手機安裝並啟動
- ESP 韌體已完成實機燒錄，本地 API 可回應 HTTP 200
- 藍牙配網、本地裝置加入與 Matter／Google Home 路徑仍應在每種手機、路由器與 Google Home hub 組合上進行回歸測試
