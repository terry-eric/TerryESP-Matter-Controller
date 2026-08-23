#pragma once

#include <esp_err.h>

#include "app_priv.h"

// Starts TerryESP's Google-independent BLE Wi-Fi provisioning mode.
// This function does not return after successful provisioning; the device reboots.
esp_err_t ble_provisioning_start(app_driver_handle_t strip);
