#include "app_priv.h"

#include <algorithm>
#include <cmath>

#include <esp_check.h>
#include <esp_log.h>
#include <esp_random.h>
#include <esp_timer.h>
#include <led_strip.h>
#include <led_strip_rmt.h>

#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

#include <app/server/Server.h>
#include <esp_matter.h>
#include <platform/CHIPDeviceLayer.h>

using namespace chip::app::Clusters;
using namespace esp_matter;

static const char *TAG = "ws2812_driver";
extern uint16_t light_endpoint_id;

namespace {
struct StripState {
    enum class ColorMode { Hsv, Xy, Temperature };
    led_strip_handle_t strip = nullptr;
    bool power = DEFAULT_POWER;
    uint8_t brightness = DEFAULT_BRIGHTNESS;
    uint8_t hue = DEFAULT_HUE;
    uint8_t saturation = DEFAULT_SATURATION;
    uint16_t x = 19900;
    uint16_t y = 20000;
    uint16_t temperature_mireds = 250;
    ColorMode color_mode = ColorMode::Hsv;
    app_effect_t effect = app_effect_t::Static;
    app_rgb_t custom_pixels[APP_CUSTOM_PIXEL_COUNT] = {};
    uint16_t custom_count = APP_DEFAULT_CUSTOM_PIXEL_COUNT;
    uint8_t speed = 5;
    bool moving = true;
    bool move_right = true;
    bool breathing = false;
    uint8_t breathing_speed = 5;
    uint16_t animation_offset = 0;
    uint16_t move_elapsed_ms = 0;
    app_rgb_t status_color = {};
    int64_t status_until_us = 0;
    uint32_t status_blink_interval_ms = 0;
    SemaphoreHandle_t mutex = nullptr;
};

struct Palette {
    app_rgb_t entries[16];
    bool blend;
};

app_rgb_t rgb(uint8_t red, uint8_t green, uint8_t blue) { return {red, green, blue}; }

app_rgb_t palette_color(const Palette &palette, uint8_t index)
{
    const uint8_t slot = index >> 4;
    if (!palette.blend) return palette.entries[slot];
    const app_rgb_t a = palette.entries[slot];
    const app_rgb_t b = palette.entries[(slot + 1) & 0x0f];
    const uint8_t fraction = index & 0x0f;
    return {
        static_cast<uint8_t>((a.red * (16 - fraction) + b.red * fraction) / 16),
        static_cast<uint8_t>((a.green * (16 - fraction) + b.green * fraction) / 16),
        static_cast<uint8_t>((a.blue * (16 - fraction) + b.blue * fraction) / 16),
    };
}

Palette effect_palette(app_effect_t effect)
{
    Palette palette{};
    const app_rgb_t black = rgb(0, 0, 0), white = rgb(255, 255, 255);
    const app_rgb_t rainbow[] = {rgb(255, 0, 0), rgb(255, 96, 0), rgb(255, 255, 0), rgb(0, 255, 0),
                                 rgb(0, 255, 255), rgb(0, 0, 255), rgb(128, 0, 255), rgb(255, 0, 160)};
    for (uint8_t i = 0; i < 16; ++i) palette.entries[i] = rainbow[i / 2];
    palette.blend = true;
    if (effect == app_effect_t::RainbowStripe || effect == app_effect_t::RainbowStripeBlend) {
        for (uint8_t i = 0; i < 16; ++i) palette.entries[i] = (i % 4 == 0) ? rainbow[(i / 4) * 2] : black;
        palette.blend = effect == app_effect_t::RainbowStripeBlend;
    } else if (effect == app_effect_t::PurpleGreen) {
        const app_rgb_t green = rgb(0, 255, 0), purple = rgb(160, 0, 255);
        const app_rgb_t values[] = {green, green, black, black, purple, purple, black, black,
                                    green, green, black, black, purple, purple, black, black};
        std::copy(std::begin(values), std::end(values), palette.entries); palette.blend = true;
    } else if (effect == app_effect_t::Random) {
        for (auto &entry : palette.entries) entry = rgb(esp_random() & 0xff, esp_random() & 0xff, esp_random() & 0xff);
    } else if (effect == app_effect_t::BlackWhiteStripe || effect == app_effect_t::BlackWhiteBlend) {
        for (uint8_t i = 0; i < 16; ++i) palette.entries[i] = (i % 4 == 0) ? white : black;
        palette.blend = effect == app_effect_t::BlackWhiteBlend;
    } else if (effect == app_effect_t::Cloud) {
        const app_rgb_t values[] = {rgb(0, 30, 100), rgb(30, 100, 180), rgb(120, 190, 255), white,
                                    rgb(80, 150, 220), rgb(10, 40, 120), rgb(90, 170, 240), white,
                                    rgb(0, 40, 120), rgb(40, 120, 200), rgb(160, 210, 255), white,
                                    rgb(30, 90, 170), rgb(0, 30, 100), rgb(70, 150, 220), white};
        std::copy(std::begin(values), std::end(values), palette.entries);
    } else if (effect == app_effect_t::Party) {
        const app_rgb_t values[] = {rgb(255, 0, 80), rgb(255, 80, 0), rgb(255, 220, 0), rgb(60, 255, 0),
                                    rgb(0, 180, 255), rgb(40, 0, 255), rgb(180, 0, 255), rgb(255, 0, 180),
                                    rgb(255, 30, 0), rgb(255, 160, 0), rgb(200, 255, 0), rgb(0, 255, 100),
                                    rgb(0, 80, 255), rgb(100, 0, 255), rgb(255, 0, 255), rgb(255, 0, 100)};
        std::copy(std::begin(values), std::end(values), palette.entries);
    } else if (effect == app_effect_t::RedWhiteBlue || effect == app_effect_t::RedWhiteBlueBlend) {
        const app_rgb_t values[] = {rgb(255, 0, 0), white, rgb(0, 0, 255), black,
                                    rgb(255, 0, 0), white, rgb(0, 0, 255), black,
                                    rgb(255, 0, 0), rgb(255, 0, 0), rgb(0, 0, 255), rgb(0, 0, 255),
                                    rgb(255, 0, 0), white, rgb(0, 0, 255), black};
        std::copy(std::begin(values), std::end(values), palette.entries);
        palette.blend = effect == app_effect_t::RedWhiteBlueBlend;
    }
    return palette;
}

uint8_t scale(uint8_t value, uint8_t brightness)
{
    const uint16_t capped = std::min<uint16_t>(brightness, CONFIG_WS2812_MAX_BRIGHTNESS * 254 / 100);
    return static_cast<uint8_t>((static_cast<uint16_t>(value) * capped) / 254);
}

void hsv_to_rgb(uint8_t hue, uint8_t saturation, uint8_t value, uint8_t &red, uint8_t &green, uint8_t &blue)
{
    const float h = (static_cast<float>(hue) / 254.0f) * 360.0f;
    const float s = static_cast<float>(saturation) / 254.0f;
    const float v = static_cast<float>(value) / 254.0f;
    const float c = v * s;
    const float x = c * (1.0f - std::fabs(std::fmod(h / 60.0f, 2.0f) - 1.0f));
    const float m = v - c;
    float r = 0, g = 0, b = 0;

    if (h < 60) { r = c; g = x; }
    else if (h < 120) { r = x; g = c; }
    else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; }
    else if (h < 300) { r = x; b = c; }
    else { r = c; b = x; }

