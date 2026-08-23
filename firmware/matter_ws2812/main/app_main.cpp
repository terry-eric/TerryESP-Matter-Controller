#include <esp_log.h>
#include <nvs_flash.h>
#include <nvs.h>
#include <driver/gpio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include <app/server/Server.h>
#include <esp_matter.h>

#include "app_priv.h"
#include "ble_provisioning.h"
#include "local_api.h"

using namespace chip::app::Clusters;
using namespace esp_matter;

static const char *TAG = "matter_ws2812";
uint16_t light_endpoint_id = 0;
static app_driver_handle_t s_strip = nullptr;

// ESP32-C3 development boards label GPIO9 as the BOOT button. It is active low.
constexpr gpio_num_t kFactoryResetButtonGpio = GPIO_NUM_9;
constexpr uint32_t kFactoryResetHoldMs = 5000;
constexpr uint32_t kLocalBleHoldMs = 500;
constexpr char kModeNamespace[] = "terry_mode";
constexpr char kBleOnceKey[] = "ble_once";

static bool consume_local_ble_mode()
{
    nvs_handle_t nvs = 0;
    if (nvs_open(kModeNamespace, NVS_READWRITE, &nvs) != ESP_OK) return false;
    uint8_t requested = 0;
    nvs_get_u8(nvs, kBleOnceKey, &requested);
    nvs_erase_key(nvs, kBleOnceKey);
    nvs_commit(nvs);
    nvs_close(nvs);
    return requested == 1;
}

static void request_local_ble_mode()
{
    nvs_handle_t nvs = 0;
    ESP_ERROR_CHECK(nvs_open(kModeNamespace, NVS_READWRITE, &nvs));
    ESP_ERROR_CHECK(nvs_set_u8(nvs, kBleOnceKey, 1));
    ESP_ERROR_CHECK(nvs_commit(nvs));
    nvs_close(nvs);
}

static void factory_reset_button_task(void *)
{
    gpio_config_t config = {};
    config.pin_bit_mask = (1ULL << kFactoryResetButtonGpio);
    config.mode = GPIO_MODE_INPUT;
    config.pull_up_en = GPIO_PULLUP_ENABLE;
    config.pull_down_en = GPIO_PULLDOWN_DISABLE;
    config.intr_type = GPIO_INTR_DISABLE;
    ESP_ERROR_CHECK(gpio_config(&config));

    TickType_t pressed_at = 0;
    bool reset_started = false;

    while (true) {
        const bool pressed = gpio_get_level(kFactoryResetButtonGpio) == 0;

        if (!pressed) {
            if (pressed_at != 0 && !reset_started) {
                const uint32_t held_ms = pdTICKS_TO_MS(xTaskGetTickCount() - pressed_at);
                if (held_ms >= kLocalBleHoldMs && held_ms < kFactoryResetHoldMs) {
                    ESP_LOGI(TAG, "BOOT short press. Rebooting into local BLE provisioning mode.");
                    app_driver_show_status(s_strip, {0, 180, 255}, 700);
                    request_local_ble_mode();
                    vTaskDelay(pdMS_TO_TICKS(700));
                    esp_restart();
                }
            }
            pressed_at = 0;
            reset_started = false;
        } else if (pressed_at == 0) {
            pressed_at = xTaskGetTickCount();
        } else if (!reset_started &&
                   (xTaskGetTickCount() - pressed_at) >= pdMS_TO_TICKS(kFactoryResetHoldMs)) {
            reset_started = true;
            ESP_LOGW(TAG, "BOOT held for 5 seconds. Clearing Matter and Wi-Fi settings.");
            app_driver_show_status(s_strip, {0, 40, 255}, 1200, 150);
            vTaskDelay(pdMS_TO_TICKS(1200));
            esp_matter::factory_reset();
        }

        vTaskDelay(pdMS_TO_TICKS(25));
    }
}

static void app_event_cb(const chip::DeviceLayer::ChipDeviceEvent *event, intptr_t)
{
    switch (event->Type) {
    case chip::DeviceLayer::DeviceEventType::kCommissioningWindowOpened:
        ESP_LOGI(TAG, "Commissioning window opened");
        app_driver_show_status(s_strip, {0, 40, 255}, 15 * 60 * 1000, 500);
        break;
    case chip::DeviceLayer::DeviceEventType::kCommissioningSessionStarted:
        ESP_LOGI(TAG, "Commissioning session started");
        app_driver_show_status(s_strip, {0, 120, 255}, 5 * 60 * 1000, 250);
        break;
    case chip::DeviceLayer::DeviceEventType::kCommissioningComplete:
        ESP_LOGI(TAG, "Commissioning complete");
        app_driver_show_status(s_strip, {0, 255, 60}, 2000);
        break;
    case chip::DeviceLayer::DeviceEventType::kFailSafeTimerExpired:
        ESP_LOGW(TAG, "Commissioning failed");
        app_driver_show_status(s_strip, {255, 0, 0}, 3000, 250);
        break;
    default:
        break;
    }
}

