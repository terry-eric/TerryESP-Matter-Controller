package tw.terry.matterlight

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalEspApi {
    suspend fun verify(hostAndPort: String, key: String) =
        request(hostAndPort, key, "/api/v1/auth")

    suspend fun setEffect(device: LightDevice, effect: LightEffect) = request(
        device,
        "/api/v1/effect?effect=" + URLEncoder.encode(effect.name.lowercase(), "UTF-8") +
            "&speed=" + device.speed.coerceIn(1, 10) +
            "&breathing=" + (if (device.breathing) 1 else 0) +
            "&breathSpeed=" + device.breathingSpeed.coerceIn(1, 10),
    )

    suspend fun setPower(device: LightDevice, enabled: Boolean) =
        request(device, "/api/v1/state", "power=" + if (enabled) "1" else "0")

    suspend fun setBrightness(device: LightDevice, brightness: Int) =
        request(device, "/api/v1/state", "brightness=" + brightness.coerceIn(1, 100))

    suspend fun setColor(device: LightDevice, color: Color) {
        val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
        val hue = ((hsv[0] / 360f) * 254f).toInt().coerceIn(0, 254)
        val saturation = (hsv[1] * 254f).toInt().coerceIn(0, 254)
        request(device, "/api/v1/state", "hue=$hue&saturation=$saturation")
    }

    suspend fun setColorTemperature(device: LightDevice, kelvin: Int) =
        request(device, "/api/v1/state", "temperature=" + kelvin.coerceIn(2000, 6500))

    suspend fun setCustom(device: LightDevice, pixels: List<androidx.compose.ui.graphics.Color>,
                          moving: Boolean, moveRight: Boolean, speed: Int,
                          breathing: Boolean, breathingSpeed: Int) {
        val colors = pixels.joinToString(",") { color ->
            "%02x%02x%02x".format(
                (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
            )
        }
        request(device, "/api/v1/custom",
            "pixels=" + colors + "&moving=" + (if (moving) 1 else 0) +
                "&right=" + (if (moveRight) 1 else 0) + "&speed=" + speed +
                "&breathing=" + (if (breathing) 1 else 0) + "&breathSpeed=" + breathingSpeed)
    }

    private suspend fun request(device: LightDevice, path: String, body: String? = null) = request(
        requireNotNull(device.localHost) { "This device has no local connection" },
        requireNotNull(device.localKey) { "This device has no local key" },
        path,
        body,
    )

    private suspend fun request(host: String, key: String, path: String, body: String? = null) = withContext(Dispatchers.IO) {
        val connection = (URL(normalizeBaseUrl(host) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            // Fail over to Google Home quickly when the phone is outside the LAN.
            connectTimeout = 1500
            readTimeout = 2500
            setRequestProperty("X-Terry-Key", key)
            // ESP-IDF may close an idle keep-alive socket. Avoid reusing it for
            // the next rapid slider/color update.
            setRequestProperty("Connection", "close")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val status = connection.responseCode
            check(status in 200..299) { "ESP returned $status" }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeBaseUrl(hostAndPort: String): String {
        if (hostAndPort.startsWith("[")) return "http://$hostAndPort"
        val colonCount = hostAndPort.count { it == ':' }
        if (colonCount <= 1) return "http://$hostAndPort"
        val lastColon = hostAndPort.lastIndexOf(':')
        val address = hostAndPort.substring(0, lastColon)
        val port = hostAndPort.substring(lastColon + 1)
        return "http://[$address]:$port"
    }
}