    red = static_cast<uint8_t>((r + m) * 255.0f);
    green = static_cast<uint8_t>((g + m) * 255.0f);
    blue = static_cast<uint8_t>((b + m) * 255.0f);
}

void xy_to_rgb(uint16_t x_value, uint16_t y_value, uint8_t &red, uint8_t &green, uint8_t &blue)
{
    // Matter encodes x/y as a 16-bit value in the 0..1 range. Use Y=1 and
    // convert CIE 1931 XYZ to sRGB. This is adequate for an RGB-only strip.
    const float x = std::max(0.0001f, static_cast<float>(x_value) / 65535.0f);
    const float y = std::max(0.0001f, static_cast<float>(y_value) / 65535.0f);
    const float Y = 1.0f;
    const float X = (Y / y) * x;
    const float Z = (Y / y) * (1.0f - x - y);
    float r =  3.2406f * X - 1.5372f * Y - 0.4986f * Z;
    float g = -0.9689f * X + 1.8758f * Y + 0.0415f * Z;
    float b =  0.0557f * X - 0.2040f * Y + 1.0570f * Z;
    const auto gamma = [](float channel) {
        channel = std::max(0.0f, channel);
        return channel <= 0.0031308f ? 12.92f * channel : 1.055f * std::pow(channel, 1.0f / 2.4f) - 0.055f;
    };
    r = gamma(r);
    g = gamma(g);
    b = gamma(b);
    const float maximum = std::max({r, g, b, 0.0001f});
    red = static_cast<uint8_t>(std::min(255.0f, (r / maximum) * 255.0f));
    green = static_cast<uint8_t>(std::min(255.0f, (g / maximum) * 255.0f));
    blue = static_cast<uint8_t>(std::min(255.0f, (b / maximum) * 255.0f));
}

