#include "local_api.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>

#include <esp_check.h>
#include <esp_http_server.h>
#include <esp_log.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <nvs.h>
#include <app/server/Server.h>
#include <platform/CHIPDeviceLayer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <lwip/inet.h>
#include <lwip/sockets.h>

namespace {
constexpr char kNvsNamespace[] = "terry_local";
constexpr char kKeyName[] = "api_key";
constexpr size_t kApiKeyLength = 32;
constexpr size_t kMaxCustomRequestBytes = 4096;
constexpr uint16_t kDiscoveryPort = 4210;
static const char *TAG = "local_api";
app_driver_handle_t s_strip = nullptr;
char s_api_key[kApiKeyLength + 1] = {};
char s_hostname[32] = {};

void create_or_load_api_key()
{
    nvs_handle_t nvs = 0;
    ESP_ERROR_CHECK(nvs_open(kNvsNamespace, NVS_READWRITE, &nvs));
    size_t length = sizeof(s_api_key);
    if (nvs_get_str(nvs, kKeyName, s_api_key, &length) != ESP_OK || length != sizeof(s_api_key)) {
        for (size_t i = 0; i < kApiKeyLength / 2; ++i) {
            std::snprintf(s_api_key + i * 2, 3, "%02x", static_cast<unsigned>(esp_random() & 0xff));
        }
        ESP_ERROR_CHECK(nvs_set_str(nvs, kKeyName, s_api_key));
        ESP_ERROR_CHECK(nvs_commit(nvs));
    }
    nvs_close(nvs);
}

bool replace_api_key(const char *key)
{
    if (key == nullptr || std::strlen(key) != kApiKeyLength) return false;
    for (size_t i = 0; i < kApiKeyLength; ++i) {
        const char value = key[i];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') ||
              (value >= 'A' && value <= 'F'))) return false;
    }
    nvs_handle_t nvs = 0;
    if (nvs_open(kNvsNamespace, NVS_READWRITE, &nvs) != ESP_OK) return false;
    const esp_err_t set_result = nvs_set_str(nvs, kKeyName, key);
    const esp_err_t commit_result = set_result == ESP_OK ? nvs_commit(nvs) : set_result;
    nvs_close(nvs);
    if (set_result != ESP_OK || commit_result != ESP_OK) return false;
    std::memcpy(s_api_key, key, kApiKeyLength);
    s_api_key[kApiKeyLength] = 0;
    return true;
}

bool request_is_authorized(httpd_req_t *request)
{
    size_t length = httpd_req_get_hdr_value_len(request, "X-Terry-Key");
    if (length != kApiKeyLength) return false;
    char key[kApiKeyLength + 1] = {};
    return httpd_req_get_hdr_value_str(request, "X-Terry-Key", key, sizeof(key)) == ESP_OK &&
           std::strcmp(key, s_api_key) == 0;
}

void send_json(httpd_req_t *request, const char *json)
{
    httpd_resp_set_type(request, "application/json");
    httpd_resp_sendstr(request, json);
}

esp_err_t info_handler(httpd_req_t *request)
{
    char response[192] = {};
    std::snprintf(response, sizeof(response),
                  "{\"deviceId\":\"%s\",\"name\":\"TerryESP Controller\",\"api\":2,\"maxPixels\":256,\"auth\":\"X-Terry-Key\",\"discovery\":\"udp:4210\"}",
                  s_hostname);
    send_json(request, response);
    return ESP_OK;
}

esp_err_t auth_handler(httpd_req_t *request)
{
    if (!request_is_authorized(request)) {
        httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED, "Invalid local key");
        return ESP_FAIL;
    }
    send_json(request, "{\"ok\":true}");
    return ESP_OK;
}

void open_matter_commissioning_window(intptr_t)
{
    auto &manager = chip::Server::GetInstance().GetCommissioningWindowManager();
    const CHIP_ERROR result = manager.OpenBasicCommissioningWindow();
    if (result != CHIP_NO_ERROR) {
        ESP_LOGE(TAG, "Unable to open Matter commissioning window: %s", result.AsString());
    } else {
        ESP_LOGI(TAG, "Matter commissioning window opened from local API");
    }
}

