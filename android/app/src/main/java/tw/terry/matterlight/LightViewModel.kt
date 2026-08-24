package tw.terry.matterlight

import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class LightViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("terryesp_controller", 0)
    private val defaultRooms = listOf("客廳", "臥室")
    private val localDevices = linkedMapOf<String, LightDevice>()
    private val googleDevices = linkedMapOf<String, LightDevice>()
    private val hiddenGoogleDeviceIds = preferences
        .getStringSet("hidden_google_devices", emptySet())
        .orEmpty()
        .toMutableSet()
    private val localOnlineById = mutableMapOf<String, Boolean>()
    private val cloudOnlineById = mutableMapOf<String, Boolean>()
    private val _devices = MutableStateFlow<List<LightDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _rooms = MutableStateFlow(loadRooms())
    val rooms = _rooms.asStateFlow()
    private val _members = MutableStateFlow(
        preferences.getStringSet("family_members", emptySet())?.toList().orEmpty().sorted()
    )
    val members = _members.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val controlLocks = mutableMapOf<String, Mutex>()
    private val colorJobs = mutableMapOf<String, Job>()
    private val colorCommandVersions = mutableMapOf<String, Int>()
    private val powerCommandVersions = mutableMapOf<String, Int>()
    private val recoveringEndpoints = mutableSetOf<String>()
    private val localDiscovery = LocalEspDiscovery(application) { candidate ->
        recoverDiscoveredDevice(candidate)
    }

    init {
        val savedLocalDevices = loadLocalDevices()
        savedLocalDevices.forEach { localDevices[it.id] = it }
        if (savedLocalDevices.isNotEmpty()) {
            saveRooms((_rooms.value + savedLocalDevices.map { it.room }).distinct())
        }
        publishDevices()
        localDiscovery.start()
        viewModelScope.launch {
            while (isActive) {
                refreshLocalStatuses()
                delay(5_000)
            }
        }
        viewModelScope.launch {
            GoogleHomeBridge.lights.collect { lights ->
                val incomingIds = lights.mapTo(mutableSetOf()) { it.id }
                if (hiddenGoogleDeviceIds.removeAll { it !in incomingIds }) {
                    saveHiddenGoogleDevices()
                }
                googleDevices.clear()
                cloudOnlineById.clear()
                lights.filterNot { it.id in hiddenGoogleDeviceIds }
                    .forEach { cloudOnlineById[it.id] = it.isOnline }
                lights.filterNot { it.id in hiddenGoogleDeviceIds }
                    .map(::withSavedMetadata).map(::withLocalBinding).forEach {
                    googleDevices[it.id] = it.copy(isOnline = it.isOnline || localOnlineById[it.id] == true)
                }
                if (lights.isNotEmpty()) {
                    saveRooms((_rooms.value + lights.map { withSavedMetadata(it).room }).distinct())
                }
                publishDevices()
            }
        }
    }

    override fun onCleared() {
        localDiscovery.stop()
        super.onCleared()
    }

    private fun recoverDiscoveredDevice(candidate: LocalEspCandidate) {
        val physicalId = candidate.deviceId?.lowercase()?.takeIf {
            it.matches(Regex("terryesp-[0-9a-f]{6}"))
        } ?: return
        val endpoint = "${candidate.host}:${candidate.port}"
        if (!recoveringEndpoints.add(endpoint)) return
        val savedDevices = localDevices.values.filter {
            !it.id.startsWith("preview-") && !it.localKey.isNullOrBlank()
        }
        viewModelScope.launch {
            try {
                val matched = savedDevices.firstOrNull { device ->
                    runCatching { LocalEspApi.verify(endpoint, device.localKey!!) }.isSuccess
                } ?: return@launch
                val fixedId = "local-$physicalId"
                val repaired = matched.copy(id = fixedId, localHost = endpoint, isOnline = true)
                localDevices.remove(matched.id)
                localDevices[fixedId] = repaired
                localOnlineById.remove(matched.id)
                localOnlineById[fixedId] = true
                persistLocalDevices()
                publishDevices()
                _messages.tryEmit("已找回並連接本地裝置")
                Log.i("TerryLocal", "Recovered $physicalId at $endpoint")
            } finally {
                recoveringEndpoints.remove(endpoint)
            }
        }
    }

    fun seedPreviewData() {
        if (localDevices.isNotEmpty()) return
        addPreviewDevice("電視燈條", "客廳", true)
        addPreviewDevice("展示櫃燈條", "客廳", false)
        addPreviewDevice("床頭燈條", "臥室", false)
    }

    private fun addPreviewDevice(name: String, room: String, enabled: Boolean) {
        val id = "preview-${localDevices.size + 1}"
        localDevices[id] = LightDevice(id, name, room, true, enabled, 50, Color(0xFF39B9FF))
        publishDevices()
    }

    fun addLocalDevice(
        name: String,
        room: String,
        host: String,
        key: String,
        physicalDeviceId: String? = null,
    ): String {
        val normalizedPhysicalId = physicalDeviceId?.lowercase()?.takeIf {
            it.matches(Regex("terryesp-[0-9a-f]{6}"))
        }
        val id = normalizedPhysicalId?.let { "local-$it" } ?: "local-${UUID.randomUUID()}"
        if (normalizedPhysicalId != null) {
            // Older app versions used a random ID. The per-device key lets us safely
            // replace that stale record instead of leaving a duplicate offline card.
            localDevices.entries.removeAll { (existingId, device) ->
                existingId != id && device.localKey.equals(key, ignoreCase = true)
            }
        }
        val resolvedRoom = room.trim().ifEmpty { _rooms.value.firstOrNull() ?: "未分類" }
        if (resolvedRoom !in _rooms.value) {
            saveRooms(_rooms.value + resolvedRoom)
        }
        localDevices[id] = LightDevice(
            id = id,
            name = name.ifBlank { "TerryESP Controller" },
            room = resolvedRoom,
            isOnline = true,
            isOn = true,
            brightness = 50,
            color = Color(0xFF39B9FF),
            localHost = host,
            localKey = key,
        )
        persistLocalDevices()
        publishDevices()
        _messages.tryEmit(if (normalizedPhysicalId != null) "已自動匹配並新增本地裝置" else "已新增本地裝置")
        return id
    }

    fun bindLocalApi(id: String, host: String, key: String) {
        preferences.edit().putString("$id.host", host).putString("$id.key", key).apply()
        localOnlineById[id] = true
        localDevices[id]?.let { localDevices[id] = it.copy(localHost = host, localKey = key, isOnline = true) }
        googleDevices[id]?.let { googleDevices[id] = it.copy(localHost = host, localKey = key) }
        persistLocalDevices()
        publishDevices()
        _messages.tryEmit("本地控制已連線")
    }

    fun promoteLocalDeviceToMatter(localId: String, matterId: String) {
        val local = localDevices.remove(localId) ?: return
        localOnlineById.remove(localId)
        localOnlineById[matterId] = local.isOnline
        preferences.edit()
            .putString("$matterId.host", local.localHost)
            .putString("$matterId.key", local.localKey)
            .putString("$matterId.name", local.name)
            .remove("$matterId.room")
            .apply()
        googleDevices[matterId]?.let {
            googleDevices[matterId] = it.copy(
                name = local.name,
                localHost = local.localHost,
                localKey = local.localKey,
                isOnline = it.isOnline || local.isOnline,
            )
        }
        persistLocalDevices()
        publishDevices()
        _messages.tryEmit("已將區網裝置加入 Matter／Google Home")
    }

    fun updateDevice(id: String, name: String, room: String) {
        val cleanName = name.trim().ifEmpty { "TerryESP Controller" }
        val cleanRoom = room.trim().ifEmpty { "未分類" }
        if (cleanRoom !in _rooms.value) addRoom(cleanRoom)
        if (localDevices.containsKey(id)) {
            localDevices[id] = localDevices.getValue(id).copy(name = cleanName, room = cleanRoom)
            persistLocalDevices()
        } else {
            preferences.edit().putString("$id.name", cleanName).remove("$id.room").apply()
            googleDevices[id]?.let { googleDevices[id] = it.copy(name = cleanName) }
        }
        publishDevices()
        _messages.tryEmit("裝置資料已更新")
    }

    fun deleteDevice(id: String) {
        if (localDevices.remove(id) != null) {
            clearSavedDevice(id)
            persistLocalDevices()
            publishDevices()
            _messages.tryEmit("本地裝置已刪除")
        } else {
            hiddenGoogleDeviceIds += id
            saveHiddenGoogleDevices()
            googleDevices.remove(id)
            cloudOnlineById.remove(id)
            localOnlineById.remove(id)
            clearSavedDevice(id)
            publishDevices()
            _messages.tryEmit("裝置已從 App 移除")
        }
    }

    private fun clearSavedDevice(id: String) {
        preferences.edit()
            .remove("$id.host")
            .remove("$id.key")
            .remove("$id.name")
            .remove("$id.room")
            .apply()
    }

    private fun saveHiddenGoogleDevices() {
        preferences.edit().putStringSet("hidden_google_devices", hiddenGoogleDeviceIds.toSet()).apply()
    }

    fun addRoom(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed !in _rooms.value) saveRooms(_rooms.value + trimmed)
    }

    fun renameRoom(oldName: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == oldName) return
        val updatedRooms = _rooms.value.map { if (it == oldName) clean else it }.distinct()
        saveRooms(updatedRooms)
        _devices.value.filter { it.room == oldName }.forEach { updateDevice(it.id, it.name, clean) }
        _messages.tryEmit("房間已重新命名")
    }

    fun deleteRoom(name: String) {
        if (_rooms.value.size <= 1) {
            _messages.tryEmit("至少需要保留一個房間")
            return
        }
        val destination = _rooms.value.first { it != name }
        _devices.value.filter { it.room == name }.forEach { updateDevice(it.id, it.name, destination) }
        saveRooms(_rooms.value - name)
        _messages.tryEmit("房間已刪除，裝置已移到 $destination")
    }

    fun inviteMember(email: String) {
        val clean = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
            _messages.tryEmit("請輸入有效的 Email")
            return
        }
        val next = (_members.value + clean).distinct().sorted()
        preferences.edit().putStringSet("family_members", next.toSet()).apply()
        _members.value = next
        _messages.tryEmit("已將 $clean 加入 TerryESP 家庭")
    }

    fun removeMember(email: String) {
        val next = _members.value - email
        preferences.edit().putStringSet("family_members", next.toSet()).apply()
        _members.value = next
    }

    fun setPower(id: String, enabled: Boolean) {
        val device = _devices.value.firstOrNull { it.id == id } ?: return
        val previous = device.isOn
        val version = (powerCommandVersions[id] ?: 0) + 1
        powerCommandVersions[id] = version

        // Reflect the tap immediately. A second tap therefore inverts the newest
        // requested state instead of the stale state from an in-flight request.
        updateState(id) { it.copy(isOn = enabled) }
        viewModelScope.launch {
            runCatching {
                controlLocks.getOrPut(id) { Mutex() }.withLock {
                    if (powerCommandVersions[id] != version) return@withLock
                    routeBasicControl(
                        id = id,
                        device = device,
                        localAction = { LocalEspApi.setPower(device, enabled) },
                        cloudAction = { GoogleHomeBridge.setPower(id, enabled) },
                    )
                }
            }.onFailure {
                if (powerCommandVersions[id] == version) {
                    updateState(id) { current -> current.copy(isOn = previous) }
                    reportControlFailure("電源", it)
                }
            }
        }
    }

    fun setBrightness(id: String, brightness: Int) = control(id, "亮度") { device ->
        val value = brightness.coerceIn(1, 100)
        routeBasicControl(
            id = id,
            device = device,
            localAction = { LocalEspApi.setBrightness(device, value) },
            cloudAction = { GoogleHomeBridge.setBrightness(id, value) },
        )
        updateState(id) { it.copy(brightness = value, isOn = true) }
    }

    fun setColor(id: String, color: Color) {
        // The color wheel emits many drag events. Update the preview immediately,
        // then send only the newest value so the ESP is not flooded with sockets.
        updateState(id) { it.copy(color = color, effect = LightEffect.STATIC, isOn = true) }
        val version = (colorCommandVersions[id] ?: 0) + 1
        colorCommandVersions[id] = version
        colorJobs.remove(id)?.cancel()
        colorJobs[id] = viewModelScope.launch {
            try {
                delay(140)
                if (colorCommandVersions[id] != version) return@launch
                val device = _devices.value.firstOrNull { it.id == id } ?: return@launch
                runCatching {
                    controlLocks.getOrPut(id) { Mutex() }.withLock {
                        if (colorCommandVersions[id] != version) return@withLock
                        routeBasicControl(
                            id = id,
                            device = device,
                            localAction = { LocalEspApi.setColor(device, color) },
                            cloudAction = { GoogleHomeBridge.setColor(id, color) },
                        )
                    }
                }.onFailure { error ->
                    // HttpURLConnection is blocking, so cancelling an older color
                    // job can finish as an IOException instead of CancellationException.
                    // Only the newest request is allowed to notify the user.
                    if (error !is CancellationException && colorCommandVersions[id] == version) {
                        reportControlFailure("顏色", error)
                    }
                }
            } finally {
                if (colorCommandVersions[id] == version) colorJobs.remove(id)
            }
        }
    }

    fun setColorTemperature(id: String, kelvin: Int) = control(id, "色溫") { device ->
        val value = kelvin.coerceIn(2000, 6500)
        routeBasicControl(
            id = id,
            device = device,
            localAction = { LocalEspApi.setColorTemperature(device, value) },
            cloudAction = { GoogleHomeBridge.setColorTemperature(id, value) },
        )
        updateState(id) { it.copy(colorTemperature = value, isOn = true) }
    }

    fun setEffect(id: String, effect: LightEffect, speed: Int, breathing: Boolean, breathingSpeed: Int) = control(id, "燈效") { device ->
        require(device.localHost != null && device.localKey != null) { "請先連接本地控制" }
        val updated = device.copy(
            effect = effect,
            speed = speed.coerceIn(1, 10),
            breathing = breathing,
            breathingSpeed = breathingSpeed.coerceIn(1, 10),
            isOn = true,
        )
        LocalEspApi.setEffect(updated, effect)
        updateState(id) { updated }
    }

    fun setCustomPixels(id: String, pixels: List<Color>, moving: Boolean, moveRight: Boolean,
                        speed: Int, breathing: Boolean, breathingSpeed: Int) = control(id, "自訂燈效") { device ->
        require(device.localHost != null && device.localKey != null) { "請先連接本地控制" }
        LocalEspApi.setCustom(device, pixels, moving, moveRight, speed, breathing, breathingSpeed)
        updateState(id) {
            it.copy(effect = LightEffect.CUSTOM, customPixels = pixels.take(256), moving = moving,
                moveRight = moveRight, speed = speed.coerceIn(1, 10), breathing = breathing,
                breathingSpeed = breathingSpeed.coerceIn(1, 10), isOn = true)
        }
    }

    private fun control(id: String, label: String, action: suspend (LightDevice) -> Unit) {
        val device = _devices.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { controlLocks.getOrPut(id) { Mutex() }.withLock { action(device) } }
                .onFailure { reportControlFailure(label, it) }
        }
    }

    /**
     * Basic controls prefer the ESP's LAN API for speed and privacy. If that
     * exact saved endpoint is unreachable, every Matter device is sent through
     * Google Home even when its cached connectivity state has not updated yet.
     * The key is never sent to newly discovered or unknown hosts.
     */
    private suspend fun routeBasicControl(
        id: String,
        device: LightDevice,
        localAction: suspend () -> Unit,
        cloudAction: suspend () -> Unit,
    ) {
        var localFailure: Throwable? = null
        if (device.localHost != null && device.localKey != null) {
            runCatching { localAction() }
                .onSuccess {
                    setLocalOnline(id, true)
                    return
                }
                .onFailure {
                    localFailure = it
                    setLocalOnline(id, false)
                }
        }
        // Do not gate remote control on sourceConnectivity. That value can lag
        // behind the real Google Home state when the phone changes networks.
        if (googleDevices.containsKey(id)) {
            cloudAction()
            return
        }
        throw localFailure ?: IllegalStateException("僅區網裝置需要與手機連接同一個 Wi-Fi")
    }

    private suspend fun refreshLocalStatuses() {
        _devices.value.filter { it.localHost != null && it.localKey != null }.forEach { device ->
            val online = if (device.localHost != null && device.localKey != null) {
                runCatching { LocalEspApi.verify(device.localHost, device.localKey) }.isSuccess
            } else {
                false
            }
            localOnlineById[device.id] = online
            localDevices[device.id]?.let { localDevice ->
                if (localDevice.isOnline != online) {
                    localDevices[device.id] = localDevice.copy(isOnline = online)
                    publishDevices()
                }
            }
            googleDevices[device.id]?.let { googleDevice ->
                val combinedOnline = online || cloudOnlineById[device.id] == true
                if (googleDevice.isOnline != combinedOnline) {
                    googleDevices[device.id] = googleDevice.copy(isOnline = combinedOnline)
                    publishDevices()
                }
            }
        }
    }

    private fun setLocalOnline(id: String, online: Boolean) {
        localOnlineById[id] = online
        localDevices[id]?.let {
            if (it.isOnline != online) localDevices[id] = it.copy(isOnline = online)
        }
        googleDevices[id]?.let {
            val combined = online || cloudOnlineById[id] == true
            if (it.isOnline != combined) googleDevices[id] = it.copy(isOnline = combined)
        }
        publishDevices()
    }

    private fun reportControlFailure(label: String, error: Throwable) {
        val detail = error.message.orEmpty()
        val networkFailure = detail.contains("unexpected end", ignoreCase = true) ||
            detail.contains("failed to connect", ignoreCase = true) ||
            detail.contains("timeout", ignoreCase = true)
        _messages.tryEmit(
            if (networkFailure) {
                "$label 控制失敗：請確認手機與 ESP 連到同一個 Wi-Fi；換網路後請重新連接裝置"
            } else {
                "$label 控制失敗：${detail.ifBlank { "無法連線" }}"
            }
        )
    }

    private fun updateState(id: String, transform: (LightDevice) -> LightDevice) {
        if (localDevices.containsKey(id)) {
            localDevices[id] = transform(localDevices.getValue(id))
            persistLocalDevices()
        }
        if (googleDevices.containsKey(id)) googleDevices[id] = transform(googleDevices.getValue(id))
        publishDevices()
    }

    private fun publishDevices() {
        _devices.value = (googleDevices.values + localDevices.values.filter { it.id !in googleDevices }).toList()
    }

    private fun withSavedMetadata(device: LightDevice) = device.copy(
        name = preferences.getString("${device.id}.name", device.name) ?: device.name,
    )

    private fun withLocalBinding(device: LightDevice) = device.copy(
        localHost = preferences.getString("${device.id}.host", device.localHost),
        localKey = preferences.getString("${device.id}.key", device.localKey),
    )

    private fun loadRooms(): List<String> =
        preferences.getString("rooms_json", null)?.let { encoded ->
            runCatching { JSONArray(encoded).let { array -> List(array.length()) { array.getString(it) } } }.getOrNull()
        }.orEmpty().ifEmpty { defaultRooms }

    private fun saveRooms(value: List<String>) {
        _rooms.value = value.distinct()
        preferences.edit().putString("rooms_json", JSONArray(_rooms.value).toString()).apply()
    }

    private fun loadLocalDevices(): List<LightDevice> {
        val encoded = preferences.getString("local_devices_json", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                LightDevice(
                    id = item.getString("id"), name = item.getString("name"), room = item.getString("room"),
                    isOnline = true, isOn = item.optBoolean("power", false), brightness = item.optInt("brightness", 50),
                    color = Color(item.optLong("color", 0xFF39B9FF)),
                    colorTemperature = item.optInt("temperature", 4000),
                    effect = runCatching {
                        LightEffect.valueOf(item.optString("effect", LightEffect.STATIC.name))
                    }.getOrDefault(LightEffect.STATIC),
                    speed = item.optInt("speed", 5).coerceIn(1, 10),
                    breathing = item.optBoolean("breathing", false),
                    breathingSpeed = item.optInt("breathingSpeed", 5).coerceIn(1, 10),
                    localHost = item.optString("host").takeIf(String::isNotBlank),
                    localKey = item.optString("key").takeIf(String::isNotBlank),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun persistLocalDevices() {
        val array = JSONArray()
        localDevices.values.filterNot { it.id.startsWith("preview-") }.forEach { device ->
            array.put(JSONObject().apply {
                put("id", device.id); put("name", device.name); put("room", device.room)
                put("power", device.isOn); put("brightness", device.brightness)
                put("color", device.color.value.toLong()); put("temperature", device.colorTemperature)
                put("effect", device.effect.name); put("speed", device.speed)
                put("breathing", device.breathing); put("breathingSpeed", device.breathingSpeed)
                put("host", device.localHost ?: ""); put("key", device.localKey ?: "")
            })
        }
        preferences.edit().putString("local_devices_json", array.toString()).apply()
    }
}