void temperature_to_rgb(uint16_t mireds, uint8_t &red, uint8_t &green, uint8_t &blue)
{
    // Approximate black-body colour temperature for RGB-only strips.
    const float kelvin = std::clamp(1000000.0f / std::max<uint16_t>(mireds, 1), 1000.0f, 40000.0f) / 100.0f;
    float r, g, b;
    if (kelvin <= 66.0f) {
        r = 255.0f;
        g = std::clamp(99.4708f * std::log(kelvin) - 161.1196f, 0.0f, 255.0f);
        b = kelvin <= 19.0f ? 0.0f : std::clamp(138.5177f * std::log(kelvin - 10.0f) - 305.0448f, 0.0f, 255.0f);
    } else {
        r = std::clamp(329.6987f * std::pow(kelvin - 60.0f, -0.1332f), 0.0f, 255.0f);
        g = std::clamp(288.1222f * std::pow(kelvin - 60.0f, -0.0755f), 0.0f, 255.0f);
        b = 255.0f;
    }
    red = static_cast<uint8_t>(r);
    green = static_cast<uint8_t>(g);
    blue = static_cast<uint8_t>(b);
}

esp_err_t render(StripState *state, int brightness_override = -1)
{
    if (state == nullptr || state->strip == nullptr) {
        return ESP_ERR_INVALID_STATE;
    }
    if (!state->power) {
        return led_strip_clear(state->strip);
    }

    uint8_t red, green, blue;
    if (state->color_mode == StripState::ColorMode::Hsv) {
        hsv_to_rgb(state->hue, state->saturation, 254, red, green, blue);
    } else if (state->color_mode == StripState::ColorMode::Xy) {
        xy_to_rgb(state->x, state->y, red, green, blue);
    } else {
        temperature_to_rgb(state->temperature_mireds, red, green, blue);
    }
    const uint8_t brightness = brightness_override >= 0
        ? static_cast<uint8_t>(std::clamp(brightness_override, 0, 254))
        : state->brightness;
    red = scale(red, brightness);
    green = scale(green, brightness);
    blue = scale(blue, brightness);
    for (uint32_t pixel = 0; pixel < CONFIG_WS2812_NUM_LEDS; ++pixel) {
        ESP_RETURN_ON_ERROR(led_strip_set_pixel(state->strip, pixel, red, green, blue), TAG, "set pixel failed");
    }
    return led_strip_refresh(state->strip);
}

esp_err_t render_effect(StripState *state)
{
    const int64_t now_us = esp_timer_get_time();
    if (now_us < state->status_until_us) {
        const bool visible = state->status_blink_interval_ms == 0 ||
            ((now_us / 1000 / state->status_blink_interval_ms) % 2) == 0;
        for (uint32_t pixel = 0; pixel < CONFIG_WS2812_NUM_LEDS; ++pixel) {
            const app_rgb_t color = visible ? state->status_color : app_rgb_t{};
            ESP_RETURN_ON_ERROR(led_strip_set_pixel(state->strip, pixel, color.red, color.green, color.blue),
                                TAG, "set status pixel failed");
        }
        return led_strip_refresh(state->strip);
    }
    if (!state->power) return led_strip_clear(state->strip);
    uint8_t effect_brightness = state->brightness;
    if (state->breathing) {
        constexpr uint32_t periods_us[] = {
            12000000, 10000000, 8000000, 6500000, 5000000,
            4000000, 3200000, 2500000, 1800000, 1200000,
        };
        const uint8_t level = std::clamp<uint8_t>(state->breathing_speed, 1, 10);
        const uint32_t period = periods_us[level - 1];
        const float phase = static_cast<float>(esp_timer_get_time() % period) / static_cast<float>(period);
        const float wave = 0.05f + 0.95f * ((std::sin(phase * 6.2831853f) + 1.0f) * 0.5f);
        effect_brightness = static_cast<uint8_t>(state->brightness * wave);
    }
    if (state->effect == app_effect_t::Static) return render(state, effect_brightness);
    if (state->effect == app_effect_t::CustomPixels) {
        const uint16_t count = std::max<uint16_t>(1, state->custom_count);
        for (uint32_t pixel = 0; pixel < CONFIG_WS2812_NUM_LEDS; ++pixel) {
            const uint32_t offset = state->move_right ? state->animation_offset + pixel
                                                      : state->animation_offset + CONFIG_WS2812_NUM_LEDS - pixel;
            const app_rgb_t color = state->custom_pixels[offset % count];
            ESP_RETURN_ON_ERROR(led_strip_set_pixel(state->strip, pixel, scale(color.red, effect_brightness),
                                                    scale(color.green, effect_brightness), scale(color.blue, effect_brightness)),
                                TAG, "set custom pixel failed");
        }
        return led_strip_refresh(state->strip);
    }
    const Palette palette = effect_palette(state->effect);
    for (uint32_t pixel = 0; pixel < CONFIG_WS2812_NUM_LEDS; ++pixel) {
        const app_rgb_t color = palette_color(palette, static_cast<uint8_t>(state->animation_offset + pixel * 3));
        ESP_RETURN_ON_ERROR(led_strip_set_pixel(state->strip, pixel, scale(color.red, effect_brightness),
                                                scale(color.green, effect_brightness), scale(color.blue, effect_brightness)),
                            TAG, "set effect pixel failed");
    }
    return led_strip_refresh(state->strip);
}