esp_err_t matter_open_handler(httpd_req_t *request)
{
    if (!request_is_authorized(request)) {
        httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED, "Invalid local key");
        return ESP_FAIL;
    }
    chip::DeviceLayer::PlatformMgr().ScheduleWork(open_matter_commissioning_window, 0);
    send_json(request, "{\"ok\":true,\"commissioning\":\"opening\"}");
    return ESP_OK;
}

int receive_full_body(httpd_req_t *request, char *buffer, size_t length)
{
    size_t total = 0;
    while (total < length) {
        const int received = httpd_req_recv(request, buffer + total, length - total);
        if (received == HTTPD_SOCK_ERR_TIMEOUT) continue;
        if (received <= 0) return received;
        total += static_cast<size_t>(received);
    }
    return static_cast<int>(total);
}

app_effect_t effect_from_name(const char *name)
{
    if (name == nullptr) return app_effect_t::Static;
    if (!std::strcmp(name, "rainbow")) return app_effect_t::Rainbow;
    if (!std::strcmp(name, "rainbow_stripe")) return app_effect_t::RainbowStripe;
    if (!std::strcmp(name, "rainbow_soft")) return app_effect_t::RainbowStripeBlend;
    if (!std::strcmp(name, "purple_green")) return app_effect_t::PurpleGreen;
    if (!std::strcmp(name, "random")) return app_effect_t::Random;
    if (!std::strcmp(name, "black_white")) return app_effect_t::BlackWhiteStripe;
    if (!std::strcmp(name, "black_white_soft")) return app_effect_t::BlackWhiteBlend;
    if (!std::strcmp(name, "cloud")) return app_effect_t::Cloud;
    if (!std::strcmp(name, "party")) return app_effect_t::Party;
    if (!std::strcmp(name, "red_white_blue")) return app_effect_t::RedWhiteBlue;
    if (!std::strcmp(name, "red_white_blue_soft")) return app_effect_t::RedWhiteBlueBlend;
    return app_effect_t::Static;
}

esp_err_t effect_handler(httpd_req_t *request)
{
    if (!request_is_authorized(request)) {
        httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED, "Invalid local key");
        return ESP_FAIL;
    }
    char query[160] = {}, effect[32] = {}, speed_text[8] = {}, breathing_text[8] = {}, breath_speed_text[8] = {};
    if (httpd_req_get_url_query_str(request, query, sizeof(query)) == ESP_OK) {
        httpd_query_key_value(query, "effect", effect, sizeof(effect));
        httpd_query_key_value(query, "speed", speed_text, sizeof(speed_text));
        httpd_query_key_value(query, "breathing", breathing_text, sizeof(breathing_text));
        httpd_query_key_value(query, "breathSpeed", breath_speed_text, sizeof(breath_speed_text));
    }
    const int speed = speed_text[0] ? std::atoi(speed_text) : 5;
    const bool breathing = breathing_text[0] && std::atoi(breathing_text) != 0;
    const int breathing_speed = breath_speed_text[0] ? std::atoi(breath_speed_text) : 5;
    ESP_RETURN_ON_ERROR(app_driver_set_effect(s_strip, effect_from_name(effect),
                                               static_cast<uint8_t>(std::clamp(speed, 1, 10)), breathing,
                                               static_cast<uint8_t>(std::clamp(breathing_speed, 1, 10))),
                        TAG, "set effect failed");
    send_json(request, "{\"ok\":true}");
    return ESP_OK;
}

int value_after(const char *body, const char *key, int fallback);

