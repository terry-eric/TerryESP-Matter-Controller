#pragma once

#include <esp_err.h>
#include <esp_matter.h>

#define DEFAULT_POWER true
#define DEFAULT_BRIGHTNESS 64
#define DEFAULT_HUE 0
#define DEFAULT_SATURATION 254

typedef void *app_driver_handle_t;

enum class app_effect_t : uint8_t {
    Static = 0,
    Rainbow = 1,
    RainbowStripe = 2,
    RainbowStripeBlend = 3,
    PurpleGreen = 4,
    Random = 5,
    BlackWhiteStripe = 6,
    BlackWhiteBlend = 7,
    Cloud = 8,
    Party = 9,
    RedWhiteBlue = 10,
    RedWhiteBlueBlend = 11,
    CustomPixels = 12,
};

struct app_rgb_t {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
};

constexpr uint16_t APP_CUSTOM_PIXEL_COUNT = 256;
constexpr uint16_t APP_DEFAULT_CUSTOM_PIXEL_COUNT = 20;

app_driver_handle_t app_driver_light_init();
esp_err_t app_driver_attribute_update(app_driver_handle_t driver_handle, uint16_t endpoint_id,
                                      uint32_t cluster_id, uint32_t attribute_id,
                                      esp_matter_attr_val_t *val);
esp_err_t app_driver_light_set_defaults(uint16_t endpoint_id);
esp_err_t app_driver_set_effect(app_driver_handle_t driver_handle, app_effect_t effect, uint8_t speed = 5,
                                bool breathing = false, uint8_t breathing_speed = 5);
esp_err_t app_driver_set_power(app_driver_handle_t driver_handle, bool enabled);
esp_err_t app_driver_set_brightness(app_driver_handle_t driver_handle, uint8_t percent);
esp_err_t app_driver_set_hsv(app_driver_handle_t driver_handle, uint8_t hue, uint8_t saturation);
esp_err_t app_driver_set_temperature(app_driver_handle_t driver_handle, uint16_t kelvin);
esp_err_t app_driver_show_status(app_driver_handle_t driver_handle, app_rgb_t color,
                                 uint32_t duration_ms, uint32_t blink_interval_ms = 0);
esp_err_t app_driver_set_custom_pixels(app_driver_handle_t driver_handle, const app_rgb_t *pixels, uint16_t count,
                                       bool moving, bool move_right, bool breathing,
                                       uint8_t speed = 5, uint8_t breathing_speed = 5);
