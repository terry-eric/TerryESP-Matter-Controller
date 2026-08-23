package tw.terry.matterlight

import androidx.compose.ui.graphics.Color

enum class LightEffect(val label: String) {
    STATIC("固定色"), RAINBOW("彩虹"), RAINBOW_STRIPE("彩虹條紋"), RAINBOW_SOFT("柔和彩虹"),
    PURPLE_GREEN("紫綠"), RANDOM("隨機"), BLACK_WHITE("黑白條紋"), BLACK_WHITE_SOFT("柔和黑白"),
    CLOUD("雲彩"), PARTY("派對"), RED_WHITE_BLUE("紅白藍"), RED_WHITE_BLUE_SOFT("柔和紅白藍"), CUSTOM("自訂圖樣"),
}

data class LightDevice(
    val id: String,
    val name: String,
    val room: String,
    val isOnline: Boolean,
    val isOn: Boolean,
    val brightness: Int,
    val color: Color,
    val colorTemperature: Int = 4000,
    val effect: LightEffect = LightEffect.STATIC,
    val customPixels: List<Color> = List(20) { Color.Black },
    val moving: Boolean = true,
    val moveRight: Boolean = true,
    val speed: Int = 5,
    val breathing: Boolean = false,
    val breathingSpeed: Int = 5,
    val localHost: String? = null,
    val localKey: String? = null,
)

interface LightRepository {
    fun devices(): List<LightDevice>
    fun addDevice(name: String, room: String)
    fun addLocalDevice(name: String, room: String, host: String, key: String)
    fun deleteDevice(id: String)
    fun setPower(id: String, enabled: Boolean)
    fun setBrightness(id: String, brightness: Int)
    fun setColor(id: String, color: Color)
    fun setEffect(id: String, effect: LightEffect)
    fun setCustomPixels(id: String, pixels: List<Color>, moving: Boolean, moveRight: Boolean,
                        speed: Int, breathing: Boolean, breathingSpeed: Int)
}

class DemoLightRepository : LightRepository {
    private val state = mutableListOf<LightDevice>()

    override fun devices(): List<LightDevice> = state.toList()
    override fun addDevice(name: String, room: String) {
        val number = state.size + 1
        state += LightDevice(
            id = "custom-" + number + "-" + System.currentTimeMillis(),
            name = name.ifBlank { "New LED Strip" },
            room = room.ifBlank { "Unassigned" },
            isOnline = true,
            isOn = false,
            brightness = 50,
            color = Color(0xFF39B9FF),
        )
    }

    override fun addLocalDevice(name: String, room: String, host: String, key: String) {
        state += LightDevice(
            id = "local-" + System.currentTimeMillis(),
            name = name.ifBlank { "TerryESP Controller" },
            room = room.ifBlank { "客廳" },
            isOnline = true,
            isOn = true,
            brightness = 50,
            color = Color(0xFF39B9FF),
            localHost = host,
            localKey = key,
        )
    }

    override fun deleteDevice(id: String) {
        state.removeAll { it.id == id }
    }

    override fun setPower(id: String, enabled: Boolean) = update(id) { it.copy(isOn = enabled) }
    override fun setBrightness(id: String, brightness: Int) = update(id) { it.copy(brightness = brightness.coerceIn(1, 100), isOn = true) }
    override fun setColor(id: String, color: Color) = update(id) { it.copy(color = color, effect = LightEffect.STATIC, isOn = true) }
    override fun setEffect(id: String, effect: LightEffect) = update(id) { it.copy(effect = effect, isOn = true) }
    override fun setCustomPixels(id: String, pixels: List<Color>, moving: Boolean, moveRight: Boolean,
                                 speed: Int, breathing: Boolean, breathingSpeed: Int) =
        update(id) { it.copy(effect = LightEffect.CUSTOM, customPixels = pixels.take(256), moving = moving,
            moveRight = moveRight, speed = speed, breathing = breathing, breathingSpeed = breathingSpeed, isOn = true) }

    private fun update(id: String, transform: (LightDevice) -> LightDevice) {
        val index = state.indexOfFirst { it.id == id }
        if (index >= 0) state[index] = transform(state[index])
    }
}