static esp_err_t attribute_update_cb(attribute::callback_type_t type, uint16_t endpoint_id,
                                     uint32_t cluster_id, uint32_t attribute_id,
                                     esp_matter_attr_val_t *value, void *priv_data)
{
    if (type != attribute::PRE_UPDATE) {
        return ESP_OK;
    }
    return app_driver_attribute_update(static_cast<app_driver_handle_t>(priv_data), endpoint_id,
                                       cluster_id, attribute_id, value);
}

static esp_err_t identify_cb(identification::callback_type_t, uint16_t, uint8_t, uint8_t, void *)
{
    // The main WS2812 strip is deliberately not flashed here: it could be a room's only light.
    // Add a separate status LED if visual identify feedback is required by the enclosure.
    return ESP_OK;
}

extern "C" void app_main()
{
    ESP_ERROR_CHECK(nvs_flash_init());

    app_driver_handle_t strip = app_driver_light_init();
    if (strip == nullptr) {
        ESP_LOGE(TAG, "WS2812 initialization failed");
        return;
    }
    s_strip = strip;
    if (consume_local_ble_mode()) {
        ESP_LOGI(TAG, "Starting one-time local BLE provisioning mode");
        const esp_err_t result = ble_provisioning_start(strip);
        ESP_LOGE(TAG, "Local BLE provisioning stopped: %s", esp_err_to_name(result));
        return;
    }

    node::config_t node_config;
    node_t *node = node::create(&node_config, attribute_update_cb, identify_cb);
    if (node == nullptr) {
        ESP_LOGE(TAG, "Matter node creation failed");
        return;
    }

    endpoint::extended_color_light::config_t config;
    config.on_off.on_off = DEFAULT_POWER;
    config.on_off_lighting.start_up_on_off = nullptr;
    config.level_control.current_level = DEFAULT_BRIGHTNESS;
    config.level_control.on_level = DEFAULT_BRIGHTNESS;
    config.level_control_lighting.start_up_current_level = DEFAULT_BRIGHTNESS;
    config.color_control.color_mode = static_cast<uint8_t>(ColorControl::ColorMode::kCurrentHueAndCurrentSaturation);
    config.color_control.enhanced_color_mode = static_cast<uint8_t>(ColorControl::ColorMode::kCurrentHueAndCurrentSaturation);

    endpoint_t *endpoint = endpoint::extended_color_light::create(node, &config, ENDPOINT_FLAG_NONE, strip);
    if (endpoint == nullptr) {
        ESP_LOGE(TAG, "Extended Color Light endpoint creation failed");
        return;
    }
    light_endpoint_id = endpoint::get_id(endpoint);

    const auto persist_if_present = [](attribute_t *attr) {
        if (attr != nullptr) attribute::set_deferred_persistence(attr);
    };
    persist_if_present(attribute::get(light_endpoint_id, LevelControl::Id,
                                      LevelControl::Attributes::CurrentLevel::Id));
    persist_if_present(attribute::get(light_endpoint_id, ColorControl::Id,
                                      ColorControl::Attributes::CurrentHue::Id));
    persist_if_present(attribute::get(light_endpoint_id, ColorControl::Id,
                                      ColorControl::Attributes::CurrentSaturation::Id));

    ESP_ERROR_CHECK(esp_matter::start(app_event_cb));
    ESP_ERROR_CHECK(app_driver_light_set_defaults(light_endpoint_id));
    const esp_err_t local_api_result = local_api_start(strip);
    if (local_api_result != ESP_OK) {
        ESP_LOGE(TAG, "Local API failed to start: %s", esp_err_to_name(local_api_result));
    }
    xTaskCreate(factory_reset_button_task, "factory_reset_button", 3072, nullptr, 5, nullptr);

    ESP_LOGI(TAG, "Hold the BOOT button for 5 seconds to factory reset.");
    ESP_LOGI(TAG, "Matter WS2812 light ready; commission it with the QR code shown in the serial log.");
}