void effect_task(void *context)
{
    auto *state = static_cast<StripState *>(context);
    while (true) {
        if (xSemaphoreTake(state->mutex, portMAX_DELAY) == pdTRUE) {
            if (state->effect != app_effect_t::Static || state->breathing) {
                render_effect(state);
                if (state->moving) {
                    constexpr uint16_t intervals_ms[] = {2000, 1500, 1100, 800, 600, 400, 250, 150, 80, 30};
                    const uint8_t level = std::clamp<uint8_t>(state->speed, 1, 10);
                    state->move_elapsed_ms = static_cast<uint16_t>(state->move_elapsed_ms + 20);
                    if (state->move_elapsed_ms >= intervals_ms[level - 1]) {
                        state->move_elapsed_ms = 0;
                        state->animation_offset = static_cast<uint16_t>(state->animation_offset + 1);
                    }
                }
            }
            xSemaphoreGive(state->mutex);
        }
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}
} // namespace

app_driver_handle_t app_driver_light_init()
{
    static StripState state;
    led_strip_config_t strip_config = {
        .strip_gpio_num = CONFIG_WS2812_GPIO,
        .max_leds = CONFIG_WS2812_NUM_LEDS,
        .led_model = LED_MODEL_WS2812,
        .color_component_format = LED_STRIP_COLOR_COMPONENT_FMT_GRB,
        .flags = {.invert_out = false},
    };
    led_strip_rmt_config_t rmt_config = {
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .resolution_hz = 10 * 1000 * 1000,
        .mem_block_symbols = 0,
        .flags = {.with_dma = false},
    };
    if (led_strip_new_rmt_device(&strip_config, &rmt_config, &state.strip) != ESP_OK) {
        ESP_LOGE(TAG, "Unable to initialize WS2812 RMT driver");
        return nullptr;
    }
    state.mutex = xSemaphoreCreateMutex();
    if (state.mutex == nullptr) return nullptr;
    led_strip_clear(state.strip);
    xTaskCreate(effect_task, "ws2812_effect", 4096, &state, 5, nullptr);
    return &state;
}

esp_err_t app_driver_attribute_update(app_driver_handle_t driver_handle, uint16_t endpoint_id,
                                      uint32_t cluster_id, uint32_t attribute_id,
                                      esp_matter_attr_val_t *val)
{
    if (endpoint_id != light_endpoint_id || driver_handle == nullptr || val == nullptr) {
        return ESP_OK;
    }
    auto *state = static_cast<StripState *>(driver_handle);
    bool reset_effect = false;
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    // A real control command takes precedence over temporary commissioning LEDs.
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    if (cluster_id == OnOff::Id && attribute_id == OnOff::Attributes::OnOff::Id) {
        state->power = val->val.b;
    } else if (cluster_id == LevelControl::Id && attribute_id == LevelControl::Attributes::CurrentLevel::Id) {
        state->brightness = val->val.u8;
    } else if (cluster_id == ColorControl::Id && attribute_id == ColorControl::Attributes::CurrentHue::Id) {
        state->hue = val->val.u8;
        state->color_mode = StripState::ColorMode::Hsv;
        reset_effect = true;
    } else if (cluster_id == ColorControl::Id && attribute_id == ColorControl::Attributes::CurrentSaturation::Id) {
        state->saturation = val->val.u8;
        state->color_mode = StripState::ColorMode::Hsv;
        reset_effect = true;
    } else if (cluster_id == ColorControl::Id && attribute_id == ColorControl::Attributes::CurrentX::Id) {
        state->x = val->val.u16;
        state->color_mode = StripState::ColorMode::Xy;
        reset_effect = true;
    } else if (cluster_id == ColorControl::Id && attribute_id == ColorControl::Attributes::CurrentY::Id) {
        state->y = val->val.u16;
        state->color_mode = StripState::ColorMode::Xy;
        reset_effect = true;
    } else if (cluster_id == ColorControl::Id && attribute_id == ColorControl::Attributes::ColorTemperatureMireds::Id) {
        state->temperature_mireds = val->val.u16;
        state->color_mode = StripState::ColorMode::Temperature;
        reset_effect = true;
    } else {
        xSemaphoreGive(state->mutex);
        return ESP_OK;
    }
    if (reset_effect) {
        state->effect = app_effect_t::Static;
        state->breathing = false;
    }
    const esp_err_t result = render_effect(state);
    xSemaphoreGive(state->mutex);
    return result;
}

