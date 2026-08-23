package tw.terry.matterlight

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPDevice
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.BleScanListener
import com.espressif.provisioning.listeners.ProvisionListener
import com.espressif.provisioning.listeners.ResponseListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject

data class BleProvisionCandidate(
    val name: String,
    val bluetoothDevice: BluetoothDevice,
    val serviceUuid: String,
)

enum class BleProvisionPhase { IDLE, SCANNING, CONNECTING, CONNECTED, PROVISIONING, DISCOVERING, SUCCESS, ERROR }

data class BlePairingInfo(
    val deviceId: String,
    val name: String,
    val localKey: String,
)

class BleProvisioningController(context: Context) {
    private val manager = ESPProvisionManager.getInstance(context.applicationContext)
    private var espDevice: ESPDevice? = null
    private val _candidates = MutableStateFlow<List<BleProvisionCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()
    private val _phase = MutableStateFlow(BleProvisionPhase.IDLE)
    val phase = _phase.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _pairingInfo = MutableStateFlow<BlePairingInfo?>(null)
    val pairingInfo = _pairingInfo.asStateFlow()

    init {
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this)
    }

    fun startScan() {
        _candidates.value = emptyList()
        _message.value = null
        _phase.value = BleProvisionPhase.SCANNING
        manager.searchBleEspDevices("TERRY_", object : BleScanListener {
            override fun scanStartFailed() = fail("無法開始藍牙搜尋，請確認藍牙已開啟。")

            override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {
                val uuid = scanResult.scanRecord?.serviceUuids?.firstOrNull()?.toString() ?: return
                val name = scanResult.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: "TerryESP"
                val candidate = BleProvisionCandidate(name, device, uuid)
                _candidates.value = (_candidates.value + candidate).distinctBy { it.bluetoothDevice.address }
            }

            override fun scanCompleted() {
                if (_phase.value == BleProvisionPhase.SCANNING) _phase.value = BleProvisionPhase.IDLE
            }

            override fun onFailure(error: Exception) = fail(error.message ?: "藍牙搜尋失敗")
        })
    }

    fun connect(candidate: BleProvisionCandidate) {
        manager.stopBleScan()
        _phase.value = BleProvisionPhase.CONNECTING
        _message.value = null
        _pairingInfo.value = null
        val proofOfPossession = proofOfPossessionFor(candidate.name)
            ?: return fail("裝置名稱缺少識別碼，請重新啟動 ESP 的藍牙設定模式。")
        espDevice = manager.createESPDevice(
            ESPConstants.TransportType.TRANSPORT_BLE,
            ESPConstants.SecurityType.SECURITY_1,
        ).also {
            it.setProofOfPossession(proofOfPossession)
            it.connectBLEDevice(candidate.bluetoothDevice, candidate.serviceUuid)
        }
    }

    fun provision(ssid: String, password: String) {
        val device = espDevice ?: return fail("藍牙裝置尚未連線")
        _phase.value = BleProvisionPhase.PROVISIONING
        _message.value = "正在將 Wi-Fi 資料傳給 ESP…"
        Log.i(TAG, "Starting Wi-Fi provisioning for ${_pairingInfo.value?.deviceId ?: "unknown device"}")
        device.provision(ssid, password, object : ProvisionListener {
            override fun createSessionFailed(error: Exception) = fail("安全驗證失敗：${error.message.orEmpty()}")
            override fun wifiConfigSent() { _message.value = "Wi-Fi 資料已傳送…" }
            override fun wifiConfigFailed(error: Exception) = fail("Wi-Fi 資料傳送失敗")
            override fun wifiConfigApplied() {
                waitForLan("Wi-Fi 設定已套用，正在區網尋找 ESP…")
            }
            override fun wifiConfigApplyFailed(error: Exception) = fail("ESP 無法套用 Wi-Fi 設定")
            override fun provisioningFailedFromDevice(reason: ESPConstants.ProvisionFailureReason) =
                fail("配網失敗：$reason")
            override fun deviceProvisioningSuccess() {
                _phase.value = BleProvisionPhase.SUCCESS
                _message.value = "Wi-Fi 已設定，正在區網中尋找同一台 ESP…"
                Log.i(TAG, "Provisioning success; waiting for LAN discovery")
            }
            override fun onProvisioningFailed(error: Exception) {
                val text = error.message.orEmpty()
                if (_pairingInfo.value != null && looksLikeExpectedBleDisconnect(text)) {
                    waitForLan("ESP 已切換至 Wi-Fi，正在區網中確認裝置…")
                } else {
                    fail(text.ifBlank { "配網失敗" })
                }
            }
        })
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDeviceConnectionEvent(event: DeviceConnectionEvent) {
        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_CONNECTED -> {
                _phase.value = BleProvisionPhase.CONNECTED
                _message.value = "藍牙安全連線完成，正在匹配裝置金鑰…"
                fetchPairingInfo()
            }
            ESPConstants.EVENT_DEVICE_CONNECTION_FAILED -> fail("無法連接 ESP，請確認裝置正在青藍燈閃爍模式。")
            ESPConstants.EVENT_DEVICE_DISCONNECTED -> when (_phase.value) {
                BleProvisionPhase.PROVISIONING, BleProvisionPhase.DISCOVERING ->
                    waitForLan("ESP 已結束藍牙連線，正在區網中確認裝置…")
                BleProvisionPhase.SUCCESS -> Unit
                else -> fail("藍牙連線已中斷")
            }
        }
    }

    fun close() {
        runCatching { manager.stopBleScan() }
        runCatching { espDevice?.disconnectDevice() }
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
    }

    private fun fail(text: String) {
        Log.e(TAG, text)
        _phase.value = BleProvisionPhase.ERROR
        _message.value = text
    }

    private fun waitForLan(text: String) {
        Log.i(TAG, text)
        _phase.value = BleProvisionPhase.DISCOVERING
        _message.value = text
    }

    private fun looksLikeExpectedBleDisconnect(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("ble") || normalized.contains("gatt") ||
            normalized.contains("disconnect") || normalized.contains("write") ||
            normalized.contains("stream")
    }

    private fun fetchPairingInfo() {
        val device = espDevice ?: return fail("藍牙裝置尚未連線")
        device.sendDataToCustomEndPoint("terry-info", "get".toByteArray(), object : ResponseListener {
            override fun onSuccess(returnData: ByteArray) {
                runCatching {
                    val json = JSONObject(String(returnData, Charsets.UTF_8))
                    BlePairingInfo(
                        deviceId = json.getString("deviceId"),
                        name = json.optString("name", "TerryESP Controller"),
                        localKey = json.getString("localKey"),
                    ).also {
                        require(it.deviceId.matches(Regex("terryesp-[0-9a-fA-F]{6}")))
                        require(it.localKey.matches(Regex("[0-9a-fA-F]{32}")))
                    }
                }.onSuccess {
                    _pairingInfo.value = it
                    _message.value = "已安全匹配 ${it.deviceId}，不需要手動輸入金鑰。"
                }.onFailure { fail("裝置識別資料格式錯誤") }
            }

            override fun onFailure(error: Exception) = fail("無法取得裝置金鑰：${error.message.orEmpty()}")
        })
    }

    private fun proofOfPossessionFor(name: String): String? {
        val suffix = Regex("TERRY_([0-9A-Fa-f]{6})").find(name)?.groupValues?.get(1) ?: return null
        return "terry-${suffix.lowercase()}"
    }

    private companion object {
        const val TAG = "TerryBleSetup"
    }
}
