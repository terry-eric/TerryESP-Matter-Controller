#include "ble_provisioning.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <esp_check.h>
#include <esp_event.h>
#include <esp_log.h>
#include <esp_mac.h>
#include <esp_netif.h>
#include <esp_system.h>
#include <esp_wifi.h>
#include <wifi_provisioning/manager.h>
#include <wifi_provisioning/scheme_ble.h>

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "local_api.h"

namespace {
static const char *TAG = "terry_ble_prov";
static app_driver_handle_t s_strip = nullptr;

void delayed_restart_task(void *)
{
    // Leave enough time for the provisioning status response to reach the app.
    vTaskDelay(pdMS_TO_TICKS(4000));
    esp_restart();
}

esp_err_t terry_info_handler(uint32_t, const uint8_t *, ssize_t,
                             uint8_t **outbuf, ssize_t *outlen, void *priv_data)
{
    if (outbuf == nullptr || outlen == nullptr || priv_data == nullptr) return ESP_ERR_INVALID_ARG;
    const char *device_id = static_cast<const char *>(priv_data);
    char response[160] = {};
    const int length = std::snprintf(
        response, sizeof(response),
        "{\"deviceId\":\"%s\",\"name\":\"TerryESP Controller\",\"localKey\":\"%s\"}",
        device_id, local_api_key());
    if (length <= 0 || length >= static_cast<int>(sizeof(response))) return ESP_FAIL;
    *outbuf = static_cast<uint8_t *>(std::malloc(static_cast<size_t>(length)));
    if (*outbuf == nullptr) return ESP_ERR_NO_MEM;
    std::memcpy(*outbuf, response, static_cast<size_t>(length));
    *outlen = length;
    return ESP_OK;
}

void provisioning_event_handler(void *, esp_event_base_t event_base, int32_t event_id, void *event_data)
{
    if (event_base != WIFI_PROV_EVENT) return;
    switch (event_id) {
    case WIFI_PROV_START:
        ESP_LOGI(TAG, "Local BLE provisioning started");
        break;
    case WIFI_PROV_CRED_FAIL: {
        const auto reason = *static_cast<wifi_prov_sta_fail_reason_t *>(event_data);
        ESP_LOGE(TAG, "Wi-Fi provisioning failed: %s",
                 reason == WIFI_PROV_STA_AUTH_ERROR ? "authentication failed" : "network not found");
        app_driver_show_status(s_strip, {255, 0, 0}, 3000, 250);
        wifi_prov_mgr_reset_sm_state_on_failure();
        break;
    }
    case WIFI_PROV_CRED_SUCCESS:
        ESP_LOGI(TAG, "Wi-Fi provisioning successful; scheduling normal-mode reboot");
        app_driver_show_status(s_strip, {0, 255, 60}, 1500);
        if (xTaskCreate(delayed_restart_task, "terry_restart", 2048, nullptr, 5, nullptr) != pdPASS) {
            ESP_LOGE(TAG, "Unable to schedule restart");
        }
        break;
    default:
        break;
    }
}
} // namespace

esp_err_t ble_provisioning_start(app_driver_handle_t strip)
{
    s_strip = strip;
    ESP_RETURN_ON_ERROR(esp_netif_init(), TAG, "esp_netif_init failed");
    const esp_err_t loop_result = esp_event_loop_create_default();
    if (loop_result != ESP_OK && loop_result != ESP_ERR_INVALID_STATE) return loop_result;
    ESP_RETURN_ON_ERROR(esp_event_handler_register(WIFI_PROV_EVENT, ESP_EVENT_ANY_ID,
                                                   provisioning_event_handler, nullptr),
                        TAG, "register provisioning handler failed");

    if (esp_netif_create_default_wifi_sta() == nullptr) return ESP_ERR_NO_MEM;
    wifi_init_config_t wifi_config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_RETURN_ON_ERROR(esp_wifi_init(&wifi_config), TAG, "Wi-Fi init failed");

    wifi_prov_mgr_config_t manager_config = {
        .scheme = wifi_prov_scheme_ble,
        .scheme_event_handler = WIFI_PROV_SCHEME_BLE_EVENT_HANDLER_FREE_BTDM,
    };
    ESP_RETURN_ON_ERROR(wifi_prov_mgr_init(manager_config), TAG, "provision manager init failed");
    ESP_RETURN_ON_ERROR(wifi_prov_mgr_reset_provisioning(), TAG, "reset Wi-Fi provisioning failed");

    uint8_t mac[6] = {};
    ESP_RETURN_ON_ERROR(esp_read_mac(mac, ESP_MAC_WIFI_STA), TAG, "read MAC failed");
    char service_name[20] = {};
    std::snprintf(service_name, sizeof(service_name), "TERRY_%02X%02X%02X", mac[3], mac[4], mac[5]);
    char device_id[32] = {};
    std::snprintf(device_id, sizeof(device_id), "terryesp-%02x%02x%02x", mac[3], mac[4], mac[5]);
    char provisioning_pop[20] = {};
    std::snprintf(provisioning_pop, sizeof(provisioning_pop), "terry-%02x%02x%02x", mac[3], mac[4], mac[5]);

    uint8_t service_uuid[] = {
        0xb4, 0xdf, 0x5a, 0x1c, 0x3f, 0x6b, 0xf4, 0xbf,
        0xea, 0x4a, 0x82, 0x03, 0x04, 0x90, 0x1a, 0x02,
    };
    wifi_prov_scheme_ble_set_service_uuid(service_uuid);
    // Create the endpoint before provisioning starts. Its response travels only
    // inside the Security 1 session and lets the app bind the unique local key.
    ESP_RETURN_ON_ERROR(wifi_prov_mgr_endpoint_create("terry-info"), TAG,
                        "create Terry info endpoint failed");
    ESP_RETURN_ON_ERROR(
        wifi_prov_mgr_start_provisioning(WIFI_PROV_SECURITY_1, provisioning_pop,
                                         service_name, nullptr),
        TAG, "start BLE provisioning failed");
    ESP_RETURN_ON_ERROR(wifi_prov_mgr_endpoint_register("terry-info", terry_info_handler, device_id),
                        TAG, "register Terry info endpoint failed");

    app_driver_show_status(strip, {0, 180, 255}, 30 * 60 * 1000, 350);
    ESP_LOGI(TAG, "BLE local setup ready: %s", service_name);
    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