esp_err_t state_handler(httpd_req_t *request)
{
    if (!request_is_authorized(request)) {
        httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED, "Invalid local key");
        return ESP_FAIL;
    }
    if (request->content_len <= 0 || request->content_len > 256) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid state request");
        return ESP_FAIL;
    }
    char body[257] = {};
    const int received = receive_full_body(request, body, request->content_len);
    if (received <= 0) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing state");
        return ESP_FAIL;
    }
    body[received] = 0;
    bool handled = false;
    if (std::strstr(body, "power=") != nullptr) {
        ESP_RETURN_ON_ERROR(app_driver_set_power(s_strip, value_after(body, "power=", 0) != 0), TAG, "set power failed");
        handled = true;
    }
    if (std::strstr(body, "brightness=") != nullptr) {
        ESP_RETURN_ON_ERROR(app_driver_set_brightness(s_strip, static_cast<uint8_t>(
                                std::clamp(value_after(body, "brightness=", 50), 1, 100))),
                            TAG, "set brightness failed");
        handled = true;
    }
    if (std::strstr(body, "hue=") != nullptr || std::strstr(body, "saturation=") != nullptr) {
        ESP_RETURN_ON_ERROR(app_driver_set_hsv(s_strip,
                                static_cast<uint8_t>(std::clamp(value_after(body, "hue=", 0), 0, 254)),
                                static_cast<uint8_t>(std::clamp(value_after(body, "saturation=", 254), 0, 254))),
                            TAG, "set color failed");
        handled = true;
    }
    if (std::strstr(body, "temperature=") != nullptr) {
        ESP_RETURN_ON_ERROR(app_driver_set_temperature(s_strip, static_cast<uint16_t>(
                                std::clamp(value_after(body, "temperature=", 4000), 2000, 6500))),
                            TAG, "set temperature failed");
        handled = true;
    }
    if (!handled) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "No supported state field");
        return ESP_FAIL;
    }
    send_json(request, "{\"ok\":true}");
    return ESP_OK;
}

int value_after(const char *body, const char *key, int fallback)
{
    const char *value = std::strstr(body, key);
    return value ? std::atoi(value + std::strlen(key)) : fallback;
}

esp_err_t custom_handler(httpd_req_t *request)
{
    if (!request_is_authorized(request)) {
        httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED, "Invalid local key");
        return ESP_FAIL;
    }
    if (request->content_len <= 0 || request->content_len >= kMaxCustomRequestBytes) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid custom pattern");
        return ESP_FAIL;
    }
    auto body = std::unique_ptr<char[]>(new (std::nothrow) char[request->content_len + 1]());
    if (!body) {
        httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Out of memory");
        return ESP_FAIL;
    }
    const int received = receive_full_body(request, body.get(), request->content_len);
    if (received <= 0) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing custom pattern");
        return ESP_FAIL;
    }
    body[received] = 0;
    const char *text = std::strstr(body.get(), "pixels=");
    if (text == nullptr) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing pixels");
        return ESP_FAIL;
    }
    text += std::strlen("pixels=");
    auto pixels = std::unique_ptr<app_rgb_t[]>(new (std::nothrow) app_rgb_t[APP_CUSTOM_PIXEL_COUNT]());
    if (!pixels) {
        httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Out of memory");
        return ESP_FAIL;
    }
    uint16_t count = 0;
    while (*text && count < APP_CUSTOM_PIXEL_COUNT) {
        unsigned red = 0, green = 0, blue = 0;
        if (std::sscanf(text, "%2x%2x%2x", &red, &green, &blue) != 3) break;
        pixels[count++] = {static_cast<uint8_t>(red), static_cast<uint8_t>(green), static_cast<uint8_t>(blue)};
        const char *next = std::strchr(text, ',');
        if (next == nullptr) break;
        text = next + 1;
    }
    if (count == 0) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "No valid pixels");
        return ESP_FAIL;
    }
    const int speed = value_after(body.get(), "speed=", 5);
    const int breathing_speed = value_after(body.get(), "breathSpeed=", 5);
    ESP_RETURN_ON_ERROR(app_driver_set_custom_pixels(s_strip, pixels.get(), count,
                                                      value_after(body.get(), "moving=", 1) != 0,
                                                      value_after(body.get(), "right=", 1) != 0,
                                                      value_after(body.get(), "breathing=", 0) != 0,
                                                      static_cast<uint8_t>(std::clamp(speed, 1, 10)),
                                                      static_cast<uint8_t>(std::clamp(breathing_speed, 1, 10))),
                        TAG, "set custom pattern failed");
    send_json(request, "{\"ok\":true}");
    return ESP_OK;
}

