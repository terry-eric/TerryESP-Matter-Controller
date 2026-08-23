package tw.terry.matterlight

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow

private val TerryIndigo = Color(0xFF26377E)
private val TerryIndigoDark = Color(0xFF17245E)
private val TerryLavender = Color(0xFFE8EAFE)
private val TerryBackground = Color(0xFFFBF9FF)
private val TerrySurface = Color(0xFFFFFFFF)
private val TerryOutline = Color(0xFFE3E3EA)
private val TerryGreen = Color(0xFF35C979)

@Composable
fun TerryMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = TerryIndigo,
            onPrimary = Color.White,
            primaryContainer = TerryLavender,
            onPrimaryContainer = TerryIndigoDark,
            secondary = Color(0xFF6D62D8),
            background = TerryBackground,
            surface = TerrySurface,
            surfaceContainer = Color(0xFFF5F3FA),
            surfaceContainerHigh = Color(0xFFEEEFF7),
            outlineVariant = TerryOutline,
        ),
        content = content,
    )
}

@Composable
fun MaterialYouHomeScreen(
    devices: List<LightDevice>,
    rooms: List<String>,
    selectedRoom: String?,
    selectedDevice: LightDevice?,
    onBack: () -> Unit,
    onRoom: (String) -> Unit,
    onDevice: (LightDevice) -> Unit,
    onAddDevice: () -> Unit,
    onAddRoom: () -> Unit,
    onPower: (String, Boolean) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onColor: (String, Color) -> Unit,
    onColorTemperature: (String, Int) -> Unit,
    onEffect: (String, LightEffect, Int, Boolean, Int) -> Unit,
    onCustom: (LightDevice) -> Unit,
    onGlobalCustom: (List<String>) -> Unit,
    signedInEmail: String?,
    localMode: Boolean,
    familyMembers: List<String>,
    onInviteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onUpdateDevice: (String, String, String) -> Unit,
    onDeleteDevice: (String) -> Unit,
    onRenameRoom: (String, String) -> Unit,
    onDeleteRoom: (String) -> Unit,
    messages: Flow<String>,
    onGoogleSignIn: () -> Unit,
    onOpenGoogleHome: () -> Unit,
    onSignOut: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    when {
        selectedDevice != null -> DeviceControlPage(
            device = selectedDevice,
            onBack = onBack,
            onPower = { onPower(selectedDevice.id, it) },
            onBrightness = { onBrightness(selectedDevice.id, it) },
            onColor = { onColor(selectedDevice.id, it) },
            onColorTemperature = { onColorTemperature(selectedDevice.id, it) },
            onEffect = { effect, speed, breathing, breathingSpeed ->
                if (selectedDevice.localHost == null || selectedDevice.localKey == null) {
                    onCustom(selectedDevice)
                } else {
                    onEffect(selectedDevice.id, effect, speed, breathing, breathingSpeed)
                }
            },
            onCustom = { onCustom(selectedDevice) },
            rooms = rooms,
            onUpdateDevice = onUpdateDevice,
            onDeleteDevice = onDeleteDevice,
        )

        selectedRoom != null -> RoomDevicesPage(
            room = selectedRoom,
            devices = devices.filter { it.room == selectedRoom },
            onBack = onBack,
            onDevice = onDevice,
            onPower = onPower,
            onRenameRoom = onRenameRoom,
            onDeleteRoom = onDeleteRoom,
        )

        tab == 0 -> HomePage(devices, rooms, onRoom, onAddDevice, onAddRoom, tab) { tab = it }
        tab == 1 -> GlobalControlPage(
            devices = devices,
            rooms = rooms,
            onPower = onPower,
            onBrightness = onBrightness,
            onColor = onColor,
            onColorTemperature = onColorTemperature,
            onEffect = onEffect,
            onCustom = onGlobalCustom,
            selectedTab = tab,
            onTab = { tab = it },
        )
        else -> FamilySettingsPage(
            email = signedInEmail,
            localMode = localMode,
            members = familyMembers,
            onInviteMember = onInviteMember,
            onRemoveMember = onRemoveMember,
            onGoogleSignIn = onGoogleSignIn,
            onOpenGoogleHome = onOpenGoogleHome,
            onSignOut = onSignOut,
            selectedTab = tab,
            onTab = { tab = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePage(
    devices: List<LightDevice>,
    rooms: List<String>,
    onRoom: (String) -> Unit,
    onAddDevice: () -> Unit,
    onAddRoom: () -> Unit,
    selectedTab: Int,
    onTab: (Int) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = TerryBackground,
        topBar = {
            TopAppBar(
                title = { Text("TerryESP Controller", color = TerryIndigoDark, fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onAddRoom) { Text("新增房間") }
                    TextButton(onClick = { menuExpanded = true }) { Text("⋮", fontSize = 24.sp) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("新增本地或 Matter 裝置") }, onClick = { menuExpanded = false; onAddDevice() })
                        DropdownMenuItem(text = { Text("新增房間") }, onClick = { menuExpanded = false; onAddRoom() })
                        DropdownMenuItem(text = { Text("家庭與設定") }, onClick = { menuExpanded = false; onTab(2) })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDevice,
                containerColor = TerryIndigo,
                contentColor = Color.White,
                shape = RoundedCornerShape(22.dp),
                icon = { Text("＋", fontSize = 24.sp) },
                text = { Text("新增裝置", fontWeight = FontWeight.Bold) },
            )
        },
        bottomBar = { TerryBottomBar(selectedTab, onTab) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { onTab(2) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text("我的家庭⌄", color = Color(0xFF15151B), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(7.dp), CircleShape, TerryGreen) {}
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (devices.all { it.isOnline }) "一切運作正常" else "有裝置離線",
                                color = Color(0xFF777783), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HomeIllustration()
                }
            }
            item { Text("房間", modifier = Modifier.padding(top = 6.dp, bottom = 2.dp), fontWeight = FontWeight.Bold) }
            items(rooms, key = { it }) { room ->
                val roomDevices = devices.filter { it.room == room }
                RoomSummaryCard(room, roomDevices) { onRoom(room) }
            }
            if (rooms.isEmpty()) item {
                DesignCard {
                    Text("還沒有房間", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("新增裝置後，就能依房間整理與控制燈光。", color = Color.Gray)
                }
            }
            item { Spacer(Modifier.height(84.dp)) }
        }
    }
}

@Composable
private fun HomeIllustration() {
    Canvas(Modifier.size(width = 150.dp, height = 112.dp)) {
        val floor = size.height * .78f
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, floor)
                cubicTo(size.width * .24f, floor, size.width * .34f, size.height * .28f, size.width * .56f, size.height * .38f)
                cubicTo(size.width * .76f, size.height * .45f, size.width * .76f, floor, size.width, floor)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            },
            Color(0xFFFFEEDA),
        )
        drawRoundRect(TerryIndigo.copy(alpha = .72f), Offset(size.width * .43f, size.height * .56f), androidx.compose.ui.geometry.Size(size.width * .38f, size.height * .25f), androidx.compose.ui.geometry.CornerRadius(18f))
        drawRoundRect(TerryIndigo, Offset(size.width * .36f, size.height * .70f), androidx.compose.ui.geometry.Size(size.width * .50f, size.height * .15f), androidx.compose.ui.geometry.CornerRadius(12f))
        drawLine(Color(0xFF315B51), Offset(size.width * .88f, size.height * .43f), Offset(size.width * .88f, floor), 7f, StrokeCap.Round)
        drawCircle(Color(0xFF3A7563), size.width * .07f, Offset(size.width * .90f, size.height * .38f))
    }
}

