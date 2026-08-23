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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
        if (result.values.all { it }) {
            currentWifiSsid(context)?.let {
                ssid = it
                ssidWasAutoFilled = true
            }
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
        currentWifiSsid(context)?.let {
            ssid = it
            ssidWasAutoFilled = true
        }
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
            }, label = { Text("Wi-Fi 名稱") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            if (ssidWasAutoFilled) {
                Text("已帶入手機目前連線的 Wi-Fi，尚未傳送。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(password, { password = it }, label = { Text("Wi-Fi 密碼") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(room, { room = it }, label = { Text("房間") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { controller.provision(ssid.trim(), password) },
                enabled = ssid.isNotBlank() && room.isNotBlank() && pairingInfo != null && phase == BleProvisionPhase.CONNECTED,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("傳送 Wi-Fi 設定") }
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

private fun currentWifiSsid(context: Context): String? = runCatching {
    @Suppress("DEPRECATION")
    val raw = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.connectionInfo?.ssid
        ?.trim()
        ?: return@runCatching null
    raw.removeSurrounding("\"")
        .takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
}.getOrNull()
