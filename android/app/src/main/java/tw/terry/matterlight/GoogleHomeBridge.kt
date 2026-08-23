package tw.terry.matterlight

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.home.FactoryRegistry
import com.google.home.ConnectivityState
import com.google.home.Home
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsState
import com.google.home.matter.standard.ColorControl
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

/** The single, real Google Home/Matter connection used by the app. */
object GoogleHomeBridge {
    private const val TAG = "GoogleHomeBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var home: com.google.home.HomeClient
    private val devicesById = mutableMapOf<String, HomeDevice>()
    val lights = MutableStateFlow<List<LightDevice>>(emptyList())

    fun connect(context: Context, activity: ComponentActivity) {
        if (!::home.isInitialized) {
            val registry = FactoryRegistry(
                traits = listOf(OnOff, LevelControl, ColorControl),
                types = listOf(ExtendedColorLightDevice, ColorTemperatureLightDevice, DimmableLightDevice, OnOffLightDevice),
            )
            home = Home.getClient(
                context.applicationContext,
                HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = registry),
            )
            home.registerActivityResultCallerForPermissions(activity)
        }
        scope.launch {
            if (home.hasPermissions().first() != PermissionsState.GRANTED) home.requestPermissions()
            observeLights()
        }
    }

    private suspend fun observeLights() {
        home.structures().collect { structures ->
            val next = mutableListOf<LightDevice>()
            devicesById.clear()
            for (structure in structures) for (device in structure.devices().list()) {
                // A freshly commissioned device can be in the structure before the user
                // assigns it to a room. Traversing only room.devices() hides that device.
                val roomName = device.room()?.name ?: "未分類"
                val traits = preferredLightTraits(device)
                Log.i(
                    TAG,
                    "Device ${device.name}: OnOff=${traits.any { it is OnOff }}, " +
                        "Level=${traits.any { it is LevelControl }}, " +
                        "Color=${traits.any { it is ColorControl }}, traitCount=${traits.size}, " +
                        "connectivity=${device.sourceConnectivity.connectivityState}",
                )
                val onOff = traits.filterIsInstance<OnOff>().firstOrNull()
                if (onOff == null) continue
                val level = traits.filterIsInstance<LevelControl>().firstOrNull()
                val colorControl = traits.filterIsInstance<ColorControl>().firstOrNull()
                val currentHue = colorControl?.currentHue?.toInt()
                val currentSaturation = colorControl?.currentSaturation?.toInt()
                val currentColor = if (currentHue != null && currentSaturation != null) {
                    Color.hsv(
                        (currentHue.coerceIn(0, 254) * 360f) / 254f,
                        currentSaturation.coerceIn(0, 254) / 254f,
                        1f,
                    )
                } else {
                    Color(0xFF39B9FF)
                }
                val temperatureMireds = colorControl?.colorTemperatureMireds?.toInt()
                val temperatureKelvin = temperatureMireds
                    ?.takeIf { it > 0 }
                    ?.let { (1_000_000 / it).coerceIn(2000, 6500) }
                    ?: 4000
                devicesById[device.id.id] = device
                next += LightDevice(
                    id = device.id.id,
                    name = device.name,
                    room = roomName,
                    isOnline = device.sourceConnectivity.connectivityState == ConnectivityState.ONLINE ||
                        device.sourceConnectivity.connectivityState == ConnectivityState.PARTIALLY_ONLINE,
                    isOn = onOff.onOff == true,
                    brightness = (((level?.currentLevel?.toInt() ?: 254) * 100) / 254).coerceIn(1, 100),
                    color = currentColor,
                    colorTemperature = temperatureKelvin,
                )
            }
            lights.value = next
        }
    }

    /**
     * Matter exposes an Extended Color Light endpoint through several compatible
     * (superset) device types. Always use the most capable type so commands are not
     * sent through a colliding trait instance belonging to a simpler light type.
     */
    private suspend fun preferredLightTraits(device: HomeDevice) = device.types().first().let { types ->
        val preferredType = types.filterIsInstance<ExtendedColorLightDevice>().firstOrNull()
            ?: types.filterIsInstance<ColorTemperatureLightDevice>().firstOrNull()
            ?: types.filterIsInstance<DimmableLightDevice>().firstOrNull()
            ?: types.filterIsInstance<OnOffLightDevice>().firstOrNull()
        preferredType?.traits().orEmpty()
    }

    suspend fun setPower(id: String, enabled: Boolean) {
        val device = devicesById[id] ?: error("Google Home 找不到裝置")
        val onOff = preferredLightTraits(device).filterIsInstance<OnOff>().firstOrNull()
            ?: error("裝置不支援電源控制")
        if (enabled) onOff.on() else onOff.off()
        Log.i(TAG, "Power command sent to ${device.name}: enabled=$enabled")
    }

    suspend fun setBrightness(id: String, percent: Int) {
        val device = devicesById[id] ?: error("Google Home 找不到裝置")
        val level = preferredLightTraits(device).filterIsInstance<LevelControl>().firstOrNull()
            ?: error("裝置不支援亮度控制")
        level.moveToLevel(
            ((percent.coerceIn(1, 100) * 254) / 100).toUByte(), null,
            com.google.home.matter.standard.LevelControlTrait.OptionsBitmap(),
            com.google.home.matter.standard.LevelControlTrait.OptionsBitmap(),
        )
        Log.i(TAG, "Brightness command sent to ${device.name}: percent=$percent")
    }

    suspend fun setColor(id: String, color: Color) {
            val device = devicesById[id] ?: error("Google Home 找不到裝置")
            val extendedColorLight = device.types().first()
                .filterIsInstance<ExtendedColorLightDevice>()
                .firstOrNull()
            val colorControl = extendedColorLight?.standardTraits?.colorControl
            if (colorControl == null) {
                error("裝置不支援顏色控制")
            }
            val supportsHueAndSaturation = colorControl.supports(ColorControl.Command.MoveToHueAndSaturation)
            Log.i(
                TAG,
                "Color command support for ${device.name}: " +
                    "MoveToHueAndSaturation=$supportsHueAndSaturation, " +
                    "MoveToHue=${colorControl.supports(ColorControl.Command.MoveToHue)}, " +
                    "MoveToSaturation=${colorControl.supports(ColorControl.Command.MoveToSaturation)}, " +
                    "MoveToColor=${colorControl.supports(ColorControl.Command.MoveToColor)}",
            )
            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
            val hue = ((hsv[0] / 360f) * 254f).roundToInt().coerceIn(0, 254).toUByte()
            val saturation = (hsv[1] * 254f).roundToInt().coerceIn(0, 254).toUByte()
            val options = com.google.home.matter.standard.ColorControlTrait.OptionsBitmap()
            when {
                supportsHueAndSaturation -> {
                    colorControl.moveToHueAndSaturation(hue, saturation, 1u, options, options)
                    Log.i(TAG, "HSV color command sent to ${device.name}: hue=$hue saturation=$saturation")
                }

                colorControl.supports(ColorControl.Command.MoveToColor) -> {
                    val (x, y) = colorToMatterXy(color)
                    colorControl.moveToColor(x, y, 1u, options, options)
                    Log.i(TAG, "XY color command sent to ${device.name}: x=$x y=$y")
                }

                else -> error("裝置沒有可用的絕對顏色命令")
            }
    }

    suspend fun setColorTemperature(id: String, kelvin: Int) {
            val device = devicesById[id] ?: error("Google Home 找不到裝置")
            val colorControl = device.types().first()
                .filterIsInstance<ExtendedColorLightDevice>()
                .firstOrNull()
                ?.standardTraits?.colorControl
            if (colorControl == null || !colorControl.supports(ColorControl.Command.MoveToColorTemperature)) {
                error("裝置不支援色溫控制")
            }
            val mireds = (1_000_000 / kelvin.coerceIn(2000, 6500)).coerceIn(153, 500).toUShort()
            val options = com.google.home.matter.standard.ColorControlTrait.OptionsBitmap()
            colorControl.moveToColorTemperature(mireds, 1u, options, options)
            Log.i(TAG, "Color temperature command sent to ${device.name}: ${kelvin}K, $mireds mireds")
    }

    /** Converts an sRGB color into Matter's CIE 1931 x/y values (0..65279). */
    private fun colorToMatterXy(color: Color): Pair<UShort, UShort> {
        fun linear(value: Int): Double {
            val channel = value / 255.0
            return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }

        val argb = color.toArgb()
        val red = linear(AndroidColor.red(argb))
        val green = linear(AndroidColor.green(argb))
        val blue = linear(AndroidColor.blue(argb))
        val cieX = red * 0.664511 + green * 0.154324 + blue * 0.162028
        val cieY = red * 0.283881 + green * 0.668433 + blue * 0.047685
        val cieZ = red * 0.000088 + green * 0.072310 + blue * 0.986039
        val total = cieX + cieY + cieZ
        if (total <= 0.0) return 0.toUShort() to 0.toUShort()
        val x = ((cieX / total) * 65279.0).roundToInt().coerceIn(0, 65279).toUShort()
        val y = ((cieY / total) * 65279.0).roundToInt().coerceIn(0, 65279).toUShort()
        return x to y
    }
}
