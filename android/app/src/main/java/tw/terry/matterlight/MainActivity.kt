package tw.terry.matterlight

import android.graphics.Color as AndroidColor
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.home.matter.Matter
import com.google.android.gms.home.matter.commissioning.CommissioningRequest
import com.google.android.gms.home.matter.commissioning.CommissioningResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val lightViewModel by viewModels<LightViewModel>()
    private var googleAccountEmail by mutableStateOf<String?>(null)
    private var useLocalMode by mutableStateOf(false)
    private var commissioningRecoveryMessage by mutableStateOf<String?>(null)
    private var commissioningInProgress = false
    private var devicesBeforeCommissioning = emptySet<String>()
    private var pendingGoogleMatterCommissioning = false
    private var pendingLocalMatterTarget: LightDevice? = null
    private var commissioningLocalDeviceId: String? = null

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                googleAccountEmail = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java).email
                useLocalMode = false
                getSharedPreferences("terryesp_controller", 0).edit().putBoolean("local_mode", false).apply()
                GoogleHomeBridge.connect(applicationContext, this)
                if (pendingGoogleMatterCommissioning) {
                    pendingGoogleMatterCommissioning = false
                    val target = pendingLocalMatterTarget
                    pendingLocalMatterTarget = null
                    commissioningInProgress = false
                    startGoogleCommissioning(target)
                }
            } catch (error: ApiException) {
                commissioningInProgress = false
                pendingGoogleMatterCommissioning = false
                pendingLocalMatterTarget = null
                Log.e("MatterLight", "Google sign-in failed", error)
            }
        }

    private val commissioningLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            commissioningInProgress = false
            if (result.resultCode == RESULT_OK) {
                val resultData = CommissioningResult.fromIntentSenderResult(result.resultCode, result.data)
                Log.i("MatterLight", "Matter commissioning completed: ${resultData.deviceName ?: "unnamed device"}")
                val localDeviceId = commissioningLocalDeviceId
                if (googleAccountEmail != null) {
                    GoogleHomeBridge.connect(applicationContext, this)
                    lifecycleScope.launch {
                        val newMatterDevice = withTimeoutOrNull(45_000) {
                            GoogleHomeBridge.lights.first { lights ->
                                lights.any { it.id !in devicesBeforeCommissioning }
                            }.firstOrNull { it.id !in devicesBeforeCommissioning }
                        }
                        if (newMatterDevice != null && localDeviceId != null) {
                            lightViewModel.promoteLocalDeviceToMatter(localDeviceId, newMatterDevice.id)
                        } else if (newMatterDevice == null) {
                            commissioningRecoveryMessage =
                                "Google 已完成配對，但 45 秒內仍未在家庭中找到燈條。請長按 ESP 的 BOOT 5 秒後重新配對。"
                        }
                        commissioningLocalDeviceId = null
                    }
                }
            } else {
                commissioningLocalDeviceId = null
                Log.i("MatterLight", "Matter commissioning cancelled or failed")
                commissioningRecoveryMessage =
                    "如果系統顯示「這個裝置已經新增過」，代表 ESP 仍保存舊的 Matter 配對資料。\n\n" +
                        "請長按 ESP 的 BOOT 5 秒，看到藍燈快速閃爍後放開，等待重新啟動，再重新掃描。若 Google Home 中存在同名舊裝置，也請先將它移除。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) = super.onCreate(savedInstanceState).also {
        val previewUi = intent.getBooleanExtra("preview_ui", false)
        useLocalMode = previewUi || getSharedPreferences("terryesp_controller", 0).getBoolean("local_mode", true)
        googleAccountEmail = if (previewUi) "preview@terryesp.local" else GoogleSignIn.getLastSignedInAccount(this)?.email
        if (googleAccountEmail == null) useLocalMode = true
        if (previewUi) {
            lightViewModel.seedPreviewData()
        } else if (googleAccountEmail != null) {
            GoogleHomeBridge.connect(applicationContext, this)
        }
        setContent {
            MatterLightApp(
                viewModel = lightViewModel,
                onStartCommissioning = { startGoogleCommissioning() },
                onStartLocalMatterCommissioning = { startGoogleCommissioning(it) },
                signedInEmail = googleAccountEmail,
                onGoogleSignIn = ::startGoogleSignIn,
                onGoogleSignOut = ::signOutGoogle,
                onOpenGoogleHome = ::openGoogleHome,
                localMode = useLocalMode,
                onUseLocalMode = {
                    useLocalMode = true
                    getSharedPreferences("terryesp_controller", 0).edit().putBoolean("local_mode", true).apply()
                },
                commissioningRecoveryMessage = commissioningRecoveryMessage,
                onDismissCommissioningRecovery = { commissioningRecoveryMessage = null },
            )
        }
    }

    private fun startGoogleSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
        googleSignInLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun signOutGoogle() {
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
        googleAccountEmail = null
        useLocalMode = true
        getSharedPreferences("terryesp_controller", 0).edit().putBoolean("local_mode", true).apply()
    }

    private fun openGoogleHome() {
        val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
        startActivity(launchIntent ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.chromecast.app")))
    }

    private fun startGoogleCommissioning(target: LightDevice? = null) {
        if (commissioningInProgress) {
            Log.i("MatterLight", "Ignoring duplicate Matter commissioning request")
            return
        }
        commissioningInProgress = true
        if (googleAccountEmail == null) {
            pendingGoogleMatterCommissioning = true
            pendingLocalMatterTarget = target
            startGoogleSignIn()
            return
        }
        if (target != null) {
            lifecycleScope.launch {
                runCatching {
                    LocalEspApi.openMatterCommissioning(target)
                    // The ESP schedules this work on the Matter thread. Give DNS-SD/BLE
                    // advertising time to become visible before Google starts discovery.
                    delay(1_200)
                }.onSuccess {
                    launchGoogleCommissioning(target)
                }.onFailure { error ->
                    commissioningInProgress = false
                    commissioningLocalDeviceId = null
                    Log.e("MatterLight", "Unable to open ESP Matter commissioning window", error)
                    commissioningRecoveryMessage = if (error.message.orEmpty().contains("401")) {
                        "燈條內的本地配對資料已更新。請重新執行一次「藍牙本地配對」，App 會自動取回資料；不需要手動輸入任何金鑰。"
                    } else {
                        "無法讓燈條進入 Matter 配對模式。請確認手機和燈條在同一個 Wi-Fi，再按一次加入 Matter。"
                    }
                }
            }
            return
        }
        launchGoogleCommissioning(null)
    }

    private fun launchGoogleCommissioning(target: LightDevice?) {
        // This path is intentionally Google Home only. Local BLE provisioning is a
        // separate flow and never calls the Google commissioning API.
        devicesBeforeCommissioning = GoogleHomeBridge.lights.value.map { it.id }.toSet()
        commissioningLocalDeviceId = target?.id
        commissioningRecoveryMessage = null
        val requestBuilder = CommissioningRequest.builder().setStoreToGoogleFabric(true)
        if (target != null) {
            // The pairing code is supplied by the app. The user is never asked for
            // the local API key or another Matter code in this upgrade flow.
            requestBuilder
                .setOnboardingPayload(DEVELOPMENT_MATTER_PAIRING_CODE)
                .setDeviceNameHint(target.name)
        }
        val request = requestBuilder.build()
        Matter.getCommissioningClient(this)
            .commissionDevice(request)
            .addOnSuccessListener { sender ->
                commissioningLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { error ->
                commissioningInProgress = false
                commissioningLocalDeviceId = null
                Log.e("MatterLight", "Unable to start Matter commissioning", error)
                commissioningRecoveryMessage = "無法啟動 Matter 配對，請稍後再試。"
            }
    }

    private companion object {
        // Development firmware currently uses the ESP-Matter default setup payload.
        // Production devices must store a unique payload per device.
        const val DEVELOPMENT_MATTER_PAIRING_CODE = "34970112332"
    }
}

private val swatches = listOf(
    Color(0xFFFF4D6D), Color(0xFFFFA62B), Color(0xFFFFE066), Color(0xFF5BE7A9),
    Color(0xFF39B9FF), Color(0xFF8B5CF6), Color(0xFFFF5CDE), Color.White,
)

private enum class AddDeviceStep { IDLE, SEARCH, SETUP }

@Composable
private fun GoogleLoginScreen(onGoogleSignIn: () -> Unit, onUseLocalMode: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("TerryESP Controller", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("先登入 Google 帳號，再管理你的 Matter 燈具與家庭。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Button(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                onClick = onGoogleSignIn,
            ) {
                Text("使用 Google 登入", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                onClick = onUseLocalMode,
            ) { Text("只使用本地 ESP", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(16.dp))
            Text(
                "家庭成員請在 Google Home 邀請；加入同一個家庭後就能控制共享的 Matter 裝置。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MatterLightApp(
    viewModel: LightViewModel = viewModel(),
    onStartCommissioning: () -> Unit = {},
    onStartLocalMatterCommissioning: (LightDevice) -> Unit = {},
    signedInEmail: String? = null,
    onGoogleSignIn: () -> Unit = {},
    onGoogleSignOut: () -> Unit = {},
    onOpenGoogleHome: () -> Unit = {},
    localMode: Boolean = false,
    onUseLocalMode: () -> Unit = {},
    commissioningRecoveryMessage: String? = null,
    onDismissCommissioningRecovery: () -> Unit = {},
) {
    val devices by viewModel.devices.collectAsState()
    val rooms by viewModel.rooms.collectAsState()
    var customDeviceId by remember { mutableStateOf<String?>(null) }
    var customTargetIds by remember { mutableStateOf<Set<String>?>(null) }
    var showAddRoom by remember { mutableStateOf(false) }
    var showAddDeviceChoice by remember { mutableStateOf(false) }
    var showLocalMatterChoice by remember { mutableStateOf(false) }
    var showLocalSetup by remember { mutableStateOf(false) }
    var showBleSetup by remember { mutableStateOf(false) }
    var pendingLocalKey by remember { mutableStateOf("") }
    var localBindingDeviceId by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    val customDevice = devices.firstOrNull { it.id == customDeviceId }
    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    TerryMaterialTheme {
        if (commissioningRecoveryMessage != null) {
            AlertDialog(
                onDismissRequest = onDismissCommissioningRecovery,
                title = { Text("Matter 裝置未出現在家庭") },
                text = { Text(commissioningRecoveryMessage) },
                confirmButton = {
                    Button(onClick = {
                        onDismissCommissioningRecovery()
                        onStartCommissioning()
                    }) { Text("重新配對") }
                },
                dismissButton = {
                    TextButton(onClick = onDismissCommissioningRecovery) { Text("稍後") }
                },
            )
        }
        if (signedInEmail == null && !localMode) {
            GoogleLoginScreen(onGoogleSignIn, onUseLocalMode)
        } else if (customTargetIds != null) {
            val targets = customTargetIds.orEmpty()
            CustomPatternScreen(
                device = devices.firstOrNull { it.id in targets } ?: return@TerryMaterialTheme,
                title = "統一自訂圖樣",
                onBack = { customTargetIds = null },
                onApply = { pixels, moving, moveRight, speed, breathing, breathingSpeed ->
                    targets.forEach { id ->
                        viewModel.setCustomPixels(id, pixels, moving, moveRight, speed, breathing, breathingSpeed)
                    }
                    customTargetIds = null
                },
            )
        } else if (customDevice != null) {
            CustomPatternScreen(
                device = customDevice,
                title = "自訂燈條",
                onBack = { customDeviceId = null },
                onApply = { pixels, moving, moveRight, speed, breathing, breathingSpeed ->
                    viewModel.setCustomPixels(customDevice.id, pixels, moving, moveRight, speed, breathing, breathingSpeed)
                },
            )
        } else if (showBleSetup) {
            BleProvisioningScreen(
                rooms = rooms,
                onBack = {
                    localBindingDeviceId = null
                    showBleSetup = false
                },
                onComplete = { result ->
                    val targetMatterDeviceId = localBindingDeviceId
                    if (targetMatterDeviceId != null) {
                        viewModel.bindLocalApi(targetMatterDeviceId, result.host, result.localKey)
                    } else {
                        viewModel.addLocalDevice(
                            result.name, result.room, result.host, result.localKey, result.deviceId,
                        )
                        selectedRoom = result.room.trim().ifEmpty { rooms.firstOrNull() ?: "未分類" }
                    }
                    localBindingDeviceId = null
                    showBleSetup = false
                },
            )
        } else if (showLocalSetup) {
            LocalEspSetupScreen(
                rooms = rooms,
                initialKey = pendingLocalKey,
                onBack = { showLocalSetup = false },
                onComplete = { name, room, host, key ->
                    val deviceId = localBindingDeviceId
                    if (deviceId != null) {
                        viewModel.bindLocalApi(deviceId, host, key)
                        customDeviceId = deviceId
                    } else {
                        viewModel.addLocalDevice(name, room, host, key)
                    }
                    pendingLocalKey = ""
                    showLocalSetup = false
                },
            )
        } else {
            if (showAddDeviceChoice) {
                AddDeviceChoiceDialog(
                    onDismiss = { showAddDeviceChoice = false },
                    onMatter = {
                        showAddDeviceChoice = false
                        onStartCommissioning()
                    },
                    onLocalMatter = {
                        showAddDeviceChoice = false
                        showLocalMatterChoice = true
                    },
                    onLocal = {
                        showAddDeviceChoice = false
                        localBindingDeviceId = null
                        showBleSetup = true
                    },
                    onExisting = {
                        showAddDeviceChoice = false
                        localBindingDeviceId = null
                        showLocalSetup = true
                    },
                )
            }
            if (showLocalMatterChoice) {
                LocalMatterDeviceDialog(
                    devices = devices.filter { it.id.startsWith("local-") && it.localHost != null },
                    onDismiss = { showLocalMatterChoice = false },
                    onSelect = {
                        showLocalMatterChoice = false
                        onStartLocalMatterCommissioning(it)
                    },
                )
            }
            if (showAddRoom) {
                AddRoomDialog(
                    onDismiss = { showAddRoom = false },
                    onAdd = {
                        viewModel.addRoom(it)
                        showAddRoom = false
                    },
                )
            }
            MaterialYouHomeScreen(
                devices = devices,
                rooms = rooms,
                selectedRoom = selectedRoom,
                selectedDevice = selectedDevice,
                onBack = {
                    if (selectedDeviceId != null) selectedDeviceId = null else selectedRoom = null
                },
                onRoom = { selectedRoom = it },
                onDevice = { selectedDeviceId = it.id },
                onAddDevice = { showAddDeviceChoice = true },
                onAddRoom = { showAddRoom = true },
                onPower = { id, value -> viewModel.setPower(id, value) },
                onBrightness = { id, value -> viewModel.setBrightness(id, value) },
                onColor = { id, value -> viewModel.setColor(id, value) },
                onColorTemperature = { id, value -> viewModel.setColorTemperature(id, value) },
                onEffect = { id, effect, speed, breathing, breathingSpeed ->
                    viewModel.setEffect(id, effect, speed, breathing, breathingSpeed)
                },
                onCustom = { device ->
                    if (device.localHost == null || device.localKey == null) {
                        localBindingDeviceId = device.id
                        showBleSetup = true
                    } else {
                        customDeviceId = device.id
                    }
                },
                onGlobalCustom = { ids -> customTargetIds = ids.toSet() },
                signedInEmail = signedInEmail,
                localMode = localMode,
                familyMembers = viewModel.members.collectAsState().value,
                onInviteMember = viewModel::inviteMember,
                onRemoveMember = viewModel::removeMember,
                onUpdateDevice = viewModel::updateDevice,
                onDeleteDevice = viewModel::deleteDevice,
                onRenameRoom = viewModel::renameRoom,
                onDeleteRoom = viewModel::deleteRoom,
                messages = viewModel.messages,
                onGoogleSignIn = onGoogleSignIn,
                onOpenGoogleHome = onOpenGoogleHome,
                onSignOut = onGoogleSignOut,
            )
        }
    }
}

@Composable
private fun LocalEspSetupScreen(rooms: List<String>, initialKey: String = "", onBack: () -> Unit,
                                onComplete: (String, String, String, String) -> Unit) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf(emptyList<LocalEspCandidate>()) }
    var selected by remember { mutableStateOf<LocalEspCandidate?>(null) }
    var localKey by remember(initialKey) { mutableStateOf(initialKey) }
    var roomName by remember { mutableStateOf(rooms.firstOrNull().orEmpty()) }
    var validating by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val discovery = remember {
        LocalEspDiscovery(context) { candidate ->
            candidates = (candidates + candidate).distinctBy { it.host + ":" + it.port }
        }
    }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedButton(onClick = onBack) { Text("返回") }
        Text("搜尋區網燈條", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("請讓手機與 ESP 連到同一個 Wi-Fi。找到裝置後，首次輸入 ESP 序列埠顯示的本機金鑰。")
        if (candidates.isEmpty()) Text("正在搜尋 TerryESP Controller…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        candidates.forEach { candidate ->
            Card(shape = RoundedCornerShape(20.dp), onClick = { selected = candidate },
                colors = CardDefaults.cardColors(containerColor =
                    if (selected == candidate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(candidate.name, fontWeight = FontWeight.Bold)
                    Text(candidate.host + ":" + candidate.port)
                }
            }
        }
        if (selected != null) {
            OutlinedTextField(value = localKey, onValueChange = { localKey = it },
                label = { Text("本機控制金鑰") }, singleLine = true)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(), value = roomName, onValueChange = { roomName = it },
                label = { Text("房間") }, singleLine = true,
            )
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(modifier = Modifier.fillMaxWidth(), enabled = localKey.trim().length == 32 && !validating,
                onClick = {
                    val candidate = selected!!
                    val host = candidate.host + ":" + candidate.port
                    val key = localKey.trim()
                    validating = true
                    validationError = null
                    scope.launch {
                        runCatching { LocalEspApi.verify(host, key) }
                            .onSuccess { onComplete(candidate.name, roomName.ifBlank { "未分類" }, host, key) }
                            .onFailure { validationError = "連線或金鑰驗證失敗，請確認 ESP 與手機在同一個 Wi-Fi。" }
                        validating = false
                    }
                }) {
                Text(if (validating) "驗證中…" else "連接並加入")
            }
        }
    }
}

@Composable
private fun AddRoomDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var roomName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立房間", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                label = { Text("房間名稱") },
                singleLine = true,
            )
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = { onAdd(roomName) }, enabled = roomName.isNotBlank()) { Text("建立") } },
    )
}

