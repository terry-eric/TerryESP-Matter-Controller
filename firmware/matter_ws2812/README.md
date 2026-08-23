# TerryESP Matter WS2812 韌體

ESP32-C3 的 ESP-IDF／ESP-Matter 韌體，建立一個 Matter `Extended Color Light`。Google Home 可控制開關、亮度、RGB 顏色及色溫，TerryESP Controller 則透過同一 Wi-Fi 的本地 API 控制動畫與逐顆 LED。

## 硬體

- ESP32-C3
- WS2812 資料腳位：預設 `GPIO10`，可由 `idf.py menuconfig` 修改
- 預設 LED 上限：256 顆，可由 menuconfig 修改
- WS2812 使用獨立 5V 電源
- ESP32 GND 與燈條 GND 必須共地
- 建議在資料線使用 3.3V 轉 5V 邏輯電平轉換器

韌體預設限制最大輸出，以降低大型燈條瞬間電流。增加亮度限制前，必須確認電源、線徑、保險絲及散熱能力。

## 開發環境

- ESP-IDF `v5.5.4`
- ESP-Matter `release/v1.5`
- Windows 可使用 WSL2 建置；USB 燒錄可使用 `usbipd-win` 連接 WSL，或在 Windows ESP-IDF 環境燒錄

## 初始化 ESP-Matter

```bash
cd /path/to/matter/esp-matter-sdk
git submodule update --init --depth 1
cd connectedhomeip/connectedhomeip
./scripts/checkout_submodules.py --platform esp32 linux --shallow
cd ../..
./install.sh --no-host-tool
```

每次開啟新的終端機後：

```bash
source /path/to/esp-idf/export.sh
cd /path/to/matter/esp-matter-sdk
source ./export.sh
export ESP_MATTER_PATH="$PWD"
```

## 設定、編譯與燒錄

```bash
cd /path/to/matter/firmware/matter_ws2812
idf.py set-target esp32c3
idf.py menuconfig
idf.py build
idf.py -p COM6 flash monitor
```

請把 `COM6` 改成自己的序列埠。第一次安裝或需要完全清除舊資料時：

```bash
idf.py -p COM6 erase-flash
idf.py -p COM6 flash monitor
```

序列監控器使用 115200 baud。開機紀錄會顯示 Matter QR Code 網址、11 位數手動配對碼、本地主機名稱及開發用本地 API 金鑰。

## Google Home 配對

1. 燒錄韌體並開啟序列監控器。
2. 取得 `SetupQRCode` 網址或手動配對碼。
3. 在 Google Home App 選擇 **新增 > 裝置 > 支援 Matter 的裝置**。
4. 掃描 QR Code 或輸入手動配對碼。
5. 選擇 Wi-Fi、家庭、房間及名稱。

長按 ESP32-C3 `BOOT` 5 秒會清除 Matter fabric 與 Wi-Fi 設定，之後可重新配對。

## 藍牙本地配網

1. ESP 正常開機後，按住 `BOOT` 約 1 秒再放開。
2. ESP 重新啟動並以青藍色閃爍，廣播 `TERRY_XXXXXX` BLE 配網服務。
3. 在 TerryESP App 選擇 **藍牙本地配對**並選取該裝置，不需手動輸入金鑰。
4. App 透過 Espressif Security 1（X25519＋PoP＋AES-CTR）建立工作階段，再由加密的 `terry-info` endpoint 取得固定裝置 ID 與唯一的本地 API 金鑰。
5. App 傳送 Wi-Fi 資料；連線成功後綠燈亮起，ESP 延遲 4 秒重新啟動並開啟區網 API。
6. App 以固定裝置 ID 找回同一台 ESP，驗證本地金鑰後自動加入。

本地 API 金鑰仍是每台裝置獨有且保存在 NVS，但不再寫入序列日誌或要求使用者輸入。藍牙自動配對只會在使用者實際按下 BOOT、進入限時設定模式後開放。量產版仍應使用每台裝置的製造資料與更完整的擁有權驗證流程。

## 本地 API

- UDP discovery：App 傳送 `TERRYESP_DISCOVER`
- `GET /api/v1/info`：裝置資訊
- `GET /api/v1/auth`：驗證本地金鑰
- `POST /api/v1/state`：設定電源、亮度、HSV 顏色或色溫
- `GET /api/v1/effect`：設定內建燈效
- `POST /api/v1/custom`：設定逐顆顏色、移動方向、速度及呼吸
- `POST /api/v1/key`：更新本地金鑰

裝置不用固定 IP；App 會在同一 Wi-Fi 自動搜尋。請勿把量產憑證、DAC 私鑰、Wi-Fi 密碼或真實裝置金鑰提交到 GitHub。

## 正式產品注意事項

目前使用開發流程。正式量產必須為每台裝置建立唯一 Matter DAC、配對碼及 discriminator，使用正式 VID/PID，並完成所需的 CSA Matter、Google Home、無線與電氣安全認證。
