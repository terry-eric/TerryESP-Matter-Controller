package tw.terry.matterlight

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class BleLocalSetupResult(
    val deviceId: String,
    val name: String,
    val room: String,
    val host: String,
    val localKey: String,
)

@Composable
fun BleProvisioningScreen(
    rooms: List<String>,
    onBack: () -> Unit,
    onComplete: (BleLocalSetupResult) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { BleProvisioningController(context) }
    val candidates by controller.candidates.collectAsState()
    val phase by controller.phase.collectAsState()
    val message by controller.message.collectAsState()
    val pairingInfo by controller.pairingInfo.collectAsState()
    var selected by remember { mutableStateOf<BleProvisionCandidate?>(null) }
    var ssid by remember { mutableStateOf("") }
    var ssidWasAutoFilled by remember { mutableStateOf(false) }
    var wifiAutofillAttempted by remember { mutableStateOf(false) }
    var wifiFrequencyMhz by remember { mutableStateOf<Int?>(null) }
    var showWifiBandWarning by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var room by remember(rooms) { mutableStateOf(rooms.firstOrNull() ?: "未分類") }
    var discovered by remember { mutableStateOf(emptyList<LocalEspCandidate>()) }
    var completing by remember { mutableStateOf(false) }
    var completionError by remember { mutableStateOf<String?>(null) }
    var verificationRetry by remember { mutableStateOf(0) }
    var canRetryVerification by remember { mutableStateOf(false) }
    val discovery = remember {
        LocalEspDiscovery(context) { candidate ->
            discovered = (discovered + candidate).distinctBy { "${it.host}:${it.port}:${it.deviceId}" }
        }
    }
    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        wifiAutofillAttempted = true
        currentWifiNetwork(context)?.let {
            ssid = it.ssid
            wifiFrequencyMhz = it.frequencyMhz
            ssidWasAutoFilled = true
        }
        val bluetoothGranted = when {
            Build.VERSION.SDK_INT >= 31 ->
                result[Manifest.permission.BLUETOOTH_SCAN] != false &&
                    result[Manifest.permission.BLUETOOTH_CONNECT] != false
            else -> result[Manifest.permission.ACCESS_FINE_LOCATION] != false
        }
        if (bluetoothGranted) {
            controller.startScan()
        }
    }

    DisposableEffect(controller, discovery) {
        onDispose {
            discovery.stop()
            controller.close()
        }
    }
    LaunchedEffect(phase) {
        if (phase in listOf(BleProvisionPhase.PROVISIONING, BleProvisionPhase.DISCOVERING, BleProvisionPhase.SUCCESS)) {
            discovery.start()
        }
    }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissions)
    }
    LaunchedEffect(phase, pairingInfo, discovered, verificationRetry) {
        val info = pairingInfo ?: return@LaunchedEffect
        if (phase !in listOf(BleProvisionPhase.PROVISIONING, BleProvisionPhase.DISCOVERING, BleProvisionPhase.SUCCESS) || completing) {
            return@LaunchedEffect
        }
        val candidate = discovered.firstOrNull { it.deviceId.equals(info.deviceId, ignoreCase = true) }
            ?: return@LaunchedEffect
        completing = true
        canRetryVerification = false
        val host = "${candidate.host}:${candidate.port}"
        Log.i("TerryBleSetup", "Matched ${info.deviceId} at $host; verifying local API")
        repeat(12) { attempt ->
            val verified = runCatching { LocalEspApi.verify(host, info.localKey) }.isSuccess
            if (verified) {
                Log.i("TerryBleSetup", "Local API verified; completing setup")
                onComplete(BleLocalSetupResult(info.deviceId.lowercase(), info.name, room, host, info.localKey.lowercase()))
                return@LaunchedEffect
            }
            Log.i("TerryBleSetup", "Local API not ready at $host; retry ${attempt + 1}/12")
            completionError = "已找到裝置，正在等待本地控制服務啟動… (${attempt + 1}/12)"
            delay(1_000)
        }
        completionError = "裝置已連上 Wi-Fi，但本地服務尚未回應。請保持此畫面並確認手機仍連著同一個 Wi-Fi。"
        completing = false
        canRetryVerification = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("返回") }
        Text("藍牙本地配對", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("1. ESP 正常開機後，按住 BOOT 約 1 秒再放開。\n2. 等待燈條青藍色閃爍。\n3. 搜尋 TERRY_ 開頭的裝置。")
        Button(onClick = { permissionLauncher.launch(permissions) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (phase == BleProvisionPhase.SCANNING) "正在搜尋…" else "搜尋藍牙裝置")
        }
        candidates.forEach { candidate ->
            Card(
                onClick = { selected = candidate },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == candidate) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(candidate.name, fontWeight = FontWeight.Bold)
                    Text(candidate.bluetoothDevice.address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (selected != null) {
            Button(
                onClick = { controller.connect(selected!!) },
                enabled = phase !in listOf(BleProvisionPhase.CONNECTING, BleProvisionPhase.PROVISIONING),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("安全連線並自動匹配") }
        }
        if (phase == BleProvisionPhase.CONNECTED || phase == BleProvisionPhase.PROVISIONING) {
            if (pairingInfo != null) {
                Text("已匹配：${pairingInfo!!.deviceId}", color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(ssid, {
                ssid = it
                ssidWasAutoFilled = false
                wifiFrequencyMhz = null
            }, label = { Text("Wi-Fi 名稱") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            if (ssidWasAutoFilled) {
                Text("已帶入手機目前連線的 Wi-Fi，尚未傳送。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (wifiAutofillAttempted && ssid.isBlank()) {
                Text(
                    "無法自動讀取目前 Wi-Fi。請確認已允許精確位置並開啟手機定位，或手動輸入 2.4 GHz Wi-Fi 名稱。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (wifiFrequencyMhz?.let { !is2_4Ghz(it) } == true) {
                Text(
                    "目前手機連線為 ${formatWifiBand(wifiFrequencyMhz!!)}。ESP32-C3 僅支援 2.4 GHz；若路由器沒有同名 2.4 GHz 網路，配網會失敗。",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedTextField(password, { password = it }, label = { Text("Wi-Fi 密碼") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(room, { room = it }, label = { Text("房間") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val currentNetwork = currentWifiNetwork(context)
                    val currentFrequency = currentNetwork
                        ?.takeIf { it.ssid == ssid.trim() }
                        ?.frequencyMhz
                    wifiFrequencyMhz = currentFrequency ?: wifiFrequencyMhz
                    if ((currentFrequency ?: wifiFrequencyMhz)?.let { !is2_4Ghz(it) } == true) {
                        showWifiBandWarning = true
                    } else {
                        controller.provision(ssid.trim(), password)
                    }
                },
                enabled = ssid.isNotBlank() && room.isNotBlank() && pairingInfo != null && phase == BleProvisionPhase.CONNECTED,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("傳送 Wi-Fi 設定") }
        }
        if (showWifiBandWarning) {
            AlertDialog(
                onDismissRequest = { showWifiBandWarning = false },
                title = { Text("目前不是 2.4 GHz Wi-Fi") },
                text = {
                    Text("手機目前連線到 ${wifiFrequencyMhz?.let(::formatWifiBand) ?: "5/6 GHz"}。ESP32-C3 只能連接 2.4 GHz。請先切換到 2.4 GHz；若路由器的 2.4 GHz 與 5 GHz 使用相同名稱，也可以選擇繼續。")
                },
                confirmButton = {
                    Button(onClick = {
                        showWifiBandWarning = false
                        controller.provision(ssid.trim(), password)
                    }) { Text("仍要繼續") }
                },
                dismissButton = {
                    TextButton(onClick = { showWifiBandWarning = false }) { Text("返回切換 Wi-Fi") }
                },
            )
        }
        if (phase == BleProvisionPhase.DISCOVERING || phase == BleProvisionPhase.SUCCESS) {
            Text("ESP 正在重新啟動；App 會按固定裝置 ID 自動搜尋、驗證並加入。")
        }
        completionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (canRetryVerification) {
            OutlinedButton(
                onClick = {
                    completionError = null
                    canRetryVerification = false
                    verificationRetry += 1
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新驗證已聯網裝置") }
        }
        message?.let {
            Text(it, color = if (phase == BleProvisionPhase.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class CurrentWifiNetwork(val ssid: String, val frequencyMhz: Int?)

private fun currentWifiNetwork(context: Context): CurrentWifiNetwork? = runCatching {
    @Suppress("DEPRECATION")
    val info = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.connectionInfo
        ?: return@runCatching null
    val ssid = info.ssid
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
        ?: return@runCatching null
    CurrentWifiNetwork(ssid, info.frequency.takeIf { it > 0 })
}.getOrNull()

private fun is2_4Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 2_400..2_500

private fun formatWifiBand(frequencyMhz: Int): String = when (frequencyMhz) {
    in 2_400..2_500 -> "2.4 GHz"
    in 4_900..5_900 -> "5 GHz"
    in 5_925..7_125 -> "6 GHz"
    else -> "$frequencyMhz MHz"
}