@Composable
private fun AddDeviceChoiceDialog(
    onDismiss: () -> Unit,
    onMatter: () -> Unit,
    onLocalMatter: () -> Unit,
    onLocal: () -> Unit,
    onExisting: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增裝置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onMatter, modifier = Modifier.fillMaxWidth()) {
                    Text("Matter／Google Home 配對")
                }
                OutlinedButton(onClick = onLocalMatter, modifier = Modifier.fillMaxWidth()) {
                    Text("將區網裝置加入 Matter")
                }
                OutlinedButton(onClick = onLocal, modifier = Modifier.fillMaxWidth()) {
                    Text("藍牙本地配對")
                }
                OutlinedButton(onClick = onExisting, modifier = Modifier.fillMaxWidth()) {
                    Text("連接已聯網的裝置")
                }
                Text("Matter 會加入 Google Home；藍牙本地配對完全不呼叫 Google。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

@Composable
private fun LocalMatterDeviceDialog(
    devices: List<LightDevice>,
    onDismiss: () -> Unit,
    onSelect: (LightDevice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選擇區網裝置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "App 會使用藍牙配對時保存的裝置資料，不需要再輸入本地金鑰或 Matter 配對碼。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (devices.isEmpty()) {
                    Text("目前沒有只有區網控制的裝置。請先使用藍牙本地配對。")
                } else {
                    devices.forEach { device ->
                        OutlinedButton(
                            onClick = { onSelect(device) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(device.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${device.room} · ${if (device.isOnline) "區網在線" else "目前離線"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    "選擇後會直接開啟 Google Matter 配對，完成後保留原本的本地控制。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun AddDeviceFlow(step: AddDeviceStep, onBack: () -> Unit, onSelectFoundDevice: () -> Unit,
                          onComplete: (String, String) -> Unit) {
    var name by remember { mutableStateOf("TerryESP Controller") }
    var room by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onBack) { Text("返回") }
        if (step == AddDeviceStep.SEARCH) {
            Text("搜尋附近裝置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("請確認燈條已進入配對模式（藍燈閃爍）。")
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                onClick = onSelectFoundDevice) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TerryESP Controller", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Matter · Wi-Fi · 點選以首次設定", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text("首次設定裝置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("設定完成後，裝置會加入主頁；之後點選卡片即可控制，不會重複出現設定頁。")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("裝置名稱") }, singleLine = true)
            OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("房間") }, singleLine = true)
            Button(modifier = Modifier.fillMaxWidth(), onClick = { onComplete(name, room) }) { Text("完成設定") }
        }
    }
}

@Composable
private fun GlobalControlCard(devices: List<LightDevice>, onPower: (Boolean) -> Unit, onBrightness: (Int) -> Unit,
                              onColor: (Color) -> Unit, onEffect: (LightEffect) -> Unit, onOpenCustom: () -> Unit) {
    val online = devices.filter { it.isOnline }
    val representative = online.firstOrNull() ?: devices.firstOrNull()
    if (representative != null) {
        DeviceCard(
            device = representative.copy(id = "all", name = "所有燈條", room = "統一調整 · ${online.size} 個線上裝置", isOnline = online.isNotEmpty()),
            onPower = onPower, onBrightness = onBrightness, onColor = onColor, onEffect = onEffect, onOpenCustom = onOpenCustom,
        )
    }
}

@Composable
private fun RoomGroup(room: String, devices: List<LightDevice>, onOpenCustom: (LightDevice) -> Unit,
                      onDelete: (String) -> Unit,
                      onPower: (String, Boolean) -> Unit, onBrightness: (String, Int) -> Unit,
                      onColor: (String, Color) -> Unit, onEffect: (String, LightEffect) -> Unit) {
    var expanded by remember(room) { mutableStateOf(false) }
    val onCount = devices.count { it.isOn }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(38.dp), color = if (onCount > 0) Color(0xFFFFC857) else Color(0xFF424956), shape = CircleShape) {}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(room, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${devices.size} 個裝置 · $onCount 盞開啟", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "收起 ↑" else "展開 ↓", color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                devices.forEach { device ->
                    DeviceCard(device, { onPower(device.id, it) }, { onBrightness(device.id, it) }, { onColor(device.id, it) },
                        { onEffect(device.id, it) }, { onOpenCustom(device) }, { onDelete(device.id) })
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: LightDevice, onPower: (Boolean) -> Unit, onBrightness: (Int) -> Unit,
                       onColor: (Color) -> Unit, onEffect: (LightEffect) -> Unit, onOpenCustom: () -> Unit,
                       onDelete: (() -> Unit)? = null) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(36.dp), color = device.color, shape = CircleShape) {}
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${device.room} · ${if (device.isOnline) "線上" else "離線"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = device.isOn, onCheckedChange = onPower, enabled = device.isOnline)
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            }
            if (device.isOnline) {
                Text("亮度 ${device.brightness}%", fontWeight = FontWeight.SemiBold)
                Slider(value = device.brightness.toFloat(), onValueChange = { onBrightness(it.roundToInt()) }, valueRange = 1f..100f)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { swatches.take(6).forEach { color -> Swatch(color, color == device.color) { onColor(color) } } }
                EffectPicker(device.effect, onEffect, onOpenCustom)
            }
        }
    }
}