esp_err_t app_driver_light_set_defaults(uint16_t endpoint_id)
{
    auto *state = static_cast<StripState *>(endpoint::get_priv_data(endpoint_id));
    if (state == nullptr) {
        return ESP_ERR_INVALID_STATE;
    }
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    const esp_err_t result = render(state);
    xSemaphoreGive(state->mutex);
    return result;
}

esp_err_t app_driver_set_effect(app_driver_handle_t driver_handle, app_effect_t effect, uint8_t speed,
                                bool breathing, uint8_t breathing_speed)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr || effect > app_effect_t::RedWhiteBlueBlend) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    state->power = true;
    state->effect = effect;
    state->moving = true;
    state->breathing = breathing;
    state->breathing_speed = std::clamp<uint8_t>(breathing_speed, 1, 10);
    state->speed = std::clamp<uint8_t>(speed, 1, 10);
    state->animation_offset = 0;
    state->move_elapsed_ms = 0;
    xSemaphoreGive(state->mutex);
    return app_driver_set_power(driver_handle, true);
}

esp_err_t app_driver_set_power(app_driver_handle_t driver_handle, bool enabled)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    state->power = enabled;
    const esp_err_t render_result = render_effect(state);
    xSemaphoreGive(state->mutex);
    ESP_RETURN_ON_ERROR(render_result, TAG, "render power failed");
    const CHIP_ERROR result = chip::DeviceLayer::PlatformMgr().ScheduleWork([](intptr_t argument) {
        esp_matter_attr_val_t value = esp_matter_bool(argument != 0);
        if (attribute::update(light_endpoint_id, OnOff::Id, OnOff::Attributes::OnOff::Id, &value) != ESP_OK) {
            ESP_LOGE(TAG, "scheduled power update failed");
        }
    }, enabled ? 1 : 0);
    return result == CHIP_NO_ERROR ? ESP_OK : ESP_FAIL;
}

esp_err_t app_driver_set_brightness(app_driver_handle_t driver_handle, uint8_t percent)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr) return ESP_ERR_INVALID_ARG;
    const uint8_t level = static_cast<uint8_t>((std::clamp<uint16_t>(percent, 1, 100) * 254) / 100);
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    state->power = true;
    state->brightness = level;
    const esp_err_t render_result = render_effect(state);
    xSemaphoreGive(state->mutex);
    ESP_RETURN_ON_ERROR(render_result, TAG, "render brightness failed");
    app_driver_set_power(driver_handle, true);
    const CHIP_ERROR result = chip::DeviceLayer::PlatformMgr().ScheduleWork([](intptr_t argument) {
        esp_matter_attr_val_t value = esp_matter_uint8(static_cast<uint8_t>(argument));
        if (attribute::update(light_endpoint_id, LevelControl::Id,
                              LevelControl::Attributes::CurrentLevel::Id, &value) != ESP_OK) {
            ESP_LOGE(TAG, "scheduled brightness update failed");
        }
    }, level);
    return result == CHIP_NO_ERROR ? ESP_OK : ESP_FAIL;
}