esp_err_t rotate_key_handler(httpd_req_t *request)
{
    if (!request_is_authorized(request)) {
        httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED, "Invalid local key");
        return ESP_FAIL;
    }
    if (request->content_len <= 4 || request->content_len > 64) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid new key");
        return ESP_FAIL;
    }
    char body[65] = {};
    const int received = receive_full_body(request, body, request->content_len);
    if (received <= 0) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing new key");
        return ESP_FAIL;
    }
    body[received] = 0;
    const char *key = std::strstr(body, "key=");
    if (key == nullptr || !replace_api_key(key + 4)) {
        httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid new key");
        return ESP_FAIL;
    }
    send_json(request, "{\"ok\":true}");
    return ESP_OK;
}

void discovery_task(void *)
{
    const int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    if (sock < 0) {
        ESP_LOGE(TAG, "Unable to create UDP discovery socket");
        vTaskDelete(nullptr);
        return;
    }
    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in listen_address = {};
    listen_address.sin_family = AF_INET;
    listen_address.sin_port = htons(kDiscoveryPort);
    listen_address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(sock, reinterpret_cast<sockaddr *>(&listen_address), sizeof(listen_address)) != 0) {
        ESP_LOGE(TAG, "Unable to bind UDP discovery socket");
        close(sock);
        vTaskDelete(nullptr);
        return;
    }
    char request[64] = {};
    char response[128] = {};
    while (true) {
        sockaddr_in source = {};
        socklen_t source_length = sizeof(source);
        const int received = recvfrom(sock, request, sizeof(request) - 1, 0,
                                      reinterpret_cast<sockaddr *>(&source), &source_length);
        if (received <= 0) continue;
        request[received] = 0;
        if (std::strcmp(request, "TERRYESP_DISCOVER") != 0) continue;
        const int response_length = std::snprintf(response, sizeof(response),
                                                  "TERRYESP|TerryESP Controller|80|%s", s_hostname);
        sendto(sock, response, response_length, 0, reinterpret_cast<sockaddr *>(&source), source_length);
    }
}
} // namespace

esp_err_t local_api_start(app_driver_handle_t strip)
{
    if (strip == nullptr) return ESP_ERR_INVALID_ARG;
    s_strip = strip;
    create_or_load_api_key();

    uint8_t mac[6] = {};
    ESP_ERROR_CHECK(esp_read_mac(mac, ESP_MAC_WIFI_STA));
    std::snprintf(s_hostname, sizeof(s_hostname), "terryesp-%02x%02x%02x", mac[3], mac[4], mac[5]);

    httpd_handle_t server = nullptr;
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.stack_size = 8192;
    config.max_uri_handlers = 7;
    ESP_RETURN_ON_ERROR(httpd_start(&server, &config), TAG, "HTTP start failed");
    const httpd_uri_t info = {.uri = "/api/v1/info", .method = HTTP_GET, .handler = info_handler};
    const httpd_uri_t auth = {.uri = "/api/v1/auth", .method = HTTP_GET, .handler = auth_handler};
    const httpd_uri_t effect = {.uri = "/api/v1/effect", .method = HTTP_GET, .handler = effect_handler};
    const httpd_uri_t state = {.uri = "/api/v1/state", .method = HTTP_POST, .handler = state_handler};
    const httpd_uri_t custom = {.uri = "/api/v1/custom", .method = HTTP_POST, .handler = custom_handler};
    const httpd_uri_t rotate_key = {.uri = "/api/v1/key", .method = HTTP_POST, .handler = rotate_key_handler};
    const httpd_uri_t matter_open = {.uri = "/api/v1/matter/open", .method = HTTP_POST, .handler = matter_open_handler};
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &info), TAG, "register info failed");
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &auth), TAG, "register auth failed");
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &effect), TAG, "register effect failed");
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &state), TAG, "register state failed");
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &custom), TAG, "register custom failed");
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &rotate_key), TAG, "register key rotation failed");
    ESP_RETURN_ON_ERROR(httpd_register_uri_handler(server, &matter_open), TAG, "register Matter open failed");
    if (xTaskCreate(discovery_task, "terryesp_discovery", 3072, nullptr, 4, nullptr) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    ESP_LOGI(TAG, "Local API ready for %s", s_hostname);
    return ESP_OK;
}

const char *local_api_key()
{
    if (s_api_key[0] == 0) create_or_load_api_key();
    return s_api_key;
}