@Composable
private fun EffectPicker(selected: LightEffect, onSelected: (LightEffect) -> Unit, onOpenCustom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("燈效", fontWeight = FontWeight.SemiBold)
        LightEffect.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { effect ->
                    FilterChip(selected = selected == effect,
                        onClick = { if (effect == LightEffect.CUSTOM) onOpenCustom() else onSelected(effect) },
                        label = { Text(effect.label) })
                }
            }
        }
    }
}

@Composable
private fun CustomPatternScreen(
    device: LightDevice,
    title: String,
    onBack: () -> Unit,
    onApply: (List<Color>, Boolean, Boolean, Int, Boolean, Int) -> Unit,
) {
    var pixels by remember(device.id) {
        mutableStateOf(
            device.customPixels.takeIf { list -> list.isNotEmpty() && list.any { it != Color.Black } }
                ?: List(20) { swatches[it % swatches.size] },
        )
    }
    var countText by remember(device.id) { mutableStateOf(pixels.size.toString()) }
    var selectedColor by remember(device.id) { mutableStateOf(swatches.first()) }
    var moving by remember(device.id) { mutableStateOf(device.moving) }
    var moveRight by remember(device.id) { mutableStateOf(device.moveRight) }
    var speed by remember(device.id) { mutableStateOf(device.speed.coerceIn(1, 10).toFloat()) }
    var breathing by remember(device.id) { mutableStateOf(device.breathing) }
    var breathingSpeed by remember(device.id) { mutableStateOf(device.breathingSpeed.coerceIn(1, 10).toFloat()) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFFBF9FF)).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 32.sp, color = Color(0xFF252630)) }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("⋮", fontSize = 24.sp)
        }
        PatternPreview(pixels)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E3EA)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LED 數量", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        val count = (pixels.size - 1).coerceAtLeast(1); pixels = pixels.take(count); countText = count.toString()
                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(38.dp)) { Text("−") }
                    Text(pixels.size.toString(), modifier = Modifier.padding(horizontal = 14.dp), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        val count = (pixels.size + 1).coerceAtMost(256); pixels = pixels + List(count - pixels.size) { selectedColor }; countText = count.toString()
                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(38.dp)) { Text("＋") }
                }
                OutlinedTextField(
                    value = countText,
                    onValueChange = { input ->
                        val digits = input.filter(Char::isDigit).take(3); countText = digits
                        val count = digits.toIntOrNull()?.coerceIn(1, 256) ?: return@OutlinedTextField
                        pixels = when { count > pixels.size -> pixels + List(count - pixels.size) { selectedColor }; count < pixels.size -> pixels.take(count); else -> pixels }
                    },
                    label = { Text("直接輸入數量（最多 256）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E3EA)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("圖樣方案", fontWeight = FontWeight.Bold)
                Text("選一個方案後仍可逐顆修改", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = {
                        pixels = List(pixels.size) { Color.hsv((it * 360f / pixels.size), 0.82f, 1f) }
                        moving = true; breathing = false
                    }, label = { Text("彩虹") })
                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = {
                        val colors = listOf(Color(0xFFFF5A36), Color(0xFFFFA62B), Color(0xFFFFE066))
                        pixels = List(pixels.size) { colors[it % colors.size] }
                        moving = true; breathing = false
                    }, label = { Text("暖陽") })
                    FilterChip(modifier = Modifier.weight(1f), selected = false, onClick = {
                        val colors = listOf(Color(0xFF0047AB), Color(0xFF00B8D9), Color(0xFF7FDBFF))
                        pixels = List(pixels.size) { colors[it % colors.size] }
                        moving = true; breathing = false
                    }, label = { Text("海洋") })
                }
                FilterChip(modifier = Modifier.fillMaxWidth(), selected = breathing && !moving && pixels.distinct().size == 1,
                    onClick = {
                        pixels = List(pixels.size) { selectedColor }
                        moving = false; breathing = true
                    }, label = { Text("單色呼吸") })
            }
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E3EA)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("條帶編輯", fontWeight = FontWeight.Bold)
                Text("先選顏色，再點選要變色的燈珠", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    swatches.forEach { color -> Swatch(color, color == selectedColor) { selectedColor = color } }
                }
                HsvColorPicker(selectedColor) { selectedColor = it }
                PixelGrid(pixels) { index -> pixels = pixels.toMutableList().also { it[index] = selectedColor } }
            }
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E3EA)),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("方向", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(modifier = Modifier.weight(1f), selected = !moving,
                        onClick = { moving = false }, label = { Text("固定") })
                    FilterChip(modifier = Modifier.weight(1f), selected = moving && !moveRight,
                        onClick = { moving = true; moveRight = false }, label = { Text("向前") })
                    FilterChip(modifier = Modifier.weight(1f), selected = moving && moveRight,
                        onClick = { moving = true; moveRight = true }, label = { Text("向後") })
                }
                Text("移動速度  ${speed.roundToInt()}", fontWeight = FontWeight.Bold)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 1f..10f, steps = 8, enabled = moving)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("呼吸", fontWeight = FontWeight.Bold); Text("開啟後顏色會緩慢呼吸變化", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = breathing, onCheckedChange = { breathing = it })
                }
                if (breathing) {
                    Text("呼吸速度  ${breathingSpeed.roundToInt()}", fontWeight = FontWeight.Bold)
                    Slider(value = breathingSpeed, onValueChange = { breathingSpeed = it }, valueRange = 1f..10f, steps = 8)
                }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26377E)),
            onClick = { onApply(pixels, moving, moveRight, speed.roundToInt(), breathing, breathingSpeed.roundToInt()) },
        ) { Text("套用", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PatternPreview(pixels: List<Color>) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151A23))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("燈帶預覽", color = Color.White, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val preview = if (pixels.isEmpty()) List(20) { Color.Black } else List(20) { pixels[it % pixels.size] }
                preview.forEach { color -> Surface(modifier = Modifier.weight(1f).height(22.dp), color = color, shape = RoundedCornerShape(6.dp)) {} }
            }
        }
    }
}