esp_err_t app_driver_set_hsv(app_driver_handle_t driver_handle, uint8_t hue, uint8_t saturation)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr) return ESP_ERR_INVALID_ARG;
    const uint8_t safe_hue = std::min<uint8_t>(hue, 254);
    const uint8_t safe_saturation = std::min<uint8_t>(saturation, 254);
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    state->power = true;
    state->hue = safe_hue;
    state->saturation = safe_saturation;
    state->color_mode = StripState::ColorMode::Hsv;
    state->effect = app_effect_t::Static;
    state->breathing = false;
    const esp_err_t render_result = render(state);
    xSemaphoreGive(state->mutex);
    ESP_RETURN_ON_ERROR(render_result, TAG, "render HSV failed");
    app_driver_set_power(driver_handle, true);
    const intptr_t packed = (static_cast<intptr_t>(safe_hue) << 8) | safe_saturation;
    const CHIP_ERROR result = chip::DeviceLayer::PlatformMgr().ScheduleWork([](intptr_t argument) {
        esp_matter_attr_val_t hue_value = esp_matter_uint8(static_cast<uint8_t>((argument >> 8) & 0xff));
        esp_matter_attr_val_t saturation_value = esp_matter_uint8(static_cast<uint8_t>(argument & 0xff));
        if (attribute::update(light_endpoint_id, ColorControl::Id,
                              ColorControl::Attributes::CurrentHue::Id, &hue_value) != ESP_OK ||
            attribute::update(light_endpoint_id, ColorControl::Id,
                              ColorControl::Attributes::CurrentSaturation::Id, &saturation_value) != ESP_OK) {
            ESP_LOGE(TAG, "scheduled HSV update failed");
        }
    }, packed);
    return result == CHIP_NO_ERROR ? ESP_OK : ESP_FAIL;
}

esp_err_t app_driver_set_temperature(app_driver_handle_t driver_handle, uint16_t kelvin)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr) return ESP_ERR_INVALID_ARG;
    const uint16_t safe_kelvin = std::clamp<uint16_t>(kelvin, 2000, 6500);
    const uint16_t mireds = static_cast<uint16_t>(1000000UL / safe_kelvin);
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    state->power = true;
    state->temperature_mireds = mireds;
    state->color_mode = StripState::ColorMode::Temperature;
    state->effect = app_effect_t::Static;
    state->breathing = false;
    const esp_err_t render_result = render(state);
    xSemaphoreGive(state->mutex);
    ESP_RETURN_ON_ERROR(render_result, TAG, "render temperature failed");
    app_driver_set_power(driver_handle, true);
    const CHIP_ERROR result = chip::DeviceLayer::PlatformMgr().ScheduleWork([](intptr_t argument) {
        esp_matter_attr_val_t value = esp_matter_uint16(static_cast<uint16_t>(argument));
        if (attribute::update(light_endpoint_id, ColorControl::Id,
                              ColorControl::Attributes::ColorTemperatureMireds::Id, &value) != ESP_OK) {
            ESP_LOGE(TAG, "scheduled temperature update failed");
        }
    }, mireds);
    return result == CHIP_NO_ERROR ? ESP_OK : ESP_FAIL;
}

esp_err_t app_driver_show_status(app_driver_handle_t driver_handle, app_rgb_t color,
                                 uint32_t duration_ms, uint32_t blink_interval_ms)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr || duration_ms == 0) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_color = color;
    state->status_until_us = esp_timer_get_time() + static_cast<int64_t>(duration_ms) * 1000;
    state->status_blink_interval_ms = blink_interval_ms;
    xSemaphoreGive(state->mutex);
    return ESP_OK;
}

esp_err_t app_driver_set_custom_pixels(app_driver_handle_t driver_handle, const app_rgb_t *pixels, uint16_t count,
                                       bool moving, bool move_right, bool breathing,
                                       uint8_t speed, uint8_t breathing_speed)
{
    auto *state = static_cast<StripState *>(driver_handle);
    if (state == nullptr || pixels == nullptr || count == 0) return ESP_ERR_INVALID_ARG;
    xSemaphoreTake(state->mutex, portMAX_DELAY);
    state->status_until_us = 0;
    state->status_blink_interval_ms = 0;
    state->power = true;
    state->custom_count = std::min<uint16_t>(count, APP_CUSTOM_PIXEL_COUNT);
    std::copy_n(pixels, state->custom_count, state->custom_pixels);
    state->moving = moving;
    state->move_right = move_right;
    state->breathing = breathing;
    state->speed = std::clamp<uint8_t>(speed, 1, 10);
    state->breathing_speed = std::clamp<uint8_t>(breathing_speed, 1, 10);
    state->animation_offset = 0;
    state->move_elapsed_ms = 0;
    state->effect = app_effect_t::CustomPixels;
    xSemaphoreGive(state->mutex);
    return app_driver_set_power(driver_handle, true);
}
