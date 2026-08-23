idf_component_register(
    SRCS "app_main.cpp" "ws2812_driver.cpp" "local_api.cpp" "ble_provisioning.cpp"
    INCLUDE_DIRS "."
    REQUIRES esp_matter nvs_flash led_strip driver esp_http_server lwip
             esp_wifi esp_event esp_netif wifi_provisioning
)

target_compile_options(${COMPONENT_LIB} PRIVATE "-DCHIP_HAVE_CONFIG_H")