@Composable
private fun PixelGrid(pixels: List<Color>, onPixelClick: (Int) -> Unit) {
    pixels.chunked(10).forEachIndexed { rowIndex, row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            row.forEachIndexed { columnIndex, color ->
                val index = rowIndex * 10 + columnIndex
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                    Surface(
                        modifier = Modifier.size(27.dp),
                        color = color,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E3EA)),
                        onClick = { onPixelClick(index) },
                    ) {}
                }
            }
        }
        Spacer(Modifier.height(7.dp))
    }
}

@Composable
private fun HsvColorPicker(color: Color, onColorChange: (Color) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) } }
    var paletteSize by remember { mutableStateOf(IntSize.Zero) }
    val hueColor = Color.hsv(hsv[0], 1f, 1f)
    Canvas(modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)).onSizeChanged { paletteSize = it }
        .pointerInput(color) { detectTapGestures { point ->
            if (paletteSize.width > 0 && paletteSize.height > 0) {
                val saturation = (point.x / paletteSize.width).coerceIn(0f, 1f)
                val value = (1f - point.y / paletteSize.height).coerceIn(0f, 1f)
                onColorChange(Color.hsv(hsv[0], saturation, value))
            }
        } }) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(Color.White, 9f, Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).pointerInput(color, paletteSize) { detectTapGestures { point ->
        if (paletteSize.width > 0) {
            val hue = (point.x / paletteSize.width * 360f).coerceIn(0f, 360f); onColorChange(Color.hsv(hue, hsv[1], hsv[2]))
        }
    } }) {
        drawRect(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), size = Size(size.width, size.height))
        drawCircle(Color.White, 8f, Offset(hsv[0] / 360f * size.width, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.size(if (selected) 34.dp else 30.dp), color = color, shape = CircleShape, onClick = onClick,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {}
}