@Composable
private fun RoomSummaryCard(room: String, devices: List<LightDevice>, onClick: () -> Unit) {
    val onCount = devices.count { it.isOn }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = TerrySurface),
        border = BorderStroke(1.dp, TerryOutline),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), RoundedCornerShape(13.dp), TerryLavender) {
                Box(contentAlignment = Alignment.Center) { Text(roomIcon(room), fontSize = 23.sp) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(room, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${devices.size} 個裝置", color = Color(0xFF5F6069), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(7.dp), CircleShape, if (onCount > 0) TerryGreen else Color(0xFFB9BBC4)) {}
                    Spacer(Modifier.width(6.dp))
                    Text(if (onCount > 0) "$onCount 個已開啟" else "全部關閉", color = Color(0xFF73747E), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("›", fontSize = 28.sp, color = Color(0xFF343640))
        }
    }
}

private fun roomIcon(room: String): String = when {
    room.contains("客") -> "▣"
    room.contains("臥") -> "▰"
    room.contains("廚") -> "▤"
    room.contains("書") -> "▥"
    else -> "⌂"
}

@Composable
private fun TerryBottomBar(selected: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = TerrySurface) {
        listOf("⌂" to "首頁", "☷" to "統一控制", "⚙" to "設定").forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onTab(index) },
                icon = { Text(item.first, fontSize = 22.sp) },
                label = { Text(item.second, fontSize = 11.sp, maxLines = 1) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomDevicesPage(
    room: String,
    devices: List<LightDevice>,
    onBack: () -> Unit,
    onDevice: (LightDevice) -> Unit,
    onPower: (String, Boolean) -> Unit,
    onRenameRoom: (String, String) -> Unit,
    onDeleteRoom: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember(room) { mutableStateOf(room) }
    var showDelete by remember { mutableStateOf(false) }
    if (showRename) {
        TextInputDialog("重新命名房間", "房間名稱", renameText, { renameText = it }, { showRename = false }) {
            onRenameRoom(room, renameText); showRename = false; onBack()
        }
    }
    if (showDelete) {
        ConfirmDialog("刪除房間？", "房內裝置會移到其他房間。", { showDelete = false }) {
            onDeleteRoom(room); showDelete = false; onBack()
        }
    }
    Scaffold(
        containerColor = TerryBackground,
        topBar = {
            PageTopBar(room, onBack, onAction = { menuExpanded = true }, menu = {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("重新命名房間") }, onClick = { menuExpanded = false; showRename = true })
                    DropdownMenuItem(text = { Text("刪除房間") }, onClick = { menuExpanded = false; showDelete = true })
                }
            })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${devices.size} 個裝置", color = Color.Gray)
                    Spacer(Modifier.width(12.dp))
                    Surface(Modifier.size(7.dp), CircleShape, TerryGreen) {}
                    Spacer(Modifier.width(6.dp))
                    Text("${devices.count { it.isOn }} 個已開啟", color = Color.Gray)
                }
            }
            items(devices, key = { it.id }) { device ->
                Card(
                    onClick = { onDevice(device) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = TerrySurface),
                    border = BorderStroke(1.dp, TerryOutline),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(46.dp), RoundedCornerShape(13.dp), Color(0xFFF1F1F6)) {
                            Box(contentAlignment = Alignment.Center) { Text("▤", color = TerryIndigo, fontSize = 22.sp) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(device.name, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    !device.isOnline -> "離線"
                                    device.isOn -> "線上  ·  亮度 ${device.brightness}%"
                                    else -> "線上  ·  已關閉"
                                },
                                color = Color(0xFF686973),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Surface(
                                onClick = { onPower(device.id, !device.isOn) },
                                modifier = Modifier.size(42.dp),
                                shape = CircleShape,
                                color = if (device.isOn) TerryIndigo else Color.Transparent,
                                border = if (device.isOn) null else BorderStroke(1.dp, TerryOutline),
                            ) { Box(contentAlignment = Alignment.Center) { PowerGlyph(if (device.isOn) Color.White else Color(0xFF42434D)) } }
                            if (device.id.startsWith("local-")) {
                                Surface(shape = RoundedCornerShape(8.dp), color = TerryLavender) {
                                    Text(
                                        "僅區網",
                                        Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        color = TerryIndigoDark,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceControlPage(
    device: LightDevice,
    onBack: () -> Unit,
    onPower: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    onColor: (Color) -> Unit,
    onColorTemperature: (Int) -> Unit,
    onEffect: (LightEffect, Int, Boolean, Int) -> Unit,
    onCustom: () -> Unit,
    rooms: List<String>,
    onUpdateDevice: (String, String, String) -> Unit,
    onDeleteDevice: (String) -> Unit,
) {
    var brightness by remember(device.id, device.brightness) { mutableFloatStateOf(device.brightness.toFloat()) }
    var selectedColor by remember(device.id, device.color) { mutableStateOf(device.color) }
    var colorTemperature by remember(device.id, device.colorTemperature) { mutableFloatStateOf(device.colorTemperature.toFloat()) }
    var effectSpeed by remember(device.id, device.speed) { mutableFloatStateOf(device.speed.toFloat()) }
    var effectBreathing by remember(device.id, device.breathing) { mutableStateOf(device.breathing) }
    var effectBreathingSpeed by remember(device.id, device.breathingSpeed) { mutableFloatStateOf(device.breathingSpeed.toFloat()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    if (showEdit) {
        DeviceEditDialog(device, rooms, { showEdit = false }) { name, room ->
            onUpdateDevice(device.id, name, room); showEdit = false
        }
    }
    if (showDelete) {
        ConfirmDialog("刪除裝置？", "此裝置會從 App 清單移除。若仍存在 Google Home，也需要在 Google Home 中移除。", { showDelete = false }) {
            onDeleteDevice(device.id); showDelete = false; onBack()
        }
    }
    val presets = listOf(Color(0xFFF28BA8), Color(0xFFF4B054), Color(0xFFF3DC4E), Color(0xFF65D44F), Color(0xFF42C8C6), Color(0xFF65A5E8), Color(0xFFA98BE7))
    Scaffold(
        containerColor = TerryBackground,
        topBar = {
            PageTopBar(device.name, onBack, action = "✎  ⋮", onAction = { menuExpanded = true }, menu = {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("編輯名稱與房間") }, onClick = { menuExpanded = false; showEdit = true })
                    DropdownMenuItem(text = { Text("刪除裝置") }, onClick = { menuExpanded = false; showDelete = true })
                }
            })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = TerryIndigoDark)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { onPower(!device.isOn) }, modifier = Modifier.size(58.dp), shape = CircleShape, color = Color(0xFF746CFF)) {
                            Box(contentAlignment = Alignment.Center) { PowerGlyph(Color.White, Modifier.size(30.dp)) }
                        }
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(if (device.isOn) "已開啟" else "已關閉", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(if (device.isOnline) "●  線上" else "●  離線", color = if (device.isOnline) Color(0xFF68E88F) else Color.LightGray)
                        }
                    }
                }
            }
            item {
                DesignCard {
                    Text("☀  亮度  ${brightness.roundToInt()}%", fontWeight = FontWeight.SemiBold)
                    Slider(value = brightness, onValueChange = { brightness = it }, onValueChangeFinished = { onBrightness(brightness.roundToInt()) }, valueRange = 1f..100f)
                }
            }
            item {
                DesignCard {
                    Text("顏色", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        ColorWheel(selectedColor, 174.dp) { color -> selectedColor = color; onColor(color) }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(Modifier.size(38.dp), CircleShape, selectedColor, border = BorderStroke(2.dp, Color.White)) {}
                            Surface(Modifier.size(38.dp), CircleShape, Color.White, border = BorderStroke(1.dp, TerryOutline)) {}
                        }
                    }
                }
            }
            item {
                DesignCard {
                    Text("色溫  ${colorTemperature.roundToInt()}K", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = colorTemperature,
                        onValueChange = { colorTemperature = it },
                        onValueChangeFinished = { onColorTemperature(colorTemperature.roundToInt()) },
                        valueRange = 2000f..6500f,
                    )
                }
            }
            item {
                DesignCard {
                    Text("預設顏色", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        presets.forEach { color -> ColorDot(color, color == selectedColor) { selectedColor = color; onColor(color) } }
                        ColorDot(Color.White, selectedColor == Color.White) { selectedColor = Color.White; onColor(Color.White) }
                    }
                }
            }
            item {
                DesignCard {
                    Text("內建燈效", fontWeight = FontWeight.Bold)
                    Text("效果速度  ${effectSpeed.roundToInt()}", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = effectSpeed,
                        onValueChange = { effectSpeed = it },
                        onValueChangeFinished = {
                            onEffect(device.effect, effectSpeed.roundToInt(), effectBreathing, effectBreathingSpeed.roundToInt())
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("呼吸效果", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = effectBreathing, onCheckedChange = {
                            effectBreathing = it
                            onEffect(device.effect, effectSpeed.roundToInt(), it, effectBreathingSpeed.roundToInt())
                        })
                    }
                    if (effectBreathing) {
                        Text("呼吸速度  ${effectBreathingSpeed.roundToInt()}", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = effectBreathingSpeed,
                            onValueChange = { effectBreathingSpeed = it },
                            onValueChangeFinished = {
                                onEffect(device.effect, effectSpeed.roundToInt(), true, effectBreathingSpeed.roundToInt())
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                    }
                    LightEffect.entries.filter { it != LightEffect.CUSTOM }.chunked(3).forEach { rowEffects ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            rowEffects.forEach { effect ->
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = device.effect == effect,
                                    onClick = { onEffect(effect, effectSpeed.roundToInt(), effectBreathing, effectBreathingSpeed.roundToInt()) },
                                    label = { Text(effect.label, maxLines = 1) },
                                )
                            }
                            repeat(3 - rowEffects.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item {
                Card(onClick = onCustom, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = TerryLavender)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✦", color = TerryIndigo, fontSize = 24.sp)
                        Text("自訂燈效", Modifier.weight(1f).padding(horizontal = 14.dp), color = TerryIndigoDark, fontWeight = FontWeight.Bold)
                        Text("›", fontSize = 25.sp)
                    }
                }
            }
            item {
                DesignCard {
                    Text("裝置資訊", fontWeight = FontWeight.Bold)
                    InfoRow("裝置名稱", device.name)
                    InfoRow("房間", device.room)
                    InfoRow("裝置 ID", device.id)
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalControlPage(
    devices: List<LightDevice>,
    rooms: List<String>,
    onPower: (String, Boolean) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onColor: (String, Color) -> Unit,
    onColorTemperature: (String, Int) -> Unit,
    onEffect: (String, LightEffect, Int, Boolean, Int) -> Unit,
    onCustom: (List<String>) -> Unit,
    selectedTab: Int,
    onTab: (Int) -> Unit,
) {
    var brightness by remember(devices) { mutableFloatStateOf(devices.map { it.brightness }.average().takeIf { !it.isNaN() }?.toFloat() ?: 50f) }
    var selectedColor by remember { mutableStateOf(Color(0xFF746CFF)) }
    var colorTemperature by remember(devices) { mutableFloatStateOf(devices.map { it.colorTemperature }.average().takeIf { !it.isNaN() }?.toFloat() ?: 4000f) }
    var effectSpeed by remember { mutableFloatStateOf(5f) }
    var effectBreathing by remember { mutableStateOf(false) }
    var effectBreathingSpeed by remember { mutableFloatStateOf(5f) }
    var selectedRoom by remember { mutableStateOf("全部") }
    var menuExpanded by remember { mutableStateOf(false) }
    val targetDevices = if (selectedRoom == "全部") devices else devices.filter { it.room == selectedRoom }
    Scaffold(
        containerColor = TerryBackground,
        topBar = {
            TopAppBar(title = { Text("統一控制", fontWeight = FontWeight.Bold) }, actions = {
                TextButton(onClick = { menuExpanded = true }) { Text("⋮", fontSize = 24.sp) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("目前範圍全部開啟") }, onClick = {
                        menuExpanded = false; targetDevices.filter { it.isOnline }.forEach { onPower(it.id, true) }
                    })
                    DropdownMenuItem(text = { Text("目前範圍全部關閉") }, onClick = {
                        menuExpanded = false; targetDevices.filter { it.isOnline }.forEach { onPower(it.id, false) }
                    })
                }
            })
        },
        bottomBar = { TerryBottomBar(selectedTab, onTab) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("選擇範圍", color = TerryIndigoDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    (listOf("全部") + rooms.take(4)).forEach { room ->
                        FilterChip(selected = selectedRoom == room, onClick = { selectedRoom = room }, label = { Text(room) })
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = TerryIndigoDark)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("全部電源", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("${targetDevices.count { it.isOn }} / ${targetDevices.size} 個已開啟", color = Color.White.copy(alpha = .82f))
                        }
                        Surface(onClick = {
                            val enabled = targetDevices.none { it.isOn }
                            targetDevices.filter { it.isOnline }.forEach { onPower(it.id, enabled) }
                        }, modifier = Modifier.size(56.dp), shape = CircleShape, color = Color.White) {
                            Box(contentAlignment = Alignment.Center) { PowerGlyph(TerryIndigoDark, Modifier.size(28.dp)) }
                        }
                    }
                }
            }
            item {
                DesignCard {
                    Text("亮度  ${brightness.roundToInt()}%", fontWeight = FontWeight.Bold)
                    Slider(value = brightness, onValueChange = { brightness = it }, onValueChangeFinished = {
                        targetDevices.filter { it.isOnline }.forEach { onBrightness(it.id, brightness.roundToInt()) }
                    }, valueRange = 1f..100f)
                }
            }
            item {
                val localTargets = targetDevices.filter { it.isOnline && it.localHost != null && it.localKey != null }
                Card(
                    onClick = { onCustom(localTargets.map { it.id }) },
                    enabled = localTargets.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = TerryLavender),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("✦", color = TerryIndigo, fontSize = 24.sp)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("統一自訂燈條", color = TerryIndigoDark, fontWeight = FontWeight.Bold)
                            Text(
                                if (localTargets.isEmpty()) "請先逐台連接本地控制" else "套用到 ${localTargets.size} 台目前範圍內的裝置",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("›", fontSize = 25.sp)
                    }
                }
            }
            item {
                DesignCard {
                    Text("顏色", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        ColorDot(selectedColor, true) {}
                        Spacer(Modifier.width(32.dp))
                        ColorWheel(selectedColor, 154.dp) { color ->
                            selectedColor = color
                            targetDevices.filter { it.isOnline }.forEach { onColor(it.id, color) }
                        }
                    }
                }
            }
            item {
                DesignCard {
                    Text("色溫  ${colorTemperature.roundToInt()}K", fontWeight = FontWeight.Bold)
                    Slider(value = colorTemperature, onValueChange = { colorTemperature = it },
                        onValueChangeFinished = {
                            targetDevices.filter { it.isOnline }.forEach { onColorTemperature(it.id, colorTemperature.roundToInt()) }
                        },
                        valueRange = 2000f..6500f)
                }
            }
            item {
                DesignCard {
                    Text("內建燈效", fontWeight = FontWeight.Bold)
                    Text("效果速度  ${effectSpeed.roundToInt()}", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = effectSpeed,
                        onValueChange = { effectSpeed = it },
                        onValueChangeFinished = {
                            targetDevices.filter { it.isOnline && it.localHost != null && it.localKey != null }
                                .forEach { onEffect(it.id, it.effect, effectSpeed.roundToInt(), effectBreathing, effectBreathingSpeed.roundToInt()) }
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("呼吸效果", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = effectBreathing, onCheckedChange = { enabled ->
                            effectBreathing = enabled
                            targetDevices.filter { it.isOnline && it.localHost != null && it.localKey != null }
                                .forEach { onEffect(it.id, it.effect, effectSpeed.roundToInt(), enabled, effectBreathingSpeed.roundToInt()) }
                        })
                    }
                    if (effectBreathing) {
                        Text("呼吸速度  ${effectBreathingSpeed.roundToInt()}", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = effectBreathingSpeed,
                            onValueChange = { effectBreathingSpeed = it },
                            onValueChangeFinished = {
                                targetDevices.filter { it.isOnline && it.localHost != null && it.localKey != null }
                                    .forEach { onEffect(it.id, it.effect, effectSpeed.roundToInt(), true, effectBreathingSpeed.roundToInt()) }
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                        )
                    }
                    LightEffect.entries.filter { it != LightEffect.CUSTOM }.chunked(3).forEach { rowEffects ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            rowEffects.forEach { effect ->
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = targetDevices.isNotEmpty() && targetDevices.all { it.effect == effect },
                                    onClick = {
                                        targetDevices.filter { it.isOnline && it.localHost != null && it.localKey != null }
                                            .forEach { onEffect(it.id, effect, effectSpeed.roundToInt(), effectBreathing, effectBreathingSpeed.roundToInt()) }
                                    },
                                    enabled = targetDevices.any { it.isOnline && it.localHost != null && it.localKey != null },
                                    label = { Text(effect.label, maxLines = 1) },
                                )
                            }
                            repeat(3 - rowEffects.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (targetDevices.none { it.localHost != null && it.localKey != null }) {
                        Text("內建燈效需要先在裝置的「自訂燈條」中連接本地控制。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilySettingsPage(
    email: String?,
    localMode: Boolean,
    members: List<String>,
    onInviteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onOpenGoogleHome: () -> Unit,
    onSignOut: () -> Unit,
    selectedTab: Int,
    onTab: (Int) -> Unit,
) {
    var showInvite by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    if (showInvite) {
        TextInputDialog("邀請 TerryESP 家庭成員", "Email", inviteEmail, { inviteEmail = it }, { showInvite = false }) {
            onInviteMember(inviteEmail); inviteEmail = ""; showInvite = false
        }
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("TerryESP Controller") },
            text = { Text("版本 ${BuildConfig.VERSION_NAME}\n\n支援 Matter／Google Home 與同 Wi-Fi 的 ESP 本地控制。純本地模式不需要 Google 帳號。") },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("完成") } },
        )
    }
    Scaffold(
        containerColor = TerryBackground,
        topBar = { TopAppBar(title = { Text("家庭與設定", fontWeight = FontWeight.Bold) }) },
        bottomBar = { TerryBottomBar(selectedTab, onTab) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionLabel("Google 帳戶") }
            item {
                Card(
                    onClick = { if (email == null) onGoogleSignIn() else onOpenGoogleHome() },
                    shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = TerrySurface),
                    border = BorderStroke(1.dp, TerryOutline),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.padding(16.dp).size(46.dp), CircleShape, Color(0xFF7567DF)) { Box(contentAlignment = Alignment.Center) { Text(email?.take(1)?.uppercase() ?: "L", color = Color.White) } }
                        Column(Modifier.weight(1f).padding(vertical = 16.dp)) {
                            Text(email ?: "本地模式", fontWeight = FontWeight.SemiBold)
                            Text(if (email == null) "按一下登入 Google" else "按一下開啟 Google Home 管理", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", fontSize = 24.sp)
                        Spacer(Modifier.width(16.dp))
                    }
                }
            }
            item { SectionLabel("TerryESP 家庭成員") }
            item {
                DesignCard(0.dp) {
                    MemberRow(email?.take(1)?.uppercase() ?: "L", email ?: "此手機", "家庭管理員", Color(0xFF7567DF))
                    members.forEach { member ->
                        HorizontalDivider(color = TerryOutline)
                        MemberRow(member.take(1).uppercase(), member, "成員", Color(0xFF4D9B83)) { onRemoveMember(member) }
                    }
                    HorizontalDivider(color = TerryOutline)
                    TextButton(onClick = { showInvite = true }, modifier = Modifier.fillMaxWidth()) { Text("♙＋  邀請成員", color = TerryIndigo, fontWeight = FontWeight.Bold) }
                }
            }
            item {
                DesignCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("⌂", fontSize = 30.sp, color = TerryIndigo)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(if (email != null) "Google Home 已授權" else "Google Home 未連結", fontWeight = FontWeight.Bold)
                            Text(if (email != null) "Matter 裝置會透過 Google Home 控制" else "目前僅使用 ESP 本地控制", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("●", color = if (email != null) TerryGreen else Color.Gray)
                    }
                }
            }
            item {
                DesignCard(0.dp) {
                    if (email != null) SettingAction("⇥", "登出 Google", onSignOut)
                    else SettingAction("G", "登入 Google", onGoogleSignIn)
                    HorizontalDivider(color = TerryOutline)
                    SettingAction("ⓘ", "版本與說明  ${BuildConfig.VERSION_NAME}") { showAbout = true }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    action: String = "⋮",
    onAction: () -> Unit = {},
    menu: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹", fontSize = 32.sp, color = Color(0xFF252630)) } },
        actions = {
            Box {
                TextButton(onClick = onAction) { Text(action, fontSize = 22.sp) }
                menu()
            }
        },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = onConfirm, enabled = value.isNotBlank()) { Text("儲存") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = onConfirm) { Text("確定") } },
    )
}

@Composable
private fun DeviceEditDialog(device: LightDevice, rooms: List<String>, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    var room by remember(device.id) { mutableStateOf(device.room) }
    val isLocalOnly = device.id.startsWith("local-")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("編輯裝置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("裝置名稱") }, singleLine = true)
                if (isLocalOnly) {
                    OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("房間") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rooms.take(3).forEach { option ->
                            FilterChip(selected = room == option, onClick = { room = option }, label = { Text(option) })
                        }
                    }
                } else {
                    Text("房間：${device.room}", fontWeight = FontWeight.SemiBold)
                    Text("Matter 裝置的房間請在 Google Home 中設定，App 會自動同步。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = { onConfirm(name, room) }, enabled = name.isNotBlank() && room.isNotBlank()) { Text("儲存") } },
    )
}

@Composable
private fun DesignCard(contentPadding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TerrySurface),
        border = BorderStroke(1.dp, TerryOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Color(0xFF555660))
        Text(value, color = Color(0xFF656670), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.padding(top = 4.dp, start = 4.dp), color = TerryIndigoDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun MemberRow(initial: String, email: String, role: String, color: Color, onRemove: (() -> Unit)? = null) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(40.dp), CircleShape, color) { Box(contentAlignment = Alignment.Center) { Text(initial, color = Color.White) } }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(email, fontWeight = FontWeight.SemiBold); Text(role, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        if (onRemove != null) {
            TextButton(onClick = { menuExpanded = true }) { Text("⋮", fontSize = 22.sp) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("移除成員") }, onClick = { menuExpanded = false; onRemove() })
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onChecked)
    }
}

@Composable
private fun SettingAction(icon: String, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 22.sp)
        Text(title, Modifier.weight(1f).padding(horizontal = 14.dp), fontWeight = FontWeight.SemiBold)
        Text("›", fontSize = 24.sp)
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(if (selected) 34.dp else 28.dp),
        shape = CircleShape,
        color = color,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) TerryIndigo else TerryOutline),
    ) {}
}

@Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier.size(23.dp)) {
    Canvas(modifier) {
        val stroke = 2.3.dp.toPx()
        drawArc(
            color = color,
            startAngle = -52f,
            sweepAngle = 284f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke, stroke),
            size = androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2),
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height * .48f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ColorWheel(color: Color, diameter: androidx.compose.ui.unit.Dp, onColor: (Color) -> Unit) {
    val hsv = remember(color) { FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) } }
    fun colorAt(point: Offset, width: Float, height: Float): Color? {
        val center = Offset(width / 2f, height / 2f)
        val dx = point.x - center.x
        val dy = point.y - center.y
        val radius = sqrt(dx * dx + dy * dy)
        val maxRadius = minOf(width, height) / 2f
        if (radius > maxRadius) return null
        val hue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
        return Color.hsv(hue, (radius / maxRadius).coerceIn(.08f, 1f), 1f)
    }
    Canvas(
        Modifier.size(diameter)
            .pointerInput(Unit) {
                detectTapGestures { point -> colorAt(point, size.width.toFloat(), size.height.toFloat())?.let(onColor) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { point -> colorAt(point, size.width.toFloat(), size.height.toFloat())?.let(onColor) },
                    onDrag = { change, _ -> colorAt(change.position, size.width.toFloat(), size.height.toFloat())?.let(onColor) },
                )
            },
    ) {
        drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent)))
        val angle = hsv[0] / 180f * PI.toFloat()
        val radius = size.minDimension / 2f * hsv[1]
        val marker = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(color, size.minDimension * .17f, center)
        drawCircle(Color.White, size.minDimension * .17f, center, style = Stroke(3.dp.toPx()))
    }
}
